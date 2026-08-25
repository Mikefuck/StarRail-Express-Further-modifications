package com.habitrain.core.api;

/**
 * Canonical short game-mode ids and mapping from registry / SRE identifiers.
 *
 * <p>Registry keys are {@code habitrain_core:<shortId>} (see
 * {@link GameModeRegistry#register}). Upstream SRE blackout is {@code sre:blackout}
 * and is treated as {@link #BLACKOUT}.
 */
public final class GameModeIds {
    public static final String BLACKOUT = "habitrain:blackout";
    public static final String MURDER = "sre:murder";
    public static final String REPAIR = "sre:repair";

    public static final String REGISTRY_BLACKOUT = "habitrain_core:habitrain:blackout";
    public static final String REGISTRY_MURDER = "habitrain_core:sre:murder";
    public static final String REGISTRY_REPAIR = "habitrain_core:sre:repair";

    public static final String SRE_BLACKOUT = "sre:blackout";
    public static final String SRE_REPAIR_ESCAPE = "canyuesama:repair_escape";

    private GameModeIds() {}

    /** Maps any registry / SRE / class-name guess to a canonical short id. */
    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return MURDER;
        }
        String lower = raw.trim().toLowerCase();
        if (isBlackout(lower)) {
            return BLACKOUT;
        }
        if (isRepair(lower)) {
            return REPAIR;
        }
        if (isMurder(lower)) {
            return MURDER;
        }
        int slash = lower.lastIndexOf('.');
        if (slash >= 0 && slash + 1 < lower.length()) {
            return canonical(lower.substring(slash + 1));
        }
        return raw.trim();
    }

    public static boolean isBlackout(String raw) {
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("blackout")
                || BLACKOUT.equals(lower)
                || REGISTRY_BLACKOUT.equals(lower)
                || SRE_BLACKOUT.equals(lower);
    }

    public static boolean isRepair(String raw) {
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("repair")
                || REPAIR.equals(lower)
                || REGISTRY_REPAIR.equals(lower)
                || SRE_REPAIR_ESCAPE.equals(lower)
                || lower.contains("repair_escape");
    }

    public static boolean isMurder(String raw) {
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase();
        return MURDER.equals(lower)
                || REGISTRY_MURDER.equals(lower)
                || lower.contains("sre:murder")
                || (lower.contains("murder") && !isBlackout(lower) && !isRepair(lower));
    }
}
