package com.habitrain.core.config;

/** Shared blackout cooldown bounds and unit conversion. */
public final class BlackoutGlobalCooldownRules {
    public static final int DEFAULT_SECONDS = 40;
    public static final int MAX_SECONDS = 3600;
    private static final int TICKS_PER_SECOND = 20;

    private BlackoutGlobalCooldownRules() {}

    public static int clampSeconds(int seconds) {
        return Math.max(0, Math.min(MAX_SECONDS, seconds));
    }

    public static int toTicks(int seconds) {
        return clampSeconds(seconds) * TICKS_PER_SECOND;
    }
}
