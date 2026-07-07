package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.game.sre.FactionFilter;
import com.habitrain.core.game.sre.TaskWeightCurves;
import com.habitrain.core.game.sre.SRETrainTaskWrapper;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.task.TaskPoolBuilder;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import com.habitrain.core.network.ActiveTaskPayload;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import io.wifi.starrailexpress.game.GameConstants;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

@Mixin(SREPlayerTaskComponent.class)
public abstract class GenerateTaskMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("GenerateTaskMixin");
    private static final Set<String> BUILTIN_SRE_TASK_IDS = Set.of(
            "sleep", "raed_book", "eat", "drink", "exercise", "meditate",
            "bathe", "chair", "note_block", "toilet", "be_alone",
            "breathe", "light_stove", "clean_dust", "transport",
            "pray", "prune_bush", "harvest_crop"
    );

    private static final Set<String> BLACKOUT_SUPPLY_TASK_IDS = Set.of(
            "habitrain_core:add_coal",
            "habitrain_core:repair_wiring",
            "habitrain_core:maintain_power"
    );

    private static final Set<String> BLACKOUT_DAILY_TASK_IDS = Set.of(
            "habitrain_core:blackout_eat",
            "habitrain_core:blackout_drink",
            "habitrain_core:blackout_search_backpack",
            "habitrain_core:blackout_betel_quest",
            "habitrain_core:blackout_pet_cat",
            "habitrain_core:blackout_be_alone",
            "habitrain_core:blackout_look_my_eyes"
    );

    @Shadow(remap = false) private Player player;
    @Shadow(remap = false) public Map<SREPlayerTaskComponent.Task, SREPlayerTaskComponent.TrainTask> tasks;
    @Shadow(remap = false) public Map<SREPlayerTaskComponent.Task, Integer> timesGotten;
    @Shadow(remap = false) public SREPlayerMoodComponent playerMoodComponent;

    @Shadow(remap = false)
    private Set<String> getDisabledTasks() {
        throw new AssertionError("Shadowed");
    }

    @Shadow(remap = false)
    private Set<String> getEnabledSceneTasks() {
        throw new AssertionError("Shadowed");
    }

    @Shadow(remap = false)
    @Nullable
    private SREPlayerTaskComponent.TrainTask createTaskInstance(SREPlayerTaskComponent.Task taskType) {
        throw new AssertionError("Shadowed");
    }

    @Inject(method = "generateTaskInternal", at = @At("HEAD"), cancellable = true, remap = false)
    private void onGenerateTaskInternal(CallbackInfoReturnable<SREPlayerTaskComponent.TrainTask> cir) {
        LOGGER.debug("[HabiDebug] ===== genTask CALLED! tasks.size={}, timesGotten={} =====",
                tasks.size(), timesGotten.size());

        float currentMood = (playerMoodComponent != null) ? playerMoodComponent.getMood() : 1f;
        Set<String> disabledTasks = getDisabledTasks();
        TaskManager mgr = TaskManager.getInstance();
        String mapName = mgr.getCurrentMapName(player);
        TaskCategory currentCategory = mgr.getCurrentGameModeCategory(player);

        FactionFilter.FactionContext ctx = FactionFilter.determineFaction(player, !this.tasks.isEmpty());

        boolean isBlackout = (ctx.activeMode() instanceof BlackoutMode);

        if (isBlackout && !ctx.killerDualTask() && player instanceof ServerPlayer sp
                && sp.level() instanceof ServerLevel level) {
            TaskInstance activeTask = mgr.getActiveTask(sp.getUUID());
            if (activeTask != null && "habitrain_core:restore_power".equals(activeTask.getFullId())
                    && !activeTask.isFulfilled()) {
                BlackoutTimerSystem.Phase phase = BlackoutTimerSystem.getPhase(level);
                if (phase == BlackoutTimerSystem.Phase.FIRST_BLACKOUT
                        || phase == BlackoutTimerSystem.Phase.SECOND_BLACKOUT) {
                    LOGGER.info("[HabiDebug] Player has active restore_power task during blackout, skipping dispatch");
                    cir.setReturnValue(null);
                    return;
                }
            }
        }

        LOGGER.debug("[HabiDebug] mapName='{}', currentMood={}, disabledTasks={}, category={}, killerDual={}, parallel={}",
                mapName, currentMood, disabledTasks, currentCategory, ctx.killerDualTask(), ctx.isParallelCall());

        List<Map.Entry<Object, Float>> weightEntries = new ArrayList<>();
        float total = 0f;

        if (!ctx.killerDualTask()) {
            total += addOriginalTasks(weightEntries, currentMood, disabledTasks, mapName, mgr, ctx.activeMode());
        }
        total += addDlcTasks(weightEntries, mgr, mapName, currentCategory, disabledTasks, ctx.activeMode(),
                ctx.forcedCategory(), ctx.skipActiveTaskGuard(), ctx.currentIsFakeTask());

        LOGGER.debug("[HabiDebug] Flat pool built: {} entries, total weight={}",
                weightEntries.size(), String.format("%.2f", total));

        cir.setReturnValue(weightedSelect(weightEntries, total, ctx.currentIsFakeTask()));
    }

    private float addOriginalTasks(List<Map.Entry<Object, Float>> entries,
                                   float currentMood, Set<String> disabledTasks,
                                   String mapName, TaskManager mgr,
                                   @Nullable GameMode activeMode) {
        if (!TaskWeightCurves.shouldIncludeOriginalTasks(activeMode, player, BUILTIN_SRE_TASK_IDS)) {
            LOGGER.debug("[HabiDebug] Original SRE tasks filtered out by active GameMode");
            return 0f;
        }

        float total = 0f;
        int added = 0;

        for (SREPlayerTaskComponent.Task task : SREPlayerTaskComponent.Task.getAvailableTasksList()) {
            if (this.tasks.containsKey(task)) {
                LOGGER.debug("[HabiDebug]   skip original {}: already in tasks", task.name());
                continue;
            }
            if (disabledTasks.contains(task.name())) {
                LOGGER.debug("[HabiDebug]   skip original {}: disabled by map", task.name());
                continue;
            }
            if (mgr.isOriginalTaskDisabled(task.name(), mapName)) {
                LOGGER.debug("[HabiDebug]   skip original {}: disabled in config", task.name());
                continue;
            }

            float weight = 1f / Math.max(1, this.timesGotten.getOrDefault(task, 1));

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

            LOGGER.debug("[HabiDebug]   ADD original {}: weight={}", task.name(), weight);
            entries.add(new AbstractMap.SimpleEntry<>(task, weight));
            total += weight;
            added++;
        }

        Set<String> enabledSceneTasks = getEnabledSceneTasks();
        if (enabledSceneTasks != null && !enabledSceneTasks.isEmpty()) {
            for (SREPlayerTaskComponent.Task task : SREPlayerTaskComponent.Task.getSceneTasksList()) {
                if (task == SREPlayerTaskComponent.Task.PRAY) continue;

                if (this.tasks.containsKey(task)) continue;
                if (!enabledSceneTasks.contains(task.name())) continue;
                if (disabledTasks.contains(task.name())) continue;
                if (mgr.isOriginalTaskDisabled(task.name(), mapName)) continue;

                float weight = 1f / Math.max(1, this.timesGotten.getOrDefault(task, 1));
                if (currentMood < GameConstants.MID_MOOD_THRESHOLD) {
                    if (task == SREPlayerTaskComponent.Task.LIGHT_STOVE) weight *= 2f;
                } else if (currentMood > GameConstants.ANGRY_MOOD_THRESHOLD) {
                    if (task == SREPlayerTaskComponent.Task.CLEAN_DUST
                            || task == SREPlayerTaskComponent.Task.TRANSPORT
                            || task == SREPlayerTaskComponent.Task.PRUNE_BUSH
                            || task == SREPlayerTaskComponent.Task.HARVEST_CROP) {
                        weight *= 1.5f;
                    }
                }

                LOGGER.debug("[HabiDebug]   ADD scene original {}: weight={}", task.name(), weight);
                entries.add(new AbstractMap.SimpleEntry<>(task, weight));
                total += weight;
                added++;
            }
        }

        LOGGER.debug("[HabiDebug] Original tasks added: {}, weight total={}", added, String.format("%.2f", total));
        return total;
    }

    private float addDlcTasks(List<Map.Entry<Object, Float>> entries,
                              TaskManager mgr, String mapName,
                              TaskCategory currentCategory, Set<String> disabledTasks,
                              @Nullable GameMode activeMode,
                              @Nullable TaskCategory forcedCategory, boolean skipActiveTaskGuard,
                              boolean currentIsFakeTask) {
        if (!skipActiveTaskGuard && mgr.getActiveTask(player.getUUID()) != null) {
            LOGGER.debug("[HabiDebug] Player already has an active DLC task, skipping DLC pool");
            return 0f;
        }

        List<TaskDefinition> dlcCandidates = TaskPoolBuilder.getPool(activeMode, mapName, forcedCategory, currentCategory, player, BUILTIN_SRE_TASK_IDS);

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

        if (activeMode instanceof BlackoutMode
                && BlackoutMode.BLACKOUT_GOOD.equals(forcedCategory)
                && !currentIsFakeTask
                && !skipActiveTaskGuard) {
            boolean wantDaily = mgr.isBlackoutNextDailyPool(player.getUUID());
            Set<String> targetPool = wantDaily ? BLACKOUT_DAILY_TASK_IDS : BLACKOUT_SUPPLY_TASK_IDS;
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

    private float getTargetRatio() {
        return ConfigManager.getInstance().getDlcProbabilityTarget();
    }

    @Nullable
    private SREPlayerTaskComponent.TrainTask weightedSelect(List<Map.Entry<Object, Float>> pool, float total,
                                                              boolean currentIsFakeTask) {
        if (pool.isEmpty() || total <= 0) {
            LOGGER.debug("[HabiDebug] weightedSelect: pool empty or total<=0, returning null");
            return null;
        }

        float random = this.player.getRandom().nextFloat() * total;
        Collections.shuffle(pool);

        LOGGER.debug("[HabiDebug] weightedSelect: random={}/{}",
                String.format("%.4f", random), String.format("%.4f", total));

        for (Map.Entry<Object, Float> entry : pool) {
            random -= entry.getValue();
            LOGGER.debug("[HabiDebug]   roll: key={}, value={}, after={}",
                    formatKey(entry.getKey()),
                    String.format("%.4f", entry.getValue()),
                    String.format("%.4f", random));
            if (random < 0) {
                return instantiateTask(entry.getKey(), currentIsFakeTask);
            }
        }

        LOGGER.debug("[HabiDebug] Fallback (float): selecting from remaining entries");
        for (Map.Entry<Object, Float> entry : pool) {
            Object key = entry.getKey();
            if (key instanceof TaskDefinition defKey) {
                LOGGER.debug("[HabiDebug] Fallback selected DLC: {}", defKey.getFullId());
                return createAndTrackDlcTask(defKey, currentIsFakeTask);
            }
        }
        Object firstKey = pool.get(0).getKey();
        if (firstKey instanceof SREPlayerTaskComponent.Task taskKey) {
            LOGGER.debug("[HabiDebug] Fallback selected original: {}", taskKey.name());
            return createTaskInstance(taskKey);
        }
        return null;
    }

    @Nullable
    private SREPlayerTaskComponent.TrainTask instantiateTask(Object key, boolean currentIsFakeTask) {
        if (key instanceof SREPlayerTaskComponent.Task taskKey) {
            LOGGER.debug("[HabiDebug] SELECTED original task: {}", taskKey.name());
            return createTaskInstance(taskKey);
        } else if (key instanceof TaskDefinition defKey) {
            LOGGER.debug("[HabiDebug] SELECTED DLC task: {}", defKey.getFullId());
            return createAndTrackDlcTask(defKey, currentIsFakeTask);
        }
        LOGGER.warn("[HabiDebug] Unknown key type: {}", key.getClass().getName());
        return null;
    }

    private String formatKey(Object key) {
        if (key instanceof SREPlayerTaskComponent.Task t) return t.name();
        if (key instanceof TaskDefinition d) return d.getFullId();
        return key.toString();
    }

    private SREPlayerTaskComponent.TrainTask createAndTrackDlcTask(TaskDefinition def, boolean isFakeTask) {
        TaskManager mgr = TaskManager.getInstance();
        LOGGER.debug("[HabiDebug] createAndTrackDlcTask: {} for {} (fake={})",
                def.getFullId(), player.getName().getString(), isFakeTask);
        TaskInstance instance = new TaskInstance(def);
        def.onAssign(player, instance);

        mgr.incrementDlcTaskCount(player.getUUID(), def.getFullId());

        if (isFakeTask) {
            mgr.setFakeTask(player.getUUID(), instance);
            SREPlayerTaskComponent.Task fakeSlot = SREPlayerTaskComponent.Task.PRAY;
            if (player instanceof ServerPlayer sp) {
                ActiveTaskPayload.sendToPlayer(sp, def.getFullId());
            }
            return new SRETrainTaskWrapper(instance, fakeSlot);
        } else {
            if (!isFakeTask && player.level() instanceof ServerLevel level) {
                GameMode activeMode = GameModeRegistry.getActiveForLevel(level).orElse(null);
                if (activeMode instanceof BlackoutMode
                        && BlackoutMode.BLACKOUT_GOOD.equals(def.getCategory())
                        && (BLACKOUT_SUPPLY_TASK_IDS.contains(def.getFullId())
                            || BLACKOUT_DAILY_TASK_IDS.contains(def.getFullId()))) {
                    boolean wasDaily = mgr.isBlackoutNextDailyPool(player.getUUID());
                    mgr.setBlackoutNextDailyPool(player.getUUID(), !wasDaily);
                    LOGGER.info("[HabiDebug] Blackout rotation flag toggled: {} -> {} for player {}",
                            wasDaily ? "DAILY" : "SUPPLY", !wasDaily ? "DAILY" : "SUPPLY",
                            player.getName().getString());
                }
            }

            mgr.setActiveTask(player.getUUID(), instance);
            if (player instanceof ServerPlayer sp) {
                ActiveTaskPayload.sendToPlayer(sp, def.getFullId());
            }
            return new SRETrainTaskWrapper(instance);
        }
    }

    private float getEffectiveWeight(TaskDefinition def) {
        var entry = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (entry != null && entry.refreshWeight >= 0f) {
            return entry.refreshWeight;
        }
        return def.getWeight() > 0 ? def.getWeight() : 1.0f;
    }
}
