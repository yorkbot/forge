import express from "express";
import { execSync } from "child_process";
import fs from "fs";
import path from "path";
import { onGameStart, onGameEnd, onDecision, handleChatEvent, ChatEvent } from "./chat";

const app = express();
app.use(express.json({ limit: "4mb" }));

const PORT = process.env.PORT || 8080;
const LOG_FILE = path.join(__dirname, "..", "decisions.log");
const NOTES_DIR = path.join(__dirname, "..", "game-notes");
const CARD_SKILLS_DIR = path.join("/home/york/.openclaw/workspace-mtg/card-skills");
const AGENT = process.env.OPENCLAW_AGENT || "mtg";

// Ensure directories exist
if (!fs.existsSync(NOTES_DIR)) {
  fs.mkdirSync(NOTES_DIR, { recursive: true });
}

interface Option {
  index: number;
  description: string;
}

interface DecideRequest {
  gameId?: string;
  method: string;
  gameState: string;
  options: Option[];
  context: string;
  turnLog?: string[];  // Item 7: running turn summaries
}

interface AnalyzeDeckRequest {
  gameId: string;
  decklist: string;
}

function log(entry: string) {
  const line = `[${new Date().toISOString()}] ${entry}\n`;
  fs.appendFileSync(LOG_FILE, line);
}

function getNotesPath(gameId: string): string {
  return path.join(NOTES_DIR, `${gameId}.md`);
}

function readGameNotes(gameId: string): string {
  const notesPath = getNotesPath(gameId);
  if (fs.existsSync(notesPath)) {
    return fs.readFileSync(notesPath, "utf-8");
  }
  return "";
}

function getDeckStrategyPath(gameId: string): string {
  return path.join(NOTES_DIR, `${gameId}-strategy.md`);
}

function readDeckStrategy(gameId: string): string {
  const stratPath = getDeckStrategyPath(gameId);
  if (fs.existsSync(stratPath)) {
    return fs.readFileSync(stratPath, "utf-8");
  }
  return "";
}

function getCardSkillsPath(gameId: string): string {
  return path.join(NOTES_DIR, `${gameId}-skills.md`);
}

function readCardSkills(gameId: string): string {
  const skillsPath = getCardSkillsPath(gameId);
  if (fs.existsSync(skillsPath)) {
    return fs.readFileSync(skillsPath, "utf-8");
  }
  return "";
}

/**
 * Item 8: Load card skill files matching cards in a decklist.
 * Converts card names to kebab-case filenames and checks for matching files.
 */
function loadCardSkillsForDeck(decklist: string, opponentCards: string[] = []): string {
  if (!fs.existsSync(CARD_SKILLS_DIR)) {
    return "";
  }

  const allCards = new Set<string>();

  // Parse decklist (format: "Card Name [cost] P/T")
  for (const line of decklist.split("\n")) {
    const cardName = line.trim().split(" [")[0].split(" {")[0].trim();
    if (cardName) allCards.add(cardName);
  }

  // Also check opponent's revealed cards
  for (const card of opponentCards) {
    allCards.add(card.trim());
  }

  const loadedSkills: string[] = [];

  for (const cardName of allCards) {
    // Convert "Splinter Twin" -> "splinter-twin"
    const filename = cardName
      .toLowerCase()
      .replace(/[^a-z0-9\s]/g, "")
      .trim()
      .replace(/\s+/g, "-") + ".md";

    const skillPath = path.join(CARD_SKILLS_DIR, filename);
    if (fs.existsSync(skillPath)) {
      const content = fs.readFileSync(skillPath, "utf-8");
      loadedSkills.push(content);
      log(`CARD-SKILL: loaded ${filename} for game`);
    }
  }

  return loadedSkills.join("\n\n---\n\n");
}

// Clean up old game notes (older than 24h)
function cleanOldNotes() {
  try {
    const files = fs.readdirSync(NOTES_DIR);
    const cutoff = Date.now() - 24 * 60 * 60 * 1000;
    for (const f of files) {
      const fp = path.join(NOTES_DIR, f);
      const stat = fs.statSync(fp);
      if (stat.mtimeMs < cutoff) {
        fs.unlinkSync(fp);
      }
    }
  } catch (_) {}
}

// POST /analyze-deck — called at game start, generates strategy and loads card skills
app.post("/analyze-deck", async (req, res) => {
  const { gameId, decklist } = req.body as AnalyzeDeckRequest;

  if (!gameId || !decklist) {
    res.status(400).json({ error: "gameId and decklist required" });
    return;
  }

  log(`DECK-ANALYSIS: gameId=${gameId}, analyzing ${decklist.split('\n').length} cards`);
  onGameStart(gameId);

  // Item 8: Load card skills for this deck immediately
  const cardSkills = loadCardSkillsForDeck(decklist);
  if (cardSkills) {
    const skillsPath = getCardSkillsPath(gameId);
    fs.writeFileSync(skillsPath, `# Card Skills — Game ${gameId}\n\nThe following cards in your deck have specific strategy notes:\n\n${cardSkills}\n`);
    log(`CARD-SKILLS: loaded skills for game ${gameId}`);
  }

  const message = `[MTG Deck Analysis] You are about to play a game with this deck. Analyze it and write a concise game plan.

## Decklist
${decklist}

## Your Task
Write a game plan (max 200 words) covering:
1. Win conditions — how does this deck win?
2. Curve — what's the ideal sequence of plays?
3. Key synergies — what combos or interactions matter most?
4. Strategic posture — are you the aggressor or the control player?
5. Mulligans — what does a keepable hand look like?

Be specific about card names. This plan will be referenced in every future decision.`;

  try {
    const result = execSync(
      `openclaw agent -m ${JSON.stringify(message)} --agent ${AGENT} --json`,
      { timeout: 90000, encoding: "utf-8" }
    );

    const parsed = JSON.parse(result);
    const strategy = parsed?.result?.payloads?.[0]?.text || "";

    if (strategy) {
      const stratPath = getDeckStrategyPath(gameId);
      fs.writeFileSync(stratPath, `# Deck Strategy — Game ${gameId}\n\n${strategy}\n`);
      log(`DECK-ANALYSIS: strategy cached for game ${gameId}`);
    }

    res.json({ ok: true, strategy });
  } catch (err: any) {
    log(`DECK-ANALYSIS ERROR: ${err.message?.substring(0, 200)}`);
    res.json({ ok: false });
  }
});

app.post("/decide", async (req, res) => {
  const { gameId, method, gameState, options, context, turnLog } = req.body as DecideRequest;

  // Auto-pick if only one option
  if (options && options.length === 1) {
    log(`AUTO: ${method} -> ${options[0].description}`);
    res.json({ index: options[0].index, reasoning: "Only legal action" });
    return;
  }

  // Auto-pass if no options
  if (!options || options.length === 0) {
    log(`PASS: ${method} -> no options`);
    res.json({ index: -1, reasoning: "No options available" });
    return;
  }

  const optionsList = options
    .map((o) => `${o.index}. ${o.description}`)
    .join("\n");

  // Load game-level context
  const deckStrategy = gameId ? readDeckStrategy(gameId) : "";
  const gameNotes = gameId ? readGameNotes(gameId) : "";
  const cardSkills = gameId ? readCardSkills(gameId) : "";

  // Build strategic framing based on game state
  let strategicFrame = "";
  if (gameState) {
    const aiLifeMatch = gameState.match(/- AI: (\d+)/);
    const oppLifeMatch = gameState.match(/- Opponent[^:]*: (\d+)/);
    if (aiLifeMatch && oppLifeMatch) {
      const aiLife = parseInt(aiLifeMatch[1]);
      const oppLife = parseInt(oppLifeMatch[1]);
      if (aiLife < 8) strategicFrame = "⚠️ You're in danger zone — play conservatively or find a path to win this turn.";
      else if (oppLife < 8) strategicFrame = "🎯 Opponent is low — look for lethal damage or the winning line.";
      else if (aiLife > oppLife + 8) strategicFrame = "📈 You're ahead on life — play conservatively and don't risk board position.";
      else if (oppLife > aiLife + 8) strategicFrame = "📉 Opponent is ahead on life — need to be aggressive or find card advantage.";
    }
  }

  // Assemble prompt with all context layers
  const sections: string[] = [];

  if (deckStrategy) {
    sections.push(`## Your Game Plan\n${deckStrategy}`);
  }

  // Item 8: Card skills — specific strategy for key cards in deck
  if (cardSkills) {
    sections.push(`## Card Skills (Key Strategy Notes)\n${cardSkills}`);
  }

  // Item 7: Turn-by-turn narrative log
  if (turnLog && turnLog.length > 0) {
    const recentTurns = turnLog.slice(-8); // last 8 turns to avoid prompt bloat
    sections.push(`## Game Narrative (Turn Log)\n${recentTurns.join("\n")}`);
  }

  if (gameNotes) {
    sections.push(`## Running Game Notes\n${gameNotes}`);
  }

  if (strategicFrame) {
    sections.push(`## Strategic Assessment\n${strategicFrame}`);
  }

  sections.push(`## Current Game State\n${gameState}`);

  sections.push(`## Decision: ${method}\n${context}\n\n## Legal Actions\n${optionsList}`);

  const contextBlock = sections.join("\n\n");

  const message = `[MTG Decision: ${method}]

${contextBlock}

Before picking, think: what do I want to accomplish next turn? How does this decision set that up?
If tapping mana: consider what you want to cast with remaining mana after this play.

Reply with ONLY the index number of your chosen action. Then on a new line, update your game notes if anything important happened (opponent revealed a key card, you committed to a plan, etc.) — prefix notes with NOTES:`;

  try {
    const result = execSync(
      `openclaw agent -m ${JSON.stringify(message)} --agent ${AGENT} --json`,
      { timeout: 45000, encoding: "utf-8" }
    );

    const parsed = JSON.parse(result);
    const reply = parsed?.result?.payloads?.[0]?.text || "";

    // Extract index from first line
    const lines = reply.trim().split("\n");
    const indexMatch = lines[0].match(/\b(\d+)\b/);

    // Extract and save notes if present
    if (gameId) {
      const notesLine = lines.find((l: string) => l.startsWith("NOTES:"));
      if (notesLine) {
        const note = notesLine.replace("NOTES:", "").trim();
        const timestamp = new Date().toISOString().substring(11, 19);
        const notesPath = getNotesPath(gameId);
        fs.appendFileSync(notesPath, `[${timestamp}] (${method}) ${note}\n`);
      }
    }

    if (indexMatch) {
      const index = parseInt(indexMatch[1]);
      const validOptions = options.map(o => o.index);
      if (validOptions.includes(index)) {
        const chosen = options.find(o => o.index === index);
        log(`${method}: chose ${index} — ${chosen?.description} (gameId: ${gameId || 'none'})`);
        if (gameId) onDecision(gameId, gameState, method, chosen?.description || "");
        res.json({ index, reasoning: reply.trim() });
        return;
      }
    }

    // Couldn't parse a valid index
    log(`FALLBACK: ${method} — unparseable response: ${reply}`);
    res.json({ index: -1, reasoning: "fallback" });
  } catch (err: any) {
    log(`ERROR: ${method} — ${err.message?.substring(0, 200)}`);
    res.json({ index: -1, reasoning: "fallback" });
  }
});

app.get("/health", (_req, res) => {
  res.json({ status: "ok", mode: "openclaw", agent: AGENT });
});

// Clean old notes on startup
cleanOldNotes();

app.listen(PORT, () => {
  console.log(`Forge LLM bridge on port ${PORT} — routing to OpenClaw agent: ${AGENT}`);
});

// --- Chat Presence ---

// POST /chat — explicit game event chat triggers
app.post("/chat", (req, res) => {
  const evt = req.body as ChatEvent;
  if (!evt.gameId || !evt.event) {
    res.status(400).json({ error: "gameId and event required" });
    return;
  }
  const result = handleChatEvent(evt);
  res.json(result);
});
