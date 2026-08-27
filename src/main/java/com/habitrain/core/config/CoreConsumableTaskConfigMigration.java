package com.habitrain.core.config;

import java.util.ArrayList;
import java.util.Map;

/** One-way migration from the former blackout-only IDs to the canonical Core IDs. */
final class CoreConsumableTaskConfigMigration {

    private static final String EAT = "habitrain_core:eat";
    private static final String DRINK = "habitrain_core:drink";
    private static final String LEGACY_EAT = "habitrain_core:blackout_eat";
    private static final String LEGACY_DRINK = "habitrain_core:blackout_drink";

    private CoreConsumableTaskConfigMigration() {
    }

    static boolean migrate(Map<String, TaskConfigEntry> configs) {
        if (configs == null) return false;
        boolean changed = migrateOne(configs, LEGACY_EAT, EAT);
        changed |= migrateOne(configs, LEGACY_DRINK, DRINK);
        return changed;
    }

    private static boolean migrateOne(Map<String, TaskConfigEntry> configs,
                                      String legacyId, String canonicalId) {
        TaskConfigEntry legacy = configs.remove(legacyId);
        if (legacy == null) return false;

        TaskConfigEntry canonical = configs.get(canonicalId);
        if (canonical == null) {
            configs.put(canonicalId, legacy);
            return true;
        }

        // A task disabled in either old system stays disabled after consolidation.
        canonical.enabled = canonical.enabled && legacy.enabled;

        // The old canonical entry controlled the upstream task's map availability.
        // Keep explicit canonical filters; otherwise inherit the working Core task filter.
        boolean canonicalHasMapPolicy = canonical.mapFilterMode != 0
                || (canonical.enabledMaps != null && !canonical.enabledMaps.isEmpty());
        if (!canonicalHasMapPolicy) {
            canonical.mapFilterMode = legacy.mapFilterMode;
            canonical.enabledMaps = legacy.enabledMaps == null
                    ? new ArrayList<>() : new ArrayList<>(legacy.enabledMaps);
        }

        // Visual and reward overrides belonged to the working Core implementation.
        if (legacy.hasInstinctColor) {
            canonical.hasInstinctColor = true;
            canonical.instinctColor = legacy.instinctColor;
            canonical.instinctColorFromLegacyJson = legacy.instinctColorFromLegacyJson;
        }
        if (Float.compare(legacy.outlineWidth, 4.0f) != 0
                || Float.compare(canonical.outlineWidth, 4.0f) == 0) {
            canonical.outlineWidth = legacy.outlineWidth;
        }
        if (legacy.hasGoldReward) {
            canonical.hasGoldReward = true;
            canonical.goldReward = legacy.goldReward;
        }
        if (legacy.hasEmotionReward) {
            canonical.hasEmotionReward = true;
            canonical.emotionReward = legacy.emotionReward;
        }
        if (legacy.hasRefreshWeight) {
            canonical.hasRefreshWeight = true;
            canonical.refreshWeight = legacy.refreshWeight;
        }
        if (legacy.hasShopPrice) {
            canonical.hasShopPrice = true;
            canonical.shopPrice = legacy.shopPrice;
        }
        return true;
    }
}
