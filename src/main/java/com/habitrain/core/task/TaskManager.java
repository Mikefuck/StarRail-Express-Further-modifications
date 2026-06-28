package com.habitrain.core.task;

import com.habitrain.core.api.TaskInstance;
import net.minecraft.server.level.ServerPlayer;

/**
 * 临时占位 — 将在 Task 4 中替换为完整实现。
 */
public class TaskManager {
    private static TaskManager INSTANCE;

    public static TaskManager getInstance() {
        if (INSTANCE == null) INSTANCE = new TaskManager();
        return INSTANCE;
    }

    public void handleTaskCompletion(ServerPlayer player, TaskInstance instance) {
        // Stub — will be properly implemented in Task 4
    }
}
