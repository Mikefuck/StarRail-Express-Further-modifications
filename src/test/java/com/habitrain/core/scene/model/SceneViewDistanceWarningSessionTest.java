package com.habitrain.core.scene.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneViewDistanceWarningSessionTest {

    @Test
    void welcomeTimeOneChecksOnFollowingClientTick() {
        SceneViewDistanceWarningSession session = new SceneViewDistanceWarningSession();
        session.startRound();
        session.onWelcomeTimer(1);

        assertEquals(SceneViewDistanceWarningSession.CheckReason.NONE,
                tick(session, 0, 0, 0, "overworld", "map1-v1", true));
        assertEquals(SceneViewDistanceWarningSession.CheckReason.INITIAL,
                tick(session, 0, 0, 0, "overworld", "map1-v1", true));
    }

    @Test
    void fallbackWaitsTwelveSecondsAndForFullscreenTransitionToClear() {
        SceneViewDistanceWarningSession session = new SceneViewDistanceWarningSession();
        session.startRound();
        for (int i = 1; i < SceneViewDistanceWarningSession.FALLBACK_DELAY_TICKS; i++) {
            assertEquals(SceneViewDistanceWarningSession.CheckReason.NONE,
                    tick(session, 0, 0, 0, "overworld", "map1-v1", true));
        }
        assertEquals(SceneViewDistanceWarningSession.CheckReason.NONE,
                tick(session, 0, 0, 0, "overworld", "map1-v1", false));
        assertEquals(SceneViewDistanceWarningSession.CheckReason.INITIAL,
                tick(session, 0, 0, 0, "overworld", "map1-v1", true));
    }

    @Test
    void cumulativeCameraPathTriggersAfterSixteenBlocks() {
        SceneViewDistanceWarningSession session = openedSession();
        assertEquals(SceneViewDistanceWarningSession.CheckReason.INITIAL,
                tick(session, 0, 0, 0, "overworld", "map1-v1", true));
        assertEquals(SceneViewDistanceWarningSession.CheckReason.NONE,
                tick(session, 8, 0, 0, "overworld", "map1-v1", true));
        assertEquals(SceneViewDistanceWarningSession.CheckReason.MOVED,
                tick(session, 8, 0, 8, "overworld", "map1-v1", true));
    }

    @Test
    void sceneAndDimensionChangesCauseImmediateChecksWithoutCountingTeleport() {
        SceneViewDistanceWarningSession session = openedSession();
        assertEquals(SceneViewDistanceWarningSession.CheckReason.INITIAL,
                tick(session, 0, 64, 0, "overworld", "map1-v1", true));
        assertEquals(SceneViewDistanceWarningSession.CheckReason.SCENE_CHANGED,
                tick(session, 0, 64, 0, "overworld", "map2-v1", true));
        assertEquals(SceneViewDistanceWarningSession.CheckReason.DIMENSION_CHANGED,
                tick(session, 900, 80, 900, "the_nether", "map2-v1", true));
        assertEquals(SceneViewDistanceWarningSession.CheckReason.NONE,
                tick(session, 901, 80, 900, "the_nether", "map2-v1", true));
    }

    @Test
    void warningLimitIsPerRoundAndPerMap() {
        SceneViewDistanceWarningSession session = new SceneViewDistanceWarningSession();
        session.startRound();
        assertFalse(session.hasWarned("map1"));
        session.markWarned("map1");
        assertTrue(session.hasWarned("map1"));
        assertFalse(session.hasWarned("map2"));

        session.startRound();
        assertFalse(session.hasWarned("map1"));
    }

    @Test
    void onlyActionablePlayerStatusesProduceHudWarnings() {
        SceneVisibilityAdvisor.AnimationBounds bounds =
                new SceneVisibilityAdvisor.AnimationBounds(0, 0, 0, 1, 1, 1, true, true);
        assertEquals(SceneViewDistanceWarningSession.WarningKind.CLIENT_SETTING_LOW,
                SceneViewDistanceWarningSession.warningKind(advice(
                        bounds, SceneVisibilityAdvisor.ViewStatus.CLIENT_SETTING_LOW, true)));
        assertEquals(SceneViewDistanceWarningSession.WarningKind.SERVER_LIMIT_LOW,
                SceneViewDistanceWarningSession.warningKind(advice(
                        bounds, SceneVisibilityAdvisor.ViewStatus.SERVER_LIMIT_LOW, true)));
        assertEquals(SceneViewDistanceWarningSession.WarningKind.NONE,
                SceneViewDistanceWarningSession.warningKind(advice(
                        bounds, SceneVisibilityAdvisor.ViewStatus.ENOUGH, true)));
        assertEquals(SceneViewDistanceWarningSession.WarningKind.NONE,
                SceneViewDistanceWarningSession.warningKind(advice(
                        bounds, SceneVisibilityAdvisor.ViewStatus.UNATTAINABLE, true)));
        assertEquals(SceneViewDistanceWarningSession.WarningKind.NONE,
                SceneViewDistanceWarningSession.warningKind(advice(
                        bounds, SceneVisibilityAdvisor.ViewStatus.CLIENT_SETTING_LOW, false)),
                "scene max-distance failures are administrator diagnostics, not player prompts");
    }

    private static SceneViewDistanceWarningSession openedSession() {
        SceneViewDistanceWarningSession session = new SceneViewDistanceWarningSession();
        session.startRound();
        session.onWelcomeTimer(1);
        tick(session, 0, 0, 0, "overworld", "map1-v1", true);
        return session;
    }

    private static SceneViewDistanceWarningSession.CheckReason tick(
            SceneViewDistanceWarningSession session, double x, double y, double z,
            String dimension, String signature, boolean unblocked) {
        return session.tick(new SceneViewDistanceWarningSession.TickInput(
                x, y, z, dimension, signature, unblocked));
    }

    private static SceneVisibilityAdvisor.Advice advice(
            SceneVisibilityAdvisor.AnimationBounds bounds,
            SceneVisibilityAdvisor.ViewStatus status, boolean sceneDistanceSufficient) {
        return new SceneVisibilityAdvisor.Advice(bounds, 100.0, 4, 32, 2, 2,
                status != SceneVisibilityAdvisor.ViewStatus.UNATTAINABLE, status,
                100.0, 256.0, sceneDistanceSufficient);
    }
}
