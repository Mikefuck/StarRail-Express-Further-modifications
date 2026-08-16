package com.habitrain.core.client.mixin;

import com.habitrain.core.client.render.TaskOverlayDrawer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import org.agmas.noellesroles.client.TaskBlockOverlayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;

/**
 * Fix SRE vanilla task-point ESP not drawing through walls.
 *
 * <p>Upstream uses {@code ITEM_ENTITY_TARGET + NO_DEPTH_TEST} on a deferred
 * {@code context.consumers()} batch. On 1.21 / some shader pipelines that
 * combination still gets depth-tested at flush time, so outlines only show
 * when unoccluded. Redirect the buffer to Habi's MAIN_TARGET xray type and
 * flush immediately after each box.
 *
 * <p>Does not edit upstream {@link TaskBlockOverlayRenderer} source.
 */
@Environment(EnvType.CLIENT)
@Mixin(TaskBlockOverlayRenderer.class)
public class TaskBlockOverlayThroughWallMixin {

    @Redirect(
            method = "renderBlockOverlay",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
                    remap = true
            ),
            remap = false,
            require = 0
    )
    private static com.mojang.blaze3d.vertex.VertexConsumer habitrain$xrayBuffer(
            MultiBufferSource consumers, RenderType ignored) {
        // Prefer the shared main buffer source so endBatch in the TAIL inject targets the same builder.
        MultiBufferSource.BufferSource source = Minecraft.getInstance().renderBuffers().bufferSource();
        return source.getBuffer(TaskOverlayDrawer.throughWallLines(TaskOverlayDrawer.DEFAULT_LINE_WIDTH));
    }

    @Inject(method = "renderBlockOverlay", at = @At("TAIL"), remap = false, require = 0)
    private static void habitrain$flushXray(
            WorldRenderContext context,
            BlockPos blockPos,
            Color color,
            float alpha,
            boolean colorize,
            float textScale,
            CallbackInfo ci) {
        MultiBufferSource.BufferSource source = Minecraft.getInstance().renderBuffers().bufferSource();
        source.endBatch(TaskOverlayDrawer.throughWallLines(TaskOverlayDrawer.DEFAULT_LINE_WIDTH));
    }
}
