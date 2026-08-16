package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.EliminatedRestAreaService;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 休息区玩家不参与对局击杀判定。
 *
 * <p>SRE 所有玩家击杀（刀/枪/棍/手雷/毒/出界等）统一经
 * {@code GameUtils.killPlayer} 进入 {@code GameMode.killPlayer}，那里会
 * 加时（误杀平民加时间）、广播击杀/友军击杀事件、统计与奖励，
 * 并可能触发仇杀客等技能。休息区（中场休息大厅）玩家互相打杀属于
 * 大厅内的娱乐行为，不应计入对局：
 * <ul>
 *   <li>休息者作为击杀者发起击杀 → 不计入对局；</li>
 *   <li>休息者作为受害者被休息者/无对局参与者的来源击杀 → 不计入对局，
 *       并在休息区原地回满血（避免被 SRE 武器命中后留下半血/残留状态）；</li>
 *   <li>休息者被存活对局玩家主动击杀 → 仍计入（“对局内的事件主动影响休息者”，
 *       如复活、技能等，见 EliminatedRestAreaService 的复活语义）；</li>
 *   <li>{@code forceDeath}（指令/巫毒等强制击杀）→ 不拦截，保持对局主动影响休息者。</li>
 * </ul>
 *
 * <p>与 {@link OutOfGameKillMixin}/{@link MeleeImmuneKillMixin} 同为对
 * {@code GameUtils.killPlayer} 的 HEAD 软拦截（require=0，签名变化不阻断启动）。
 */
@Mixin(value = GameUtils.class, remap = false)
public class RestAreaKillMixin {

    @Inject(
            method = "killPlayer(Lnet/minecraft/world/entity/player/Player;ZLnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceLocation;Z)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void habitrain$restAreaKillIsNotMatchJudgment(Player victim, boolean spawnBody, Player killer,
                                                                 ResourceLocation deathReason, boolean forceDeath,
                                                                 CallbackInfo ci) {
        if (forceDeath || victim == null) {
            return;
        }

        boolean victimResting = isResting(victim);
        boolean killerResting = isResting(killer);

        // 休息者发起击杀，或休息者被“非存活对局参与者”（休息者/无来源）击杀：
        // 都属于休息区行为，不进入 GameMode.killPlayer 的对局判定。
        if (killerResting || (victimResting && !GameUtils.isPlayerAliveAndSurvival(killer))) {
            ci.cancel();
            if (victimResting && victim.getHealth() < victim.getMaxHealth()) {
                victim.setHealth(victim.getMaxHealth());
            }
        }
    }

    private static boolean isResting(Player player) {
        return player instanceof ServerPlayer serverPlayer && EliminatedRestAreaService.isResting(serverPlayer);
    }
}
