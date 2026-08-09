package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.RepairModeManager;
import net.exmo.sre.repair.logic.RepairGameSetup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin - 修机模式开局准备的维修员保护（第一道闸）。
 *
 * <p>{@code RepairGameSetup.prepareWorld} 会把「不在参战名单」的玩家（含维修员）强制设旁观
 * 并传送到庄园上方观察位。本 mixin 对维修员短路跳过这两处，保持其创造模式与修图位置。</p>
 *
 * <p>{@code prepareWorld} 内共有两处 {@code ServerPlayer.setGameMode}：先对参战名单玩家设
 * {@code SURVIVAL}（清包+重置），再对全体非参战玩家设 {@code SPECTATOR}。若不指定
 * {@code ordinal}，本重定向会同时拦截这两处：非维修员原样委托（行为不变），维修员两处都跳过
 * （他们既不在参战名单中，也不应被拖成旁观）。曾用 {@code ordinal = 0} 只拦到 SURVIVAL 那处
 * （对维修员本就不会触发），导致 SPECTATOR 赋值泄漏到维修员身上。</p>
 *
 * <p>目标类 {@link RepairGameSetup} 为 SRE mod 类，方法名不在官方映射表，{@code method}
 * 不重映射；但 {@code @At} 的 Minecraft 方法（ServerPlayer.setGameMode / ServerPlayer.teleportTo）
 * 需要重映射到运行时 intermediary 名称，因此不使用 {@code remap=false}。{@code prepareWorld}
 * 为 {@code static} 方法，回调与 {@link SRERepairModeProtectMixin} 一样声明为 {@code static}；
 * {@code teleportTo} 字节码 owner 为 {@code ServerPlayer}（覆写方法），目标串必须写
 * {@code ServerPlayer;teleportTo(...)}。</p>
 */
@Mixin(RepairGameSetup.class)
public abstract class SRERepairGameSetupProtectMixin {

    /** prepareWorld 的 setGameMode 全部调用：维修员跳过，保持创造模式；其余原样委托。 */
    @Redirect(
            method = "prepareWorld",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;setGameMode(Lnet/minecraft/world/level/GameType;)Z"))
    private static boolean habitrain$skipSpectatorForRepairerPrepareWorld(ServerPlayer player, GameType gameType) {
        if (RepairModeManager.isRepairer(player)) {
            return false; // 保持维修员当前（创造）模式
        }
        return player.setGameMode(gameType);
    }

    /** prepareWorld 把非参战玩家传送庄园上方观察位：维修员跳过，保持原位。 */
    @Redirect(
            method = "prepareWorld",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V"))
    private static void habitrain$skipSpectatorTeleportForRepairerPrepareWorld(ServerPlayer player, ServerLevel level,
            double x, double y, double z, float yaw, float pitch) {
        if (!RepairModeManager.isRepairer(player)) {
            player.teleportTo(level, x, y, z, yaw, pitch);
        }
    }
}
