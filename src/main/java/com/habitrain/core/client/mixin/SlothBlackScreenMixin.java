package com.habitrain.core.client.mixin;

import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class SlothBlackScreenMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void habitrain$renderSleepBlackout(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!SlothComponent.isSleepingSloth(client.player) || client.screen instanceof PauseScreen) return;
        if (client.screen instanceof com.habitrain.core.client.gui.GameEndTransitionScreen
                || client.screen instanceof com.habitrain.core.client.gui.VoteLaunchTransitionScreen) return;
        GuiGraphics graphics = new GuiGraphics(client, client.renderBuffers().bufferSource());
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xFF000000);
        graphics.flush();
    }
}
