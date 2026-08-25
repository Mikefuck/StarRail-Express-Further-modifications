package com.habitrain.core.api.match;

/**
 * Grant / settlement faction bucket. Distinct from {@code RoleFaction} (which
 * includes vigilante/mafia tags used for queries).
 */
public enum MatchWinFaction {
    PASSENGER,
    KILLER,
    NEUTRAL;

    public static MatchWinFaction fromBlackoutName(String factionName, boolean killerWon) {
        if (factionName == null || factionName.isBlank()) {
            return null;
        }
        return switch (factionName) {
            case "BAD" -> KILLER;
            case "SIN_KILLER_SHARE" -> killerWon ? KILLER : NEUTRAL;
            case "SIN_INDEPENDENT" -> NEUTRAL;
            case "GOOD" -> PASSENGER;
            default -> null;
        };
    }

    public static MatchWinFaction fromRoleFlags(
            boolean neutrals,
            boolean customWinner,
            boolean neutralForInnocent,
            boolean neutralForKiller,
            boolean killer,
            boolean killerTeam,
            boolean canUseKiller,
            boolean mafiaTeam) {
        if (neutrals
                || customWinner
                || (neutralForInnocent && neutralForKiller && !killerTeam && !canUseKiller)) {
            return NEUTRAL;
        }
        if (killer || killerTeam || canUseKiller || mafiaTeam) {
            return KILLER;
        }
        return PASSENGER;
    }
}
