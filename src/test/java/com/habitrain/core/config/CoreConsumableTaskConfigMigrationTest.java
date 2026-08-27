package com.habitrain.core.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreConsumableTaskConfigMigrationTest {

    @Test
    void movesLegacyEntryWhenCanonicalEntryIsMissing() {
        TaskConfigEntry legacy = new TaskConfigEntry(false);
        Map<String, TaskConfigEntry> configs = new HashMap<>();
        configs.put("habitrain_core:blackout_eat", legacy);

        assertTrue(CoreConsumableTaskConfigMigration.migrate(configs));
        assertSame(legacy, configs.get("habitrain_core:eat"));
        assertNull(configs.get("habitrain_core:blackout_eat"));
    }

    @Test
    void mergesCanonicalEnableAndMapSettingsWithLegacyCoreOverrides() {
        TaskConfigEntry canonical = new TaskConfigEntry(false);
        canonical.mapFilterMode = 2;
        canonical.enabledMaps = List.of("map_a");
        canonical.outlineWidth = 6.0f;

        TaskConfigEntry legacy = new TaskConfigEntry(true);
        legacy.hasInstinctColor = true;
        legacy.instinctColor = 0xCA112233;
        legacy.outlineWidth = 8.0f;
        legacy.hasGoldReward = true;
        legacy.goldReward = 77;
        legacy.hasEmotionReward = true;
        legacy.emotionReward = 0.75f;
        legacy.hasRefreshWeight = true;
        legacy.refreshWeight = 2.5f;

        Map<String, TaskConfigEntry> configs = new HashMap<>();
        configs.put("habitrain_core:eat", canonical);
        configs.put("habitrain_core:blackout_eat", legacy);

        assertTrue(CoreConsumableTaskConfigMigration.migrate(configs));
        TaskConfigEntry migrated = configs.get("habitrain_core:eat");
        assertFalse(migrated.enabled, "disabled in either old system must remain disabled");
        assertEquals(2, migrated.mapFilterMode);
        assertEquals(List.of("map_a"), migrated.enabledMaps);
        assertTrue(migrated.hasInstinctColor);
        assertEquals(0xCA112233, migrated.instinctColor);
        assertEquals(8.0f, migrated.outlineWidth);
        assertEquals(77, migrated.goldReward);
        assertEquals(0.75f, migrated.emotionReward);
        assertEquals(2.5f, migrated.refreshWeight);
        assertNull(configs.get("habitrain_core:blackout_eat"));
    }

    @Test
    void repeatedMigrationIsIdempotent() {
        Map<String, TaskConfigEntry> configs = new HashMap<>();
        configs.put("habitrain_core:drink", new TaskConfigEntry(true));

        assertFalse(CoreConsumableTaskConfigMigration.migrate(configs));
    }
}
