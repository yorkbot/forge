# Forge LLM Decision Server + Java Mod

## What to Build

### Part A: Decision Server (runs on this VM)
TypeScript HTTP server at ~/york/forge-llm-server/

- POST /decide endpoint
- Receives: { method, gameState (markdown string), options (array of {index, description}), context (string) }
- Builds a Claude prompt with the game state + options
- Calls Anthropic API (key in env ANTHROPIC_API_KEY)
- Returns: { index (number), reasoning (string) }
- Skips LLM call when only 1 option (auto-pick index 0)
- Logs all decisions to decisions.log

Use the Anthropic TypeScript SDK (@anthropic-ai/sdk).
Model: claude-sonnet-4-20250514 (good enough for gameplay, fast)

System prompt should include:
- "You are an expert Magic: The Gathering player"
- "Pick the best play from the numbered options"
- "Consider tempo, card advantage, board state, and win conditions"
- "Return ONLY the index number of your choice"

### Part B: Java Source Files (written here, built by James on Manjaro)
Write these files into ~/york/forge-llm-server/java-src/ so James can copy them into the Forge source tree.

#### PlayerControllerLLM.java
- Package: forge.ai
- Extends PlayerControllerAi
- Overrides these methods to make HTTP POST to decision server:
  - getAbilityToPlay() — serialize legal SpellAbility options
  - declareAttackers() — serialize possible attackers
  - declareBlockers() — serialize possible blockers  
  - chooseCardsForEffect() — serialize card options
  - chooseSingleEntityForEffect() — serialize entity options
  - confirmAction() — yes/no as two options
  - arrangeForScry() — top/bottom choices
  - chooseCardsToDiscardFrom() — discard options
  - chooseSingleSpellForEffect() — spell choices

For each override:
1. Build a markdown game state string using GameStateSerializer
2. Convert the method's options into a simple list of {index, description} 
3. POST to http://DECISION_SERVER_URL/decide (configurable, default http://localhost:8080)
4. Parse response index
5. Return the corresponding option
6. On HTTP error/timeout, fall back to super (stock AI)

The DECISION_SERVER_URL should be configurable via system property: -Dforge.llm.url=http://...

#### GameStateSerializer.java
- Package: forge.ai
- Static method: String serialize(Game game, Player aiPlayer)
- Outputs readable markdown:
  - Turn number, phase, active player
  - Life totals
  - AI's board: each permanent with name, P/T, tapped/untapped, counters, attachments
  - Opponent's board: same detail
  - AI's hand: card names with mana costs and oracle text summary
  - AI's graveyard: card names
  - Opponent's graveyard: card names
  - Stack contents
  - Opponent's hand size (not contents — hidden info)

#### Registration: How to wire it in
Look at how PlayerControllerAi gets instantiated in the Forge codebase. The likely place is in GamePlayerUtil or the lobby/match setup code. Write a README explaining where James needs to make the 1-2 line change to use PlayerControllerLLM instead of PlayerControllerAi when a flag is set.

Check these files for the registration point:
- forge-gui/src/main/java/forge/gamemodes/match/HostedMatch.java
- forge-gui/src/main/java/forge/player/GamePlayerUtil.java
- forge-gui-desktop/src/main/java/forge/screens/match/CMatchUI.java
- forge-ai/src/main/java/forge/ai/AiController.java

The registration hook should be minimal: if system property forge.llm.enabled=true, use PlayerControllerLLM instead of PlayerControllerAi.

## Reference Files
- Base class: ~/york/forge-source/forge-game/src/main/java/forge/game/player/PlayerController.java
- Stock AI: ~/york/forge-source/forge-ai/src/main/java/forge/ai/PlayerControllerAi.java
- AI Controller: ~/york/forge-source/forge-ai/src/main/java/forge/ai/AiController.java
- Game class: ~/york/forge-source/forge-game/src/main/java/forge/game/Game.java
- SpellAbility: ~/york/forge-source/forge-game/src/main/java/forge/game/spellability/SpellAbility.java
- Card: ~/york/forge-source/forge-game/src/main/java/forge/game/card/Card.java
- Combat: ~/york/forge-source/forge-game/src/main/java/forge/game/combat/Combat.java
- Player: ~/york/forge-source/forge-game/src/main/java/forge/game/player/Player.java

## Constraints
- No Java/Maven on this VM. Write .java files but don't try to compile.
- Decision server: use npm/node (available on VM)
- Keep it simple. This is v1 — get it working, optimize later.

When completely finished, run this command to notify me:
openclaw system event --text "Done: Forge LLM decision server + Java mod source files written" --mode now
