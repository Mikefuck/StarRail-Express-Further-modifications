package com.habitrain.core.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameModeIdsTest {

    @Test
    void canonicalMapsRegistryAndSreBlackoutToHabitrainBlackout() {
        assertEquals("habitrain:blackout", GameModeIds.canonical("sre:blackout"));
        assertEquals("habitrain:blackout", GameModeIds.canonical("habitrain_core:habitrain:blackout"));
        assertEquals("habitrain:blackout", GameModeIds.canonical("SREBlackoutGameMode"));
        assertEquals(GameModeIds.MURDER, GameModeIds.canonical("habitrain_core:sre:murder"));
        assertEquals(GameModeIds.REPAIR, GameModeIds.canonical("canyuesama:repair_escape"));
        assertEquals(GameModeIds.MURDER, GameModeIds.canonical(null));
        assertEquals(GameModeIds.MURDER, GameModeIds.canonical(""));
    }

    @Test
    void familyPredicates() {
        assertTrue(GameModeIds.isBlackout("sre:blackout"));
        assertTrue(GameModeIds.isRepair("sre:repair"));
        assertTrue(GameModeIds.isMurder("sre:murder"));
        assertFalse(GameModeIds.isMurder("habitrain:blackout"));
    }

    @Test
    void thirdPartyNamesContainingBuiltInWordsKeepTheirIdentity() {
        for (String id : java.util.List.of("addon:ship_repair", "addon:murder_party", "addon:blackout_plus",
                "ship_repair", "ShipRepairGameMode", "MurderMysteryGameMode", "BlackoutChallenge")) {
            assertEquals(id, GameModeIds.canonical(id));
            assertFalse(GameModeIds.isBlackout(id));
            assertFalse(GameModeIds.isRepair(id));
            assertFalse(GameModeIds.isMurder(id));
        }
        assertEquals(GameModeIds.REPAIR, GameModeIds.canonical("net.exmo.sre.repair.RepairEscapeGameMode"));
    }

    @Test
    void builtInAliasesDoNotDependOnTurkishOrAzeriLocale() {
        var original = java.util.Locale.getDefault();
        try {
            for (String tag : java.util.List.of("tr-TR", "az-AZ")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag));
                assertEquals(GameModeIds.REPAIR, GameModeIds.canonical("SRE:REPAIR"));
                assertEquals(GameModeIds.MURDER, GameModeIds.canonical("SREMURDERGAMEMODE"));
            }
        } finally { java.util.Locale.setDefault(original); }
    }
}
