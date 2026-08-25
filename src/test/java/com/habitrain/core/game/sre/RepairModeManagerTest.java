package com.habitrain.core.game.sre;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
