package com.habitrain.core.game.sre.role.sins.component;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GameTickThrottleTest {
    @Test void firstScanBoundaryResetAndTimeRollback() {
        GameTickThrottle throttle = new GameTickThrottle(5);
        assertTrue(throttle.acquire(0));
        assertFalse(throttle.acquire(4));
        assertTrue(throttle.acquire(5));
        assertTrue(throttle.acquire(2));
        assertFalse(throttle.acquire(6));
        assertTrue(throttle.acquire(7));
        throttle.reset();
        assertTrue(throttle.acquire(7));
        throttle.reset();
        assertTrue(throttle.acquire(100_000));
    }
    @Test void extremeElapsedTimeCannotOverflowIntoAWait() {
        GameTickThrottle throttle = new GameTickThrottle(5);
        assertTrue(throttle.acquire(Long.MIN_VALUE));
        assertTrue(throttle.acquire(Long.MAX_VALUE));
    }
}
