package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.Map;

public class GameStateSerializer {

    public static String serialize(Game game, Player aiPlayer) {
        StringBuilder sb = new StringBuilder();

        // Turn info
        sb.append("# Turn ").append(game.getPhaseHandler().getTurn());
        sb.append(" — ").append(game.getPhaseHandler().getPhase());
        Player active = game.getPhaseHandler().getPlayerTurn();
        if (active != null) {
            sb.append(" (").append(active == aiPlayer ? "AI's turn" : "Opponent's turn").append(")");
        }
        sb.append("\n\n");

        // Life totals
        sb.append("## Life Totals\n");
        sb.append("- AI: ").append(aiPlayer.getLife()).append("\n");
        for (Player opp : aiPlayer.getOpponents()) {
            sb.append("- Opponent (").append(opp.getName()).append("): ").append(opp.getLife()).append("\n");
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

        // AI's hand
        sb.append("## AI's Hand\n");
        CardCollectionView hand = aiPlayer.getCardsIn(ZoneType.Hand);
        if (hand.isEmpty()) {
            sb.append("(empty)\n");
        } else {
            for (Card c : hand) {
                sb.append("- ").append(c.getName());
                if (c.getManaCost() != null) {
                    sb.append(" {").append(c.getManaCost().toString()).append("}");
                }
                String oracle = c.getOracleText();
                if (oracle != null && !oracle.isEmpty()) {
                    // Truncate long oracle text
                    String summary = oracle.length() > 120
                            ? oracle.substring(0, 120) + "..."
                            : oracle;
                    sb.append(" — ").append(summary.replace("\n", " "));
                }
                sb.append("\n");
            }
        }
        sb.append("\n");

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

        // Mana available
        sb.append("## AI's Mana Pool\n");
        sb.append(aiPlayer.getManaPool().toString()).append("\n");

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
            if (c.isTapped()) {
                sb.append(" (tapped)");
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
