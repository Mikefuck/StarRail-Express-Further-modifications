package com.habitrain.core.scene.model;

import com.habitrain.core.client.gui.menu.ui.SceneAngleDial;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SceneOrbitP11Test {
    private static final double EPS = 1.0e-6;

    @Test
    void oneFourEightAndSixteenCopiesHaveStableUniqueFullCircleAngles() {
        for (int count : new int[]{1, 4, 8, 16}) {
            SceneProfile profile = orbitProfile();
            profile.getOrbit().setInstanceCount(count);
            profile.getOrbit().setInstanceSpreadDegrees(360.0);

            List<SceneInstanceTransform> transforms =
                    SceneOrbitMath.calculateAllInstanceTransforms(profile, 0.0);
            assertEquals(count, transforms.size());

            Set<Long> milliDegrees = new HashSet<>();
            for (SceneInstanceTransform transform : transforms) {
                milliDegrees.add(Math.round(transform.orbitAngleDegrees() * 1_000.0));
            }
            assertEquals(count, milliDegrees.size(), "0° and 360° must not duplicate a copy");
        }
    }

    @Test
    void clockwiseAndCounterClockwiseMarkerDistributionMatchesRuntimeMath() {
        List<Double> clockwise = SceneAngleDial.getInstanceMarkerAngles(15.0, 4, 180.0, true);
        List<Double> counterClockwise = SceneAngleDial.getInstanceMarkerAngles(15.0, 4, 180.0, false);

        assertEquals(List.of(15.0, 75.0, 135.0, 195.0), clockwise);
        assertEquals(List.of(15.0, 315.0, 255.0, 195.0), counterClockwise);
    }

    @Test
    void verticalAndRadialBobReachConfiguredExtremesWithSharedPhase() {
        SceneProfile profile = orbitProfile();
        profile.getOrbit().setAngularSpeedDegreesPerSecond(0.0);
        profile.getOrbit().setVerticalBobAmplitudeBlocks(2.0);
        profile.getOrbit().setRadialBobAmplitudeBlocks(3.0);
        profile.getOrbit().setBobCyclesPerSecond(1.0);
        profile.getOrbit().setInstanceCount(4);

        List<SceneInstanceTransform> crest = SceneOrbitMath.calculateAllInstanceTransforms(profile, 0.25);
        for (SceneInstanceTransform transform : crest) {
            assertEquals(2.0, transform.verticalBob(), EPS);
            assertEquals(3.0, transform.radialBob(), EPS);
            assertEquals(13.0, transform.effectiveRadius(), EPS);
        }

        SceneInstanceTransform trough = SceneOrbitMath.calculateInstanceTransform(profile, 0.75, 0);
        assertEquals(-2.0, trough.verticalBob(), EPS);
        assertEquals(-3.0, trough.radialBob(), EPS);
        assertEquals(7.0, trough.effectiveRadius(), EPS);
    }

    @Test
    void transformedBoundingSphereFollowsRenderMatrixAndDistanceCullKeepsIntersectingGeometry() {
        SceneProfile profile = orbitProfile();
        profile.setSourceBounds(new SceneBounds(0, 0, 0, 10, 4, 6));
        profile.getOrbit().setRotateModelWithOrbit(false);

        SceneInstanceTransform transform = SceneOrbitMath.calculateInstanceTransform(profile, 0.0, 0);
        SceneInstanceBounds bounds = SceneOrbitMath.calculateInstanceBounds(profile, transform);

        assertEquals(5.0, bounds.centerX(), EPS);
        assertEquals(2.0, bounds.centerY(), EPS);
        assertEquals(-7.0, bounds.centerZ(), EPS);
        assertEquals(0.5 * Math.sqrt(152.0), bounds.radius(), EPS);

        assertTrue(bounds.isWithinDistance(bounds.centerX() + bounds.radius() + 9.5,
                bounds.centerY(), bounds.centerZ(), 10.0));
        assertFalse(bounds.isWithinDistance(bounds.centerX() + bounds.radius() + 10.5,
                bounds.centerY(), bounds.centerZ(), 10.0));
    }

    @Test
    void orbitRotationMovesBoundingSphereCenterButPreservesRadius() {
        SceneProfile profile = orbitProfile();
        profile.setSourceBounds(new SceneBounds(0, 0, 0, 2, 2, 2));
        profile.getOrbit().setRotateModelWithOrbit(true);
        profile.getOrbit().setAngularSpeedDegreesPerSecond(90.0);

        SceneInstanceTransform transform = SceneOrbitMath.calculateInstanceTransform(profile, 1.0, 0);
        SceneInstanceBounds bounds = SceneOrbitMath.calculateInstanceBounds(profile, transform);

        // Anchor has moved east to (10,0,0); local center (1,1,1) follows the same Y-axis rotation.
        assertEquals(9.0, bounds.centerX(), EPS);
        assertEquals(1.0, bounds.centerY(), EPS);
        assertEquals(1.0, bounds.centerZ(), EPS);
        assertEquals(Math.sqrt(3.0), bounds.radius(), EPS);
    }

    @Test
    void p11EditorRangesRejectInvalidCopiesAndBobValues() {
        assertTrue(SceneProfileValidator.validateInstanceCount("1").isValid());
        assertTrue(SceneProfileValidator.validateInstanceCount("16").isValid());
        assertFalse(SceneProfileValidator.validateInstanceCount("0").isValid());
        assertFalse(SceneProfileValidator.validateInstanceCount("17").isValid());
        assertFalse(SceneProfileValidator.validateInstanceCount("4.5").isValid());
        assertFalse(SceneProfileValidator.validateVerticalBob("64.01").isValid());
        assertFalse(SceneProfileValidator.validateRadialBob("-0.01").isValid());
        assertFalse(SceneProfileValidator.validateBobCycles("10.01").isValid());
        assertFalse(SceneProfileValidator.validateInstanceSpread("360.01").isValid());
    }

    private static SceneProfile orbitProfile() {
        SceneProfile profile = new SceneProfile();
        profile.setMotionMode(SceneMotionMode.ORBIT);
        profile.setDisplayOrigin(0.0, 0.0, -10.0);
        profile.getOrbit().setCenterMode(SceneOrbitCenterMode.WORLD_BLOCK);
        profile.getOrbit().setCenterWorld(0.0, 0.0, 0.0);
        profile.getOrbit().setAxis(SceneOrbitAxis.Y);
        profile.getOrbit().setStartAngleDegrees(0.0);
        profile.getOrbit().setSweepDegrees(360.0);
        profile.getOrbit().setClockwise(true);
        profile.getOrbit().setAngularSpeedDegreesPerSecond(0.0);
        return profile;
    }
}
