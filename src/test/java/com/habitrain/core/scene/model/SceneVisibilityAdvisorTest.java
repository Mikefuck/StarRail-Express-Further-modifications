package com.habitrain.core.scene.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneVisibilityAdvisorTest {
    private static final double EPS = 1.0e-6;

    @Test
    void linearLoopIncludesBothCopiesAndAllEightRotatedCorners() {
        SceneProfile profile = new SceneProfile();
        profile.setSourceBounds(new SceneBounds(0, 0, 0, 4, 2, 3));
        profile.setDisplayOrigin(100, 10, 20);
        profile.setDirection(1, 0, 0);
        profile.setLoop(new SceneLoopSettings(true, SceneLoopDistanceMode.CUSTOM, 8, 2));

        SceneVisibilityAdvisor.AnimationBounds bounds =
                SceneVisibilityAdvisor.calculateAnimationBounds(profile);

        assertTrue(bounds.valid());
        assertEquals(92.0, bounds.minX(), EPS);
        assertEquals(112.0, bounds.maxX(), EPS);
        assertEquals(10.0, bounds.minY(), EPS);
        assertEquals(12.0, bounds.maxY(), EPS);
        assertEquals(20.0, bounds.minZ(), EPS);
        assertEquals(23.0, bounds.maxZ(), EPS);
    }

    @Test
    void orbitEnvelopeIncludesFullCircleCopiesAndBothBobExtremes() {
        SceneProfile profile = orbitProfile();
        profile.setSourceBounds(new SceneBounds(0, 0, 0, 2, 2, 2));
        profile.getOrbit().setSweepDegrees(360.0);
        profile.getOrbit().setInstanceCount(4);
        profile.getOrbit().setInstanceSpreadDegrees(360.0);
        profile.getOrbit().setVerticalBobAmplitudeBlocks(2.0);
        profile.getOrbit().setRadialBobAmplitudeBlocks(3.0);
        profile.getOrbit().setBobCyclesPerSecond(1.0);
        profile.getOrbit().setRotateModelWithOrbit(false);

        SceneVisibilityAdvisor.AnimationBounds bounds =
                SceneVisibilityAdvisor.calculateAnimationBounds(profile);

        assertEquals(-13.0, bounds.minX(), EPS);
        assertEquals(15.0, bounds.maxX(), EPS);
        assertEquals(-2.0, bounds.minY(), EPS);
        assertEquals(4.0, bounds.maxY(), EPS);
        assertEquals(-13.0, bounds.minZ(), EPS);
        assertEquals(15.0, bounds.maxZ(), EPS);
    }

    @Test
    void partialOrbitUsesArcExtremaInsteadOfExpandingToAFullCircle() {
        SceneProfile profile = orbitProfile();
        profile.setSourceBounds(new SceneBounds(0, 0, 0, 1, 1, 1));
        profile.getOrbit().setSweepDegrees(90.0);
        profile.getOrbit().setAngularSpeedDegreesPerSecond(90.0);
        profile.getOrbit().setRotateModelWithOrbit(false);

        SceneVisibilityAdvisor.AnimationBounds bounds =
                SceneVisibilityAdvisor.calculateAnimationBounds(profile);

        assertEquals(0.0, bounds.minX(), EPS);
        assertEquals(11.0, bounds.maxX(), EPS);
        assertEquals(-10.0, bounds.minZ(), EPS);
        assertEquals(1.0, bounds.maxZ(), EPS);
    }

    @Test
    void stoppedOrbitAndDisabledBobOnlyIncludeTheStaticPose() {
        SceneProfile profile = orbitProfile();
        profile.setSourceBounds(new SceneBounds(0, 0, 0, 1, 1, 1));
        profile.getOrbit().setSweepDegrees(360.0);
        profile.getOrbit().setAngularSpeedDegreesPerSecond(0.0);
        profile.getOrbit().setVerticalBobAmplitudeBlocks(20.0);
        profile.getOrbit().setRadialBobAmplitudeBlocks(20.0);
        profile.getOrbit().setBobCyclesPerSecond(0.0);
        profile.getOrbit().setRotateModelWithOrbit(false);

        SceneVisibilityAdvisor.AnimationBounds bounds =
                SceneVisibilityAdvisor.calculateAnimationBounds(profile);

        assertEquals(0.0, bounds.minX(), EPS);
        assertEquals(1.0, bounds.maxX(), EPS);
        assertEquals(0.0, bounds.minY(), EPS);
        assertEquals(1.0, bounds.maxY(), EPS);
        assertEquals(-10.0, bounds.minZ(), EPS);
        assertEquals(-9.0, bounds.maxZ(), EPS);
    }

    @Test
    void recommendationRoundsUpAtExactFormulaBoundary() {
        assertEquals(2, SceneVisibilityAdvisor.requiredChunksForDistance(112.0));
        assertEquals(3, SceneVisibilityAdvisor.requiredChunksForDistance(112.0001));
        assertEquals(3, SceneVisibilityAdvisor.requiredChunksForDistance(176.0));
        assertEquals(4, SceneVisibilityAdvisor.requiredChunksForDistance(176.0001));
    }

    @Test
    void viewDistanceTwoBelowExactAndAboveRecommendationHaveDistinctStatuses() {
        SceneProfile profile = pointSizedProfileAt(176.0001);
        SceneVisibilityAdvisor.Advice low = SceneVisibilityAdvisor.advise(profile, 0, 0, 0, 32, 2, 2);
        assertEquals(4, low.rawRequiredChunks());
        assertEquals(SceneVisibilityAdvisor.ViewStatus.CLIENT_SETTING_LOW, low.viewStatus());

        SceneVisibilityAdvisor.Advice oneBelow = SceneVisibilityAdvisor.advise(profile, 0, 0, 0, 32, 3, 3);
        assertEquals(SceneVisibilityAdvisor.ViewStatus.CLIENT_SETTING_LOW, oneBelow.viewStatus());
        SceneVisibilityAdvisor.Advice exact = SceneVisibilityAdvisor.advise(profile, 0, 0, 0, 32, 4, 4);
        assertEquals(SceneVisibilityAdvisor.ViewStatus.ENOUGH, exact.viewStatus());
        SceneVisibilityAdvisor.Advice above = SceneVisibilityAdvisor.advise(profile, 0, 0, 0, 32, 8, 8);
        assertEquals(SceneVisibilityAdvisor.ViewStatus.ENOUGH, above.viewStatus());
    }

    @Test
    void rawRequirementIsNeverClampedBeforeAttainabilityCheck() {
        SceneProfile profile = pointSizedProfileAt(3000.0);
        SceneVisibilityAdvisor.Advice advice = SceneVisibilityAdvisor.advise(profile, 0, 0, 0, 32, 32, 32);

        assertTrue(advice.rawRequiredChunks() > advice.clientMaximumChunks());
        assertFalse(advice.attainable());
        assertEquals(SceneVisibilityAdvisor.ViewStatus.UNATTAINABLE, advice.viewStatus());
    }

    @Test
    void serverLimitAndSceneDistanceAreReportedSeparately() {
        SceneProfile profile = pointSizedProfileAt(180.0);
        profile.setRender(new SceneRenderSettings(64.0, true));
        SceneVisibilityAdvisor.Advice advice = SceneVisibilityAdvisor.advise(profile, 0, 0, 0, 32, 8, 2);

        assertEquals(SceneVisibilityAdvisor.ViewStatus.SERVER_LIMIT_LOW, advice.viewStatus());
        assertFalse(advice.sceneDistanceSufficient());
        assertTrue(advice.requiredSceneDistanceBlocks() > advice.configuredSceneDistanceBlocks());
    }

    private static SceneProfile orbitProfile() {
        SceneProfile profile = new SceneProfile();
        profile.setMotionMode(SceneMotionMode.ORBIT);
        profile.setDisplayOrigin(0.0, 0.0, -10.0);
        profile.getOrbit().setCenterMode(SceneOrbitCenterMode.WORLD_BLOCK);
        profile.getOrbit().setCenterWorld(0.0, 0.0, 0.0);
        profile.getOrbit().setAxis(SceneOrbitAxis.Y);
        profile.getOrbit().setStartAngleDegrees(0.0);
        profile.getOrbit().setClockwise(true);
        return profile;
    }

    private static SceneProfile pointSizedProfileAt(double x) {
        SceneProfile profile = new SceneProfile();
        profile.setSourceBounds(new SceneBounds(0, 0, 0, 1, 1, 1));
        profile.setDisplayOrigin(x, 0, 0);
        profile.setSpeedBlocksPerSecond(0.0);
        profile.setLoop(new SceneLoopSettings(false, SceneLoopDistanceMode.CUSTOM, 1, 2));
        return profile;
    }
}
