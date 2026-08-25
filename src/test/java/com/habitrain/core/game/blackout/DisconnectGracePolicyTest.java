package com.habitrain.core.game.blackout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisconnectGracePolicyTest {

    @Test
    void skipDisconnectKillOnlyWhenGraceAndAlive() {
        assertTrue(DisconnectGracePolicy.shouldSkipDisconnectKill(true, true));
        assertFalse(DisconnectGracePolicy.shouldSkipDisconnectKill(false, true),
                "grace 0 = murder-style disconnect kill");
        assertFalse(DisconnectGracePolicy.shouldSkipDisconnectKill(true, false),
                "empty blackout alive table (murder) must not skip");
        assertFalse(DisconnectGracePolicy.shouldSkipDisconnectKill(false, false));
    }

    @Test
    void blackoutAliveNeverSpectatesAnyStatus() {
        for (String status : new String[] {
                "STARTING", "INITIATING", "ACTIVE", "INACTIVE", "STOPPING", null, ""
        }) {
            assertFalse(DisconnectGracePolicy.shouldSpectatorOnJoin(status, true, false),
                    status + " + blackoutAlive + not in snapshot");
            assertFalse(DisconnectGracePolicy.shouldSpectatorOnJoin(status, true, true),
                    status + " + blackoutAlive + in snapshot");
        }
    }

    @Test
    void startingAndInitiatingSpectateLateJoinersOnly() {
        assertTrue(DisconnectGracePolicy.shouldSpectatorOnJoin("STARTING", false, false));
        assertTrue(DisconnectGracePolicy.shouldSpectatorOnJoin("INITIATING", false, false));
        assertTrue(DisconnectGracePolicy.shouldSpectatorOnJoin("starting", false, false));
        assertFalse(DisconnectGracePolicy.shouldSpectatorOnJoin("STARTING", false, true));
        assertFalse(DisconnectGracePolicy.shouldSpectatorOnJoin("INITIATING", false, true));
    }

    @Test
    void activeSpectatesUnlessBlackoutAlive() {
        assertTrue(DisconnectGracePolicy.shouldSpectatorOnJoin("ACTIVE", false, false));
        assertTrue(DisconnectGracePolicy.shouldSpectatorOnJoin("ACTIVE", false, true),
                "forced-ready snapshot does not keep ACTIVE late/dead joins in adventure");
        assertFalse(DisconnectGracePolicy.shouldSpectatorOnJoin("ACTIVE", true, false));
    }

    @Test
    void inactiveAndOtherDoNotSpectator() {
        assertFalse(DisconnectGracePolicy.shouldSpectatorOnJoin("INACTIVE", false, false));
        assertFalse(DisconnectGracePolicy.shouldSpectatorOnJoin("INACTIVE", false, true));
        assertFalse(DisconnectGracePolicy.shouldSpectatorOnJoin("STOPPING", false, false));
        assertFalse(DisconnectGracePolicy.shouldSpectatorOnJoin("UNKNOWN", false, false));
        assertFalse(DisconnectGracePolicy.shouldSpectatorOnJoin(null, false, false));
        assertFalse(DisconnectGracePolicy.shouldSpectatorOnJoin("", false, false));
    }
}
