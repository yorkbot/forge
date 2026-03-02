"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = __importDefault(require("express"));
const child_process_1 = require("child_process");
const fs_1 = __importDefault(require("fs"));
const path_1 = __importDefault(require("path"));
const app = (0, express_1.default)();
app.use(express_1.default.json({ limit: "4mb" }));
const PORT = process.env.PORT || 8080;
const LOG_FILE = path_1.default.join(__dirname, "..", "decisions.log");
const NOTES_DIR = path_1.default.join(__dirname, "..", "game-notes");
const AGENT = process.env.OPENCLAW_AGENT || "mtg";
// Ensure game notes directory exists
if (!fs_1.default.existsSync(NOTES_DIR)) {
    fs_1.default.mkdirSync(NOTES_DIR, { recursive: true });
}
function log(entry) {
    const line = `[${new Date().toISOString()}] ${entry}\n`;
    fs_1.default.appendFileSync(LOG_FILE, line);
}
function getNotesPath(gameId) {
    return path_1.default.join(NOTES_DIR, `${gameId}.md`);
}
function readGameNotes(gameId) {
    const notesPath = getNotesPath(gameId);
    if (fs_1.default.existsSync(notesPath)) {
        return fs_1.default.readFileSync(notesPath, "utf-8");
    }
    return "";
}
function getDeckStrategyPath(gameId) {
    return path_1.default.join(NOTES_DIR, `${gameId}-strategy.md`);
}
function readDeckStrategy(gameId) {
    const stratPath = getDeckStrategyPath(gameId);
    if (fs_1.default.existsSync(stratPath)) {
        return fs_1.default.readFileSync(stratPath, "utf-8");
    }
    return "";
}
// Clean up old game notes (older than 24h)
function cleanOldNotes() {
    try {
        const files = fs_1.default.readdirSync(NOTES_DIR);
        const cutoff = Date.now() - 24 * 60 * 60 * 1000;
        for (const f of files) {
            const fp = path_1.default.join(NOTES_DIR, f);
            const stat = fs_1.default.statSync(fp);
            if (stat.mtimeMs < cutoff) {
                fs_1.default.unlinkSync(fp);
            }
        }
    }
    catch (_) { }
}
// POST /analyze-deck — called at game start, generates strategy
app.post("/analyze-deck", async (req, res) => {
    const { gameId, decklist } = req.body;
    if (!gameId || !decklist) {
        res.status(400).json({ error: "gameId and decklist required" });
        return;
    }
    log(`DECK-ANALYSIS: gameId=${gameId}, analyzing ${decklist.split('\n').length} cards`);
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
        const result = (0, child_process_1.execSync)(`openclaw agent -m ${JSON.stringify(message)} --agent ${AGENT} --json`, { timeout: 90000, encoding: "utf-8" });
        const parsed = JSON.parse(result);
        const strategy = parsed?.result?.payloads?.[0]?.text || "";
        if (strategy) {
            const stratPath = getDeckStrategyPath(gameId);
            fs_1.default.writeFileSync(stratPath, `# Deck Strategy — Game ${gameId}\n\n${strategy}\n`);
            log(`DECK-ANALYSIS: strategy cached for game ${gameId}`);
        }
        res.json({ ok: true, strategy });
    }
    catch (err) {
        log(`DECK-ANALYSIS ERROR: ${err.message?.substring(0, 200)}`);
        res.json({ ok: false });
    }
});
app.post("/decide", async (req, res) => {
    const { gameId, method, gameState, options, context } = req.body;
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
    // Build strategic framing based on game state
    let strategicFrame = "";
    if (gameState) {
        // Simple heuristics for strategic framing
        const aiLifeMatch = gameState.match(/- AI: (\d+)/);
        const oppLifeMatch = gameState.match(/- Opponent[^:]*: (\d+)/);
        if (aiLifeMatch && oppLifeMatch) {
            const aiLife = parseInt(aiLifeMatch[1]);
            const oppLife = parseInt(oppLifeMatch[1]);
            if (aiLife < 8)
                strategicFrame = "⚠️ You're in danger zone — play conservatively or find a path to win this turn.";
            else if (oppLife < 8)
                strategicFrame = "🎯 Opponent is low — look for lethal damage or the winning line.";
            else if (aiLife > oppLife + 8)
                strategicFrame = "📈 You're ahead on life — play conservatively and don't risk board position.";
            else if (oppLife > aiLife + 8)
                strategicFrame = "📉 Opponent is ahead on life — need to be aggressive or find card advantage.";
        }
    }
    // Assemble prompt with all context layers
    const sections = [];
    if (deckStrategy) {
        sections.push(`## Your Game Plan\n${deckStrategy}`);
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
        const result = (0, child_process_1.execSync)(`openclaw agent -m ${JSON.stringify(message)} --agent ${AGENT} --json`, { timeout: 45000, encoding: "utf-8" });
        const parsed = JSON.parse(result);
        const reply = parsed?.result?.payloads?.[0]?.text || "";
        // Extract index from first line
        const lines = reply.trim().split("\n");
        const indexMatch = lines[0].match(/\b(\d+)\b/);
        // Extract and save notes if present
        if (gameId) {
            const notesLine = lines.find((l) => l.startsWith("NOTES:"));
            if (notesLine) {
                const note = notesLine.replace("NOTES:", "").trim();
                const timestamp = new Date().toISOString().substring(11, 19);
                const notesPath = getNotesPath(gameId);
                fs_1.default.appendFileSync(notesPath, `[${timestamp}] (${method}) ${note}\n`);
            }
        }
        if (indexMatch) {
            const index = parseInt(indexMatch[1]);
            const validOptions = options.map(o => o.index);
            if (validOptions.includes(index)) {
                const chosen = options.find(o => o.index === index);
                log(`${method}: chose ${index} — ${chosen?.description} (gameId: ${gameId || 'none'})`);
                res.json({ index, reasoning: reply.trim() });
                return;
            }
        }
        // Couldn't parse a valid index
        log(`FALLBACK: ${method} — unparseable response: ${reply}`);
        res.json({ index: -1, reasoning: "fallback" });
    }
    catch (err) {
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
