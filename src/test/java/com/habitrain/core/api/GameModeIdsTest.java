package com.habitrain.core.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameModeIdsTest {

    @Test
    void canonicalMapsRegistryAndSreBlackoutToHabitrainBlackout() {
        assertEquals(GameModeIds.BLACKOUT, GameModeIds.canonical("sre:blackout"));
        assertEquals(GameModeIds.BLACKOUT, GameModeIds.canonical("habitrain_core:habitrain:blackout"));
        assertEquals(GameModeIds.BLACKOUT, GameModeIds.canonical("SREBlackoutGameMode"));
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
}
