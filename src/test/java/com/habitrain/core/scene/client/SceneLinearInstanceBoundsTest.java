package com.habitrain.core.scene.client;

import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.api.scene.model.SceneInstanceBounds;
import com.habitrain.core.api.scene.model.SceneMotionMath;
import com.habitrain.core.api.scene.model.SceneMotionMode;
import com.habitrain.core.api.scene.model.SceneProfile;
import com.habitrain.core.api.scene.model.SceneRotation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 直线模式裁剪球的契约。
 *
 * <p>最关键的一条：<b>裁剪用的球心必须与渲染矩阵把局部几何中心送到的地方完全一致</b>。
 * 两者一旦分家，就会出现「背景仍然可见却被剔掉」或「已经看不见却还在提交绘制」——
 * 这正是报告 §6.1 指出的「只按原点距离判断」的两种病症。</p>
 */
class SceneLinearInstanceBoundsTest {
    private static final double EPSILON = 1.0e-3;

    private static SceneProfile rotatedProfile() {
        SceneProfile profile = SceneProfile.createDefault();
        profile.setMotionMode(SceneMotionMode.LINEAR);
        profile.setSourceBounds(new SceneBounds(0, 0, 0, 20, 8, 12));
        profile.setDisplayOrigin(100.0, 64.0, -30.0);
        profile.setPivotLocal(3.0, 2.0, 5.0);
        profile.setRotationDegrees(new SceneRotation(35.0, 20.0, -15.0));
        return profile;
    }

    @Test
    void sphereCenterMatchesWhatTheRenderMatrixDoesToTheModelCenter() {
        SceneProfile profile = rotatedProfile();
        double motionX = -12.5;
        double loopX = 40.0;
        Vec3 camPos = new Vec3(10.0, 70.0, 5.0);

        SceneInstanceBounds bounds = SceneMotionMath.calculateLinearInstanceBounds(
                profile, motionX + loopX, 0.0, 0.0);

        double[] origin = profile.getDisplayOrigin();
        Matrix4f modelMatrix = SceneRenderRuntime.buildSceneModelMatrix(
                origin[0] + motionX + loopX - camPos.x,
                origin[1] - camPos.y,
                origin[2] - camPos.z,
                profile.getPivotLocal(), profile.getRotationDegrees(), null, 0.0, false);

        SceneBounds source = profile.getSourceBounds();
        Vector3f mapped = modelMatrix.transformPosition(new Vector3f(
                source.sizeX() / 2.0f, source.sizeY() / 2.0f, source.sizeZ() / 2.0f));

        assertEquals(mapped.x, bounds.centerX() - camPos.x, EPSILON, "球心与模型矩阵必须同源");
        assertEquals(mapped.y, bounds.centerY() - camPos.y, EPSILON);
        assertEquals(mapped.z, bounds.centerZ() - camPos.z, EPSILON);
    }

    @Test
    void everyTransformedCornerStaysInsideTheRadius() {
        SceneProfile profile = rotatedProfile();
        SceneInstanceBounds bounds = SceneMotionMath.calculateLinearInstanceBounds(profile, -7.0, 0.0, 3.0);

        SceneBounds source = profile.getSourceBounds();
        double[] origin = profile.getDisplayOrigin();
        Matrix4f modelMatrix = SceneRenderRuntime.buildSceneModelMatrix(
                origin[0] - 7.0, origin[1], origin[2] + 3.0,
                profile.getPivotLocal(), profile.getRotationDegrees(), null, 0.0, false);

        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? 0.0f : source.sizeX();
            float y = (i & 2) == 0 ? 0.0f : source.sizeY();
            float z = (i & 4) == 0 ? 0.0f : source.sizeZ();
            Vector3f corner = modelMatrix.transformPosition(new Vector3f(x, y, z));
            double dx = corner.x - bounds.centerX();
            double dy = corner.y - bounds.centerY();
            double dz = corner.z - bounds.centerZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertTrue(distance <= bounds.radius() + EPSILON,
                    "第 " + i + " 个角点在包围球之外：" + distance + " > " + bounds.radius());
        }
    }

    @Test
    void radiusIsHalfTheBoundingBoxDiagonal() {
        SceneProfile profile = rotatedProfile();
        SceneInstanceBounds bounds = SceneMotionMath.calculateLinearInstanceBounds(profile, 0.0, 0.0, 0.0);

        double expected = 0.5 * Math.sqrt(20.0 * 20.0 + 8.0 * 8.0 + 12.0 * 12.0);
        assertEquals(expected, bounds.radius(), EPSILON);
        assertEquals(expected, bounds.maxX() - bounds.centerX(), EPSILON,
                "外接 AABB 由半径导出，视锥判定直接吃这一对 min/max");
    }

    @Test
    void zeroPivotAndZeroRotationCentreOnTheBoxMiddle() {
        SceneProfile profile = SceneProfile.createDefault();
        profile.setMotionMode(SceneMotionMode.LINEAR);
        profile.setSourceBounds(new SceneBounds(0, 0, 0, 20, 8, 12));
        profile.setDisplayOrigin(10.0, 20.0, 30.0);

        SceneInstanceBounds bounds = SceneMotionMath.calculateLinearInstanceBounds(profile, 5.0, 0.0, 0.0);

        assertEquals(10.0 + 5.0 + 10.0, bounds.centerX(), EPSILON);
        assertEquals(20.0 + 4.0, bounds.centerY(), EPSILON);
        assertEquals(30.0 + 6.0, bounds.centerZ(), EPSILON);
    }

    @Test
    void emptyProfileDegradesToAPointInsteadOfNaN() {
        SceneInstanceBounds bounds = SceneMotionMath.calculateLinearInstanceBounds(null, 0.0, 0.0, 0.0);

        assertEquals(0.0, bounds.radius(), EPSILON);
        assertTrue(bounds.isWithinDistance(0.0, 0.0, 0.0, 1.0));
    }
}
