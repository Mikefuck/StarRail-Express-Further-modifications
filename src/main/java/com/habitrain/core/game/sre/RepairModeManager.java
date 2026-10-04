package com.habitrain.core.game.sre;

import com.habitrain.core.vote.OptionVoteManager;
import io.wifi.starrailexpress.cca.ParticipationComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 维修人员模式的服务端权威管理器。
 *
 * <p>维修员进入维修模式后：把自己标记为「不参与 SRE 对局」（{@link ParticipationComponent}），
 * 从而不计入人数统计、不进开局名单、不分配任务/角色；同时切换为创造模式以便修图。其锁定的
 * 地图将从 {@code ModeMapVoteOrchestrator} 的投票候选池中排除（见 {@link #isMapLocked}）；
 * 进入时还会删除维修员已投的票，并从进行中的地图投票候选里拿掉该图。
 *
 * <p>退出/断线/停服时恢复原参与状态与游戏模式，并释放对地图的锁。一张地图可被多位玩家同时
 * 锁定；当某地图不再有任何负责玩家时，它自动回到投票池（满足「强制要求有一名玩家为当前锁定的
 * 地图负责」）。所有 SRE 调用 try/catch，缺失 SRE 不崩溃。</p>
 */
public final class RepairModeManager {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("habitrain_core|RepairModeManager");

    /** 玩家 UUID → 维修记录。 */
    private static final ConcurrentMap<UUID, RepairEntry> REPAIRS = new ConcurrentHashMap<>();

    /**
     * 对局开始/结束后待做「冒险 → 创造」切换的维修员：值为剩余步数
     * （{@link #REFRESH_TO_ADVENTURE}=下一 tick 切冒险，{@link #REFRESH_TO_CREATIVE}=再下一 tick 切回创造）。
     */
    private static final ConcurrentMap<UUID, Integer> GAME_MODE_REFRESH = new ConcurrentHashMap<>();
    private static final int REFRESH_TO_ADVENTURE = 2;
    private static final int REFRESH_TO_CREATIVE = 1;

    private RepairModeManager() {}

    /** 注册 SRE 对局开始/结束事件：维修员各切一次冒险再切回创造。 */
    public static void registerEvents() {
        try {
            io.wifi.starrailexpress.event.OnGameStarted.EVENT.register(level -> scheduleGameModeRefresh());
            io.wifi.starrailexpress.event.OnGameEnd.EVENT.register((level, game) -> scheduleGameModeRefresh());
        } catch (Throwable t) {
            LOGGER.error("[RepairMode] failed to register game start/end events", t);
        }
    }

    /** 给当前所有维修员排一次「冒险 → 创造」切换；在之后两个服务端 tick 内完成，避开 SRE 同 tick 的重置。 */
    public static void scheduleGameModeRefresh() {
        for (UUID uuid : REPAIRS.keySet()) {
            GAME_MODE_REFRESH.put(uuid, REFRESH_TO_ADVENTURE);
        }
    }

    /** 每个服务端 tick 推进一步待处理的切换（由 ModTickHandler 调用）。 */
    public static void tickGameModeRefresh(MinecraftServer server) {
        if (server == null || GAME_MODE_REFRESH.isEmpty()) return;
        for (UUID uuid : new ArrayList<>(GAME_MODE_REFRESH.keySet())) {
            Integer step = GAME_MODE_REFRESH.get(uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            // 期间已退出维修模式或离线：exit 已恢复原模式，不再干预
            if (step == null || player == null || !isRepairer(uuid)) {
                GAME_MODE_REFRESH.remove(uuid);
                continue;
            }
            try {
                if (step == REFRESH_TO_ADVENTURE) {
                    player.setGameMode(GameType.ADVENTURE);
                    GAME_MODE_REFRESH.put(uuid, REFRESH_TO_CREATIVE);
                } else {
                    GAME_MODE_REFRESH.remove(uuid);
                    player.setGameMode(GameType.CREATIVE);
                }
            } catch (Throwable t) {
                GAME_MODE_REFRESH.remove(uuid);
                LOGGER.warn("[RepairMode] game mode refresh failed for {}", uuid, t);
                try {
                    player.setGameMode(GameType.CREATIVE);
                } catch (Throwable ignored) {
                    // 已记录上面的失败
                }
            }
        }
    }

    /** 单个维修记录：玩家名、锁定地图、进入前的参与状态与游戏模式。 */
    private static final class RepairEntry {
        final String playerName;
        final String mapId;
        final boolean priorParticipating;
        final GameType priorGameType;
        final long lockedAtMs;

        RepairEntry(String playerName, String mapId, boolean priorParticipating, GameType priorGameType) {
            this.playerName = playerName;
            this.mapId = mapId;
            this.priorParticipating = priorParticipating;
            this.priorGameType = priorGameType;
            this.lockedAtMs = System.currentTimeMillis();
        }
    }

    /**
     * 玩家进入维修模式并锁定一张地图。
     * prior 读取 / setParticipating(false) / 创造模式任一步失败则整段 abort，不登记维修员。
     * （需 ServerPlayer，纯单测无法覆盖完整 enter 路径。）
     */
    public static boolean enter(ServerPlayer player, String mapId) {
        if (player == null || mapId == null || mapId.isBlank()) return false;
        UUID uuid = player.getUUID();
        if (REPAIRS.containsKey(uuid)) return false; // 已在维修模式

        ServerLevel level = player.serverLevel();
        boolean priorParticipating;
        try {
            ParticipationComponent participation = ParticipationComponent.KEY.get(level);
            priorParticipating = participation.isParticipating(uuid);
        } catch (Throwable t) {
            LOGGER.error("[RepairMode] read prior participating failed for {}; refuse enter", uuid, t);
            return false;
        }
        GameType priorGameType = player.gameMode.getGameModeForPlayer();

        // 豁免：标记不参与对局（不计入人数/开局/任务/角色）
        try {
            ParticipationComponent.KEY.get(level).setParticipating(uuid, false);
        } catch (Throwable t) {
            LOGGER.error("[RepairMode] setParticipating(false) failed for {}; refuse enter", uuid, t);
            return false;
        }
        // 创造模式便于修图；失败则恢复参与状态，不登记维修员
        try {
            player.setGameMode(GameType.CREATIVE);
        } catch (Throwable t) {
            LOGGER.error("[RepairMode] setGameMode(CREATIVE) failed for {}; abort enter", uuid, t);
            try {
                ParticipationComponent.KEY.get(level).setParticipating(uuid, priorParticipating);
            } catch (Throwable restore) {
                LOGGER.error("[RepairMode] restore participating after creative failure failed for {}",
                        uuid, restore);
            }
            return false;
        }

        REPAIRS.put(uuid, new RepairEntry(player.getGameProfile().getName(), mapId, priorParticipating, priorGameType));
        LOGGER.info("[RepairMode] {} entered repair mode, locking map={}", uuid, mapId);
        dropRepairerVoteAndLockedMap(player, mapId);
        // 同步到本机客户端：屏蔽开局黑场/转场与结尾动画
        com.habitrain.core.network.RepairModeSyncPayload.sendToPlayer(player, true);
        return true;
    }

    /** 进维修后已投票作废，并把被锁图从进行中的地图投票候选里拿掉。 */
    private static void dropRepairerVoteAndLockedMap(ServerPlayer player, String mapId) {
        UUID uuid = player.getUUID();
        try {
            MinecraftServer server = player.getServer();
            if (server != null) {
                for (ServerLevel sl : server.getAllLevels()) {
                    OptionVoteManager.onVoterRemoved(sl, uuid);
                    OptionVoteManager.invalidateMapOption(sl, mapId);
                }
            } else if (player.serverLevel() != null) {
                OptionVoteManager.onVoterRemoved(player.serverLevel(), uuid);
                OptionVoteManager.invalidateMapOption(player.serverLevel(), mapId);
            }
        } catch (Throwable t) {
            LOGGER.warn("[RepairMode] failed to drop vote/map option for {} map={}", uuid, mapId, t);
        }
    }

    /** 玩家退出维修模式（cancel / remove 共用）。 */
    public static boolean exit(ServerPlayer player) {
        if (player == null) return false;
        return exit(player.getUUID(), player.serverLevel());
    }

    /** 按 UUID 退出（断线/停服时玩家可能不在线）。 */
    public static boolean exit(UUID uuid, ServerLevel level) {
        RepairEntry entry = REPAIRS.remove(uuid);
        if (entry == null) return false;

        // 恢复原参与状态（不覆盖玩家原本的参与意愿）
        if (level != null) {
            try {
                ParticipationComponent.KEY.get(level).setParticipating(uuid, entry.priorParticipating);
            } catch (Throwable t) {
                LOGGER.warn("[RepairMode] restore participation failed for {}", uuid, t);
            }
        }
        // 恢复原游戏模式（仅当玩家在线）
        ServerPlayer online = level != null ? level.getServer().getPlayerList().getPlayer(uuid) : null;
        if (online != null) {
            try {
                online.setGameMode(entry.priorGameType);
            } catch (Throwable t) {
                LOGGER.warn("[RepairMode] restore gameMode failed for {}", uuid, t);
            }
            // 同步到本机客户端：恢复开局黑场/转场与结尾动画
            com.habitrain.core.network.RepairModeSyncPayload.sendToPlayer(online, false);
        }
        LOGGER.info("[RepairMode] {} exited repair mode, released map={}", uuid, entry.mapId);
        return true;
    }

    /** 断线时自动解锁（由 DISCONNECT 事件调用）。
     *  玩家已断线，用服务器主世界挂载的 ParticipationComponent 恢复参与状态。 */
    public static void onPlayerDisconnect(UUID uuid, MinecraftServer server) {
        if (uuid == null) return;
        ServerLevel level = (server != null) ? server.overworld() : null;
        exit(uuid, level);
    }

    /** 异常兜底：释放「玩家已离线但仍持锁」的记录（覆盖服务器崩溃/异常断线）。 */
    public static void checkAbnormal(MinecraftServer server) {
        if (server == null) return;
        for (UUID uuid : REPAIRS.keySet()) {
            if (server.getPlayerList().getPlayer(uuid) == null) {
                LOGGER.warn("[RepairMode] abnormal state: player {} offline but still holds repair lock; releasing", uuid);
                exit(uuid, server.overworld());
            }
        }
    }

    /** 停服清理：恢复所有维修员参与状态与模式并清空。
     *  遍历时先收集快照再逐个退出，避免 ConcurrentModificationException。 */
    public static void resetAll(MinecraftServer server) {
        List<UUID> all = new ArrayList<>(REPAIRS.keySet());
        ServerLevel level = (server != null) ? server.overworld() : null;
        for (UUID uuid : all) {
            exit(uuid, level);
        }
        REPAIRS.clear();
        GAME_MODE_REFRESH.clear();
    }

    public static boolean isRepairer(UUID uuid) {
        return uuid != null && REPAIRS.containsKey(uuid);
    }

    public static boolean isRepairer(ServerPlayer player) {
        return player != null && isRepairer(player.getUUID());
    }

    public static boolean isRepairer(Player player) {
        return player != null && isRepairer(player.getUUID());
    }

    /** 当前被锁定的地图集合。 */
    public static Set<String> getLockedMapIds() {
        Set<String> ids = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (RepairEntry e : REPAIRS.values()) {
            if (e.mapId != null && !e.mapId.isBlank()) ids.add(e.mapId);
        }
        return ids;
    }

    /** 某地图当前是否被任意维修员锁定（从投票池排除）。 */
    public static boolean isMapLocked(String mapId) {
        if (mapId == null || mapId.isBlank()) return false;
        for (RepairEntry e : REPAIRS.values()) {
            if (mapId.equals(e.mapId)) return true;
        }
        return false;
    }

    /** 强制解锁一张地图：移除该地图所有负责玩家并返回被移除的玩家数。 */
    public static int unlockMap(String mapId, MinecraftServer server) {
        if (mapId == null || mapId.isBlank()) return 0;
        int removed = 0;
        ServerLevel level = (server != null) ? server.overworld() : null;
        for (UUID uuid : new ArrayList<>(REPAIRS.keySet())) {
            RepairEntry e = REPAIRS.get(uuid);
            if (e != null && mapId.equals(e.mapId)) {
                exit(uuid, level);
                removed++;
            }
        }
        return removed;
    }

    /** 维修记录快照（供 list 命令展示）。 */
    public static List<RepairEntryView> list() {
        List<RepairEntryView> out = new ArrayList<>();
        for (RepairEntry e : REPAIRS.values()) {
            out.add(new RepairEntryView(e.playerName, e.mapId, e.lockedAtMs));
        }
        return out;
    }

    /** list 命令的只读视图。 */
    public record RepairEntryView(String playerName, String mapId, long lockedAtMs) {}
}