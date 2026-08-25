package com.habitrain.core.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C2SRateLimiterTest {

    @Test
    void firstAcquireSucceeds() {
        UUID id = UUID.randomUUID();
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
    }

    @Test
    void immediateSecondAcquireOnSameChannelFails() {
        UUID id = UUID.randomUUID();
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
        assertFalse(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
    }

    @Test
    void afterCooldownSucceeds() throws InterruptedException {
        UUID id = UUID.randomUUID();
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 25));
        assertFalse(C2SRateLimiter.tryAcquire(id, "channel_a", 25));
        Thread.sleep(80);
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 25));
    }

    @Test
    void differentChannelIndependent() {
        UUID id = UUID.randomUUID();
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_b", 1000));
        assertFalse(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
    }

    @Test
    void nonPositiveCooldownAlwaysTrue() {
        UUID id = UUID.randomUUID();
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 0));
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 0));
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", -5));
    }

    @Test
    void clearAllowsImmediateReacquire() {
        UUID id = UUID.randomUUID();
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
        assertFalse(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
        C2SRateLimiter.clear(id);
        assertTrue(C2SRateLimiter.tryAcquire(id, "channel_a", 1000));
    }
}
