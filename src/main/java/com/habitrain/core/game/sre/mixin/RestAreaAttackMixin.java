package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.EliminatedRestAreaService;
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
 * 休息区玩家不能对玩家造成伤害。
 *
 * <p>无论伤害来源是近战、子弹还是爆炸，只要来源实体（{@link DamageSource#getEntity()}，
 * 弹射物会回溯到发射者）是休息区玩家，且目标是玩家，就取消这次伤害
 * （无伤害、无击退）。配合 {@link RestAreaKillMixin} 阻断 SRE 击杀链路
 * （{@code GameUtils.killPlayer}），休息者无法以任何方式伤害或击杀玩家。
 *
 * <p>与上游 {@code LivingEntityDamageMixin} 同为对 {@code LivingEntity#hurt} 的
 * HEAD 注入；上游仅记录伤害、本 mixin 仅在休息者打玩家时取消，互不冲突。
 */
@Mixin(LivingEntity.class)
public class RestAreaAttackMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void habitrain$restingPlayersCannotHurtPlayers(DamageSource source, float amount,
            CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof Player)) {
            return;
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof ServerPlayer serverAttacker
                && EliminatedRestAreaService.isResting(serverAttacker)) {
            cir.setReturnValue(false);
        }
    }
}
