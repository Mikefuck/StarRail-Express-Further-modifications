package com.habitrain.core.game.sre;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScenePreloadReleasePolicyTest {
    @Test
    void waitsForEnvironmentEvenWhenEveryPlayerIsReady() {
        assertFalse(ScenePreloadReleasePolicy.shouldRelease(false, true, 100L, 200L));
    }

    @Test
    void releasesImmediatelyWhenEnvironmentAndPlayersAreReady() {
        assertTrue(ScenePreloadReleasePolicy.shouldRelease(true, true, 100L, 200L));
    }

    @Test
    void releasesAtFiveSecondDeadlineEvenWhenTransferIsIncomplete() {
        assertEquals(100L, ScenePreloadReleasePolicy.RESET_COMPLETE_TIMEOUT_TICKS);
        assertFalse(ScenePreloadReleasePolicy.shouldRelease(true, false, 199L, 200L));
        assertTrue(ScenePreloadReleasePolicy.shouldRelease(true, false, 200L, 200L));
    }
}
