package com.habitrain.core.client.mixin;

import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.client.gui.screen.ingame.LimitedInventoryScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Turns Sloth's backpack into the server-authoritative sleep-target roster. */
@Mixin(value = LimitedInventoryScreen.class, remap = false)
public abstract class SlothBackpackSleepMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void habitrain$requestSlothSleepRoster(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        try {
            if (!HabiRoles.isHabiRole(client.player, SevenSins.SLOTH)) return;
        } catch (Throwable ignored) {
            return;
        }
        PayloadSenders.requestSlothSleepRoster();
    }
}
