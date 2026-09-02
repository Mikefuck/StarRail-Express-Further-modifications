package com.habitrain.core.client.mixin;

import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.render.CustomTitlePanoramaRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the panorama used by vanilla title-menu child screens.
 *
 * <p>StarRailExpress replaces {@link Screen#PANORAMA} globally during
 * {@code Screen.<clinit>}. Its custom title screen has a separate render path,
 * while screens such as world selection and multiplayer still call
 * {@code Screen.renderPanorama}. Intercepting that render boundary
 * keeps both paths on the same optional Core panorama without mutating the
 * upstream DLC.</p>
 */
@Environment(EnvType.CLIENT)
@Mixin(Screen.class)
public abstract class ScreenPanoramaMixin {

    @Inject(method = "renderPanorama", at = @At("HEAD"), cancellable = true, require = 1)
    private void habitrain$renderCustomPanorama(GuiGraphics graphics, float delta, CallbackInfo ci) {
        if (!ClientVisualPreferences.isCustomTitlePanoramaEnabled()) return;

        Screen screen = (Screen) (Object) this;
        CustomTitlePanoramaRenderer.render(graphics, screen.width, screen.height, 1.0F, delta);
        ci.cancel();
    }
}
