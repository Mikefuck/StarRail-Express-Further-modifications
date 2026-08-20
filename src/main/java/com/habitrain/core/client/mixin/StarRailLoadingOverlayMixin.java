package com.habitrain.core.client.mixin;

import com.habitrain.core.client.loading.HabiLoadingScreenTextures;
import net.exmo.sre.loading.FrameAnimationRenderer;
import net.exmo.sre.loading.StarRailLoadingOverlay;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 替换游戏启动 / 资源重载阶段的加载页面（StarRailLoadingOverlay）背景图。
 */
@Environment(EnvType.CLIENT)
@Mixin(StarRailLoadingOverlay.class)
public class StarRailLoadingOverlayMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "registerTextures", at = @At("TAIL"))
    private static void habitrain$registerLoadingTextures(Minecraft minecraft, CallbackInfo ci) {
        HabiLoadingScreenTextures.registerTextures(minecraft);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void habitrain$ensureTexturesRegistered(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        HabiLoadingScreenTextures.registerTextures(this.minecraft);
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/exmo/sre/loading/FrameAnimationRenderer;hasFrames()Z")
    )
    private boolean habitrain$disableVideoAnim(FrameAnimationRenderer instance) {
        return false;
    }

    @ModifyArg(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIFFIIII)V"
            ),
            index = 0
    )
    private ResourceLocation habitrain$replaceStartupLoadingTexture(ResourceLocation original) {
        return HabiLoadingScreenTextures.GAME_LOADING_TEXTURE;
    }
}
