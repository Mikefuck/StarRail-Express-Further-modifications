package com.habitrain.core.api.match;

/**
 * Coarse win bucket for settlement consumers. Mapped from SRE {@code GameUtils.WinStatus}.
 */
public enum MatchWinKind {
    NONE,
    INNOCENT,
    KILLER,
    CUSTOM,
    NO_PLAYER,
    OTHER;

    public static MatchWinKind fromWinStatusName(String name) {
        if (name == null || name.isBlank()) {
            return NONE;
        }
        return switch (name.trim().toUpperCase()) {
            case "NONE", "NOT_MODIFY" -> NONE;
            case "KILLERS" -> KILLER;
            case "PASSENGERS", "TIME" -> INNOCENT;
            case "CUSTOM", "CUSTOM_COMPONENT", "LOVERS", "GAMBLER", "RECORDER",
                    "LOOSE_END", "NIAN_SHOU" -> CUSTOM;
            case "NO_PLAYER" -> NO_PLAYER;
            default -> OTHER;
        };
    }

    public boolean isKillerWin() {
        return this == KILLER;
    }

    public boolean isInnocentWin() {
        return this == INNOCENT;
    }
}
