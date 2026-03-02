package forge.ai;

import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterType;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.Map;

public class GameStateSerializer {

    public static String serialize(Game game, Player aiPlayer) {
        StringBuilder sb = new StringBuilder();

        // Turn info
        sb.append("# Turn ").append(game.getPhaseHandler().getTurn());
        sb.append(" — ").append(game.getPhaseHandler().getPhase().nameForUi);
        Player active = game.getPhaseHandler().getPlayerTurn();
        if (active != null) {
            sb.append(" (").append(active == aiPlayer ? "AI's turn" : "Opponent's turn").append(")");
        }
        sb.append("\n");

        // Land drop status (only relevant on AI's turn)
        if (active == aiPlayer) {
            int landsPlayed = aiPlayer.getLandsPlayedThisTurn();
            int maxLands = aiPlayer.getMaxLandPlays();
            if (landsPlayed < maxLands) {
                sb.append("Land drop available (").append(landsPlayed).append("/").append(maxLands).append(" played this turn)\n");
            } else {
                sb.append("Land drop used (").append(landsPlayed).append("/").append(maxLands).append(")\n");
            }
        }
        sb.append("\n");

        // Life totals
        sb.append("## Life Totals\n");
        sb.append("- AI: ").append(aiPlayer.getLife()).append("\n");
        for (Player opp : aiPlayer.getOpponents()) {
            sb.append("- Opponent (").append(opp.getName()).append("): ").append(opp.getLife()).append("\n");
        }
        sb.append("\n");

        // AI's hand with costs
        sb.append("## AI's Hand (").append(aiPlayer.getCardsIn(ZoneType.Hand).size()).append(" cards)\n");
        CardCollectionView hand = aiPlayer.getCardsIn(ZoneType.Hand);
        if (hand.isEmpty()) {
            sb.append("(empty)\n");
        } else {
            for (Card c : hand) {
                sb.append("- ").append(c.getName());
                if (c.getManaCost() != null && !c.getManaCost().isNoCost()) {
                    sb.append(" [").append(c.getManaCost().toString()).append("]");
                }
                if (c.isCreature()) {
                    sb.append(" (").append(c.getNetPower()).append("/").append(c.getNetToughness()).append(")");
                }
                String oracle = c.getOracleText();
                if (oracle != null && !oracle.isEmpty()) {
                    String summary = oracle.length() > 100
                            ? oracle.substring(0, 100) + "..."
                            : oracle;
                    sb.append(" — ").append(summary.replace("\n", " "));
                }
                sb.append("\n");
            }
        }
        sb.append("\n");

        // Mana available (color breakdown)
        sb.append("## Available Mana\n");
        ManaPool pool = aiPlayer.getManaPool();
        int totalMana = pool.totalMana();
        if (totalMana == 0) {
            // Show untapped lands and what they can produce
            sb.append("Pool: (empty)\n");
            sb.append("Untapped lands: ");
            StringBuilder lands = new StringBuilder();
            for (Card c : aiPlayer.getCardsIn(ZoneType.Battlefield)) {
                if (c.isLand() && !c.isTapped()) {
                    if (lands.length() > 0) lands.append(", ");
                    lands.append(c.getName());
                }
            }
            if (lands.length() == 0) sb.append("none");
            else sb.append(lands);
            sb.append("\n");
        } else {
            sb.append("Pool: ");
            int w = pool.getAmountOfColor(MagicColor.WHITE);
            int u = pool.getAmountOfColor(MagicColor.BLUE);
            int b = pool.getAmountOfColor(MagicColor.BLACK);
            int r = pool.getAmountOfColor(MagicColor.RED);
            int g = pool.getAmountOfColor(MagicColor.GREEN);
            int c = pool.getAmountOfColor(MagicColor.COLORLESS);
            StringBuilder manaStr = new StringBuilder();
            for (int i = 0; i < w; i++) manaStr.append("{W}");
            for (int i = 0; i < u; i++) manaStr.append("{U}");
            for (int i = 0; i < b; i++) manaStr.append("{B}");
            for (int i = 0; i < r; i++) manaStr.append("{R}");
            for (int i = 0; i < g; i++) manaStr.append("{G}");
            for (int i = 0; i < c; i++) manaStr.append("{C}");
            sb.append(manaStr.length() > 0 ? manaStr : "(empty)").append(" (total: ").append(totalMana).append(")\n");
        }
        sb.append("\n");

        // AI's board
        sb.append("## AI's Battlefield\n");
        serializeBattlefield(sb, aiPlayer.getCardsIn(ZoneType.Battlefield));
        sb.append("\n");

        // Opponent's board
        for (Player opp : aiPlayer.getOpponents()) {
            sb.append("## Opponent's Battlefield (").append(opp.getName()).append(")\n");
            serializeBattlefield(sb, opp.getCardsIn(ZoneType.Battlefield));
            sb.append("\n");
        }

        // AI's graveyard
        sb.append("## AI's Graveyard\n");
        serializeCardNames(sb, aiPlayer.getCardsIn(ZoneType.Graveyard));
        sb.append("\n");

        // Opponent's graveyard + hand size
        for (Player opp : aiPlayer.getOpponents()) {
            sb.append("## Opponent's Graveyard (").append(opp.getName()).append(")\n");
            serializeCardNames(sb, opp.getCardsIn(ZoneType.Graveyard));
            sb.append("- Opponent hand size: ").append(opp.getCardsIn(ZoneType.Hand).size()).append("\n");
            sb.append("\n");
        }

        // Library sizes
        sb.append("## Library Sizes\n");
        sb.append("- AI: ").append(aiPlayer.getCardsIn(ZoneType.Library).size()).append(" cards remaining\n");
        for (Player opp : aiPlayer.getOpponents()) {
            sb.append("- Opponent (").append(opp.getName()).append("): ").append(opp.getCardsIn(ZoneType.Library).size()).append(" cards remaining\n");
        }
        sb.append("\n");

        // Stack
        sb.append("## Stack\n");
        if (game.getStack().isEmpty()) {
            sb.append("(empty)\n");
        } else {
            for (forge.game.spellability.SpellAbilityStackInstance si : game.getStack()) {
                sb.append("- ").append(si.getSpellAbility().getStackDescription()).append("\n");
            }
        }
        sb.append("\n");

        return sb.toString();
    }

    private static void serializeBattlefield(StringBuilder sb, CardCollectionView cards) {
        if (cards.isEmpty()) {
            sb.append("(empty)\n");
            return;
        }
        for (Card c : cards) {
            sb.append("- ").append(c.getName());
            if (c.isCreature()) {
                sb.append(" ").append(c.getNetPower()).append("/").append(c.getNetToughness());
            }
            if (c.isLand()) {
                sb.append(" (land)");
            }
            if (c.isTapped()) {
                sb.append(" [tapped]");
            }
            // Counters
            if (c.getCounters() != null) {
                for (Map.Entry<CounterType, Integer> entry : c.getCounters().entrySet()) {
                    if (entry.getValue() > 0) {
                        sb.append(" [").append(entry.getValue()).append(" ")
                          .append(entry.getKey().getName()).append(" counter(s)]");
                    }
                }
            }
            // Attachments (auras/equipment)
            if (c.hasCardAttachments()) {
                sb.append(" attached: ");
                boolean first = true;
                for (Card attached : c.getAttachedCards()) {
                    if (!first) sb.append(", ");
                    sb.append(attached.getName());
                    first = false;
                }
            }
            sb.append("\n");
        }
    }

    private static void serializeCardNames(StringBuilder sb, CardCollectionView cards) {
        if (cards.isEmpty()) {
            sb.append("(empty)\n");
            return;
        }
        for (Card c : cards) {
            sb.append("- ").append(c.getName()).append("\n");
        }
    }
}
