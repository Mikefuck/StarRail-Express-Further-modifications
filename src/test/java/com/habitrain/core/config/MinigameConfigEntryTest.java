package com.habitrain.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinigameConfigEntryTest {
    @Test
    void mapFilterSemanticsMatchWhitelistAndBlacklistRules() {
        MinigameConfigEntry entry = MinigameConfigEntry.createDefault();

        entry.mapFilterMode = 1;
        assertFalse(entry.isAllowedOnMap("station"));
        entry.enabledMaps.add("station");
        assertTrue(entry.isAllowedOnMap("station"));

        entry.enabledMaps.clear();
        entry.mapFilterMode = 2;
        assertTrue(entry.isAllowedOnMap("station"));
    }
}

