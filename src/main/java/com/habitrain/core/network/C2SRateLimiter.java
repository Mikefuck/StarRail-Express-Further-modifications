package com.habitrain.core.network;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cheap per-player C2S cooldown. Keys are {@code playerId:channel}.
 * Cleared on disconnect so a reconnect does not inherit the last stamp.
 */
public final class C2SRateLimiter {
    private static final ConcurrentMap<String, Long> LAST_MS = new ConcurrentHashMap<>();

    private C2SRateLimiter() {}

    public static boolean tryAcquire(UUID playerId, String channel, long cooldownMs) {
        if (playerId == null || channel == null || cooldownMs <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        String slot = playerId + ":" + channel;
        boolean[] granted = {false};
        LAST_MS.compute(slot, (key, previous) -> {
            if (previous != null && now - previous < cooldownMs) {
                return previous;
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
        LAST_MS.keySet().removeIf(key -> key.startsWith(prefix));
    }
}
