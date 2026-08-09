package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.RepairModeManager;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin - 维修人员模式的对局保护。
 *
 * <p>维修员已通过 {@link ParticipationComponent} 标记为「不参与」，因此 SRE 开局时不会把它
 * 计入开局名单、不分配角色/任务。但 SRE 的上游仍在多处对「全体玩家」做强制处理，会把维修员
 * 从修图位拖走并取消其创造模式：</p>
 *
 * <ul>
 *   <li>{@code GameUtils.initializeGame} 把全体玩家强制设为 {@code SPECTATOR}；</li>
 *   <li>{@code GameUtils.baseInitialize} 把全体玩家清包并 {@code resetPlayer}（强制生存）；
 *       本 mixin 通过过滤循环源列表把维修员排除在清理循环之外；</li>
 *   <li>{@code GameUtils.baseInitialize} 把非开局玩家传送 spectator spawn；</li>
 *   <li>修机模式 {@code RepairGameSetup.prepareWorld} 把非参战玩家设旁观并传送庄园上方；</li>
 *   <li>{@code SREGameWorldComponent.serverTick} 里 {@code requiresAssignedRole()} 的兜底会把
 *       无角色且非创造模式的玩家强制变旁观。</li>
 * </ul>
 *
 * <p>本 mixin 用 {@code @Redirect} 对维修员短路跳过这些强制处理，保持其创造模式与修图位置。
 * 非维修员行为不变。目标类为 SRE mod 类（不在官方映射表，默认重映射会原样保留类名/方法名），
 * 而 {@code @At} 的 Minecraft 类（ServerPlayer/GameType/ServerLevel）需正确重映射——因此
 * Minecraft 目标的注入不使用 {@code remap=false}。</p>
 *
 * <p><b>实现注意（static 与 owner）：</b>{@code GameUtils} 的对局方法（initializeGame /
 * baseInitialize 等）全部为 {@code static}，而 Mixin 严格禁止「非 static 回调 + static 目标」
 * （抛 {@code InvalidInjectionException} 并导致整个 mixin 应用失败、全部重定向失效），因此本
 * mixin 的回调全部声明为 {@code static}。另外 {@code teleportTo} 调用在字节码常量池中的
 * owner 是 {@code ServerPlayer}（覆写方法，非父类 {@code Entity}），{@code @At} 目标必须写
 * {@code ServerPlayer;teleportTo(...)} 才能精确命中。</p>
 */
@Mixin(GameUtils.class)
public abstract class SRERepairModeProtectMixin {

    /** initializeGame 把全部玩家设为 SPECTATOR（ordinal 0）：维修员跳过，保持创造模式。 */
    @Redirect(
            method = "initializeGame",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;setGameMode(Lnet/minecraft/world/level/GameType;)Z",
                    ordinal = 0))
    private static boolean habitrain$skipSpectatorForRepairerInit(ServerPlayer player, GameType gameType) {
        if (RepairModeManager.isRepairer(player)) {
            return false; // 保持维修员当前（创造）模式
        }
        return player.setGameMode(gameType);
    }

    /** baseInitialize 把非开局玩家设为 SPECTATOR：维修员跳过，保持创造模式。 */
    @Redirect(
            method = "baseInitialize",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;setGameMode(Lnet/minecraft/world/level/GameType;)Z",
                    ordinal = 0))
    private static boolean habitrain$skipSpectatorForRepairerBase(ServerPlayer player, GameType gameType) {
        if (RepairModeManager.isRepairer(player)) {
            return false;
        }
        return player.setGameMode(gameType);
    }

    /**
     * baseInitialize 对「全体在线玩家」的清理循环（{@code world.players()}：读名牌 →
     * {@code clearInventory} → {@code resetPlayer}）：维修员跳过。
     *
     * <p>{@code resetPlayer} 会把玩家强制设回 {@code SURVIVAL} 并清空背包——若维修员被
     * 该循环处理，创造模式与修图材料都会被抹掉。这里把循环源列表过滤掉维修员，使其完全
     * 不进入该循环。baseInitialize 内仅此一处调用 {@code ServerLevel.players()}
     * （另一个全体遍历用的是 {@code getPlayerList().getPlayers()}，已由 setGameMode/
     * teleportTo 两个重定向覆盖），故不会误伤其他逻辑。</p>
     */
    @Redirect(
            method = "baseInitialize",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"))
    private static java.util.List<ServerPlayer> habitrain$filterRepairersFromResetLoop(ServerLevel level) {
        java.util.List<ServerPlayer> out = new java.util.ArrayList<>(level.players().size());
        for (ServerPlayer p : level.players()) {
            if (p != null && !RepairModeManager.isRepairer(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** baseInitialize 把非开局玩家传送到 spectator spawn（6 参重载）：维修员跳过，保持原位。 */
    @Redirect(
            method = "baseInitialize",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V"))
    private static void habitrain$skipSpectatorTeleportForRepairer(ServerPlayer player, ServerLevel level,
            double x, double y, double z, float yaw, float pitch) {
        if (!RepairModeManager.isRepairer(player)) {
            player.teleportTo(level, x, y, z, yaw, pitch);
        }
    }

}
