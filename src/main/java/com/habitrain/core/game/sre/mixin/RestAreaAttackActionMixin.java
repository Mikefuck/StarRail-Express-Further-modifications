package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.EliminatedRestAreaService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 休息区玩家无法对玩家发起攻击动作（左键近战 / SRE 左键武器）。
 *
 * <p>在 {@code Player#attack} 的 HEAD 取消：休息者只要把目标锁定为玩家，整个
 * 攻击动作（含上游 {@code PlayerEntityMixin} 对左键武器 {@code LeftClickHurtable}
 * 的 {@code onTryHurt} 处理与 {@code original.call} 的近战）都不会执行；
 * 目标不是玩家（如怪物）不拦截。此拦截是动作层的保险，伤害层由
 * {@link RestAreaAttackMixin}（{@code LivingEntity#hurt}）兜底。
 *
 * <p>已用 {@code require = 0} 软化：上游对 {@code Player#attack} 的
 * {@code @WrapMethod} 重写方式变化时，本注入缺失也不阻断启动。
 */
@Mixin(Player.class)
public class RestAreaAttackActionMixin {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true, require = 0)
    private void habitrain$restingPlayersCannotAttackPlayers(Entity target, CallbackInfo ci) {
        if (!(target instanceof Player)) {
            return;
        }
        if ((Object) this instanceof ServerPlayer self
                && EliminatedRestAreaService.isResting(self)) {
            ci.cancel();
        }
    }
}
