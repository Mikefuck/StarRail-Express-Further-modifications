package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.rest.EliminatedRestAreaService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 休息区玩家既不能对玩家造成伤害，也不能受到任何伤害。
 */
@Mixin(LivingEntity.class)
public class RestAreaAttackMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void habitrain$restingPlayersCannotHurtOrBeHurt(DamageSource source, float amount,
            CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof Player victim)) {
            return;
        }
        if (victim instanceof ServerPlayer serverVictim
                && EliminatedRestAreaService.isResting(serverVictim)) {
            cir.setReturnValue(false);
            return;
        }
        Entity attacker = source != null ? source.getEntity() : null;
        if (attacker instanceof ServerPlayer serverAttacker
                && EliminatedRestAreaService.isResting(serverAttacker)) {
            cir.setReturnValue(false);
        }
    }
}
