package com.habitrain.core.game;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.WinResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * GameMode 骨架实现。
 * 子类只需实现 getId()、getDisplayName()、getTaskCategories()、isActive()。
 * 生命周期钩子按需覆盖，默认均为空操作。
 */
public abstract class AbstractGameMode implements GameMode {

    @Override
    public void onPreStart(ServerLevel level) {}

    @Override
    public void onStart(ServerLevel level) {}

    @Override
    public void onTick(ServerLevel level) {}

    @Override
    public void onPlayerJoin(ServerPlayer player) {}

    @Override
    public void onPlayerLeave(ServerPlayer player) {}

    @Override
    public void onTaskComplete(ServerPlayer player, TaskInstance task) {}

    @Override
    public Optional<WinResult> checkWinCondition(ServerLevel level) {
        return Optional.empty();
    }

    @Override
    public void onEnd(ServerLevel level, WinResult result) {}

    @Override
    public void onCleanup(ServerLevel level) {}

    @Override
    public List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player) {
        return tasks;
    }

    @Override
    public void onTaskAssign(ServerPlayer player, TaskInstance task) {}

    @Override
    public void onTaskTick(ServerPlayer player, TaskInstance task) {}

    @Override
    public void onTaskProgressChange(ServerPlayer player, TaskInstance task, int oldProgress) {}

    @Override
    public Optional<Boolean> overrideCompletionCheck(ServerPlayer player, TaskInstance task) {
        return Optional.empty();
    }
}
