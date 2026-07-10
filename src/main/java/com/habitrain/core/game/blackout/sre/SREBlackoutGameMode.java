package com.habitrain.core.game.blackout.sre;

import com.habitrain.core.api.WinResult;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutVictoryChecker;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.game.modes.SREMurderGameMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.Harpymodloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 停电模式专用 SRE GameMode。
 *
 * 角色分配完全复用 SRE 原版机制（父类 {@link SREMurderGameMode#assignRole} →
 * {@code assignRolesToPlayers}，含 RoleCountManager/权重/forced role），
 * 再由 {@link BlackoutRoleManager#syncFactionsFromSreRoles} 从 SRE 分配结果同步
 * 停电阵营状态（canUseKiller=BAD, 其余=GOOD）。
 *
 * 胜负结算由 {@link BlackoutMode#checkVictory} 独立接管，{@link #allowGameEnd}
 * 始终返回 NOT_MODIFY 以阻止 SRE 自带判定介入。
 */
public class SREBlackoutGameMode extends SREMurderGameMode {
    private static final Logger LOGGER = LoggerFactory.getLogger("SREBlackoutGameMode");
    public static final ResourceLocation MODE_ID = ResourceLocation.fromNamespaceAndPath("sre", "blackout");
    /** 最少玩家数 */
    private static final int MIN_PLAYERS = 10;
    /** 初始杀手数 */
    private static final int KILLER_COUNT = 1;
    private static boolean registered = false;

    public SREBlackoutGameMode() {
        super(MODE_ID, MIN_PLAYERS, KILLER_COUNT);
    }

    @Override
    public void initializeGame(ServerLevel world, SREGameWorldComponent game, List<ServerPlayer> players) {
        Harpymodloader.refreshRoles();
        game.clearRoleMap();

        addPlayersToTeam(world.getServer().createCommandSourceStack(), players, "harpymodloader_game");

        // 开局不刷新警察阵营角色（需求：开局无警察，警察只能通过电话聘请产生）
        BlackoutRoleManager.disableAllVigilanteRoles();
        // 复用 SRE 原版角色分配流程（含 RoleCountManager/权重/forced role）
        assignRole(world, game, players);
        // 从 SRE 分配结果同步停电阵营状态
        BlackoutRoleManager.syncFactionsFromSreRoles(world, game, players);
        game.syncRoles();

        com.habitrain.core.network.BlackoutTimerPayload.broadcastToAll(world.getServer(), 300, 120, false, 0);
        executeFunction(world.getServer().createCommandSourceStack(), "harpymodloader:start_game");
    }

    @Override
    public boolean shouldRecordPlayerStats() {
        return false;
    }

    @Override
    public boolean hasSafeTime() {
        return true;
    }

    @Override
    public boolean requiresAssignedRole() {
        return false;
    }

    /**
     * 阻止 SRE 自带的胜负判定接管游戏结束，让 {@link BlackoutMode#checkVictory}
     * 作为唯一结算入口，再由 {@link #finalizeGame} 负责填充回放数据。
     */
    @Override
    public io.wifi.starrailexpress.game.GameUtils.WinStatus allowGameEnd(ServerLevel world,
                                                                          io.wifi.starrailexpress.game.GameUtils.WinStatus current,
                                                                          boolean flag,
                                                                          SREGameWorldComponent game) {
        return GameUtils.WinStatus.NOT_MODIFY;
    }

    /**
     * 在 SRE 停止游戏后接管回放数据填充，确保结算屏显示正确的胜负阵营与头像分组。
     * <p>
     * endGame 已在 STOPPING 前写入 roundEnd；此处再补一次（幂等），并延后
     * {@code GameModeRegistry.stop}，以便 habitrain 清理发生在身份揭示数据就绪之后。
     */
    @Override
    public void finalizeGame(ServerLevel world, SREGameWorldComponent game) {
        super.finalizeGame(world, game);

        try {
            BlackoutRoleManager.Faction winner = null;
            BlackoutMode bm = null;
            var modeOpt = com.habitrain.core.api.GameModeRegistry.getActiveForLevel(world);
            if (modeOpt.isPresent() && modeOpt.get() instanceof BlackoutMode active) {
                bm = active;
                winner = active.getLastWinningFaction();
            } else {
                // 兜底：用注册表里的单例（ACTIVE 可能已摘掉，但 lastWinningFaction 仍在）
                var registered = com.habitrain.core.api.GameModeRegistry.get(BlackoutMode.REGISTRY_FULL_ID);
                if (registered instanceof BlackoutMode fallback) {
                    bm = fallback;
                    winner = fallback.getLastWinningFaction();
                }
            }

            // 再写一次 roundEnd（若 endGame 已写则刷新 hasWin / 在线列表）
            BlackoutVictoryChecker.populateRoundEndData(world, winner);

            // 延后停止 habitrain GameMode：字幕已在 endGame 广播，这里只做清理
            if (bm != null && com.habitrain.core.api.GameModeRegistry.isActiveInLevel(world)) {
                WinResult result = bm.getPendingWinResult();
                if (result == null) {
                    result = WinResult.forceEnd("游戏结束");
                }
                com.habitrain.core.api.GameModeRegistry.stop(world, result);
            }
        } catch (Throwable t) {
            LOGGER.error("finalizeGame: failed to populate blackout round-end data", t);
            try {
                // 保证 habitrain 模式不会永远卡在 active
                if (com.habitrain.core.api.GameModeRegistry.isActiveInLevel(world)) {
                    com.habitrain.core.api.GameModeRegistry.stop(world, WinResult.forceEnd("finalize 异常"));
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        SREGameModes.registerGameMode(new SREBlackoutGameMode());
        LOGGER.info("SREBlackoutGameMode registered: {}", MODE_ID);
    }
}