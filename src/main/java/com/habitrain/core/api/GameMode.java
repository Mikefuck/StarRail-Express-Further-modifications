package com.habitrain.core.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * 游戏模式核心接口。
 * DLC 模组实现此接口并通过 {@link GameModeRegistry} 注册。
 *
 * 每个 GameMode 拥有：
 * - 唯一 ID
 * - 自己的任务分类列表
 * - 完整的生命周期钩子
 * - 任务行为拦截能力
 */
public interface GameMode {

    /** 唯一标识，例如 "sre:murder"、"my_mod:arena" */
    String getId();

    /** 人类可读的名称 */
    String getDisplayName();

    /** 此模式拥有的所有任务分类（含继承自 ALL 的分类） */
    List<TaskCategory> getTaskCategories();

    /** 检查此模式当前是否在给定世界中激活 */
    boolean isActive(ServerLevel level);

    // ========== 生命周期钩子 ==========

    /** 准备阶段（加载地图、分配角色） */
    default void onPreStart(ServerLevel level) {}

    /** 游戏正式开始 */
    default void onStart(ServerLevel level) {}

    /** 每 tick 更新 */
    default void onTick(ServerLevel level) {}

    /** 玩家加入游戏 */
    default void onPlayerJoin(ServerPlayer player) {}

    /** 玩家离开游戏 */
    default void onPlayerLeave(ServerPlayer player) {}

    /** 任务完成时触发 */
    default void onTaskComplete(ServerPlayer player, TaskInstance task) {}

    /** 检查胜利条件，返回非空 Optional 表示游戏结束 */
    default Optional<WinResult> checkWinCondition(ServerLevel level) {
        return Optional.empty();
    }

    /** 游戏结束 */
    default void onEnd(ServerLevel level, WinResult result) {}

    /** 清理现场（重置世界状态等） */
    default void onCleanup(ServerLevel level) {}

    // ========== 任务行为拦截 ==========

    /** 分配任务前的过滤逻辑 */
    default List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player) {
        return tasks;
    }

    /** 任务分配时调用 */
    default void onTaskAssign(ServerPlayer player, TaskInstance task) {}

    /** 任务 tick 时调用 */
    default void onTaskTick(ServerPlayer player, TaskInstance task) {}

    /** 进度变化时调用 */
    default void onTaskProgressChange(ServerPlayer player, TaskInstance task, int oldProgress) {}

    /** 覆盖任务的完成检测。返回 non-empty Optional 则替代任务自己的 checker。 */
    default Optional<Boolean> overrideCompletionCheck(ServerPlayer player, TaskInstance task) {
        return Optional.empty();
    }
}
