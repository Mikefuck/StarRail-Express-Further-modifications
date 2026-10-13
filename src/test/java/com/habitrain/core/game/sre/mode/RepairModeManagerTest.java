package com.habitrain.core.game.sre.mode;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RepairModeManagerTest {

    // enter() needs a live ServerPlayer + ParticipationComponent; covered by fail-closed
    // branches in RepairModeManager.enter source, not mocked here.

    @Test
    void lockedAndRepairerQueriesRejectNullAndBlank() {
        assertFalse(RepairModeManager.isMapLocked(null));
        assertFalse(RepairModeManager.isMapLocked(""));
        assertFalse(RepairModeManager.isMapLocked("   "));
        assertFalse(RepairModeManager.isRepairer((UUID) null));
        assertFalse(RepairModeManager.getLockedMapIds().contains(""));
    }

    @Test
    void restorationResolvesRecordedDimensionAndNeverFallsBackToOverworld() {
        var lookedUp = new java.util.ArrayList<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>>();
        var restored = new java.util.ArrayList<String>();
        assertTrue(RepairModeManager.restoreParticipation(net.minecraft.world.level.Level.NETHER,
                key -> { lookedUp.add(key); return "nether"; }, restored::add));
        assertEquals(java.util.List.of(net.minecraft.world.level.Level.NETHER), lookedUp);
        assertEquals(java.util.List.of("nether"), restored);
        assertFalse(RepairModeManager.restoreParticipation(net.minecraft.world.level.Level.NETHER,
                key -> null, world -> { throw new AssertionError("no fallback world"); }));
        assertFalse(RepairModeManager.restoreParticipation(net.minecraft.world.level.Level.NETHER,
                key -> "nether", world -> { throw new IllegalStateException("injected restore failure"); }));
    }
}
