package com.habitrain.core.network;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Cheap per-player C2S cooldown. Keys are {@code playerId:channel}.
 * Cleared on disconnect so a reconnect does not inherit the last stamp.
 */
public final class C2SRateLimiter {
    private static final ConcurrentMap<String, Long> LAST_NANOS = new ConcurrentHashMap<>();

    private C2SRateLimiter() {}

    public static boolean tryAcquire(UUID playerId, String channel, long cooldownMs) {
        if (playerId == null || channel == null || cooldownMs <= 0) {
            return true;
        }
        long now = System.nanoTime();
        long cooldownNanos = TimeUnit.MILLISECONDS.toNanos(cooldownMs);
        String slot = playerId + ":" + channel;
        boolean[] granted = {false};
        LAST_NANOS.compute(slot, (key, previous) -> {
            if (previous != null) {
                long elapsed = now - previous;
                if (elapsed >= 0 && elapsed < cooldownNanos) {
                    return previous;
                }
            }
            granted[0] = true;
            return now;
        });
        return granted[0];
    }

    public static void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }
        String prefix = playerId + ":";
        LAST_NANOS.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public static void clearAll() {
        LAST_NANOS.clear();
    }
}
