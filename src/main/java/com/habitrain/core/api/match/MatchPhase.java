package com.habitrain.core.api.match;

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

    /** True once the round has left the lobby (cards / lobby menus must refuse). UNKNOWN fail-closed. */
    public boolean hasLeftLobby() {
        return this != INACTIVE;
    }

    public static MatchPhase fromStatusName(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        return switch (name.trim().toUpperCase()) {
            case "INACTIVE" -> INACTIVE;
            case "STARTING" -> STARTING;
            case "INITIATING" -> INITIATING;
            case "ACTIVE" -> ACTIVE;
            case "STOPPING" -> STOPPING;
            default -> UNKNOWN;
        };
    }
}
