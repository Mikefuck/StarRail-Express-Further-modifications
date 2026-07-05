package com.habitrain.core.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.wifi.starrailexpress.content.block.CameraBlock;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.agmas.noellesroles.client.TaskBlockOverlayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.awt.Color;

@Environment(EnvType.CLIENT)
@Mixin(TaskBlockOverlayRenderer.class)
public class CameraBlockOverlayMixin {

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/agmas/noellesroles/client/TaskBlockOverlayRenderer;renderBlockOverlay(Lnet/fabricmc/fabric/api/client/rendering/v1/WorldRenderContext;Lnet/minecraft/core/BlockPos;Ljava/awt/Color;FZFLnet/minecraft/network/chat/Component;)V",
                    remap = false
            ),
            remap = false
    )
    private static void habitrain$skipCameraOverlay(
            net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext context,
            BlockPos pos, Color color, float alpha, boolean throughWalls,
            float lineWidth, Component text) {
        ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof CameraBlock) {
                return;
            }
        }
        TaskBlockOverlayRenderer.renderBlockOverlay(context, pos, color, alpha, throughWalls, lineWidth, text);
    }
}