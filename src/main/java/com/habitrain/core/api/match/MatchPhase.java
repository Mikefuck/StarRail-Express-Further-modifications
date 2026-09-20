package com.habitrain.core.api.match;

import java.util.Locale;

/**
 * Public match phase. Names align with SRE {@code SREGameWorldComponent.GameStatus}.
 */
public enum MatchPhase {
    INACTIVE,
    STARTING,
    INITIATING,
    ACTIVE,
    STOPPING,
    UNKNOWN;

    /**
     * True once the round has left the lobby (cards / lobby menus must refuse).
     *
     * <p>{@link #UNKNOWN} is fail-<b>closed</b>: it counts as "occupied" so an
     * unreadable status never lets a lobby card through. The method name reads as
     * "did we leave the lobby", so treat {@code UNKNOWN} as "assume we did"
     * (审核 C4).</p>
     */
    public boolean hasLeftLobby() {
        return this != INACTIVE;
    }

    public static MatchPhase fromStatusName(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        // 审核 B9：默认 Locale 在 tr/az 下会把 "INITIATING" 变成 "INITIATİNG"，字面量比较失效。
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "INACTIVE" -> INACTIVE;
            case "STARTING" -> STARTING;
            case "INITIATING" -> INITIATING;
            case "ACTIVE" -> ACTIVE;
            case "STOPPING" -> STOPPING;
            default -> UNKNOWN;
        };
    }
}
