package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.RepairModeManager;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin - 维修员对 {@code GameUtils} 玩家重置的豁免（开局清理 / 局终清理）。
 *
 * <p>SRE 在开局与局终都会对「全体在线玩家」执行强制重置，而非只对参战名单：</p>
 * <ul>
 *   <li>{@code GameUtils.baseInitialize} 开局清理循环对全体在线玩家调用
 *       {@code clearInventory + resetPlayer}（{@code resetPlayer} 会把玩家强制设回
 *       {@code SURVIVAL}、清空 SRE 组件与背包）；</li>
 *   <li>{@code GameUtils.finalizeGame} 局终对 {@code getPlayerList().getPlayers()}
 *       全体调用 {@code resetPlayerAfterGame}（清包、设生存、传送到地图出生点）。</li>
 * </ul>
 * <p>维修员不参与对局，应始终保持创造模式、保留修图背包与当前位置，因此对这两处入口做
 * {@code isRepairer} 短路直接返回。开局清理循环的 {@code clearInventory} 部分由
 * {@link SRERepairModeProtectMixin} 过滤循环源列表处理；本 mixin 的 {@code resetPlayer}
 * 兜底覆盖所有调用方（含 {@code baseInitialize} 与局终 {@code resetPlayerAfterGame}）。</p>
 *
 * <p>目标类与方法名均为 SRE 侧名称（不在官方映射表），无需重映射；{@code @Inject} 无
 * Minecraft {@code @At} 目标，故混入类保持默认重映射设置即可。</p>
 */
@Mixin(GameUtils.class)
public abstract class SRERepairResetPlayerMixin {

    /** resetPlayer：维修员跳过（保持创造模式/背包/位置）。 */
    @Inject(method = "resetPlayer", at = @At("HEAD"), cancellable = true)
    private static void habitrain$skipResetPlayerForRepairer(ServerPlayer player, CallbackInfo ci) {
        if (RepairModeManager.isRepairer(player)) {
            ci.cancel();
        }
    }

    /** resetPlayerAfterGame：维修员跳过（不清包、不变生存、不回出生点）。 */
    @Inject(method = "resetPlayerAfterGame", at = @At("HEAD"), cancellable = true)
    private static void habitrain$skipResetPlayerAfterGameForRepairer(ServerPlayer player, CallbackInfo ci) {
        if (RepairModeManager.isRepairer(player)) {
            ci.cancel();
        }
    }
}
