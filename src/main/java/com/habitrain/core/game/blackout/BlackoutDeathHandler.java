package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameModeRegistry;
import io.wifi.starrailexpress.event.OnPlayerDeath;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 停电模式死亡 → 黑名单 eliminate。
 *
 * SRE 杀手击杀等路径走 {@code GameUtils.killPlayer} → {@link OnPlayerDeath}，
 * 此前黑名单只在断线/理智崩溃/放逐时更新，导致死人仍占 GOOD/BAD 计数、胜负卡住。
 * eliminate 幂等：与放逐/理智路径重复调用安全。
 */
public final class BlackoutDeathHandler {
    private static boolean registered = false;

    private BlackoutDeathHandler() {}

    public static void register() {
        if (registered) return;
        registered = true;

        OnPlayerDeath.EVENT.register(BlackoutDeathHandler::onPlayerDeath);
        HabiTrainCore.LOGGER.info("[BlackoutDeathHandler] registered OnPlayerDeath → eliminate");
    }

    private static void onPlayerDeath(Player player, net.minecraft.resources.ResourceLocation deathReason) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        ServerLevel level = serverPlayer.serverLevel();
        if (level == null) return;

        // 仅停电模式对局
        var modeOpt = GameModeRegistry.getActiveForLevel(level);
        if (modeOpt.isEmpty() || !(modeOpt.get() instanceof BlackoutMode mode)) return;
        if (!mode.isActive(level) || mode.isGameEnded(level)) return;

        // 已不在黑名单存活表 → 无需处理（幂等）
        if (!BlackoutRoleManager.isAlive(level, serverPlayer.getUUID())) return;

        BlackoutRoleManager.eliminate(level, serverPlayer.getUUID());
        mode.checkVictoryAfterExile(level);

        HabiTrainCore.LOGGER.info("[BlackoutDeath] eliminated {} reason={}",
                serverPlayer.getName().getString(),
                deathReason != null ? deathReason : "null");
    }
}
