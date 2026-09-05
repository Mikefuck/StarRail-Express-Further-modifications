package com.habitrain.core.scene.model;

import com.habitrain.core.client.gui.menu.ui.SceneAngleDial;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SceneOrbitPageAndDialTest {
    private static final double EPS = 1.0e-4;

    @Test
    public void testSceneAngleDialDirectionNamesAndLabels() {
        // Y 轴
        assertEquals("北 0°", SceneAngleDial.getLabel0(SceneOrbitAxis.Y));
        assertEquals("东 90°", SceneAngleDial.getLabel90(SceneOrbitAxis.Y));
        assertEquals("南 180°", SceneAngleDial.getLabel180(SceneOrbitAxis.Y));
        assertEquals("西 270°", SceneAngleDial.getLabel270(SceneOrbitAxis.Y));

        assertEquals("北", SceneAngleDial.getDirectionName(SceneOrbitAxis.Y, 0.0));
        assertEquals("东北", SceneAngleDial.getDirectionName(SceneOrbitAxis.Y, 45.0));
        assertEquals("东", SceneAngleDial.getDirectionName(SceneOrbitAxis.Y, 90.0));
        assertEquals("东南", SceneAngleDial.getDirectionName(SceneOrbitAxis.Y, 135.0));
        assertEquals("南", SceneAngleDial.getDirectionName(SceneOrbitAxis.Y, 180.0));
        assertEquals("西南", SceneAngleDial.getDirectionName(SceneOrbitAxis.Y, 225.0));
        assertEquals("西", SceneAngleDial.getDirectionName(SceneOrbitAxis.Y, 270.0));
        assertEquals("西北", SceneAngleDial.getDirectionName(SceneOrbitAxis.Y, 315.0));

        // X 轴
        assertEquals("上 0°", SceneAngleDial.getLabel0(SceneOrbitAxis.X));
        assertEquals("南 90°", SceneAngleDial.getLabel90(SceneOrbitAxis.X));
        assertEquals("下 180°", SceneAngleDial.getLabel180(SceneOrbitAxis.X));
        assertEquals("北 270°", SceneAngleDial.getLabel270(SceneOrbitAxis.X));

        // Z 轴
        assertEquals("上 0°", SceneAngleDial.getLabel0(SceneOrbitAxis.Z));
        assertEquals("东 90°", SceneAngleDial.getLabel90(SceneOrbitAxis.Z));
        assertEquals("下 180°", SceneAngleDial.getLabel180(SceneOrbitAxis.Z));
        assertEquals("西 270°", SceneAngleDial.getLabel270(SceneOrbitAxis.Z));
    }

    @Test
    public void testSceneAngleDialMouseMath() {
        int x = 100;
        int y = 50;
        int width = 200;
        int cx = x + width / 2; // 200
        int cy = y + 50; // 100

        // 中心附近检测
        assertTrue(SceneAngleDial.isMouseOverDial(cx, cy, x, y, width));
        assertTrue(SceneAngleDial.isMouseOverDial(cx + 30, cy, x, y, width));
        assertFalse(SceneAngleDial.isMouseOverDial(cx + 100, cy, x, y, width));

        AtomicReference<Double> appliedAngle = new AtomicReference<>();

        // 点击正北 (dx=0, dy=-30) -> 0°
        SceneAngleDial.applyMouseAngle(cx, cy - 30, x, y, width, appliedAngle::set);
        assertNotNull(appliedAngle.get());
        assertEquals(0.0, appliedAngle.get(), 1.0);

        // 点击正东 (dx=30, dy=0) -> 90°
        SceneAngleDial.applyMouseAngle(cx + 30, cy, x, y, width, appliedAngle::set);
        assertEquals(90.0, appliedAngle.get(), 1.0);

        // 点击正南 (dx=0, dy=30) -> 180°
        SceneAngleDial.applyMouseAngle(cx, cy + 30, x, y, width, appliedAngle::set);
        assertEquals(180.0, appliedAngle.get(), 1.0);

        // 点击正西 (dx=-30, dy=0) -> 270°
        SceneAngleDial.applyMouseAngle(cx - 30, cy, x, y, width, appliedAngle::set);
        assertEquals(270.0, appliedAngle.get(), 1.0);

        // 模型中心模式下不可交互
        boolean handled = SceneAngleDial.handleClick(cx + 30, cy, x, y, width, true, true, appliedAngle::set);
        assertFalse(handled);

        // 非可编辑模式下不可交互
        handled = SceneAngleDial.handleClick(cx + 30, cy, x, y, width, false, false, appliedAngle::set);
        assertFalse(handled);

        // 正常点击
        handled = SceneAngleDial.handleClick(cx + 30, cy, x, y, width, true, false, appliedAngle::set);
        assertTrue(handled);
    }

    @Test
    public void testSceneAngleDialPathSummaryGeneration() {
        // 360° 整圈
        Component loopComp = SceneAngleDial.getPathSummaryComponent(SceneOrbitAxis.Y, 0.0, 360.0, true);
        assertNotNull(loopComp);
        assertTrue(loopComp.getContents() instanceof TranslatableContents);
        TranslatableContents loopContents = (TranslatableContents) loopComp.getContents();
        assertEquals("screen.habitrain_core.scene_motion.orbit_path_summary_loop", loopContents.getKey());
        assertEquals("360.0", loopContents.getArgs()[1]);

        // 往返
        Component pingpongComp = SceneAngleDial.getPathSummaryComponent(SceneOrbitAxis.Y, 0.0, 90.0, true);
        assertNotNull(pingpongComp);
        assertTrue(pingpongComp.getContents() instanceof TranslatableContents);
        TranslatableContents pingpongContents = (TranslatableContents) pingpongComp.getContents();
        assertEquals("screen.habitrain_core.scene_motion.orbit_path_summary_pingpong", pingpongContents.getKey());
        assertEquals("90.0", pingpongContents.getArgs()[3]);
    }

    @Test
    public void testDraftMotionModeAndOrbitPreservation() {
        SceneProfileDraft draft = new SceneProfileDraft();
        assertEquals(SceneMotionMode.LINEAR, draft.getMotionMode());

        draft.setMotionMode(SceneMotionMode.ORBIT);
        assertEquals(SceneMotionMode.ORBIT, draft.getMotionMode());

        SceneOrbitSettings orbit = draft.getOrbit();
        orbit.setAxis(SceneOrbitAxis.X);
        orbit.setCenterMode(SceneOrbitCenterMode.WORLD_BLOCK);
        orbit.setCenterWorld(10.0, 20.0, 30.0);
        orbit.setStartAngleDegrees(45.0);
        orbit.setSweepDegrees(180.0);
        orbit.setClockwise(false);
        orbit.setAngularSpeedDegreesPerSecond(60.0);
        orbit.setRotateModelWithOrbit(true);

        SceneProfile profile = draft.toProfile();
        assertEquals(SceneMotionMode.ORBIT, profile.getMotionMode());
        assertEquals(SceneOrbitAxis.X, profile.getOrbit().getAxis());
        assertEquals(SceneOrbitCenterMode.WORLD_BLOCK, profile.getOrbit().getCenterMode());
        assertArrayEquals(new double[]{10.0, 20.0, 30.0}, profile.getOrbit().getCenterWorld(), EPS);
        assertEquals(45.0, profile.getOrbit().getStartAngleDegrees(), EPS);
        assertEquals(180.0, profile.getOrbit().getSweepDegrees(), EPS);
        assertFalse(profile.getOrbit().isClockwise());
        assertEquals(60.0, profile.getOrbit().getAngularSpeedDegreesPerSecond(), EPS);
        assertTrue(profile.getOrbit().isRotateModelWithOrbit());

        // 验证 resolveRadius
        draft.setDisplayOrigin(10.0, 20.0, 40.0); // 沿 Z 轴偏移 10 格
        profile = draft.toProfile();
        double radius = SceneOrbitMath.resolveRadius(profile);
        assertEquals(10.0, radius, EPS);
    }

    @Test
    public void testModelCenterAutoDerivation() {
        SceneProfileDraft draft = new SceneProfileDraft();
        draft.setSourceBounds(new SceneBounds(0, 0, 0, 10, 10, 10));
        draft.setDisplayOrigin(100.0, 64.0, 100.0);
        draft.setPivotLocal(0.0, 0.0, 0.0);
        draft.setRotationDegrees(new SceneRotation(0f, 0f, 0f));

        SceneOrbitSettings orbit = draft.getOrbit();
        orbit.setCenterMode(SceneOrbitCenterMode.MODEL_CENTER);
        orbit.setAxis(SceneOrbitAxis.Y);

        SceneProfile profile = draft.toProfile();
        double[] center = SceneOrbitMath.resolveOrbitCenter(profile);
        // 包围盒几何中心相对 (0,0,0) 为 (5, 5, 5)，故世界几何中心为 (105, 69, 105)
        assertEquals(105.0, center[0], EPS);
        assertEquals(69.0, center[1], EPS);
        assertEquals(105.0, center[2], EPS);

        // resolveStartAngle 反算
        double startAngle = SceneOrbitMath.resolveStartAngle(profile);
        // P0 (100, 64, 100) 相对中心 (105, 69, 105) 的水平位移为 (-5, -5)
        // dx=-5, dz=-5 (Minecraft中-Z为北，故-dz=+5为北, dx=-5为西 -> 西北 315°)
        assertEquals(315.0, startAngle, 1.0);
    }

    @Test
    public void testOrbitValidation() {
        assertTrue(SceneProfileValidator.validateStartAngle("0.0").isValid());
        assertTrue(SceneProfileValidator.validateStartAngle("360.0").isValid());
        assertFalse(SceneProfileValidator.validateStartAngle("abc").isValid());
        assertFalse(SceneProfileValidator.validateStartAngle("400.0").isValid());
        assertFalse(SceneProfileValidator.validateStartAngle("-10.0").isValid());

        assertTrue(SceneProfileValidator.validateSweep("180.0").isValid());
        assertTrue(SceneProfileValidator.validateSweep("360.0").isValid());
        assertFalse(SceneProfileValidator.validateSweep("-1.0").isValid());
        assertFalse(SceneProfileValidator.validateSweep("400.0").isValid());

        assertTrue(SceneProfileValidator.validateAngularSpeed("30.0").isValid());
        assertFalse(SceneProfileValidator.validateAngularSpeed("-5.0").isValid());
        assertFalse(SceneProfileValidator.validateAngularSpeed("1000.0").isValid());
    }
}
