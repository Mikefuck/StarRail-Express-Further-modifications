package com.habitrain.core.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.habitrain.core.client.loading.HabiLoadingScreenTextures;
import net.exmo.sre.loading.FrameAnimationRenderer;
import net.exmo.sre.loading.TrainLoadingScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 替换区块生成 / 世界加载界面（TrainLoadingScreen）的背景图。
 */
@Environment(EnvType.CLIENT)
@Mixin(TrainLoadingScreen.class)
public abstract class TrainLoadingScreenMixin extends Screen {

    protected TrainLoadingScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void habitrain$onInit(CallbackInfo ci) {
        HabiLoadingScreenTextures.registerTextures(this.minecraft);
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/exmo/sre/loading/FrameAnimationRenderer;hasFrames()Z")
    )
    private boolean habitrain$forceHasFrames(FrameAnimationRenderer instance) {
        return true;
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/exmo/sre/loading/FrameAnimationRenderer;render(Lnet/minecraft/client/gui/GuiGraphics;IIFF)V"
            )
    )
    private void habitrain$renderWorldLoadingBackground(FrameAnimationRenderer instance, GuiGraphics g, int screenWidth, int screenHeight, float delta, float overallAlpha) {
        HabiLoadingScreenTextures.registerTextures(this.minecraft);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1.0F, 1.0F, 1.0F, overallAlpha);
        g.blit(HabiLoadingScreenTextures.WORLD_LOADING_TEXTURE, 0, 0, 0.0F, 0.0F, screenWidth, screenHeight, screenWidth, screenHeight);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }
}
