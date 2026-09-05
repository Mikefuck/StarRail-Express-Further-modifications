package com.habitrain.core.scene.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SceneOrbitMathTest {
    private static final double EPS = 1.0e-5;

    @Test
    public void testAngleNormalizationAndFloorMod() {
        assertEquals(0.0, SceneOrbitMath.normalizeAngle(0.0), EPS);
        assertEquals(0.0, SceneOrbitMath.normalizeAngle(360.0), EPS);
        assertEquals(0.0, SceneOrbitMath.normalizeAngle(720.0), EPS);
        assertEquals(270.0, SceneOrbitMath.normalizeAngle(-90.0), EPS);
        assertEquals(0.0, SceneOrbitMath.normalizeAngle(-360.0), EPS);
        assertEquals(90.0, SceneOrbitMath.normalizeAngle(450.0), EPS);
        assertEquals(0.0, SceneOrbitMath.normalizeAngle(Double.NaN), EPS);
        assertEquals(0.0, SceneOrbitMath.normalizeAngle(Double.POSITIVE_INFINITY), EPS);

        assertEquals(0.0, SceneOrbitMath.floorMod(0.0, 360.0), EPS);
        assertEquals(10.0, SceneOrbitMath.floorMod(370.0, 360.0), EPS);
        assertEquals(350.0, SceneOrbitMath.floorMod(-10.0, 360.0), EPS);
        assertEquals(0.0, SceneOrbitMath.floorMod(720.0, 360.0), EPS);
    }

    @Test
    public void testPingPongContinuity() {
        // [0, 180] sweep
        assertEquals(0.0, SceneOrbitMath.pingPong(0.0, 0.0, 180.0), EPS);
        assertEquals(90.0, SceneOrbitMath.pingPong(90.0, 0.0, 180.0), EPS);
        assertEquals(180.0, SceneOrbitMath.pingPong(180.0, 0.0, 180.0), EPS);
        assertEquals(90.0, SceneOrbitMath.pingPong(270.0, 0.0, 180.0), EPS);
        assertEquals(0.0, SceneOrbitMath.pingPong(360.0, 0.0, 180.0), EPS);
        assertEquals(90.0, SceneOrbitMath.pingPong(450.0, 0.0, 180.0), EPS);

        // Continuity check around turnaround points
        double delta = 1.0e-4;
        double before180 = SceneOrbitMath.pingPong(180.0 - delta, 0.0, 180.0);
        double after180 = SceneOrbitMath.pingPong(180.0 + delta, 0.0, 180.0);
        assertEquals(before180, after180, 1.0e-3);

        double before360 = SceneOrbitMath.pingPong(360.0 - delta, 0.0, 180.0);
        double after360 = SceneOrbitMath.pingPong(360.0 + delta, 0.0, 180.0);
        assertEquals(before360, after360, 1.0e-3);
    }

    @Test
    public void testOrthogonalBasesAndCompassDirections() {
        // Axis Y (Horizontal orbit)
        // 0° = North (-Z), 90° = East (+X), 180° = South (+Z), 270° = West (-X)
        double[] north = SceneOrbitMath.unitRadiusVector(SceneOrbitAxis.Y, 0.0);
        assertEquals(0.0, north[0], EPS);
        assertEquals(0.0, north[1], EPS);
        assertEquals(-1.0, north[2], EPS);

        double[] east = SceneOrbitMath.unitRadiusVector(SceneOrbitAxis.Y, 90.0);
        assertEquals(1.0, east[0], EPS);
        assertEquals(0.0, east[1], EPS);
        assertEquals(0.0, east[2], EPS);

        double[] south = SceneOrbitMath.unitRadiusVector(SceneOrbitAxis.Y, 180.0);
        assertEquals(0.0, south[0], EPS);
        assertEquals(0.0, south[1], EPS);
        assertEquals(1.0, south[2], EPS);

        double[] west = SceneOrbitMath.unitRadiusVector(SceneOrbitAxis.Y, 270.0);
        assertEquals(-1.0, west[0], EPS);
        assertEquals(0.0, west[1], EPS);
        assertEquals(0.0, west[2], EPS);

        // Axis X (Pitch orbit in Y-Z)
        // 0° = Up (+Y), 90° = South (+Z), 180° = Down (-Y), 270° = North (-Z)
        double[] upX = SceneOrbitMath.unitRadiusVector(SceneOrbitAxis.X, 0.0);
        assertEquals(0.0, upX[0], EPS);
        assertEquals(1.0, upX[1], EPS);
        assertEquals(0.0, upX[2], EPS);

        double[] southX = SceneOrbitMath.unitRadiusVector(SceneOrbitAxis.X, 90.0);
        assertEquals(0.0, southX[0], EPS);
        assertEquals(0.0, southX[1], EPS);
        assertEquals(1.0, southX[2], EPS);

        // Axis Z (Roll orbit in X-Y)
        // 0° = Up (+Y), 90° = East (+X), 180° = Down (-Y), 270° = West (-X)
        double[] upZ = SceneOrbitMath.unitRadiusVector(SceneOrbitAxis.Z, 0.0);
        assertEquals(0.0, upZ[0], EPS);
        assertEquals(1.0, upZ[1], EPS);
        assertEquals(0.0, upZ[2], EPS);

        double[] eastZ = SceneOrbitMath.unitRadiusVector(SceneOrbitAxis.Z, 90.0);
        assertEquals(1.0, eastZ[0], EPS);
        assertEquals(0.0, eastZ[1], EPS);
        assertEquals(0.0, eastZ[2], EPS);
    }

    @Test
    public void testAngleFromVectorRoundTrip() {
        for (SceneOrbitAxis axis : SceneOrbitAxis.values()) {
            for (double angle = 0.0; angle < 360.0; angle += 45.0) {
                double[] vec = SceneOrbitMath.unitRadiusVector(axis, angle);
                double recovered = SceneOrbitMath.angleFromVector(axis, vec);
                assertEquals(angle, recovered, 1.0e-4,
                        "Axis " + axis + " angle " + angle + " round-trip failed; got " + recovered);
            }
        }
    }

    @Test
    public void testRotateAroundAxisMatchesUnitVector() {
        // Rotating North (0°) around Y by 90° should give East (90°)
        double[] north = new double[]{0.0, 0.0, -1.0};
        double[] rotated90 = SceneOrbitMath.rotateAroundAxis(SceneOrbitAxis.Y, north, 90.0);
        double[] east = new double[]{1.0, 0.0, 0.0};
        assertArrayEquals(east, rotated90, 1.0e-4);

        double[] rotated180 = SceneOrbitMath.rotateAroundAxis(SceneOrbitAxis.Y, north, 180.0);
        double[] south = new double[]{0.0, 0.0, 1.0};
        assertArrayEquals(south, rotated180, 1.0e-4);

        // Rotating Up around X by 90° should give South
        double[] up = new double[]{0.0, 1.0, 0.0};
        double[] rotatedX90 = SceneOrbitMath.rotateAroundAxis(SceneOrbitAxis.X, up, 90.0);
        assertArrayEquals(south, rotatedX90, 1.0e-4);

        // Rotating Up around Z by 90° should give East
        double[] rotatedZ90 = SceneOrbitMath.rotateAroundAxis(SceneOrbitAxis.Z, up, 90.0);
        assertArrayEquals(east, rotatedZ90, 1.0e-4);
    }

    @Test
    public void testClockwiseVsCounterClockwise() {
        SceneProfile profileCw = new SceneProfile();
        profileCw.setMotionMode(SceneMotionMode.ORBIT);
        profileCw.setDisplayOrigin(0.0, 0.0, -10.0); // North 10 blocks from (0,0,0)
        profileCw.getOrbit().setCenterWorld(0.0, 0.0, 0.0);
        profileCw.getOrbit().setStartAngleDegrees(0.0);
        profileCw.getOrbit().setSweepDegrees(360.0);
        profileCw.getOrbit().setClockwise(true);
        profileCw.getOrbit().setAngularSpeedDegreesPerSecond(90.0); // 90 deg/s

        // t = 1.0s: CW rotated 90° -> should be at East (+X, 0, 0)
        SceneInstanceTransform tCw = SceneOrbitMath.calculateInstanceTransform(profileCw, 1.0, 0);
        assertEquals(90.0, tCw.orbitAngleDegrees(), EPS);
        assertEquals(10.0, tCw.position()[0], 1.0e-3);
        assertEquals(0.0, tCw.position()[2], 1.0e-3);

        // CCW: rotated -90° -> should be at West (-X, 0, 0) (270°)
        SceneProfile profileCcw = profileCw.copy();
        profileCcw.getOrbit().setClockwise(false);
        SceneInstanceTransform tCcw = SceneOrbitMath.calculateInstanceTransform(profileCcw, 1.0, 0);
        assertEquals(270.0, tCcw.orbitAngleDegrees(), EPS);
        assertEquals(-10.0, tCcw.position()[0], 1.0e-3);
        assertEquals(0.0, tCcw.position()[2], 1.0e-3);
    }

    @Test
    public void testZeroSpeedPositionFixed() {
        SceneProfile profile = new SceneProfile();
        profile.setMotionMode(SceneMotionMode.ORBIT);
        profile.setDisplayOrigin(5.0, 64.0, -5.0);
        profile.getOrbit().setCenterWorld(0.0, 64.0, 0.0);
        profile.getOrbit().setAngularSpeedDegreesPerSecond(0.0);

        SceneInstanceTransform t0 = SceneOrbitMath.calculateInstanceTransform(profile, 0.0, 0);
        SceneInstanceTransform t100 = SceneOrbitMath.calculateInstanceTransform(profile, 100.0, 0);
        assertArrayEquals(t0.position(), t100.position(), 1.0e-4);
        assertEquals(t0.orbitAngleDegrees(), t100.orbitAngleDegrees(), EPS);
    }

    @Test
    public void testMultiInstanceDistribution() {
        // 360° spread with 4 instances -> 0, 90, 180, 270
        assertEquals(0.0, SceneOrbitMath.instanceOffsetAngle(0, 4, 360.0), EPS);
        assertEquals(90.0, SceneOrbitMath.instanceOffsetAngle(1, 4, 360.0), EPS);
        assertEquals(180.0, SceneOrbitMath.instanceOffsetAngle(2, 4, 360.0), EPS);
        assertEquals(270.0, SceneOrbitMath.instanceOffsetAngle(3, 4, 360.0), EPS);

        // 180° spread with 3 instances -> 0, 90, 180 (endpoints included)
        assertEquals(0.0, SceneOrbitMath.instanceOffsetAngle(0, 3, 180.0), EPS);
        assertEquals(90.0, SceneOrbitMath.instanceOffsetAngle(1, 3, 180.0), EPS);
        assertEquals(180.0, SceneOrbitMath.instanceOffsetAngle(2, 3, 180.0), EPS);

        // 1 instance
        assertEquals(0.0, SceneOrbitMath.instanceOffsetAngle(0, 1, 360.0), EPS);
    }

    @Test
    public void testBobbingExtrema() {
        double amplitude = 2.5;
        double freq = 0.25; // cycle duration = 4.0s
        assertEquals(0.0, SceneOrbitMath.calculateBob(amplitude, freq, 0.0), EPS);
        assertEquals(2.5, SceneOrbitMath.calculateBob(amplitude, freq, 1.0), 1.0e-4); // sin(pi/2)
        assertEquals(0.0, SceneOrbitMath.calculateBob(amplitude, freq, 2.0), 1.0e-4); // sin(pi)
        assertEquals(-2.5, SceneOrbitMath.calculateBob(amplitude, freq, 3.0), 1.0e-4); // sin(3pi/2)
        assertEquals(0.0, SceneOrbitMath.calculateBob(amplitude, freq, 4.0), 1.0e-4); // sin(2pi)

        // 0 amplitude or 0 freq
        assertEquals(0.0, SceneOrbitMath.calculateBob(0.0, freq, 1.0), EPS);
        assertEquals(0.0, SceneOrbitMath.calculateBob(amplitude, 0.0, 1.0), EPS);
    }

    @Test
    public void testModelCenterCalculation() {
        // 10x20x30 bounding box at display origin (100, 60, 200)
        SceneBounds bounds = new SceneBounds(0, 0, 0, 10, 20, 30);
        double[] origin = new double[]{100.0, 60.0, 200.0};
        double[] pivot = new double[]{0.0, 0.0, 0.0};
        SceneRotation zeroRot = SceneRotation.ZERO;

        // Zero rotation: center is origin + (5, 10, 15) = (105, 70, 215)
        double[] center = SceneOrbitMath.calculateModelCenter(bounds, origin, pivot, zeroRot);
        assertEquals(105.0, center[0], EPS);
        assertEquals(70.0, center[1], EPS);
        assertEquals(215.0, center[2], EPS);

        // With 90 deg yaw around pivot (5, 10, 15)
        double[] pivotAtCenter = new double[]{5.0, 10.0, 15.0};
        SceneRotation yaw90 = new SceneRotation(90.0, 0.0, 0.0);
        double[] centerRot = SceneOrbitMath.calculateModelCenter(bounds, origin, pivotAtCenter, yaw90);
        // Pivot is already at geometric center, so rotation around pivot leaves center unchanged!
        assertEquals(105.0, centerRot[0], EPS);
        assertEquals(70.0, centerRot[1], EPS);
        assertEquals(215.0, centerRot[2], EPS);
    }

    @Test
    public void testModelCenterRigidBodyOrbit() {
        // Model with dimensions 4x4x4 placed at (0, 0, 0)
        SceneProfile profile = new SceneProfile();
        profile.setMotionMode(SceneMotionMode.ORBIT);
        profile.setSourceBounds(new SceneBounds(0, 0, 0, 4, 4, 4));
        profile.setDisplayOrigin(0.0, 0.0, 0.0);
        profile.getOrbit().setCenterMode(SceneOrbitCenterMode.MODEL_CENTER);
        profile.getOrbit().setAngularSpeedDegreesPerSecond(90.0);
        profile.getOrbit().setSweepDegrees(360.0);
        profile.getOrbit().setClockwise(true);

        double[] center = SceneOrbitMath.resolveOrbitCenter(profile);
        assertEquals(2.0, center[0], EPS);
        assertEquals(2.0, center[1], EPS);
        assertEquals(2.0, center[2], EPS);

        // Over time, anchor moves, but distance from anchor to center remains sqrt(2^2 + 2^2) in XZ
        double initialDist = Math.sqrt((0 - 2.0) * (0 - 2.0) + (0 - 2.0) * (0 - 2.0));
        for (double t = 0.0; t <= 4.0; t += 0.5) {
            SceneInstanceTransform transform = SceneOrbitMath.calculateInstanceTransform(profile, t, 0);
            double[] pos = transform.position();
            double dist = Math.sqrt((pos[0] - center[0]) * (pos[0] - center[0])
                    + (pos[2] - center[2]) * (pos[2] - center[2]));
            assertEquals(initialDist, dist, 1.0e-3, "Distance to center must remain invariant at t=" + t);
            assertTrue(transform.rotateModelWithOrbit(), "MODEL_CENTER must force rotateModelWithOrbit");
        }
    }

    @Test
    public void testLargeWorldTickNoJitter() {
        SceneProfile profile = new SceneProfile();
        profile.setMotionMode(SceneMotionMode.ORBIT);
        profile.setDisplayOrigin(10.0, 64.0, 10.0);
        profile.getOrbit().setCenterWorld(0.0, 64.0, 0.0);
        profile.getOrbit().setAngularSpeedDegreesPerSecond(33.33);
        profile.getOrbit().setSweepDegrees(360.0);

        // Test at 1,000,000 seconds
        double largeTime = 1_000_000.0;
        SceneInstanceTransform transform = SceneOrbitMath.calculateInstanceTransform(profile, largeTime, 0);
        assertTrue(Double.isFinite(transform.orbitAngleDegrees()));
        assertTrue(transform.orbitAngleDegrees() >= 0.0 && transform.orbitAngleDegrees() < 360.0);
        assertTrue(Double.isFinite(transform.position()[0]));
        assertTrue(Double.isFinite(transform.position()[1]));
        assertTrue(Double.isFinite(transform.position()[2]));
    }
}
