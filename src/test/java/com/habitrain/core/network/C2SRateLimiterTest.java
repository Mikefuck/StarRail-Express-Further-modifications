package com.habitrain.core.network;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C2SRateLimiterTest {

    @Test
    void firstAcquireSucceeds() {
        UUID id = UUID.randomUUID();
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
        C2SRateLimiter.clear(id);
    }

    @Test
    void immediateSecondAcquireOnSameChannelFails() {
        UUID id = UUID.randomUUID();
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
        assertFalse(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
        C2SRateLimiter.clear(id);
    }

    @Test
    @SuppressWarnings("unchecked")
    void afterCooldownSucceedsWithoutWallClockSleep() throws ReflectiveOperationException {
        UUID id = UUID.randomUUID();
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 25));
        assertFalse(C2SRateLimiter.tryAcquire(id, "channel_a", 25));

        Field mapField = Arrays.stream(C2SRateLimiter.class.getDeclaredFields())
                .filter(f -> ConcurrentMap.class.isAssignableFrom(f.getType()))
                .findFirst()
                .orElseThrow();
        mapField.setAccessible(true);
        ConcurrentMap<String, Long> stamps = (ConcurrentMap<String, Long>) mapField.get(null);
        String key = id + ":channel_a";
        stamps.computeIfPresent(key, (k, prev) -> prev - TimeUnit.SECONDS.toNanos(10));

        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 25));
        C2SRateLimiter.clear(id);
    }

    @Test
    void differentChannelIndependent() {
        UUID id = UUID.randomUUID();
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_b", 1000));
        assertFalse(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
        C2SRateLimiter.clear(id);
    }

    @Test
    void nonPositiveCooldownAlwaysTrue() {
        UUID id = UUID.randomUUID();
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 0));
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 0));
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", -5));
        C2SRateLimiter.clear(id);
    }

    @Test
    void clearAllowsImmediateReacquire() {
        UUID id = UUID.randomUUID();
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
        assertFalse(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
        C2SRateLimiter.clear(id);
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
        C2SRateLimiter.clear(id);
    }
}
