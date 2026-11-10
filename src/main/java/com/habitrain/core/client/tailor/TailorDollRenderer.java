package com.habitrain.core.client.tailor;

import com.habitrain.core.game.sre.role.tailor.DollTailorRules;
import com.habitrain.core.game.sre.role.tailor.TailorDollEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

/** Draws the skin directly on a cloth-posed model; no fake player is added to the world. */
public final class TailorDollRenderer extends EntityRenderer<TailorDollEntity> {
    private final PlayerModel<TailorDollEntity> wide;
    private final PlayerModel<TailorDollEntity> slim;
    public TailorDollRenderer(EntityRendererProvider.Context context) {
        super(context);
        wide = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false);
        slim = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        shadowRadius = .3F;
    }
    private PlayerSkin skin(TailorDollEntity doll) {
        var connection = Minecraft.getInstance().getConnection();
        var info = connection == null || doll.targetId() == null ? null : connection.getPlayerInfo(doll.targetId());
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(doll.targetId() == null ? doll.getUUID() : doll.targetId());
    }
    @Override public ResourceLocation getTextureLocation(TailorDollEntity doll) { return skin(doll).texture(); }
    @Override public void render(TailorDollEntity doll, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        var appearance = skin(doll);
        var model = appearance.model() == PlayerSkin.Model.SLIM ? slim : wide;
        float progress = Math.min(1F, (doll.stitches() + partial) / DollTailorRules.SEWING_TICKS);
        float alpha = doll.complete() ? 1F : .1F + .9F * progress;
        model.setAllVisible(true); model.crouching = false; model.riding = false; model.attackTime = 0;
        model.setupAnim(doll, 0, 0, 0, 0, 0);
        model.head.zRot = .1F; model.head.xScale = model.head.yScale = model.head.zScale = 1.08F;
        model.hat.copyFrom(model.head);
        model.rightArm.zRot = .16F; model.leftArm.zRot = -.16F;
        model.rightArm.xRot = model.leftArm.xRot = -.12F;
        model.rightSleeve.copyFrom(model.rightArm); model.leftSleeve.copyFrom(model.leftArm);
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180 - yaw));
        float bounce = DollTailorClient.bounce(doll, partial);
        pose.translate(0, bounce, 0);
        pose.scale(-1, -1, 1); pose.translate(0, -1.501, 0);
        model.renderToBuffer(pose, buffers.getBuffer(RenderType.entityTranslucent(appearance.texture())), light,
                OverlayTexture.NO_OVERLAY, ((int) (alpha * 255) << 24) | 0xFFFFFF);
        pose.popPose();
    }
}
