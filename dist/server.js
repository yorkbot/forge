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
app.use(express_1.default.json({ limit: "2mb" }));
const PORT = process.env.PORT || 8080;
const LOG_FILE = path_1.default.join(__dirname, "..", "decisions.log");
const AGENT = process.env.OPENCLAW_AGENT || "mtg";
function log(entry) {
    const line = `[${new Date().toISOString()}] ${entry}\n`;
    fs_1.default.appendFileSync(LOG_FILE, line);
}
app.post("/decide", async (req, res) => {
    const { method, gameState, options, context } = req.body;
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
    const message = `[MTG Decision: ${method}] ${context}

${gameState}

## Legal Actions
${optionsList}

Pick the best play. Reply with ONLY the index number.`;
    try {
        const result = (0, child_process_1.execSync)(`openclaw agent -m ${JSON.stringify(message)} --agent ${AGENT} --json`, { timeout: 45000, encoding: "utf-8" });
        const parsed = JSON.parse(result);
        const reply = parsed?.result?.payloads?.[0]?.text || "";
        // Extract index from response
        const indexMatch = reply.match(/\b(\d+)\b/);
        if (indexMatch) {
            const index = parseInt(indexMatch[1]);
            if (index >= 0 && index < options.length) {
                log(`${method}: chose ${index} — ${options[index].description} (reason: ${reply.trim()})`);
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
app.listen(PORT, () => {
    console.log(`Forge LLM bridge on port ${PORT} — routing to OpenClaw agent: ${AGENT}`);
});
