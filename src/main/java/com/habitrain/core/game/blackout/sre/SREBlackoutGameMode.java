package com.habitrain.core.game.blackout.sre;

import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutRoles;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.game.modes.SREMurderGameMode;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.Harpymodloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Blackout mode-specific SRE game mode.
 *
 * The blackout mode keeps SRE's game loop alive by mapping every player to a
 * valid SRE role while the real faction/role logic stays inside this mod's
 * registry-backed blackout model.
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

        BlackoutRoleManager.initRandomAssignment(world, players);
        assignSreRoles(world, game, players);
        game.syncRoles();

        int badCount = BlackoutRoleManager.getRemainingBad(world);
        int goodCount = BlackoutRoleManager.getRemainingGood(world);
        for (ServerPlayer player : players) {
            var definition = BlackoutRoleManager.getRoleDefinition(world, player.getUUID());
            if (definition == null) {
                definition = BlackoutRoles.CIVILIAN;
            }

            ServerPlayNetworking.send(player, new com.habitrain.core.network.BlackoutAnnouncePayload(
                    definition.announcementName(),
                    definition.announcementSubtitle(),
                    definition.announcementGoal(),
                    badCount,
                    goodCount
            ));
        }

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
     * 阻止 SRE 自带的胜负判定接管游戏结束。
     * <p>
     * SRE 默认会基于 {@code isInnocent}/{@code canUseKiller} 的角色分类来判定好人/杀手全灭并
     * 自行调用 {@code setRoundEndData}+{@code stopGame}，这与黑夜模式的阵营模型不一致
     * （警长会被误判、复用的 SRE 原版角色分类也不可靠）。这里始终返回
     * {@code NOT_MODIFY}，让 {@link BlackoutMode#checkVictory} 作为唯一结算入口，
     * 再由 {@link #finalizeGame} 负责正确填充回放数据。
     */
    @Override
    public io.wifi.starrailexpress.game.GameUtils.WinStatus allowGameEnd(ServerLevel world,
                                                                         io.wifi.starrailexpress.game.GameUtils.WinStatus current,
                                                                         boolean flag,
                                                                         SREGameWorldComponent game) {
        return GameUtils.WinStatus.NOT_MODIFY;
    }

    /**
     * 在 SRE 停止游戏后接管回放数据填充，确保 DLC 结算屏显示正确的胜负阵营与头像分组。
     * <p>
     * 因为 {@link #allowGameEnd} 返回 {@code NOT_MODIFY}，SRE 不会自行调用
     * {@code setRoundEndData}，所以这里必须手动用 {@link BlackoutRoleManager} 的阵营数据
     * 重建回放快照：根据 {@link BlackoutMode#getLastWinningFaction()} 决定
     * {@code winStatus}，并按阵营匹配逐人设置 {@code hasWin}。
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

            // 收集本局所有参与过角色分配的玩家（在线 + 已离线）。
            // roleHistory 记录了所有被分配过角色的玩家 UUID，含已淘汰者。
            Map<UUID, ResourceLocation> history = BlackoutRoleManager.getRoleHistory(world);
            java.util.List<ServerPlayer> participants = new java.util.ArrayList<>();
            for (ServerPlayer p : world.getServer().getPlayerList().getPlayers()) {
                if (history.containsKey(p.getUUID())) {
                    participants.add(p);
                }
            }

            // setRoundEndData 只接受 ServerPlayer 列表，会从中提取 GameProfile 与存活状态。
            // 离线玩家无法通过此路径进入回放列表；这是 SRE API 的固有限制，可接受。
            roundEnd.setRoundEndData(participants, winStatus);

            // setRoundEndData 内部把每个玩家的 hasWin 置为 false，需要按阵营逐人覆盖。
            for (ServerPlayer p : participants) {
                BlackoutRoleManager.Faction f = BlackoutRoleManager.getFaction(world, p.getUUID());
                boolean didWin = (winner != null && f == winner);
                roundEnd.setPlayerWin(p.getUUID(), didWin);
            }
            roundEnd.sync();

            ensureDefaultReplayScreen(world);
        } catch (Throwable t) {
            LOGGER.error("finalizeGame: failed to populate blackout round-end data", t);
        }
    }

    /**
     * 确保地图存在一个默认回放屏，否则 SRE 的 showReplay → showDefault 会因
     * defaultScreenId 为空直接返回 false，结束通报页完全不显示。
     * <p>
     * 若地图作者已通过 /replayscreen 配置过默认屏，这里不会覆盖。
     * 否则在出生点上方自动创建一个朝南的回放屏并设为默认。
     */
    private static void ensureDefaultReplayScreen(ServerLevel world) {
        try {
            var savedData = io.wifi.starrailexpress.api.replay.screen.ReplayScreenSavedData.get(world);
            if (savedData == null) return;

            var existing = savedData.getDefaultScreen();
            if (existing.isPresent()) {
                LOGGER.info("[BlackoutReplayScreen] map already has default replay screen, skip auto-create");
                return;
            }

            net.minecraft.core.BlockPos origin = world.getSharedSpawnPos();
            if (origin == null) {
                origin = new net.minecraft.core.BlockPos(0, 100, 0);
            }
            // 屏幕放在出生点正前方一格，朝向出生点（南向 = NORTH 朝向玩家）
            net.minecraft.core.BlockPos screenPos = origin.above(2).south(3);
            String screenId = "habitrain_blackout_default";

            var entry = io.wifi.starrailexpress.api.replay.screen.ReplayScreenService.createScreen(
                    world,
                    screenId,
                    screenPos,
                    7,
                    5,
                    net.minecraft.core.Direction.NORTH);
            if (entry == null) {
                LOGGER.warn("[BlackoutReplayScreen] createScreen returned null, replay screen will not show");
                return;
            }

            boolean ok = savedData.setDefaultScreen(screenId);
            LOGGER.info("[BlackoutReplayScreen] auto-created default replay screen at {} id={} setDefault={}",
                    screenPos, screenId, ok);
        } catch (Throwable t) {
            LOGGER.error("[BlackoutReplayScreen] failed to ensure default replay screen", t);
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

    private static void assignSreRoles(ServerLevel world, SREGameWorldComponent game, List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            var definition = BlackoutRoleManager.getRoleDefinition(world, player.getUUID());
            if (definition == null) {
                definition = BlackoutRoles.CIVILIAN;
            }
            game.addRole(player, definition.sreRole(), false);
            LOGGER.info("[BlackoutAssignSre] player={} blackoutRoleId={} blackoutDisplayName={} sreRoleId={} sreRoleClass={}",
                    player.getName().getString(),
                    definition.identifier(),
                    definition.displayName(),
                    definition.sreRole().getIdentifier(),
                    definition.sreRole().getClass().getSimpleName());
        }
    }
}
