package com.habitrain.core.scene;

import com.habitrain.core.scene.model.SceneBounds;
import com.habitrain.core.scene.model.SceneMotionMath;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneRuntimeState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SceneMotionMathTest {

    @Test
    public void testDeterministicPhaseCalculation() {
        double speed = 20.0; // 20 blocks/sec
        double loopDist = 100.0; // 100 blocks
        double phaseOffset = 0.0;

        // t = 0s -> phase = 0
        assertEquals(0.0, floorMod(speed * 0.0 + phaseOffset, loopDist), 0.001);

        // t = 2.5s -> 50 blocks
        assertEquals(50.0, floorMod(speed * 2.5 + phaseOffset, loopDist), 0.001);

        // t = 5.0s -> 100 blocks -> wraps to 0
        assertEquals(0.0, floorMod(speed * 5.0 + phaseOffset, loopDist), 0.001);

        // t = 7.5s -> 150 blocks -> wraps to 50
        assertEquals(50.0, floorMod(speed * 7.5 + phaseOffset, loopDist), 0.001);
    }

    @Test
    public void testPhaseWithOffset() {
        double speed = 10.0;
        double loopDist = 50.0;
        double phaseOffset = 15.0;

        // t = 0s -> 15.0
        assertEquals(15.0, floorMod(speed * 0.0 + phaseOffset, loopDist), 0.001);

        // t = 3.5s -> 35 + 15 = 50 -> wraps to 0.0
        assertEquals(0.0, floorMod(speed * 3.5 + phaseOffset, loopDist), 0.001);
    }

    @Test
    public void testDirectionNormalization() {
        SceneProfile profile = new SceneProfile();
        profile.setDirection(3.0, 4.0, 0.0);
        double[] dir = profile.getDirection();

        // Length should be 1.0 (3/5 = 0.6, 4/5 = 0.8)
        assertEquals(0.6, dir[0], 0.001);
        assertEquals(0.8, dir[1], 0.001);
        assertEquals(0.0, dir[2], 0.001);
    }

    @Test
    public void configuredSpacingIsNotRestrictedByCapturedExtent() {
        SceneBounds bounds = new SceneBounds(-890, -1, -333, -872, 8, -321);

        assertEquals(512.0,
                SceneMotionMath.seamlessLoopDistance(bounds, new double[]{-1.0, 0.0, 0.0}, 512.0),
                0.001);
        assertEquals(512.0,
                SceneMotionMath.seamlessLoopDistance(bounds, new double[]{0.0, 0.0, 1.0}, 512.0),
                0.001);
    }

    @Test
    public void configuredSpacingAllowsIntentionalOverlapAndGaps() {
        SceneBounds bounds = new SceneBounds(0, 0, 0, 40, 10, 10);

        assertEquals(24.0,
                SceneMotionMath.seamlessLoopDistance(bounds, new double[]{1.0, 0.0, 0.0}, 24.0),
                0.001);
        assertEquals(100.0,
                SceneMotionMath.seamlessLoopDistance(bounds, new double[]{1.0, 0.0, 0.0}, 100.0),
                0.001);
    }

    @Test
    public void shallowSelectionOnlyRecommendsItsExtentAndDoesNotRestrictConfiguredDistance() {
        SceneBounds bounds = new SceneBounds(0, 0, 0, 40, 1, 10);

        SceneMotionMath.LoopDistances vertical = SceneMotionMath.resolveLoopDistances(
                bounds, new double[]{0.0, 1.0, 0.0}, 18.0);
        assertEquals(18.0, vertical.configuredDistance(), 0.001);
        assertEquals(1.0, vertical.recommendedDistance(), 0.001);
        assertEquals(18.0, vertical.effectiveDistance(), 0.001);

        SceneMotionMath.LoopDistances horizontal = SceneMotionMath.resolveLoopDistances(
                bounds, new double[]{1.0, 0.0, 0.0}, vertical.configuredDistance());
        assertEquals(18.0, horizontal.configuredDistance(), 0.001);
        assertEquals(40.0, horizontal.recommendedDistance(), 0.001);
        assertEquals(18.0, horizontal.effectiveDistance(), 0.001);
    }

    @Test
    public void runtimeElapsedTimeKeepsSubTickPrecisionAtLargeWorldTimes() {
        SceneRuntimeState state = new SceneRuntimeState(
                true, 175_012_730L, 1, "map2", "hash", new SceneProfile());

        assertEquals(0.025, state.calculateElapsedSeconds(175_012_730L, 0.5f), 1.0e-9);
        assertEquals(0.075, state.calculateElapsedSeconds(175_012_731L, 0.5f), 1.0e-9);
    }

    @Test
    public void nonLoopingMotionDoesNotTeleportBack() {
        assertEquals(135.0, SceneMotionMath.phase(false, 20.0, 6.0, 15.0, 50.0), 0.001);
        assertEquals(35.0, SceneMotionMath.phase(true, 20.0, 6.0, 15.0, 50.0), 0.001);
    }

    private static double floorMod(double x, double y) {
        if (y <= 0.0) return 0.0;
        return x - Math.floor(x / y) * y;
    }
}
