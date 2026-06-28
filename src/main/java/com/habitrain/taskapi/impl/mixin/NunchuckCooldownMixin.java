package com.habitrain.taskapi.impl.mixin;

import com.habitrain.taskapi.HabiTrainTaskAPI;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.index.TMMItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin - 杀手双节棍击杀冷却（v2）
 *
 * ★ 设计变更（根据用户要求）★
 * - 移除旧版对所有角色每次命中都设置CD的逻辑
 * - 改为：仅杀手（roleType=4）在击杀目标后才设置 50 秒冷却（1000 ticks）
 * - 其他职业和杀手的非击杀命中：不干预，保持原版逻辑（5秒/0秒冷却）
 *
 * 实现方式：
 *   在 GameUtils.killPlayer() 调用处注入标记 killHappened，
 *   在 TAIL 检查标记 + 角色类型，只对杀手的击杀行为覆盖冷却。
 */
@Mixin(targets = "io.wifi.starrailexpress.network.original.NunchuckHitPayload")
public class NunchuckCooldownMixin {

    /** 追踪本次 onHurt 调用中是否发生了击杀 */
    private static final ThreadLocal<Boolean> killHappened = ThreadLocal.withInitial(() -> false);

    /**
     * HEAD 注入 - 重置击杀标记
     */
    @Inject(
            method = "onHurt",
            at = @At("HEAD"),
            remap = false
    )
    private static void habitrain$resetKillFlag(ServerPlayer attacker, Player target, int direction_,
                                                CallbackInfo ci) {
        killHappened.set(false);
    }

    /**
     * 在 GameUtils.killPlayer() 调用处标记击杀
     *
     * killPlayer 只在 onHurt 的击杀路径中被调用（shouldKill=true），
     * 非击杀路径不含此调用。因此在此注入即表示本次命中触发了击杀。
     */
    @Inject(
            method = "onHurt",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/game/GameUtils;killPlayer(Lnet/minecraft/world/entity/player/Player;ZLnet/minecraft/world/entity/player/Player;Lnet/minecraft/resources/ResourceLocation;)V"
            ),
            remap = false
    )
    private static void habitrain$markKillHappened(ServerPlayer attacker, Player target, int direction_,
                                                   CallbackInfo ci) {
        killHappened.set(true);
    }

    /**
     * TAIL 注入 - 仅对杀手的击杀行为覆盖冷却
     *
     * 逻辑：
     * - 如果发生了击杀（killHappened=true）：
     *   - 且攻击者是杀手（roleType=4）：覆盖冷却为 1000 ticks（50秒）
     *   - 且攻击者不是杀手：不动（保留原版 5 秒冷却）
     * - 如果未发生击杀（非击杀命中或早期返回）：不动
     */
    @Inject(
            method = "onHurt",
            at = @At("TAIL"),
            remap = false
    )
    private static void habitrain$applyKillerCooldown(ServerPlayer attacker, Player target, int direction_,
                                                      CallbackInfo ci) {
        if (!Boolean.TRUE.equals(killHappened.get())) {
            return; // 未发生击杀，不处理
        }
        killHappened.set(false);

        try {
            // 只处理杀手角色
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(attacker.level());
            var role = game.getRole(attacker);
            if (role == null || role.getRoleType() != 4) {
                return; // 非杀手，保留原版冷却（5秒）
            }

            // 杀手击杀：覆盖为 50 秒冷却
            attacker.getCooldowns().addCooldown(TMMItems.NUNCHUCK, 1000);

            HabiTrainTaskAPI.LOGGER.debug(
                    "[NunchuckCD] 杀手 {} 使用双节棍击杀，设置冷却=1000 ticks (50秒)",
                    attacker.getName().getString());

        } catch (Exception e) {
            HabiTrainTaskAPI.LOGGER.warn("[NunchuckCD] 设置冷却时出错", e);
        }
    }
}
