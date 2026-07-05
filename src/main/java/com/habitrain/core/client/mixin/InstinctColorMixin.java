package com.habitrain.core.client.mixin;

import com.habitrain.core.client.InstinctColorHelper;
import io.wifi.starrailexpress.client.SREClient;
import io.wifi.starrailexpress.content.block.SecurityMonitorBlock;
import io.wifi.starrailexpress.content.block.api.TaskInstinctShowableInterface;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.agmas.noellesroles.client.NoellesrolesClient;
import org.agmas.noellesroles.client.TaskBlockOverlayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;

@Environment(EnvType.CLIENT)
@Mixin(TaskBlockOverlayRenderer.class)
public class InstinctColorMixin {

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private static void habitrain$buildOverrides(CallbackInfo ci) {
        if (!InstinctColorHelper.isDirty()) {
            return;
        }
        InstinctColorHelper.rebuildOverrides();
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/agmas/noellesroles/client/TaskBlockOverlayRenderer;renderBlockOverlay(Lnet/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext;Lnet/minecraft/core/BlockPos;Ljava/awt/Color;FZFLnet/minecraft/network/chat/Component;)V"
            ),
            remap = false,
            require = 0
    )
    private static void habitrain$redirectOverlay(
            WorldRenderContext ctx, BlockPos pos, Color color, float alpha,
            boolean colorize, float textScale, Component text) {
        Integer type = NoellesrolesClient.taskBlocks.get(pos);
        if (type != null) {
            var level = ctx.world();
            if (level != null) {
                var state = level.getBlockState(pos);
                var block = state.getBlock();

                if (!SREClient.isPlayerSpectatingOrCreative()) {
                    if (block instanceof SecurityMonitorBlock) return;
                    if (type == 11) return;
                }

                if (block instanceof TaskInstinctShowableInterface) {
                    TaskBlockOverlayRenderer.renderBlockOverlay(
                            ctx, pos, color, alpha, colorize, textScale, text);
                    return;
                }
            }

            Color override = InstinctColorHelper.getOverrideColors().get(type);
            if (override != null) {
                TaskBlockOverlayRenderer.renderBlockOverlay(
                        ctx, pos, override, alpha, colorize, textScale, text);
                return;
            }
        }

        TaskBlockOverlayRenderer.renderBlockOverlay(
                ctx, pos, color, alpha, colorize, textScale, text);
    }
}
