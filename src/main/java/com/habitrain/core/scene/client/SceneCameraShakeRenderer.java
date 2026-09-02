package com.habitrain.core.scene.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;

/** 仅改变客户端渲染矩阵的舒适度微震，不修改玩家真实位置或朝向。 */
public final class SceneCameraShakeRenderer {
    private SceneCameraShakeRenderer() {}

    public static void apply(WorldRenderContext context) {
        SceneShakeController controller = SceneShakeController.getInstance();
        PoseStack matrices = context.matrixStack();
        if (!controller.isActive() || matrices == null) return;

        float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
        float[] pos = controller.getPositionOffset(partialTick);
        float[] rot = controller.getRotationOffset(partialTick);
        matrices.translate(-pos[0], -pos[1], -pos[2]);
        matrices.mulPose(Axis.ZP.rotationDegrees(rot[2]));
        matrices.mulPose(Axis.XP.rotationDegrees(rot[0]));
        matrices.mulPose(Axis.YP.rotationDegrees(rot[1]));
    }
}
