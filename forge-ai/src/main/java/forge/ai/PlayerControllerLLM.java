package forge.ai;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

/**
 * LLM-powered player controller for Forge.
 * Delegates decisions to an external HTTP decision server,
 * falling back to stock AI (super) on errors.
 *
 * Configure via system properties:
 *   -Dforge.llm.url=http://localhost:8080  (decision server URL)
 */
public class PlayerControllerLLM extends PlayerControllerAi {

    private static final String SERVER_URL = System.getProperty("forge.llm.url", "http://localhost:8080");
    private static final int TIMEOUT_MS = 30000;

    /** Unique ID for this game instance — sent with every request for server-side game notes. */
    private final String gameId = UUID.randomUUID().toString().substring(0, 8);

    // Item 7: Turn-by-turn narrative log
    private final List<String> turnLog = new ArrayList<>();
    private int lastTrackedTurn = -1;
    private final List<String> currentTurnEvents = new ArrayList<>();

    public PlayerControllerLLM(Game game, Player p, LobbyPlayer lp) {
        super(game, p, lp);
        // Kick off deck analysis asynchronously so game start isn't delayed
        analyzeDeckAsync();
    }

    // --- Deck analysis ---

    private void analyzeDeckAsync() {
        Thread t = new Thread(() -> {
            try {
                CardCollectionView deck = player.getCardsIn(forge.game.zone.ZoneType.Library);
                if (deck.isEmpty()) return;

                StringBuilder deckList = new StringBuilder();
                for (Card c : deck) {
                    deckList.append(c.getName());
                    if (c.getManaCost() != null && !c.getManaCost().isNoCost()) {
                        deckList.append(" [").append(c.getManaCost()).append("]");
                    }
                    if (c.isCreature()) {
                        deckList.append(" ").append(c.getNetPower()).append("/").append(c.getNetToughness());
                    }
                    deckList.append("\n");
                }

                StringBuilder json = new StringBuilder();
                json.append("{");
                json.append("\"gameId\":").append(jsonString(gameId)).append(",");
                json.append("\"decklist\":").append(jsonString(deckList.toString()));
                json.append("}");

                URL url = new URL(SERVER_URL + "/analyze-deck");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(60000);
                conn.setReadTimeout(60000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.toString().getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                System.out.println("[LLM] Deck analysis response: HTTP " + status);
            } catch (Exception e) {
                System.err.println("[LLM] Deck analysis failed: " + e.getMessage());
            }
        }, "LLM-DeckAnalysis");
        t.setDaemon(true);
        t.start();
    }

    // --- Smart auto-pass logic ---

    /**
     * Returns true if this is a trivial priority — only pass and mana abilities available,
     * or opponent's turn with no instant-speed plays.
     */
    private boolean shouldAutoPass(List<OptionEntry> options, String method) {
        // If it's a "chooseSpellAbilityToPlay" call with only pass + mana abilities → auto-pass
        if ("chooseSpellAbilityToPlay".equals(method)) {
            // Check if opponent's turn during a non-instant phase
            Player active = player.getGame().getPhaseHandler().getPlayerTurn();
            PhaseType phase = player.getGame().getPhaseHandler().getPhase();
            boolean isOpponentTurn = (active != null && active != player);
            boolean isInstantWindow = isOpponentTurn || phase == PhaseType.END_OF_TURN
                    || phase == PhaseType.COMBAT_DECLARE_ATTACKERS
                    || phase == PhaseType.COMBAT_DECLARE_BLOCKERS;

            // If only option is pass (index 0), auto-pass regardless
            if (options.size() == 1 && options.get(0).index == 0) {
                return true;
            }

            // During opponent's main phase with no instant-speed plays, auto-pass
            if (isOpponentTurn && (phase == PhaseType.MAIN1 || phase == PhaseType.MAIN2)) {
                // Check if any non-pass option is instant speed
                boolean hasInstantPlay = false;
                for (OptionEntry opt : options) {
                    if (opt.index > 0) {
                        hasInstantPlay = true;
                        break;
                    }
                }
                // If no real plays during opp's main, auto-pass
                // We still let the LLM decide if there ARE instant-speed plays
                return !hasInstantPlay;
            }
        }
        return false;
    }

    // --- Turn log tracking (Item 7) ---

    /**
     * Check if the turn number has advanced. If so, finalize the last turn's summary.
     */
    private void checkTurnBoundary() {
        int currentTurn = player.getGame().getPhaseHandler().getTurn();
        if (lastTrackedTurn == -1) {
            lastTrackedTurn = currentTurn;
            return;
        }
        if (currentTurn != lastTrackedTurn) {
            if (!currentTurnEvents.isEmpty()) {
                Player active = player.getGame().getPhaseHandler().getPlayerTurn();
                String whose = (active == player) ? "AI" : "Opponent";
                String summary = "Turn " + lastTrackedTurn + " (" + whose + "): "
                        + String.join("; ", currentTurnEvents);
                turnLog.add(summary);
                while (turnLog.size() > 12) turnLog.remove(0);
            }
            currentTurnEvents.clear();
            lastTrackedTurn = currentTurn;
        }
    }

    /** Record an event for the current turn narrative. */
    private void recordEvent(String event) {
        if (event != null && !event.isEmpty()) {
            currentTurnEvents.add(event);
        }
    }

    // --- HTTP helper ---

    private static class DecisionResponse {
        int index;
        String reasoning;
    }

    /**
     * POST to the decision server and return the chosen index.
     * Returns -1 on failure.
     */
    private DecisionResponse callDecisionServer(String method, String gameState, List<OptionEntry> options, String context) {
        // Update turn narrative tracking
        checkTurnBoundary();

        // Smart auto-pass check
        if (shouldAutoPass(options, method)) {
            System.out.println("[LLM] Auto-pass: " + method);
            DecisionResponse r = new DecisionResponse();
            r.index = 0; // pass
            r.reasoning = "auto-pass";
            return r;
        }

        try {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"gameId\":").append(jsonString(gameId)).append(",");
            json.append("\"method\":").append(jsonString(method)).append(",");
            json.append("\"gameState\":").append(jsonString(gameState)).append(",");
            json.append("\"context\":").append(jsonString(context)).append(",");

            // Include turn-by-turn narrative log
            if (!turnLog.isEmpty()) {
                json.append("\"turnLog\":[");
                for (int i = 0; i < turnLog.size(); i++) {
                    if (i > 0) json.append(",");
                    json.append(jsonString(turnLog.get(i)));
                }
                json.append("],");
            }

            json.append("\"options\":[");
            for (int i = 0; i < options.size(); i++) {
                if (i > 0) json.append(",");
                json.append("{\"index\":").append(options.get(i).index)
                    .append(",\"description\":").append(jsonString(options.get(i).description)).append("}");
            }
            json.append("]}");

            URL url = new URL(SERVER_URL + "/decide");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                System.err.println("[LLM] Decision server returned HTTP " + status);
                return null;
            }

            String body;
            try (Scanner sc = new Scanner(conn.getInputStream(), "UTF-8")) {
                body = sc.useDelimiter("\\A").next();
            }

            // Minimal JSON parsing — extract "index" and "reasoning"
            DecisionResponse resp = new DecisionResponse();
            resp.index = extractJsonInt(body, "index");
            resp.reasoning = extractJsonString(body, "reasoning");
            System.out.println("[LLM] " + method + " -> index " + resp.index + " (" + resp.reasoning + ")");
            return resp;
        } catch (Exception e) {
            System.err.println("[LLM] Decision server error: " + e.getMessage());
            return null;
        }
    }

    private static class OptionEntry {
        int index;
        String description;
        OptionEntry(int index, String description) {
            this.index = index;
            this.description = description;
        }
    }

    private String getGameState() {
        return GameStateSerializer.serialize(player.getGame(), player);
    }

    // --- Overrides ---

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, forge.util.ITriggerEvent triggerEvent) {
        if (abilities.isEmpty()) {
            return null;
        }
        if (abilities.size() == 1) {
            return abilities.get(0);
        }

        List<OptionEntry> options = new ArrayList<>();
        for (int i = 0; i < abilities.size(); i++) {
            SpellAbility sa = abilities.get(i);
            options.add(new OptionEntry(i, sa.toString()));
        }

        DecisionResponse resp = callDecisionServer("getAbilityToPlay", getGameState(), options,
                "Choose which ability to activate on " + hostCard.getName());
        if (resp != null && resp.index >= 0 && resp.index < abilities.size()) {
            return abilities.get(resp.index);
        }
        return super.getAbilityToPlay(hostCard, abilities, triggerEvent);
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        // Get creatures that could attack
        CardCollection creatures = attacker.getCreaturesInPlay();
        if (creatures.isEmpty()) {
            return;
        }

        List<OptionEntry> options = new ArrayList<>();
        StringBuilder allDesc = new StringBuilder("Attack with all: ");
        List<Card> canAttack = new ArrayList<>();
        for (Card c : creatures) {
            if (CombatUtil.canAttack(c) && !c.isTapped()) {
                canAttack.add(c);
                if (canAttack.size() > 1) allDesc.append(", ");
                allDesc.append(c.getName()).append(" ").append(c.getNetPower()).append("/").append(c.getNetToughness());
            }
        }

        if (canAttack.isEmpty()) {
            return;
        }

        options.add(new OptionEntry(0, "Don't attack"));
        options.add(new OptionEntry(1, allDesc.toString()));
        for (int i = 0; i < canAttack.size(); i++) {
            Card c = canAttack.get(i);
            options.add(new OptionEntry(i + 2, "Attack with " + c.getName() + " " + c.getNetPower() + "/" + c.getNetToughness()));
        }

        DecisionResponse resp = callDecisionServer("declareAttackers", getGameState(), options,
                "Choose attackers. You can pick all, none, or specific creatures.");
        if (resp != null) {
            if (resp.index == 0) {
                return;
            } else if (resp.index == 1) {
                GameEntity defender = combat.getDefenders().iterator().next();
                for (Card c : canAttack) {
                    combat.addAttacker(c, defender);
                }
                // Record attack
                StringBuilder attackDesc = new StringBuilder("attacked with all:");
                for (Card c : canAttack) attackDesc.append(" ").append(c.getName());
                recordEvent(attackDesc.toString());
                return;
            } else {
                int creatureIdx = resp.index - 2;
                if (creatureIdx >= 0 && creatureIdx < canAttack.size()) {
                    GameEntity defender = combat.getDefenders().iterator().next();
                    Card attackCard = canAttack.get(creatureIdx);
                    combat.addAttacker(attackCard, defender);
                    recordEvent("attacked with " + attackCard.getName());
                    return;
                }
            }
        }
        super.declareAttackers(attacker, combat);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        CardCollection creatures = defender.getCreaturesInPlay();
        CardCollection attackers = combat.getAttackers();
        if (creatures.isEmpty() || attackers.isEmpty()) {
            return;
        }

        List<Card> canBlock = new ArrayList<>();
        for (Card c : creatures) {
            if (!c.isTapped()) {
                canBlock.add(c);
            }
        }

        if (canBlock.isEmpty()) {
            return;
        }

        List<OptionEntry> options = new ArrayList<>();
        options.add(new OptionEntry(0, "Don't block"));

        int idx = 1;
        for (Card blocker : canBlock) {
            for (Card attacker : attackers) {
                options.add(new OptionEntry(idx,
                        "Block " + attacker.getName() + " " + attacker.getNetPower() + "/" + attacker.getNetToughness()
                        + " with " + blocker.getName() + " " + blocker.getNetPower() + "/" + blocker.getNetToughness()));
                idx++;
            }
        }

        DecisionResponse resp = callDecisionServer("declareBlockers", getGameState(), options,
                "Choose blocking assignments.");
        if (resp != null) {
            if (resp.index == 0) {
                return;
            }
            int pairIdx = resp.index - 1;
            int blockerIdx = pairIdx / attackers.size();
            int attackerIdx = pairIdx % attackers.size();
            if (blockerIdx < canBlock.size() && attackerIdx < attackers.size()) {
                Card blocker = canBlock.get(blockerIdx);
                Card atk = attackers.get(attackerIdx);
                combat.addBlocker(blocker, atk);
                recordEvent("blocked " + atk.getName() + " with " + blocker.getName());
                return;
            }
        }
        super.declareBlockers(defender, combat);
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        if (sourceList.size() <= min) {
            return sourceList;
        }

        List<OptionEntry> options = new ArrayList<>();
        for (int i = 0; i < sourceList.size(); i++) {
            Card c = sourceList.get(i);
            options.add(new OptionEntry(i, c.getName() + (c.isCreature() ? " " + c.getNetPower() + "/" + c.getNetToughness() : "")));
        }

        DecisionResponse resp = callDecisionServer("chooseCardsForEffect", getGameState(), options,
                title + " (pick " + min + " to " + max + ")");
        if (resp != null && resp.index >= 0 && resp.index < sourceList.size()) {
            CardCollection result = new CardCollection();
            result.add(sourceList.get(resp.index));
            return result;
        }
        return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        if (optionList.size() <= 1) {
            return optionList.isEmpty() ? null : optionList.getFirst();
        }

        List<OptionEntry> options = new ArrayList<>();
        List<T> entities = new ArrayList<>();
        int i = 0;
        for (T entity : optionList) {
            entities.add(entity);
            options.add(new OptionEntry(i, entity.toString()));
            i++;
        }

        DecisionResponse resp = callDecisionServer("chooseSingleEntityForEffect", getGameState(), options, title);
        if (resp != null && resp.index >= 0 && resp.index < entities.size()) {
            return entities.get(resp.index);
        }
        return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        List<OptionEntry> llmOptions = new ArrayList<>();
        llmOptions.add(new OptionEntry(0, "Yes"));
        llmOptions.add(new OptionEntry(1, "No"));

        DecisionResponse resp = callDecisionServer("confirmAction", getGameState(), llmOptions, message);
        if (resp != null) {
            return resp.index == 0;
        }
        return super.confirmAction(sa, mode, message, options, cardToShow, params);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        if (topN.size() <= 1) {
            List<OptionEntry> options = new ArrayList<>();
            options.add(new OptionEntry(0, "Keep on top: " + (topN.isEmpty() ? "none" : topN.get(0).getName())));
            options.add(new OptionEntry(1, "Put on bottom: " + (topN.isEmpty() ? "none" : topN.get(0).getName())));

            DecisionResponse resp = callDecisionServer("arrangeForScry", getGameState(), options, "Scry: top or bottom?");
            if (resp != null) {
                CardCollection top = new CardCollection();
                CardCollection bottom = new CardCollection();
                if (resp.index == 0) {
                    top.addAll(topN);
                } else {
                    bottom.addAll(topN);
                }
                return ImmutablePair.of(top, bottom);
            }
        }

        CardCollection top = new CardCollection();
        CardCollection bottom = new CardCollection();
        for (Card c : topN) {
            List<OptionEntry> options = new ArrayList<>();
            options.add(new OptionEntry(0, "Keep on top: " + c.getName()));
            options.add(new OptionEntry(1, "Put on bottom: " + c.getName()));

            DecisionResponse resp = callDecisionServer("arrangeForScry", getGameState(), options,
                    "Scry " + c.getName() + ": top or bottom?");
            if (resp != null && resp.index == 1) {
                bottom.add(c);
            } else {
                top.add(c);
            }
        }
        return ImmutablePair.of(top, bottom);
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min, int max) {
        if (validCards.size() <= min) {
            return validCards;
        }

        List<OptionEntry> options = new ArrayList<>();
        for (int i = 0; i < validCards.size(); i++) {
            Card c = validCards.get(i);
            options.add(new OptionEntry(i, c.getName() + (c.getManaCost() != null ? " {" + c.getManaCost() + "}" : "")));
        }

        DecisionResponse resp = callDecisionServer("chooseCardsToDiscardFrom", getGameState(), options,
                "Choose a card to discard (need " + min + " to " + max + ")");
        if (resp != null && resp.index >= 0 && resp.index < validCards.size()) {
            CardCollection result = new CardCollection();
            result.add(validCards.get(resp.index));
            if (min > 1) {
                CardCollection remaining = new CardCollection(validCards);
                remaining.remove(validCards.get(resp.index));
                CardCollection moreDiscards = super.chooseCardsToDiscardFrom(p, sa, remaining, min - 1, max - 1);
                result.addAll(moreDiscards);
            }
            return result;
        }
        return super.chooseCardsToDiscardFrom(p, sa, validCards, min, max);
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        if (spells.size() <= 1) {
            return spells.isEmpty() ? null : spells.get(0);
        }

        List<OptionEntry> options = new ArrayList<>();
        for (int i = 0; i < spells.size(); i++) {
            options.add(new OptionEntry(i, spells.get(i).toString()));
        }

        DecisionResponse resp = callDecisionServer("chooseSingleSpellForEffect", getGameState(), options, title);
        if (resp != null && resp.index >= 0 && resp.index < spells.size()) {
            return spells.get(resp.index);
        }
        return super.chooseSingleSpellForEffect(spells, sa, title, params);
    }

    // --- JSON helpers (no external dependency) ---

    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    private static int extractJsonInt(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return -1;
        idx += search.length();
        while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) idx++;
        StringBuilder num = new StringBuilder();
        while (idx < json.length() && (Character.isDigit(json.charAt(idx)) || json.charAt(idx) == '-')) {
            num.append(json.charAt(idx));
            idx++;
        }
        return num.length() > 0 ? Integer.parseInt(num.toString()) : -1;
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx == -1) return "";
        idx += search.length();
        StringBuilder sb = new StringBuilder();
        while (idx < json.length() && json.charAt(idx) != '"') {
            if (json.charAt(idx) == '\\' && idx + 1 < json.length()) {
                idx++;
                char c = json.charAt(idx);
                if (c == 'n') sb.append('\n');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
            } else {
                sb.append(json.charAt(idx));
            }
            idx++;
        }
        return sb.toString();
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        List<SpellAbility> allPlayable = new ArrayList<>();
        for (Card c : player.getCardsIn(forge.game.zone.ZoneType.Hand)) {
            for (SpellAbility sa : c.getAllPossibleAbilities(player, false)) {
                if (sa.canPlay()) { allPlayable.add(sa); }
            }
        }
        for (Card c : player.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
            for (SpellAbility sa : c.getAllPossibleAbilities(player, false)) {
                if (sa.canPlay() && !sa.isLandAbility()) { allPlayable.add(sa); }
            }
        }
        CardCollection lands = ComputerUtilAbility.getAvailableLandsToPlay(player.getGame(), player);
        if (lands != null) {
            for (Card land : lands) {
                for (SpellAbility sa : land.getAllPossibleAbilities(player, true)) {
                    if (sa.isLandAbility()) { allPlayable.add(sa); }
                }
            }
        }

        // Filter out pure mana abilities — they're not meaningful decisions
        List<SpellAbility> nonManaPlayable = new ArrayList<>();
        for (SpellAbility sa : allPlayable) {
            if (!sa.isManaAbility()) {
                nonManaPlayable.add(sa);
            }
        }

        if (nonManaPlayable.isEmpty()) {
            // Only mana abilities available — auto-pass
            return null;
        }

        List<OptionEntry> options = new ArrayList<>();
        options.add(new OptionEntry(0, "Pass priority (do nothing)"));
        for (int i = 0; i < nonManaPlayable.size(); i++) {
            SpellAbility sa = nonManaPlayable.get(i);
            String desc = sa.getHostCard() != null ? sa.getHostCard().getName() + " - " + sa.toString() : sa.toString();
            options.add(new OptionEntry(i + 1, desc));
        }

        DecisionResponse resp = callDecisionServer("chooseSpellAbilityToPlay", getGameState(), options,
                "Choose what to play. Option 0 passes priority.");
        if (resp != null && resp.index > 0 && resp.index <= nonManaPlayable.size()) {
            SpellAbility chosen = nonManaPlayable.get(resp.index - 1);
            // Record for turn narrative
            String cardName = chosen.getHostCard() != null ? chosen.getHostCard().getName() : chosen.toString();
            recordEvent("played " + cardName);
            List<SpellAbility> result = new ArrayList<>();
            result.add(chosen);
            return result;
        } else if (resp != null && resp.index == 0) {
            return null;
        }
        return super.chooseSpellAbilityToPlay();
    }
}
