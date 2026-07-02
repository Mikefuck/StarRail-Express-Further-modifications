package com.habitrain.core.task;

import com.habitrain.core.api.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务分配引擎。
 * 根据当前 GameMode、地图、配置过滤后做加权随机选择。
 */
public class Engine {

    private static volatile Engine INSTANCE;
    public static Engine getInstance() {
        if (INSTANCE == null) {
            synchronized (Engine.class) {
                if (INSTANCE == null) {
                    INSTANCE = new Engine();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 为玩家生成一个随机任务。
     *
     * @param player    目标玩家
     * @param gameMode  当前活跃的 GameMode（null = 只用 SRE 原版分类）
     * @return 生成的实例，或 null（无可用任务）
     */
    public TaskInstance generateTask(Player player, GameMode gameMode) {
        List<TaskDefinition> pool = buildTaskPool(player, gameMode);
        if (pool.isEmpty()) return null;

        // 加权随机
        float totalWeight = 0;
        for (TaskDefinition def : pool) totalWeight += def.getWeight();
        if (totalWeight <= 0) return null;

        float rand = player.getRandom().nextFloat() * totalWeight;
        for (TaskDefinition def : pool) {
            rand -= def.getWeight();
            if (rand <= 0) {
                TaskInstance instance = new TaskInstance(def);
                def.onAssign(player, instance);
                if (gameMode != null) gameMode.onTaskAssign((ServerPlayer) player, instance);
                return instance;
            }
        }

        // fallback — 取最后一项
        TaskDefinition last = pool.get(pool.size() - 1);
        TaskInstance instance = new TaskInstance(last);
        last.onAssign(player, instance);
        if (gameMode != null) gameMode.onTaskAssign((ServerPlayer) player, instance);
        return instance;
    }

    /**
     * 构建当前可用的任务池。
     */
    public List<TaskDefinition> buildTaskPool(Player player, GameMode gameMode) {
        List<TaskDefinition> all = new ArrayList<>(TaskRegistry.getAll());

        if (gameMode != null) {
            all = gameMode.filterAvailableTasks(all, (ServerPlayer) player);
        }

        return all.stream()
                .filter(def -> def.canAssign(player, null))
                .collect(Collectors.toList());
    }
}
