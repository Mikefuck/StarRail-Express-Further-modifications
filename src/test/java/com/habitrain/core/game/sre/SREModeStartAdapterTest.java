package com.habitrain.core.game.sre;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SREModeStartAdapterTest {

    @Test
    void blockingFromTreatsLookupFailureAsBlocking() {
        assertTrue(SREModeStartAdapter.blockingFrom(false, false, true));
        assertTrue(SREModeStartAdapter.blockingFrom(true, false, false));
        assertTrue(SREModeStartAdapter.blockingFrom(false, true, false));
        assertFalse(SREModeStartAdapter.blockingFrom(false, false, false));
    }

    @Test
    void startedFromTreatsLookupFailureAsNotStarted() {
        assertFalse(SREModeStartAdapter.startedFrom(false, false, true));
        assertFalse(SREModeStartAdapter.startedFrom(true, false, true));
        assertTrue(SREModeStartAdapter.startedFrom(true, false, false));
        assertTrue(SREModeStartAdapter.startedFrom(false, true, false));
        assertFalse(SREModeStartAdapter.startedFrom(false, false, false));
    }
}
