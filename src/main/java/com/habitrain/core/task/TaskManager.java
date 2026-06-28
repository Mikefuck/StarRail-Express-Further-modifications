package com.habitrain.core.task;

import com.habitrain.core.api.*;
import com.habitrain.taskapi.api.HabiTaskCategory;
import com.habitrain.taskapi.impl.config.HabiConfigManager;
import com.habitrain.taskapi.impl.config.HabiTaskConfigEntry;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务管理器 — 取代 HabiTaskManager。
 * 管理活跃自定义任务跟踪、任务完成处理、SRE 集成方法。
 */
public class TaskManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("TaskManager");
    private static TaskManager INSTANCE;

    public static TaskManager getInstance() {
        if (INSTANCE == null) INSTANCE = new TaskManager();
        return INSTANCE;
    }

    private final Map<UUID, TaskInstance> activeCustomTasks = new HashMap<>();

    public TaskInstance getActiveTask(UUID playerUuid) { return activeCustomTasks.get(playerUuid); }
    public void setActiveTask(UUID playerUuid, TaskInstance task) { activeCustomTasks.put(playerUuid, task); }
    public void removeActiveTask(UUID playerUuid) { activeCustomTasks.remove(playerUuid); }

    public boolean hasTaskWithId(UUID playerUuid, String fullId) {
        TaskInstance existing = activeCustomTasks.get(playerUuid);
        return existing != null && existing.getFullId().equals(fullId);
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

    public HabiTaskCategory getCurrentGameModeCategory(Player player) {
        if (player == null || player.level() == null) return HabiTaskCategory.ALL;
        try {
            SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(player.level());
            if (gameWorld == null || gameWorld.getGameMode() == null) return HabiTaskCategory.ALL;
            String modeId = gameWorld.getGameMode().identifier.toString();
            if (modeId.contains("repair_escape") || modeId.contains("repair")) {
                return HabiTaskCategory.REPAIR;
            }
            return HabiTaskCategory.MURDER;
        } catch (Exception e) {
            return HabiTaskCategory.ALL;
        }
    }

    public List<TaskDefinition> getAvailableTasks(String mapName, HabiTaskCategory currentCategory) {
        List<TaskDefinition> available = new ArrayList<>();
        HabiConfigManager config = HabiConfigManager.getInstance();

        for (TaskDefinition def : TaskRegistry.getAll()) {
            HabiTaskConfigEntry entry = config.getTaskConfig(def.getFullId());
            boolean mapEnabled = isTaskEnabledForMap(entry, mapName);
            if (!mapEnabled) continue;

            boolean categoryMatch = (def.getOriginalCategory() == HabiTaskCategory.ALL
                || def.getOriginalCategory() == HabiTaskCategory.CUSTOM
                || def.getOriginalCategory() == currentCategory);
            if (!categoryMatch) continue;

            available.add(def);
        }
        return available;
    }

    public boolean isOriginalTaskDisabled(String taskName, String mapName) {
        String fullId = "habitrain_core:" + taskName.toLowerCase();
        HabiTaskConfigEntry entry = HabiConfigManager.getInstance().getTaskConfig(fullId);
        if (entry == null) return false;
        return !isTaskEnabledForMap(entry, mapName);
    }

    private boolean isTaskEnabledForMap(HabiTaskConfigEntry entry, String mapName) {
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

        if (player.level() instanceof ServerLevel sl) {
            GameModeRegistry.getActiveForLevel(sl).ifPresent(gm ->
                gm.onTaskComplete(player, instance));
        }

        if (def.canDirectlyWin()) {
            triggerDirectWin(player, instance);
        }
    }

    private void triggerDirectWin(ServerPlayer player, TaskInstance instance) {
        try {
            SREGameRoundEndComponent roundEnd =
                    SREGameRoundEndComponent.KEY.get(player.level());
            if (roundEnd != null) {
                roundEnd.CustomWinnerID = instance.getDefinition().getModId()
                        + "_" + instance.getDefinition().getTaskId() + "_win";
                roundEnd.CustomWinnerPlayers.add(player.getUUID());
                roundEnd.setWinStatus(
                        io.wifi.starrailexpress.game.GameUtils.WinStatus.CUSTOM);
                roundEnd.sync();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to trigger direct win: " + instance.getFullId(), e);
        }
    }
}
