package com.habitrain.core.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.wifi.starrailexpress.client.render.entity.PlayerBodyEntityRenderer;
import io.wifi.starrailexpress.content.entity.PlayerBodyEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 默剧杀手尸体隐藏：SRE {@link PlayerBodyEntityRenderer} 在 invisible 时仍会渲染骨架
 * （肉体走 isBodyVisible，骨架不检查 invisible）。整段取消渲染，才是“尸体消失、血迹仍在”。
 * 服务端用 {@code body.setInvisible(true/false)} 同步 5 秒窗口。
 */
@Mixin(value = PlayerBodyEntityRenderer.class, remap = false)
public class MimeBodyHideMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    private void habitrain$skipHiddenBodyCull(PlayerBodyEntity entity, Frustum frustum,
                                              double camX, double camY, double camZ,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (entity != null && entity.isInvisible()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void habitrain$skipHiddenBodyRender(PlayerBodyEntity entity, float yaw, float tickDelta,
                                                PoseStack poseStack, MultiBufferSource buffer, int light,
                                                CallbackInfo ci) {
        if (entity != null && entity.isInvisible()) {
            ci.cancel();
        }
    }
}
