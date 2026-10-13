package com.habitrain.core.game.sre.role.sins.component;

/** A first-tick-immediate throttle which also recovers when world time goes backwards. */
final class GameTickThrottle {
    private final long interval;
    private boolean initialized;
    private long previous;

    GameTickThrottle(long interval) {
        if (interval <= 0) throw new IllegalArgumentException("interval must be positive");
        this.interval = interval;
    }

    boolean acquire(long now) {
        if (initialized && now >= previous && now - previous >= 0 && now - previous < interval) {
            return false;
        }
        initialized = true;
        previous = now;
        return true;
    }

    void reset() {
        initialized = false;
    }
}
