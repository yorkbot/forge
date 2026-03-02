# Forge LLM Integration — Java Source Files

## Files to copy into the Forge source tree

Copy all `.java` files from this directory into:
```
forge-ai/src/main/java/forge/ai/
```

Files:
- `PlayerControllerLLM.java` — LLM-powered player controller (extends PlayerControllerAi)
- `GameStateSerializer.java` — Serializes game state to markdown for the LLM
- `LobbyPlayerLLM.java` — Factory that creates LLM controllers (replaces LobbyPlayerAi)

## How to wire it in

### Option A: Minimal change in GamePlayerUtil (recommended)

Edit `forge-gui/src/main/java/forge/player/GamePlayerUtil.java`.

Add this import at the top:
```java
import forge.ai.LobbyPlayerLLM;
```

In the method `createAiPlayer(String name, int avatarIndex, int sleeveIndex, Set<AIOption> options, String profileOverride)` (line ~70), change this line:

```java
final LobbyPlayerAi player = new LobbyPlayerAi(name, options);
```

To:

```java
final LobbyPlayer player;
if ("true".equals(System.getProperty("forge.llm.enabled"))) {
    player = new LobbyPlayerLLM(name, options);
} else {
    player = new LobbyPlayerAi(name, options);
}
```

Note: You'll also need to cast `player` to `LobbyPlayerAi` or `LobbyPlayerLLM` for the `setRotateProfileEachGame`, `setAiProfile`, etc. calls that follow. The simplest approach is to keep those calls behind an `instanceof` check or add the same setter methods to both classes (which LobbyPlayerLLM already has).

The full replacement for the method body:

```java
public static LobbyPlayer createAiPlayer(final String name, final int avatarIndex, final int sleeveIndex, final Set<AIOption> options, final String profileOverride) {
    final boolean useLlm = "true".equals(System.getProperty("forge.llm.enabled"));

    String profile = "";
    if (profileOverride == null || profileOverride.isEmpty()) {
        String lastProfileChosen = FModel.getPreferences().getPref(FPref.UI_CURRENT_AI_PROFILE);
        if (!AiProfileUtil.getProfilesDisplayList().contains(lastProfileChosen)) {
            lastProfileChosen = "Default";
            FModel.getPreferences().setPref(FPref.UI_CURRENT_AI_PROFILE, "Default");
            FModel.getPreferences().save();
        }
        if (lastProfileChosen.equals(AiProfileUtil.AI_PROFILE_RANDOM_MATCH)) {
            lastProfileChosen = AiProfileUtil.getRandomProfile();
        }
        profile = lastProfileChosen;
    } else {
        profile = profileOverride;
    }

    if (useLlm) {
        LobbyPlayerLLM player = new LobbyPlayerLLM(name, options);
        player.setAiProfile(profile);
        player.setAvatarIndex(avatarIndex);
        player.setSleeveIndex(sleeveIndex);
        Logger.debug("[LLM AI] " + name + " using LLM controller");
        return player;
    } else {
        LobbyPlayerAi player = new LobbyPlayerAi(name, options);
        String lastProfileChosen = profile;
        player.setRotateProfileEachGame(lastProfileChosen.equals(AiProfileUtil.AI_PROFILE_RANDOM_DUEL));
        player.setAiProfile(profile);
        player.setAvatarIndex(avatarIndex);
        player.setSleeveIndex(sleeveIndex);
        Logger.debug("[AI Preferences] " + name + " using profile " + profile);
        return player;
    }
}
```

### Option B: Even simpler — modify LobbyPlayerAi directly

Edit `forge-ai/src/main/java/forge/ai/LobbyPlayerAi.java`.

Change the `createControllerFor` method (line ~43):

```java
private PlayerControllerAi createControllerFor(Player ai) {
    PlayerControllerAi result;
    if ("true".equals(System.getProperty("forge.llm.enabled"))) {
        result = new PlayerControllerLLM(ai.getGame(), ai, this);
    } else {
        result = new PlayerControllerAi(ai.getGame(), ai, this);
    }
    result.setUseSimulation(useSimulation);
    result.allowCheatShuffle(allowCheatShuffle);
    return result;
}
```

This approach requires NO changes outside of `forge-ai/` and doesn't need `LobbyPlayerLLM.java` at all.

## Running

1. Start the decision server on the VM:
```bash
cd ~/york/forge-llm-server
npm start
```

2. Launch Forge with LLM enabled:
```bash
java -Dforge.llm.enabled=true -Dforge.llm.url=http://YOUR_VM_IP:8080 -jar forge.jar
```

If `-Dforge.llm.url` is not set, it defaults to `http://localhost:8080`.

## How it works

1. When the AI needs to make a decision, `PlayerControllerLLM` serializes the game state to markdown via `GameStateSerializer`
2. It POSTs to the decision server with the game state + options
3. The decision server sends the prompt to Claude (claude-sonnet-4-20250514)
4. Claude picks the best option index
5. The server returns the index to the Java client
6. On any error or timeout (30s), it falls back to the stock AI logic
