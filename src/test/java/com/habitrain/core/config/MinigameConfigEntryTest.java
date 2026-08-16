package com.habitrain.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MinigameConfigEntryTest {
    @Test
    void emptyMapFilterListsMeanUnrestricted() {
        MinigameConfigEntry entry = MinigameConfigEntry.createDefault();

        entry.mapFilterMode = 1;
        assertTrue(entry.isAllowedOnMap("station"));

        entry.mapFilterMode = 2;
        assertTrue(entry.isAllowedOnMap("station"));
    }
}
