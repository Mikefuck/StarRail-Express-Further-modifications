package com.habitrain.core.game.sre;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.blackout.BlackoutMode;
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
     * Builds the DLC task pool with filtering, blackout rotation, and adaptive auto-boost.
     *
     * @param entries             mutable list to append weighted entries into
     * @param mgr                 task manager instance
     * @param mapName             current map name
     * @param currentCategory     current game mode category
     * @param disabledTasks       globally disabled task IDs
     * @param activeMode          active game mode (nullable)
     * @param forcedCategory      category override from faction context
     * @param skipActiveTaskGuard whether to skip the active-task guard
     * @param currentIsFakeTask   whether the current context is a fake task call
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
            @Nullable TaskCategory forcedCategory,
            boolean skipActiveTaskGuard,
            boolean currentIsFakeTask,
            Player player,
            Set<String> builtinSreTaskIds
    ) {
        if (!skipActiveTaskGuard && mgr.getActiveTask(player.getUUID()) != null) {
            LOGGER.debug("[HabiDebug] Player already has an active DLC task, skipping DLC pool");
            return 0f;
        }

        List<TaskDefinition> dlcCandidates = TaskPoolBuilder.getPool(
                activeMode, mapName, forcedCategory, currentCategory, player, builtinSreTaskIds);

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

        // Blackout rotation filtering
        Set<String> blackoutSupplyTaskIds = Set.of(
                "habitrain_core:add_coal",
                "habitrain_core:repair_wiring",
                "habitrain_core:maintain_power"
        );
        Set<String> blackoutDailyTaskIds = Set.of(
                "habitrain_core:blackout_eat",
                "habitrain_core:blackout_drink",
                "habitrain_core:blackout_search_backpack",
                "habitrain_core:blackout_betel_quest",
                "habitrain_core:blackout_pet_cat",
                "habitrain_core:blackout_be_alone",
                "habitrain_core:blackout_look_my_eyes"
        );

        if (activeMode instanceof BlackoutMode
                && BlackoutMode.BLACKOUT_GOOD.equals(forcedCategory)
                && !currentIsFakeTask
                && !skipActiveTaskGuard) {
            boolean wantDaily = mgr.isBlackoutNextDailyPool(player.getUUID());
            Set<String> targetPool = wantDaily ? blackoutDailyTaskIds : blackoutSupplyTaskIds;
            List<TaskDefinition> rotationFiltered = new ArrayList<>();
            for (TaskDefinition def : filteredDlc) {
                if (targetPool.contains(def.getFullId())) {
                    rotationFiltered.add(def);
                }
            }
            if (!rotationFiltered.isEmpty()) {
                LOGGER.info("[HabiDebug] Blackout rotation: filtering to {} pool ({} candidates)",
                        wantDaily ? "DAILY" : "SUPPLY", rotationFiltered.size());
                filteredDlc = rotationFiltered;
            } else {
                LOGGER.info("[HabiDebug] Blackout rotation: {} pool empty, keeping full GOOD pool",
                        wantDaily ? "DAILY" : "SUPPLY");
            }
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
        float autoBoost;
        if (dlcCount > 0 && origCount > 0) {
            autoBoost = (target / (1f - target)) * ((float) origCount / (float) dlcCount);
            autoBoost = Math.max(0.0f, Math.min(10.0f, autoBoost));
        } else {
            autoBoost = 1.0f;
        }

        LOGGER.debug("[HabiDebug] ★ 自适应平衡: 目标={}%, {}个可用原版 + {}个可用DLC → autoBoost={}",
                Math.round(target * 100), origCount, dlcCount, String.format("%.2f", autoBoost));

        float total = 0f;
        for (TaskDefinition def : filteredDlc) {
            float baseWeight = getEffectiveWeight(def);
            float boostedWeight = baseWeight * autoBoost;

            if (activeMode instanceof BlackoutMode) {
                boostedWeight *= TaskWeightCurves.computeBlackoutDynamicMultiplier(def, player);
            }

            int timesAssigned = mgr.getDlcTaskCount(player.getUUID(), def.getFullId());
            float antiRepeat = 1f / Math.max(1, timesAssigned);
            boostedWeight *= antiRepeat;

            LOGGER.debug("[HabiDebug]   ADD DLC {}: baseWeight={} × autoBoost={} × dyn={} × antiRepeat={} = finalWeight={}",
                    def.getFullId(), baseWeight, autoBoost,
                    (activeMode instanceof BlackoutMode) ? String.format("%.2f", TaskWeightCurves.computeBlackoutDynamicMultiplier(def, player)) : "N/A",
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
