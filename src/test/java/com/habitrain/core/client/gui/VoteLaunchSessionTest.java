package com.habitrain.core.client.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoteLaunchSessionTest {
    @AfterEach
    void resetSession() {
        VoteLaunchSession.clear();
    }

    @Test
    void startConfirmationOpensTransitionOnlyOnce() {
        VoteLaunchSession.begin("map_a");

        assertTrue(VoteLaunchSession.onStartConfirmed("map_a"));
        assertFalse(VoteLaunchSession.onStartConfirmed("map_a"));

        assertTrue(VoteLaunchSession.isStartConfirmed());
        assertFalse(VoteLaunchSession.isLaunchConfirmed());
    }

    @Test
    void startConfirmationWithoutSessionIsIgnored() {
        assertFalse(VoteLaunchSession.onStartConfirmed("map_a"));
        assertFalse(VoteLaunchSession.isStartConfirmed());
    }

    @Test
    void repeatedBeginKeepsProgressButStillUpdatesMatchDetails() {
        VoteLaunchSession.begin("map_a");
        VoteLaunchSession.updateProgress(70, 8, 1, "map_a", "mode_a");
        // 地图结算包重发/重复 begin 不能清掉进度。
        VoteLaunchSession.begin("map_a");
        VoteLaunchSession.updateProgress(5, 12, 2, "map_a", "mode_b");

        assertEquals(70, VoteLaunchSession.getProgress());
        assertEquals(12, VoteLaunchSession.getPlayerCount());
        assertEquals(2, VoteLaunchSession.getKillerCount());
        assertEquals("mode_b", VoteLaunchSession.getModeId());
        VoteLaunchSession.updateProgress(90, 12, 2, "map_a", "mode_b");
        assertEquals(90, VoteLaunchSession.getProgress());
    }

    @Test
    void launchConfirmationCompletesProgressEvenIfFinalProgressPacketWasMissed() {
        VoteLaunchSession.begin("map_a");
        VoteLaunchSession.updateProgress(90, 8, 1, "map_a", "mode_a");
        VoteLaunchSession.onStartConfirmed("map_a");
        assertTrue(VoteLaunchSession.onLaunchConfirmed("map_a"));
        assertEquals(100, VoteLaunchSession.getProgress());
        VoteLaunchSession.updateProgress(99, 8, 1, "map_a", "mode_a");
        assertEquals(100, VoteLaunchSession.getProgress());
    }

    @Test
    void abortAllowsTheNextMatchToStartAtZero() {
        VoteLaunchSession.begin("map_a");
        VoteLaunchSession.updateProgress(99, 8, 1, "map_a", "mode_a");
        VoteLaunchSession.onAbort();
        VoteLaunchSession.begin("map_b");
        assertEquals(0, VoteLaunchSession.getProgress());
        assertFalse(VoteLaunchSession.isStartConfirmed());
        VoteLaunchSession.updateProgress(10, 6, 1, "map_b", "mode_a");
        assertEquals(10, VoteLaunchSession.getProgress());
    }
}
