package com.habitrain.core.scene.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import org.joml.Matrix4f;

/** 仅改变客户端渲染矩阵的舒适度微震，不修改玩家真实位置或朝向。 */
public final class SceneCameraShakeRenderer {
    private SceneCameraShakeRenderer() {}

    public static void apply(WorldRenderContext context) {
        if (context == null) return;
        SceneShakeController controller = SceneShakeController.getInstance();
        if (!controller.isActive()) return;
        PoseStack matrices = context.matrixStack();
        Matrix4f positionMatrix = context.positionMatrix();
        if (matrices == null && positionMatrix == null) return;

        float partialTick = context.tickCounter() != null
                ? context.tickCounter().getGameTimeDeltaPartialTick(true)
                : 0.0f;
        float[] pos = controller.getPositionOffset(partialTick);
        float[] rot = controller.getRotationOffset(partialTick);
        if (matrices != null) {
            matrices.translate(-pos[0], -pos[1], -pos[2]);
            matrices.mulPose(Axis.ZP.rotationDegrees(rot[2]));
            matrices.mulPose(Axis.XP.rotationDegrees(rot[0]));
            matrices.mulPose(Axis.YP.rotationDegrees(rot[1]));
        } else {
            positionMatrix.translate(-pos[0], -pos[1], -pos[2]);
            positionMatrix.rotate(Axis.ZP.rotationDegrees(rot[2]));
            positionMatrix.rotate(Axis.XP.rotationDegrees(rot[0]));
            positionMatrix.rotate(Axis.YP.rotationDegrees(rot[1]));
        }
    }
}
