package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.task.TaskBalancer;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.task.TaskPoolBuilder;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class DlcTaskPoolBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger("DlcTaskPoolBuilder");

    private DlcTaskPoolBuilder() {}

    /**
     * Builds the DLC task pool with filtering and adaptive auto-boost.
     *
     * <p>只有一个「分区」：当前活跃模式 + 当前分类。曾经的强制分类 / 跳过 active 守卫 /
     * 假任务分区已随杀手双任务机制一并删除（见 2.0.10 变更记录），不再有恒真/恒假的开关。
     *
     * @param entries             mutable list to append weighted entries into
     * @param mgr                 task manager instance
     * @param mapName             current map name
     * @param currentCategory     current game mode category
     * @param disabledTasks       globally disabled task IDs
     * @param activeMode          active game mode (nullable)
     * @param player              the player
     * @param builtinSreTaskIds   set of built-in SRE task IDs for pool building
     * @return total accumulated weight
     */
    public static float addDlcTasks(
            List<Map.Entry<Object, Float>> entries,
            TaskManager mgr,
            String mapName,
            TaskCategory currentCategory,
            Set<String> disabledTasks,
            @Nullable GameMode activeMode,
            Player player,
            Set<String> builtinSreTaskIds
    ) {
        if (mgr.getActiveTask(player.getUUID()) != null) {
            LOGGER.debug("[HabiDebug] Player already has an active DLC task, skipping DLC pool");
            return 0f;
        }

        List<TaskDefinition> dlcCandidates = TaskPoolBuilder.getPool(
                activeMode, mapName, currentCategory, player, builtinSreTaskIds);

        if (dlcCandidates.isEmpty()) return 0f;

        List<TaskDefinition> filteredDlc = new ArrayList<>();
        for (TaskDefinition def : dlcCandidates) {
            if (mgr.hasTaskWithId(player.getUUID(), def.getFullId())) {
                LOGGER.debug("[HabiDebug]   skip DLC {}: already has this task", def.getFullId());
                continue;
            }
            if (disabledTasks.contains(def.getFullId())) {
                LOGGER.debug("[HabiDebug]   skip DLC {}: disabled by map", def.getFullId());
                continue;
            }
            if (!def.canAssign(player)) {
                LOGGER.debug("[HabiDebug]   skip DLC {}: canAssign returned false", def.getFullId());
                continue;
            }
            filteredDlc.add(def);
        }


        int dlcCount = filteredDlc.size();
        if (dlcCount == 0) return 0f;

        int origCount = 0;
        for (Map.Entry<Object, Float> entry : entries) {
            if (entry.getKey() instanceof SREPlayerTaskComponent.Task) {
                origCount++;
            }
        }

        float target = getTargetRatio();
        // 单一真相：自动平衡公式只在 TaskBalancer 实现一次（ConfigStore 的展示/迁移也用它）。
        float autoBoost = TaskBalancer.calcBoost(target, dlcCount, origCount);

        LOGGER.debug("[HabiDebug] ★ 自适应平衡: 目标={}%, {}个可用原版 + {}个可用DLC → autoBoost={}",
                Math.round(target * 100), origCount, dlcCount, String.format("%.2f", autoBoost));

        float total = 0f;
        for (TaskDefinition def : filteredDlc) {
            float baseWeight = getEffectiveWeight(def);
            float boostedWeight = baseWeight * autoBoost;

            int timesAssigned = mgr.getDlcTaskCount(player.getUUID(), def.getFullId());
            float antiRepeat = 1f / Math.max(1, timesAssigned);
            boostedWeight *= antiRepeat;

            LOGGER.debug("[HabiDebug]   ADD DLC {}: baseWeight={} × autoBoost={} × antiRepeat={} = finalWeight={}",
                    def.getFullId(), baseWeight, autoBoost,
                    String.format("%.2f", antiRepeat),
                    boostedWeight);
            entries.add(new AbstractMap.SimpleEntry<>(def, boostedWeight));
            total += boostedWeight;
        }

        LOGGER.debug("[HabiDebug] DLC tasks added: {}, total weight={}",
                dlcCount, String.format("%.2f", total));
        return total;
    }

    private static float getTargetRatio() {
        return ConfigManager.getInstance().getDlcProbabilityTarget();
    }

    private static float getEffectiveWeight(TaskDefinition def) {
        var entry = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (entry != null && entry.hasRefreshWeight) {
            return entry.refreshWeight;
        }
        return def.getWeight() > 0 ? def.getWeight() : 1.0f;
    }
}
