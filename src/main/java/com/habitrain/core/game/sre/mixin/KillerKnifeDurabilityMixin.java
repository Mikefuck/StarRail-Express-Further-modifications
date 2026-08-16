package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.sre.KnifeDurabilityToggleService;
import io.wifi.starrailexpress.game.KillerKnifeDurability;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes Core's Mod Menu setting authoritative while preserving upstream murder-mode scope. */
@Mixin(value = KillerKnifeDurability.class, remap = false)
public abstract class KillerKnifeDurabilityMixin {
    @Inject(method = "isDurabilityModeEnabled", at = @At("RETURN"), cancellable = true, remap = false)
    private static void habitrain$applyDurabilitySetting(
            Level level,
            CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(
                ConfigManager.getInstance().isKnifeDurabilityEnabled()
                        && KillerKnifeDurability.isMurderMode(level));
    }

    @Inject(method = "applyFreshDurability", at = @At("RETURN"), remap = false)
    private static void habitrain$stripDurabilityWhenDisabled(ItemStack stack, CallbackInfo ci) {
        if (!ConfigManager.getInstance().isKnifeDurabilityEnabled()) {
            KnifeDurabilityToggleService.removeDurability(stack);
        }
    }
}
