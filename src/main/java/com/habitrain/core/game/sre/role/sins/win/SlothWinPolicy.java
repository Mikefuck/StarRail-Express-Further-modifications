package com.habitrain.core.game.sre.role.sins.win;

import org.jetbrains.annotations.Nullable;

/**
 * Sloth may steal a real faction/timer proposal, never the blackout
 * per-second {@code BLACKOUT} probe.
 */
public final class SlothWinPolicy {

    private SlothWinPolicy() {}

    public static boolean shouldDeclare(@Nullable String proposed,
                                        boolean slothAlive,
                                        boolean prideBlocking,
                                        boolean onlyPrideAlive) {
        if (!slothAlive || prideBlocking || onlyPrideAlive) {
            return false;
        }
        return "KILLERS".equals(proposed)
                || "PASSENGERS".equals(proposed)
                || "TIME".equals(proposed);
    }
}
