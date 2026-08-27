package com.habitrain.core.client.mixin;

import com.habitrain.core.client.render.TaskOverlayDrawer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.core.BlockPos;
import org.agmas.noellesroles.client.TaskBlockOverlayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;

/**
 * Fix SRE vanilla task-point ESP not drawing through walls.
 *
 * <p>Upstream discovers overlays during {@code AFTER_TRANSLUCENT} and writes to
 * {@code context.consumers()}, although Fabric does not expose a deferred-consumer contract in
 * that phase. Capture each approved overlay at the method boundary and let Habi's dedicated
 * {@code LAST} pass submit it after all world framebuffer writes have completed.
 *
 * <p>Does not edit upstream {@link TaskBlockOverlayRenderer} source.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = TaskBlockOverlayRenderer.class, remap = false)
public class TaskBlockOverlayThroughWallMixin {

    @Inject(
            method = "renderBlockOverlay",
            at = @At("HEAD"),
            remap = false,
            require = 1,
            cancellable = true
    )
    private static void habitrain$queueXray(
            WorldRenderContext context,
            BlockPos blockPos,
            Color color,
            float alpha,
            boolean colorize,
            float textScale,
            CallbackInfo ci) {
        if (TaskOverlayDrawer.queueUpstreamOverlay(
                context, blockPos, color, alpha, TaskOverlayDrawer.DEFAULT_LINE_WIDTH)) {
            ci.cancel();
        }
    }
}
