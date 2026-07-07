package com.habitrain.core.game.blackout.sre;

import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
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
import java.util.Map;
import java.util.UUID;

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
    private static boolean registered = false;

    public SREBlackoutGameMode() {
        super(MODE_ID, 10, 1);
    }

    @Override
    public void initializeGame(ServerLevel world, SREGameWorldComponent game, List<ServerPlayer> players) {
        Harpymodloader.refreshRoles();
        game.clearRoleMap();

        addPlayersToTeam(world.getServer().createCommandSourceStack(), players, "harpymodloader_game");

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
     */
    @Override
    public void finalizeGame(ServerLevel world, SREGameWorldComponent game) {
        super.finalizeGame(world, game);

        try {
            SREGameRoundEndComponent roundEnd = SREGameRoundEndComponent.KEY.get(world);
            if (roundEnd == null) return;

            BlackoutRoleManager.Faction winner = BlackoutMode.getLastWinningFaction();
            GameUtils.WinStatus winStatus;
            if (winner == BlackoutRoleManager.Faction.GOOD) {
                winStatus = GameUtils.WinStatus.PASSENGERS;
            } else if (winner == BlackoutRoleManager.Faction.BAD) {
                winStatus = GameUtils.WinStatus.KILLERS;
            } else {
                winStatus = GameUtils.WinStatus.NONE;
            }

            Map<UUID, ResourceLocation> history = BlackoutRoleManager.getRoleHistory(world);
            java.util.List<ServerPlayer> participants = new java.util.ArrayList<>();
            for (ServerPlayer p : world.getServer().getPlayerList().getPlayers()) {
                if (history.containsKey(p.getUUID())) {
                    participants.add(p);
                }
            }

            roundEnd.setRoundEndData(participants, winStatus);

            for (ServerPlayer p : participants) {
                BlackoutRoleManager.Faction f = BlackoutRoleManager.getFaction(world, p.getUUID());
                boolean didWin = (winner != null && f == winner);
                roundEnd.setPlayerWin(p.getUUID(), didWin);
            }
            roundEnd.sync();

            // SRE 4.3.0 移除了 replay.screen 包（ReplayScreenSavedData/ReplayScreenService），
            // 之前的 ensureDefaultReplayScreen 调用已失效。replay screen 是 SRE 内部功能，
            // habitrain_core 不再自动创建默认 screen — 如需要请由地图作者在地图里配置。
        } catch (Throwable t) {
            LOGGER.error("finalizeGame: failed to populate blackout round-end data", t);
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