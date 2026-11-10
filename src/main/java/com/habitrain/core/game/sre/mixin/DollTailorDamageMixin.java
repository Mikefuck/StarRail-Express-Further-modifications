package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.tailor.DollTailorService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class DollTailorDamageMixin {
    @Inject(method = "hurt", at = @At("RETURN"))
    private void habitrain$cutThreadOnDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && amount > 0 && (Object) this instanceof ServerPlayer player)
            DollTailorService.interrupt(player);
    }
}
