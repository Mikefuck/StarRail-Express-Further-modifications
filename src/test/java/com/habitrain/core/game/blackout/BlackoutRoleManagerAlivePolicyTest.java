package com.habitrain.core.game.blackout;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackoutRoleManagerAlivePolicyTest {

    @Test
    void disconnectedStillCountsForVictoryDuringGrace() {
        assertTrue(BlackoutRoleManager.countsAsAliveForVictory(true, true, false),
                "grace-disconnected UUID must still occupy remaining good/bad");
    }

    @Test
    void creativeOrSpectatorDoesNotCountForVictory() {
        assertFalse(BlackoutRoleManager.countsAsAliveForVictory(true, false, true));
    }

    @Test
    void assignedSurvivalCountsForVictory() {
        assertTrue(BlackoutRoleManager.countsAsAliveForVictory(true, false, false));
    }

    @Test
    void unassignedNeverCounts() {
        assertFalse(BlackoutRoleManager.countsAsAliveForVictory(false, false, false));
        assertFalse(BlackoutRoleManager.countsAsAliveForVictory(false, true, true));
    }

    @Test
    void nullLevelOrUuidIsNotAliveForVictory() {
        assertFalse(BlackoutRoleManager.countsAsAliveForVictory(null, UUID.randomUUID()));
        assertFalse(BlackoutRoleManager.countsAsAliveForVictory(null, null));
    }
}
