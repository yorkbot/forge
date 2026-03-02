# Overnight Improvement Task — Forge LLM AI

## Context
The Forge LLM AI is working. Forge calls POST /decide on the decision server, which routes to OpenClaw's MTG agent. The agent picks from legal actions. Games are playable but the AI makes tactical mistakes (bad land tapping, no multi-turn planning).

## What to Improve (Priority Order)

### 1. Game State Serialization (GameStateSerializer.java)
Current state is basic markdown. Improve:
- Show available mana COLORS (not just "Mountain untapped" — say "R available")
- Total mana available by color
- Cards in hand with full mana costs
- Show which phase it is more clearly
- Library size (cards remaining)
- Whether land drop has been used this turn

### 2. Pre-Game Deck Analysis
When a game starts, the LLM should receive the full decklist and generate a game plan. Store this in the prompt context.
- Add a new endpoint POST /analyze-deck that receives the decklist
- The MTG agent scans the deck for: win conditions, synergies, curve, game plan
- Returns a strategy summary that gets included in every subsequent /decide call
- The Java side should call this at game start and cache the strategy

### 3. Running Game Notes
The MTG agent should maintain notes across decisions within a game:
- What the opponent has played (tracking removal, threats, tricks)
- What the AI has drawn and what's likely still in deck
- Current strategic assessment ("I'm the beatdown" / "I need to stabilize")
- Update notes after each meaningful decision

Implementation: Add a game_id to each /decide request. The server maintains a notes file per game. Each decision reads and can update the notes.

### 4. Smarter Auto-Pass
Currently every priority pass goes to the LLM even when there's nothing meaningful to do.
- If the only options are "Pass" and mana abilities, auto-pass
- If it's the opponent's turn and the AI has no instant-speed plays, auto-pass
- Only call the LLM when there's a real decision to make
- This saves tokens AND speeds up the game significantly

### 5. Better Decision Prompting
Current prompt is just "pick a number." Improve to include:
- Game plan from deck analysis
- Running notes from this game
- "Think about what you want to do next turn before deciding how to tap lands"
- Brief strategic framing ("you're ahead on board, play conservatively" etc.)

### 6. Land Tapping Intelligence
The mana payment system is handled by stock AI (chooseManaFromPool). Override this:
- Before tapping lands, consider what you might want to cast with remaining mana
- Keep colored mana open for instants in hand
- This might need a chooseManaToPay or similar override

## Files to Modify
- `~/york/forge-source/forge-ai/src/main/java/forge/ai/GameStateSerializer.java` — better state
- `~/york/forge-source/forge-ai/src/main/java/forge/ai/PlayerControllerLLM.java` — smarter auto-pass, deck analysis call
- `~/york/forge-llm-server/src/server.ts` — game notes, deck analysis endpoint, better prompting
- `~/.openclaw/workspace-mtg/SOUL.md` — improved strategic guidance

## Testing
- Build Forge after Java changes: `cd ~/york/forge-source && mvn package -DskipTests -pl forge-gui-desktop -am`
- Test decision server: `curl -X POST http://localhost:9090/decide -H "Content-Type: application/json" -d '...'`
- Push changes to yorkbot/forge llm-ai branch
- Log all changes to ~/york/builds/changelog.md

## Constraints
- No Java GUI available on this VM — can't run full game tests
- Can compile and verify Java builds
- Can test decision server fully
- Push to git for James to test in the morning
