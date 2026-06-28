package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.task.TaskManager;
import com.habitrain.taskapi.api.HabiTaskCategory;
import com.habitrain.taskapi.impl.config.HabiConfigManager;
import com.habitrain.taskapi.impl.config.HabiTaskConfigEntry;
import com.habitrain.core.network.ActiveTaskPayload;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import io.wifi.starrailexpress.game.GameConstants;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.*;
import java.util.stream.Collectors;

@Mixin(SREPlayerTaskComponent.class)
public abstract class GenerateTaskMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("GenerateTaskMixin");

    @Shadow(remap = false) private Player player;
    @Shadow(remap = false) public Map<SREPlayerTaskComponent.Task, SREPlayerTaskComponent.TrainTask> tasks;
    @Shadow(remap = false) public Map<SREPlayerTaskComponent.Task, Integer> timesGotten;
    @Shadow(remap = false) public SREPlayerMoodComponent playerMoodComponent;

    @Shadow(remap = false)
    private Set<String> getDisabledTasks() {
        throw new AssertionError("Shadowed");
    }

    @Shadow(remap = false)
    @Nullable
    private SREPlayerTaskComponent.TrainTask createTaskInstance(SREPlayerTaskComponent.Task taskType) {
        throw new AssertionError("Shadowed");
    }

    @Overwrite(remap = false)
    @Nullable
    public SREPlayerTaskComponent.TrainTask generateTaskInternal() {
        LOGGER.info("[HabiDebug] ===== genTask CALLED! tasks.size={}, timesGotten={} =====",
                tasks.size(), timesGotten.size());

        float currentMood = (playerMoodComponent != null) ? playerMoodComponent.getMood() : 1f;
        Set<String> disabledTasks = getDisabledTasks();
        TaskManager mgr = TaskManager.getInstance();
        String mapName = mgr.getCurrentMapName(player);
        HabiTaskCategory currentCategory = mgr.getCurrentGameModeCategory(player);

        LOGGER.info("[HabiDebug] mapName='{}', currentMood={}, disabledTasks={}, category={}",
                mapName, currentMood, disabledTasks, currentCategory);

        List<Map.Entry<Object, Float>> weightEntries = new ArrayList<>();
        float total = 0f;

        total += addOriginalTasks(weightEntries, currentMood, disabledTasks, mapName, mgr);
        total += addDlcTasks(weightEntries, mgr, mapName, currentCategory, disabledTasks);

        LOGGER.info("[HabiDebug] Flat pool built: {} entries, total weight={}",
                weightEntries.size(), String.format("%.2f", total));

        return weightedSelect(weightEntries, total);
    }

    private float addOriginalTasks(List<Map.Entry<Object, Float>> entries,
                                   float currentMood, Set<String> disabledTasks,
                                   String mapName, TaskManager mgr) {
        float total = 0f;
        int added = 0;

        for (SREPlayerTaskComponent.Task task : SREPlayerTaskComponent.Task.getAvailableTasksList()) {
            if (this.tasks.containsKey(task)) {
                LOGGER.info("[HabiDebug]   skip original {}: already in tasks", task.name());
                continue;
            }
            if (disabledTasks.contains(task.name())) {
                LOGGER.info("[HabiDebug]   skip original {}: disabled by map", task.name());
                continue;
            }
            if (mgr.isOriginalTaskDisabled(task.name(), mapName)) {
                LOGGER.info("[HabiDebug]   skip original {}: disabled in config", task.name());
                continue;
            }

            float weight = 1f / this.timesGotten.getOrDefault(task, 1);

            if (currentMood < GameConstants.MID_MOOD_THRESHOLD) {
                if (task == SREPlayerTaskComponent.Task.MEDITATE
                        || task == SREPlayerTaskComponent.Task.SLEEP
                        || task == SREPlayerTaskComponent.Task.CHAIR) {
                    weight *= 2f;
                }
                if (task == SREPlayerTaskComponent.Task.EXERCISE
                        || task == SREPlayerTaskComponent.Task.OUTSIDE
                        || task == SREPlayerTaskComponent.Task.BREATHE) {
                    weight *= 0.5f;
                }
            } else if (currentMood > GameConstants.ANGRY_MOOD_THRESHOLD) {
                if (task == SREPlayerTaskComponent.Task.EXERCISE
                        || task == SREPlayerTaskComponent.Task.OUTSIDE
                        || task == SREPlayerTaskComponent.Task.NOTE_BLOCK) {
                    weight *= 1.5f;
                }
                if (task == SREPlayerTaskComponent.Task.SLEEP
                        || task == SREPlayerTaskComponent.Task.MEDITATE) {
                    weight *= 0.5f;
                }
            }

            LOGGER.info("[HabiDebug]   ADD original {}: weight={}", task.name(), weight);
            entries.add(new AbstractMap.SimpleEntry<>(task, weight));
            total += weight;
            added++;
        }
        LOGGER.info("[HabiDebug] Original tasks added: {}, weight total={}", added, String.format("%.2f", total));
        return total;
    }

    private float addDlcTasks(List<Map.Entry<Object, Float>> entries,
                              TaskManager mgr, String mapName,
                              HabiTaskCategory currentCategory, Set<String> disabledTasks) {
        if (mgr.getActiveTask(player.getUUID()) != null) {
            LOGGER.info("[HabiDebug] Player already has an active DLC task, skipping DLC pool");
            return 0f;
        }

        List<TaskDefinition> dlcCandidates = getAvailableDlcTasks(mgr, mapName, currentCategory);
        if (dlcCandidates.isEmpty()) return 0f;

        List<TaskDefinition> filteredDlc = new ArrayList<>();
        for (TaskDefinition def : dlcCandidates) {
            if (mgr.hasTaskWithId(player.getUUID(), def.getFullId())) {
                LOGGER.info("[HabiDebug]   skip DLC {}: already has this task", def.getFullId());
                continue;
            }
            if (disabledTasks.contains(def.getFullId())) {
                LOGGER.info("[HabiDebug]   skip DLC {}: disabled by map", def.getFullId());
                continue;
            }
            if (!def.canAssign(player, null)) {
                LOGGER.info("[HabiDebug]   skip DLC {}: canAssign returned false", def.getFullId());
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
        float autoBoost;
        if (dlcCount > 0 && origCount > 0) {
            autoBoost = (target / (1f - target)) * ((float) origCount / (float) dlcCount);
            autoBoost = Math.max(0.0f, Math.min(10.0f, autoBoost));
        } else {
            autoBoost = 1.0f;
        }

        LOGGER.info("[HabiDebug] ★ 自适应平衡: 目标={}%, {}个可用原版 + {}个可用DLC → autoBoost={}",
                Math.round(target * 100), origCount, dlcCount, String.format("%.2f", autoBoost));

        float total = 0f;
        for (TaskDefinition def : filteredDlc) {
            float baseWeight = getEffectiveWeight(def);
            float boostedWeight = baseWeight * autoBoost;

            LOGGER.info("[HabiDebug]   ADD DLC {}: baseWeight={} × autoBoost={} = finalWeight={}",
                    def.getFullId(), baseWeight, autoBoost, boostedWeight);
            entries.add(new AbstractMap.SimpleEntry<>(def, boostedWeight));
            total += boostedWeight;
        }

        LOGGER.info("[HabiDebug] DLC tasks added: {}, total weight={}",
                dlcCount, String.format("%.2f", total));
        return total;
    }

    private float getTargetRatio() {
        return HabiConfigManager.getInstance().getDlcProbabilityTarget();
    }

    @Nullable
    private SREPlayerTaskComponent.TrainTask weightedSelect(List<Map.Entry<Object, Float>> pool, float total) {
        if (pool.isEmpty() || total <= 0) {
            LOGGER.info("[HabiDebug] weightedSelect: pool empty or total<=0, returning null");
            return null;
        }

        float random = this.player.getRandom().nextFloat() * total;
        Collections.shuffle(pool);

        LOGGER.info("[HabiDebug] weightedSelect: random={}/{}",
                String.format("%.4f", random), String.format("%.4f", total));

        for (Map.Entry<Object, Float> entry : pool) {
            random -= entry.getValue();
            LOGGER.info("[HabiDebug]   roll: key={}, value={}, after={}",
                    formatKey(entry.getKey()),
                    String.format("%.4f", entry.getValue()),
                    String.format("%.4f", random));
            if (random <= 0) {
                return instantiateTask(entry.getKey());
            }
        }

        LOGGER.info("[HabiDebug] Fallback (float): selecting from remaining entries");
        for (Map.Entry<Object, Float> entry : pool) {
            Object key = entry.getKey();
            if (key instanceof TaskDefinition defKey) {
                LOGGER.info("[HabiDebug] Fallback selected DLC: {}", defKey.getFullId());
                return createAndTrackDlcTask(defKey);
            }
        }
        Object firstKey = pool.get(0).getKey();
        if (firstKey instanceof SREPlayerTaskComponent.Task taskKey) {
            LOGGER.info("[HabiDebug] Fallback selected original: {}", taskKey.name());
            return createTaskInstance(taskKey);
        }
        return null;
    }

    @Nullable
    private SREPlayerTaskComponent.TrainTask instantiateTask(Object key) {
        if (key instanceof SREPlayerTaskComponent.Task taskKey) {
            LOGGER.info("[HabiDebug] SELECTED original task: {}", taskKey.name());
            return createTaskInstance(taskKey);
        } else if (key instanceof TaskDefinition defKey) {
            LOGGER.info("[HabiDebug] SELECTED DLC task: {}", defKey.getFullId());
            return createAndTrackDlcTask(defKey);
        }
        LOGGER.warn("[HabiDebug] Unknown key type: {}", key.getClass().getName());
        return null;
    }

    private String formatKey(Object key) {
        if (key instanceof SREPlayerTaskComponent.Task t) return t.name();
        if (key instanceof TaskDefinition d) return d.getFullId();
        return key.toString();
    }

    private List<TaskDefinition> getAvailableDlcTasks(TaskManager mgr, String mapName, HabiTaskCategory currentCategory) {
        List<TaskDefinition> tasks = mgr.getAvailableTasks(mapName, currentCategory).stream()
                .filter(def -> !"habitrain_core".equals(def.getModId()))
                .collect(Collectors.toList());
        if (!tasks.isEmpty()) {
            LOGGER.info("[HabiDebug] getAvailableDlcTasks: {} via category {}", tasks.size(), currentCategory);
            return tasks;
        }

        if (currentCategory != HabiTaskCategory.MURDER) {
            tasks = mgr.getAvailableTasks(mapName, HabiTaskCategory.MURDER).stream()
                    .filter(def -> !"habitrain_core".equals(def.getModId()))
                    .collect(Collectors.toList());
            if (!tasks.isEmpty()) {
                LOGGER.warn("[HabiDebug] getAvailableDlcTasks: fallback {}->MURDER, {}", currentCategory, tasks.size());
                return tasks;
            }
        }

        if (currentCategory != HabiTaskCategory.ALL) {
            tasks = mgr.getAvailableTasks(mapName, HabiTaskCategory.ALL).stream()
                    .filter(def -> !"habitrain_core".equals(def.getModId()))
                    .collect(Collectors.toList());
            if (!tasks.isEmpty()) {
                LOGGER.warn("[HabiDebug] getAvailableDlcTasks: fallback {}->ALL, {}", currentCategory, tasks.size());
                return tasks;
            }
        }

        LOGGER.warn("[HabiDebug] getAvailableDlcTasks: ULTIMATE fallback (ignoring category)");
        tasks = TaskRegistry.getAll().stream()
                .filter(def -> !"habitrain_core".equals(def.getModId()))
                .filter(def -> isTaskMapEnabled(def.getFullId(), mapName))
                .collect(Collectors.toList());
        LOGGER.warn("[HabiDebug] getAvailableDlcTasks: ultimate found {} tasks", tasks.size());
        return tasks;
    }

    private boolean isTaskMapEnabled(String fullId, String mapName) {
        HabiTaskConfigEntry entry = HabiConfigManager.getInstance().getTaskConfig(fullId);
        if (entry == null) return true;
        if (!entry.enabled) return false;
        if (entry.mapFilterMode == 0) return true;

        boolean listEmpty = entry.enabledMaps == null || entry.enabledMaps.isEmpty();
        boolean contained = !listEmpty && entry.enabledMaps.contains(mapName);

        if (entry.mapFilterMode == 1) return listEmpty || contained;
        return listEmpty || !contained;
    }

    private SREPlayerTaskComponent.TrainTask createAndTrackDlcTask(TaskDefinition def) {
        TaskManager mgr = TaskManager.getInstance();
        LOGGER.info("[HabiDebug] createAndTrackDlcTask: {} for {}", def.getFullId(), player.getName().getString());
        TaskInstance instance = new TaskInstance(def);
        def.onAssign(player, instance);
        mgr.setActiveTask(player.getUUID(), instance);

        if (player instanceof ServerPlayer sp) {
            ActiveTaskPayload.sendToPlayer(sp, def.getFullId());
        }

        return instance;
    }

    private float getEffectiveWeight(TaskDefinition def) {
        var entry = HabiConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (entry != null && entry.refreshWeight >= 0f) {
            return entry.refreshWeight;
        }
        return def.getWeight() > 0 ? def.getWeight() : 1.0f;
    }
}
