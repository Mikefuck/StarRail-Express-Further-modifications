package com.habitrain.core.game.sre;

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
 * 地图将从 {@code ModeMapVoteOrchestrator} 的投票候选池中排除（见 {@link #isMapLocked}）。
 *
 * <p>退出/断线/停服时恢复原参与状态与游戏模式，并释放对地图的锁。一张地图可被多位玩家同时
 * 锁定；当某地图不再有任何负责玩家时，它自动回到投票池（满足「强制要求有一名玩家为当前锁定的
 * 地图负责」）。所有 SRE 调用 try/catch，缺失 SRE 不崩溃。</p>
 */
public final class RepairModeManager {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("habitrain_core|RepairModeManager");

    /** 玩家 UUID → 维修记录。 */
    private static final ConcurrentMap<UUID, RepairEntry> REPAIRS = new ConcurrentHashMap<>();

    private RepairModeManager() {}

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

    /** 玩家进入维修模式并锁定一张地图。 */
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
            priorParticipating = true;
        }
        GameType priorGameType = player.gameMode.getGameModeForPlayer();

        // 豁免：标记不参与对局（不计入人数/开局/任务/角色）
        try {
            ParticipationComponent.KEY.get(level).setParticipating(uuid, false);
        } catch (Throwable t) {
            LOGGER.warn("[RepairMode] setParticipating(false) failed for {}", uuid, t);
        }
        // 创造模式便于修图
        try {
            player.setGameMode(GameType.CREATIVE);
        } catch (Throwable t) {
            LOGGER.warn("[RepairMode] setGameMode(CREATIVE) failed for {}", uuid, t);
        }

        REPAIRS.put(uuid, new RepairEntry(player.getGameProfile().getName(), mapId, priorParticipating, priorGameType));
        LOGGER.info("[RepairMode] {} entered repair mode, locking map={}", uuid, mapId);
        // 同步到本机客户端：屏蔽开局黑场/转场与结尾动画
        com.habitrain.core.network.RepairModeSyncPayload.sendToPlayer(player, true);
        return true;
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