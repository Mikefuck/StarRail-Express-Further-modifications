package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.RepairModeManager;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin - 对局运行期「无角色强制旁观」的维修员豁免（兜底）。
 *
 * <p>{@code SREGameWorldComponent.serverTick} 在 ACTIVE 阶段会
 * {@code if (requiresAssignedRole() && getRole(player)==null) player.setGameMode(SPECTATOR)}。
 * 维修员不应被当作「无角色玩家」踢成旁观。虽然维修员进入时是创造模式（大部分路径已被
 * {@code !isCreative()} 豁免），一旦有任何上游代码把维修员改成冒险/生存，该兜底会把它强制
 * 变旁观。这里直接对 {@code GameType.SPECTATOR} 的写入做 {@code isRepairer} 短路。</p>
 *
 * <p>目标类为 SRE mod 类（类名与方法名不在官方映射表，重映射查找失败会原样保留），
 * 但 {@code @At} 的 {@code ServerPlayer.setGameMode} 为 Minecraft 方法，其目标串必须由
 * 注解处理器重映射为运行时 intermediary 名称——因此混入类上不能使用 {@code remap=false}
 * （那样会连带禁用 {@code @At} 目标的映射），与同组其他维修 mixin 保持一致。</p>
 */
@Mixin(SREGameWorldComponent.class)
public abstract class SRERepairGameWorldTickMixin {

    /** serverTick 里 requiresAssignedRole 兜底将无角色玩家设为旁观：维修员跳过。 */
    @Redirect(
            method = "serverTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;setGameMode(Lnet/minecraft/world/level/GameType;)Z"))
    private static boolean habitrain$skipSpectatorForRepairerTick(ServerPlayer player, GameType gameType) {
        if (RepairModeManager.isRepairer(player)) {
            return false; // 保持维修员当前模式
        }
        return player.setGameMode(gameType);
    }
}
