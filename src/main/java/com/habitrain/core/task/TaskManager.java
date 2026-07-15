package com.habitrain.core.task;

import com.habitrain.core.api.*;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 任务管理器 — 取代 HabiTaskManager。
 * 管理活跃自定义任务跟踪、任务完成处理、SRE 集成方法。
 */
public class TaskManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("TaskManager");
    private static volatile TaskManager INSTANCE;

    /** 自定义胜利后缀常量。 */
    public static final String WIN_SUFFIX = "_win";

    /** SRE 游戏状态提供者 — 通过 setter 注入以解除对 SRE 具体类的编译依赖。 */
    private GameStateProvider gameStateProvider;

    public static TaskManager getInstance() {
        if (INSTANCE == null) {
            synchronized (TaskManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new TaskManager();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 设置 SRE 游戏状态提供者。应在模组初始化时调用一次。
     * 解除 TaskManager 对 SRE 具体类的直接编译依赖。
     */
    public void setGameStateProvider(GameStateProvider provider) {
        this.gameStateProvider = provider;
    }

    // ConcurrentHashMap：避免单机模式下 Netty IO 线程编码与主线程修改导致 CME，
    // 以及未来 off-thread 访问的可见性问题。
    private final Map<UUID, TaskInstance> activeCustomTasks = new ConcurrentHashMap<>();

    /**
     * 杀手双任务机制：杀手的"假任务"（并行任务）单独追踪，不覆盖主任务。
     * key = playerUUID, value = 假任务实例（来自好人任务池，完成只给金币不推进胜利）
     */
    private final Map<UUID, TaskInstance> activeFakeTasks = new ConcurrentHashMap<>();

    private final Map<UUID, Boolean> blackoutNextDailyPool = new ConcurrentHashMap<>();

    private final Map<UUID, Map<String, Integer>> dlcTaskCounts = new ConcurrentHashMap<>();

    public int getDlcTaskCount(UUID playerUuid, String fullId) {
        Map<String, Integer> counts = dlcTaskCounts.get(playerUuid);
        return counts == null ? 0 : counts.getOrDefault(fullId, 0);
    }

    public void incrementDlcTaskCount(UUID playerUuid, String fullId) {
        dlcTaskCounts.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
                .merge(fullId, 1, Integer::sum);
    }

    public void clearDlcTaskCounts(UUID playerUuid) {
        dlcTaskCounts.remove(playerUuid);
    }

    public boolean isBlackoutNextDailyPool(UUID playerUuid) {
        return blackoutNextDailyPool.getOrDefault(playerUuid, false);
    }

    public void setBlackoutNextDailyPool(UUID playerUuid, boolean dailyPool) {
        blackoutNextDailyPool.put(playerUuid, dailyPool);
    }

    public void clearBlackoutRotationFlag(UUID playerUuid) {
        blackoutNextDailyPool.remove(playerUuid);
    }

    public TaskInstance getActiveTask(UUID playerUuid) { return activeCustomTasks.get(playerUuid); }
    public void setActiveTask(UUID playerUuid, TaskInstance task) { activeCustomTasks.put(playerUuid, task); }
    public void removeActiveTask(UUID playerUuid) { activeCustomTasks.remove(playerUuid); }

    public TaskInstance getFakeTask(UUID playerUuid) { return activeFakeTasks.get(playerUuid); }
    public void setFakeTask(UUID playerUuid, TaskInstance task) { activeFakeTasks.put(playerUuid, task); }
    public void removeFakeTask(UUID playerUuid) { activeFakeTasks.remove(playerUuid); }

    /** 清空所有玩家的活跃任务（游戏结束时调用） */
    public void clearAllActiveTasks() { activeCustomTasks.clear(); activeFakeTasks.clear(); blackoutNextDailyPool.clear(); dlcTaskCounts.clear(); }

    public boolean hasTaskWithId(UUID playerUuid, String fullId) {
        TaskInstance existing = activeCustomTasks.get(playerUuid);
        if (existing != null && existing.getFullId().equals(fullId)) {
            return true;
        }
        TaskInstance fake = activeFakeTasks.get(playerUuid);
        return fake != null && fake.getFullId().equals(fullId);
    }

    // ==================== SRE 集成方法（供 Mixin 使用） ====================

    public String getCurrentMapName(Player player) {
        if (player == null || player.level() == null) return "";
        try {
            AreasWorldComponent areas = AreasWorldComponent.KEY.get(player.level());
            return areas != null && areas.mapName != null ? areas.mapName : "";
        } catch (Exception e) {
            return "";
        }
    }

    public TaskCategory getCurrentGameModeCategory(Player player) {
        if (player == null || player.level() == null) return TaskCategory.ALL;
        try {
            SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(player.level());
            if (gameWorld == null || gameWorld.getGameMode() == null) return TaskCategory.ALL;
            String modeId = gameWorld.getGameMode().identifier.toString();
            if (modeId.contains("repair_escape") || modeId.contains("repair")) {
                return TaskCategory.REPAIR;
            }
            if (modeId.contains("murder")) {
                return TaskCategory.MURDER;
            }
            return TaskCategory.ALL;
        } catch (Exception e) {
            return TaskCategory.ALL;
        }
    }

    public boolean isOriginalTaskDisabled(String taskName, String mapName) {
        String fullId = "habitrain_core:" + taskName.toLowerCase();
        TaskConfigEntry entry = ConfigManager.getInstance().getTaskConfig(fullId);
        if (entry == null) return false;
        return !isTaskEnabledForMap(entry, mapName);
    }

    private boolean isTaskEnabledForMap(TaskConfigEntry entry, String mapName) {
        if (entry == null) return true;
        if (!entry.enabled) return false;
        if (entry.mapFilterMode == 0) return true;

        boolean listEmpty = entry.enabledMaps == null || entry.enabledMaps.isEmpty();
        boolean contained = !listEmpty && entry.enabledMaps.contains(mapName);

        if (entry.mapFilterMode == 1) return listEmpty || contained;
        return listEmpty || !contained;
    }

    // ==================== 任务完成处理 ====================

    public void handleTaskCompletion(ServerPlayer player, TaskInstance instance) {
        TaskDefinition def = instance.getDefinition();

        // 先移除活跃任务，避免 DLC 在 onTaskComplete 回调内连发新任务被随后清除。
        // 用 == instance 守卫，避免删掉回调里新分配的任务。
        if (getActiveTask(player.getUUID()) == instance) {
            removeActiveTask(player.getUUID());
        }

        if (player.level() instanceof ServerLevel sl) {
            GameModeRegistry.getActiveForLevel(sl).ifPresent(gm ->
                gm.onTaskComplete(player, instance));
        }

        // 默剧杀手：habitrain TaskManager 完成的任务也累计狂暴折扣
        // （SRE 原版任务走 RoleMethodDispatcherMixin；此处覆盖本 mod 任务路径，避免漏计）
        try {
            if (com.habitrain.core.game.sre.role.HabiRoles.isHabiRole(
                    player, com.habitrain.core.game.sre.role.HabiRoles.MIME_KILLER)) {
                com.habitrain.core.game.sre.role.component.MimeKillerComponent.KEY
                        .maybeGet(player)
                        .ifPresent(com.habitrain.core.game.sre.role.component.MimeKillerComponent::onTaskComplete);
            }
        } catch (Throwable t) {
            LOGGER.debug("MimeKiller task discount apply failed", t);
        }

        // 谦卑：自定义任务完成时附近玩家 actionbar「谢谢」
        try {
            com.habitrain.core.game.sre.modifier.virtue.HumilityVirtue.onTaskComplete(player);
        } catch (Throwable t) {
            LOGGER.debug("Humility onTaskComplete failed", t);
        }

        if (def.canDirectlyWin()) {
            triggerDirectWin(player, instance);
        }
    }

    private void triggerDirectWin(ServerPlayer player, TaskInstance instance) {
        try {
            String winnerId = instance.getDefinition().getModId()
                    + "_" + instance.getDefinition().getTaskId() + WIN_SUFFIX;
            if (gameStateProvider != null && player.level() instanceof ServerLevel sl) {
                gameStateProvider.triggerCustomWin(sl, winnerId, player.getUUID());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to trigger direct win: " + instance.getFullId(), e);
        }
    }
}
