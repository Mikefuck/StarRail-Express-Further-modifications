package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.modifier.virtue.ChastityVirtue;
import com.habitrain.core.game.sre.role.sins.component.GluttonyComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects newly applied ordinary debuffs before their first tick. */
@Mixin(LivingEntity.class)
public abstract class VirtueEffectImmunityMixin {
    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z",
            at = @At("HEAD"), cancellable = true)
    private void habitrain$rejectEffect(MobEffectInstance effect,
                                        CallbackInfoReturnable<Boolean> cir) {
        reject(effect, cir);
    }

    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("HEAD"), cancellable = true)
    private void habitrain$rejectEffectFromEntity(MobEffectInstance effect, Entity source,
                                                  CallbackInfoReturnable<Boolean> cir) {
        reject(effect, cir);
    }

    private void reject(MobEffectInstance effect, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer player) || effect == null) return;
        if (GluttonyComponent.isGluttony(player)
                && GluttonyComponent.isOrdinaryDebuff(effect.getEffect())) {
            cir.setReturnValue(false);
            return;
        }
        if (ChastityVirtue.hasChastity(player)
                && ChastityVirtue.isRegisteredPoison(effect.getEffect())) {
            cir.setReturnValue(false);
        }
    }
}
