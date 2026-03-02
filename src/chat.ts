/**
 * Discord chat presence for MTG AI.
 * Posts reactive messages to #mtg channel during games.
 * Lightweight: mostly templates, occasional LLM for complex moments.
 */

import { execSync } from "child_process";

const DISCORD_TARGET = "1477853452829987016";
const DISCORD_GUILD = "1473331036099444870";
const AGENT = process.env.OPENCLAW_AGENT || "mtg";

// Per-game chat state
interface ChatState {
  gameId: string;
  lastChatTurn: number;
  currentTurn: number;
  aiLife: number;
  oppLife: number;
  prevAiLife: number;
  prevOppLife: number;
  aiCreatureCount: number;
  oppCreatureCount: number;
  messageCount: number;
  sentGreeting: boolean;
  sentGG: boolean;
}

const games = new Map<string, ChatState>();

function getState(gameId: string): ChatState {
  if (!games.has(gameId)) {
    games.set(gameId, {
      gameId,
      lastChatTurn: 0,
      currentTurn: 0,
      aiLife: 20,
      oppLife: 20,
      prevAiLife: 20,
      prevOppLife: 20,
      aiCreatureCount: 0,
      oppCreatureCount: 0,
      messageCount: 0,
      sentGreeting: false,
      sentGG: false,
    });
  }
  return games.get(gameId)!;
}

function cleanupGame(gameId: string) {
  games.delete(gameId);
}

// --- Message pools ---

const GREETINGS = ["gl hf", "gl hf 🤖", "glhf"];
const GG_WIN = ["gg wp", "gg", "good game"];
const GG_LOSE = ["gg", "gg wp", "well played"];
const GG_CLOSE = ["gg that was close", "gg wp, tight game", "gg, what a game"];
const REMATCH = ["rematch?", "run it back?", "again?"];

const TOOK_BIG_HIT = ["oof", "that hurt", "ouch", "okay then", "pain"];
const DEALT_BIG_HIT = ["let's go", "📈", "that's a clock"];
const BOARD_WIPE_RECEIVED = ["well there goes my board", "cool cool cool", "rude", "didn't see that coming"];
const BOARD_WIPE_DEALT = ["clean slate", "needed that", "reset button"];
const LOW_LIFE_SELF = ["I think I'm dead here", "this is bad", "sweating", "need a miracle"];
const LOW_LIFE_OPP = ["got you on the ropes", "one more hit", "almost there"];
const THINKING = ["thinking...", "hmm", "wait", "let me think about this", "interesting"];
const MISPLAY = ["should've held that up", "misplayed that", "that was wrong", "wait no", "I immediately regret that"];
const INTERESTING_LINE = ["interesting line", "spicy", "okay I see you", "that's a line"];

function pick(pool: string[]): string {
  return pool[Math.floor(Math.random() * pool.length)];
}

// --- Discord posting ---

function sendToDiscord(text: string) {
  try {
    const escaped = text.replace(/'/g, "'\\''");
    execSync(
      `openclaw agent -m 'Send this exact Discord message to channel ${DISCORD_TARGET} guild ${DISCORD_GUILD}. Message: ${escaped}' --agent ${AGENT} --json`,
      { timeout: 15000, encoding: "utf-8" }
    );
  } catch (err: any) {
    console.error(`[chat] Discord send failed: ${err.message?.substring(0, 100)}`);
  }
}

// Fire-and-forget
function sendAsync(text: string) {
  setImmediate(() => sendToDiscord(text));
}

// --- Cooldown check ---

function canChat(state: ChatState, minTurnGap = 3): boolean {
  return state.currentTurn - state.lastChatTurn >= minTurnGap;
}

function markChatted(state: ChatState) {
  state.lastChatTurn = state.currentTurn;
  state.messageCount++;
}

// --- Event handlers ---

export function onGameStart(gameId: string) {
  const state = getState(gameId);
  if (!state.sentGreeting) {
    state.sentGreeting = true;
    sendAsync(pick(GREETINGS));
    markChatted(state);
  }
}

export function onGameEnd(gameId: string, result: "win" | "lose" | "draw") {
  const state = getState(gameId);
  if (state.sentGG) return;
  state.sentGG = true;

  const wasClose = Math.abs(state.aiLife - state.oppLife) <= 5 ||
    (state.aiLife <= 8 && state.oppLife <= 8);

  let msg: string;
  if (wasClose) msg = pick(GG_CLOSE);
  else if (result === "win") msg = pick(GG_WIN);
  else msg = pick(GG_LOSE);

  sendAsync(msg);

  if (Math.random() < 0.4) {
    setTimeout(() => sendAsync(pick(REMATCH)), 3000);
  }

  setTimeout(() => cleanupGame(gameId), 10000);
}

/**
 * Called after each /decide with updated game context.
 * Parses state and decides whether to chat.
 */
export function onDecision(
  gameId: string,
  gameState: string,
  method: string,
  chosenDescription: string,
) {
  const state = getState(gameId);

  // Parse turn
  const turnMatch = gameState.match(/Turn\s+(\d+)/i);
  if (turnMatch) state.currentTurn = parseInt(turnMatch[1]);

  const prevAiLife = state.aiLife;
  const prevOppLife = state.oppLife;

  const aiLifeMatch = gameState.match(/- AI[^:]*:\s*(\d+)/);
  const oppLifeMatch = gameState.match(/- Opponent[^:]*:\s*(\d+)/);
  if (aiLifeMatch) state.aiLife = parseInt(aiLifeMatch[1]);
  if (oppLifeMatch) state.oppLife = parseInt(oppLifeMatch[1]);

  state.prevAiLife = prevAiLife;
  state.prevOppLife = prevOppLife;

  // Count creatures (rough)
  const aiSection = (gameState.match(/AI.*?Battlefield.*?(?=Opponent|$)/is) || [""])[0];
  const oppSection = (gameState.match(/Opponent.*?Battlefield.*?(?=$)/is) || [""])[0];
  const prevAiCreatures = state.aiCreatureCount;
  const prevOppCreatures = state.oppCreatureCount;
  state.aiCreatureCount = (aiSection.match(/\n/g) || []).length;
  state.oppCreatureCount = (oppSection.match(/\n/g) || []).length;

  // --- Trigger checks (priority order, one message max) ---

  // Took 5+ damage
  if (prevAiLife - state.aiLife >= 5 && canChat(state, 2)) {
    sendAsync(pick(TOOK_BIG_HIT));
    markChatted(state);
    return;
  }

  // Dealt 5+ damage
  if (prevOppLife - state.oppLife >= 5 && canChat(state, 3)) {
    sendAsync(pick(DEALT_BIG_HIT));
    markChatted(state);
    return;
  }

  // Lost 3+ creatures (board wipe)
  if (prevAiCreatures >= 3 && state.aiCreatureCount <= 1 && canChat(state, 2)) {
    sendAsync(pick(BOARD_WIPE_RECEIVED));
    markChatted(state);
    return;
  }

  // Wiped their board
  if (prevOppCreatures >= 3 && state.oppCreatureCount <= 1 && canChat(state, 2)) {
    sendAsync(pick(BOARD_WIPE_DEALT));
    markChatted(state);
    return;
  }

  // Dropped to low life
  if (state.aiLife <= 5 && state.aiLife > 0 && prevAiLife > 5 && canChat(state, 3)) {
    sendAsync(pick(LOW_LIFE_SELF));
    markChatted(state);
    return;
  }

  // Opponent dropped to low life
  if (state.oppLife <= 5 && state.oppLife > 0 && prevOppLife > 5 && canChat(state, 3)) {
    sendAsync(pick(LOW_LIFE_OPP));
    markChatted(state);
    return;
  }

  // Random thinking on complex decisions (~20%)
  if (method.includes("Choose") && canChat(state, 4) && Math.random() < 0.2) {
    sendAsync(pick(THINKING));
    markChatted(state);
    return;
  }

  // Random interesting line (~10%)
  if (canChat(state, 4) && Math.random() < 0.1) {
    sendAsync(pick(INTERESTING_LINE));
    markChatted(state);
    return;
  }
}

/**
 * POST /chat endpoint handler.
 */
export interface ChatEvent {
  gameId: string;
  event: "game_start" | "game_end" | "decision_made" | "opponent_play" | "life_change" | "board_wipe" | "topdeck";
  result?: "win" | "lose" | "draw";
  gameState?: string;
  method?: string;
  description?: string;
}

export function handleChatEvent(evt: ChatEvent): { sent: boolean; message?: string } {
  switch (evt.event) {
    case "game_start":
      onGameStart(evt.gameId);
      return { sent: true };

    case "game_end":
      onGameEnd(evt.gameId, evt.result || "lose");
      return { sent: true };

    case "decision_made":
      if (evt.gameState) {
        onDecision(evt.gameId, evt.gameState, evt.method || "", evt.description || "");
      }
      return { sent: true };

    case "topdeck": {
      const state = getState(evt.gameId);
      if (canChat(state, 2)) {
        sendAsync("nice topdeck");
        markChatted(state);
        return { sent: true };
      }
      return { sent: false };
    }

    case "board_wipe": {
      const state = getState(evt.gameId);
      if (canChat(state, 2)) {
        sendAsync(pick(BOARD_WIPE_RECEIVED));
        markChatted(state);
        return { sent: true };
      }
      return { sent: false };
    }

    default:
      return { sent: false };
  }
}
