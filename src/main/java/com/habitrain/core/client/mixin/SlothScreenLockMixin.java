package com.habitrain.core.client.mixin;

import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents a sleeping Sloth from opening gameplay, inventory, shop, or chat screens. */
@Mixin(Minecraft.class)
public abstract class SlothScreenLockMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void habitrain$lockSleepingSlothScreens(Screen screen, CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;
        if (screen == null || client.player == null) return;
        if (!SlothComponent.isSleepingSloth(client.player)) return;

        String name = screen.getClass().getSimpleName();
        if ("PauseScreen".equals(name) || "DeathScreen".equals(name)
                || "ReceivingLevelScreen".equals(name)) {
            return;
        }
        ci.cancel();
    }
}
