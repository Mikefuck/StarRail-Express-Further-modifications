package com.habitrain.core.task;

import com.habitrain.core.api.*;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 任务管理器 — 取代 HabiTaskManager。
 * 管理活跃自定义任务跟踪、任务完成处理。
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

    /**
     * 处理任务完成 — 移除了自动录制逻辑。
     * 触发 GameMode 的 onTaskComplete 回调。
     */
    public void handleTaskCompletion(ServerPlayer player, TaskInstance instance) {
        TaskDefinition def = instance.getDefinition();

        // 通知活跃 GameMode
        if (player.level() instanceof ServerLevel sl) {
            GameModeRegistry.getActiveForLevel(sl).ifPresent(gm ->
                gm.onTaskComplete(player, instance));
        }

        // 直接获胜
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
