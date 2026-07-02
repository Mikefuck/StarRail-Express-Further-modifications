package com.habitrain.core.task;

import com.habitrain.core.HabiTrainCore;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 背包翻找任务状态管理器
 * 记录本局游戏已完成背包翻找任务的玩家，防止同一玩家在一局内重复刷新该任务。
 * 游戏结束时由 GameLifecycleHandler 重置所有状态。
 */
public class BackpackQuestState {
    private static volatile BackpackQuestState instance;

    /** 本局已完成背包翻找任务的玩家 UUID 集合 */
    private final Set<UUID> completedPlayers = new HashSet<>();

    private BackpackQuestState() {}

    public static void init() {
        instance = new BackpackQuestState();
    }

    public static BackpackQuestState getInstance() {
        if (instance == null) {
            synchronized (BackpackQuestState.class) {
                if (instance == null) {
                    instance = new BackpackQuestState();
                }
            }
        }
        return instance;
    }

    /**
     * 标记玩家本局已完成背包翻找任务
     */
    public static void markCompleted(UUID uuid) {
        getInstance().completedPlayers.add(uuid);
        HabiTrainCore.LOGGER.debug("玩家 {} 已在本局完成背包翻找任务", uuid);
    }

    /**
     * 检查玩家本局是否已完成过背包翻找任务
     */
    public static boolean hasCompleted(UUID uuid) {
        return getInstance().completedPlayers.contains(uuid);
    }

    /**
     * 重置所有玩家状态（游戏结束时调用）
     */
    public void resetAll() {
        completedPlayers.clear();
        HabiTrainCore.LOGGER.info("已重置所有玩家的背包翻找任务完成状态");
    }
}
