package com.habitrain.core.client.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoteLaunchSessionTest {
    @AfterEach
    void resetSession() {
        VoteLaunchSession.clear();
    }

    @Test
    void visibleLoadingKeepsOriginalPathAtStartConfirmation() {
        VoteLaunchSession.begin("map_a");

        VoteLaunchSession.StartConfirmedResult result =
                VoteLaunchSession.onStartConfirmed("map_a");

        assertFalse(result.recover());
        assertFalse(VoteLaunchSession.isRecoverPath());
        assertFalse(VoteLaunchSession.isLaunchConfirmed());
        assertFalse(VoteLaunchSession.canHide());
    }

    @Test
    void hiddenLoadingAlwaysBecomesRecoverCoverAtStartConfirmation() {
        VoteLaunchSession.begin("map_a");
        VoteLaunchSession.markHiddenByUser();

        // 地图结算包重发/重复 begin 不能清掉 sticky 隐藏意图。
        VoteLaunchSession.begin("map_a");
        VoteLaunchSession.StartConfirmedResult result =
                VoteLaunchSession.onStartConfirmed("map_a");

        assertTrue(result.forceOpen());
        assertTrue(result.recover());
        assertTrue(VoteLaunchSession.isRecoverPath());
        assertTrue(VoteLaunchSession.isLaunchConfirmed());
        assertFalse(VoteLaunchSession.isHiddenByUser());
    }

    @Test
    void lateEnvironmentSignalPromotesMissedHiddenStartToRecoverCover() {
        VoteLaunchSession.begin("map_a");
        VoteLaunchSession.markHiddenByUser();

        assertTrue(VoteLaunchSession.promoteStickyHideToRecoverIfNeeded());
        VoteLaunchSession.onLaunchConfirmed("map_a");

        assertTrue(VoteLaunchSession.isStartConfirmed());
        assertTrue(VoteLaunchSession.isRecoverPath());
        assertTrue(VoteLaunchSession.isLaunchConfirmed());
    }

    @Test
    void reopenedLoadingDoesNotClearStickyHiddenIntent() {
        VoteLaunchSession.begin("map_a");
        VoteLaunchSession.markHiddenByUser();

        assertTrue(VoteLaunchSession.canReopenLoading());
        VoteLaunchSession.markEnterCompletedOnce();

        assertTrue(VoteLaunchSession.isHiddenByUser());
        assertTrue(VoteLaunchSession.onStartConfirmed("map_a").recover());
    }
}
