package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.sre.SRETrainTaskWrapper;
import com.habitrain.core.task.TaskManager;
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
import java.util.stream.Collectors;

@Mixin(SREPlayerTaskComponent.class)
public abstract class GenerateTaskMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("GenerateTaskMixin");
    private static final Set<String> BUILTIN_SRE_TASK_IDS = Set.of(
            "sleep", "raed_book", "eat", "drink", "exercise", "meditate",
            "bathe", "chair", "note_block", "toilet", "be_alone",
            "breathe", "light_stove", "clean_dust", "transport",
            "pray", "prune_bush", "harvest_crop"
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
    @Nullable
    private SREPlayerTaskComponent.TrainTask createTaskInstance(SREPlayerTaskComponent.Task taskType) {
        throw new AssertionError("Shadowed");
    }

    /**
     * 当前 generateTaskInternal 调用是否为杀手"假任务"（并行任务）路径。
     * 通过方法参数传递（而非实例字段），避免 generateTaskInternal 嵌套调用时
     * 内层覆盖外层状态导致主任务被误存为 fakeTask。
     */

    @Inject(method = "generateTaskInternal", at = @At("HEAD"), cancellable = true, remap = false)
    private void onGenerateTaskInternal(CallbackInfoReturnable<SREPlayerTaskComponent.TrainTask> cir) {
        LOGGER.debug("[HabiDebug] ===== genTask CALLED! tasks.size={}, timesGotten={} =====",
                tasks.size(), timesGotten.size());

        float currentMood = (playerMoodComponent != null) ? playerMoodComponent.getMood() : 1f;
        Set<String> disabledTasks = getDisabledTasks();
        TaskManager mgr = TaskManager.getInstance();
        String mapName = mgr.getCurrentMapName(player);
        TaskCategory currentCategory = mgr.getCurrentGameModeCategory(player);
        GameMode activeMode = resolveActiveGameMode();

        // ===== 阵营池过滤（停电模式核心）=====
        // 停电模式下，所有玩家都按阵营强制分配任务池：
        //   GOOD 玩家 → 只抽 BLACKOUT_GOOD 池（添煤/修理线路/维持供电）
        //   BAD  玩家 → 主任务抽 BLACKOUT_BAD 池（破坏线路/炸毁熔炉），
        //              并行调用(假任务)抽 BLACKOUT_GOOD 池以伪装身份
        // 新增任务时，用 .category(BlackoutMode.BLACKOUT_GOOD) 或 .category(BlackoutMode.BLACKOUT_BAD) 归属阵营。
        // 阵营归属只由注册时的 TaskCategory 决定，TaskConfigEntry 不重复存储 faction 字段。
        boolean killerDualTask = isKillerDualTaskMode(activeMode);
        boolean isParallelCall = !this.tasks.isEmpty();
        TaskCategory forcedCategory = null;
        boolean skipActiveTaskGuard = false;
        boolean isBlackout = (activeMode instanceof BlackoutMode);
        boolean currentIsFakeTask = false;

        if (killerDualTask) {
            // BAD 玩家（杀手双任务）：保留原有逻辑
            if (isParallelCall) {
                forcedCategory = BlackoutMode.BLACKOUT_GOOD;
                skipActiveTaskGuard = true;
                currentIsFakeTask = true;
                LOGGER.info("[HabiDebug] Killer dual-task: parallel call, forcing GOOD pool as fake task");
            } else {
                forcedCategory = BlackoutMode.BLACKOUT_BAD;
                currentIsFakeTask = false;
                LOGGER.info("[HabiDebug] Killer dual-task: main call, forcing BAD pool as real task");
            }
        } else if (isBlackout) {
            // GOOD 玩家（停电模式非杀手）：强制只抽 GOOD 池，修复好人可能拿到坏人任务的漏洞
            forcedCategory = BlackoutMode.BLACKOUT_GOOD;
            currentIsFakeTask = false;
            LOGGER.info("[HabiDebug] Blackout GOOD player: forcing GOOD pool only");
        } else {
            currentIsFakeTask = false;
        }

        LOGGER.debug("[HabiDebug] mapName='{}', currentMood={}, disabledTasks={}, category={}, killerDual={}, parallel={}",
                mapName, currentMood, disabledTasks, currentCategory, killerDualTask, isParallelCall);

        List<Map.Entry<Object, Float>> weightEntries = new ArrayList<>();
        float total = 0f;

        if (!killerDualTask) {
            total += addOriginalTasks(weightEntries, currentMood, disabledTasks, mapName, mgr, activeMode);
        }
        total += addDlcTasks(weightEntries, mgr, mapName, currentCategory, disabledTasks, activeMode,
                forcedCategory, skipActiveTaskGuard, currentIsFakeTask);

        LOGGER.debug("[HabiDebug] Flat pool built: {} entries, total weight={}",
                weightEntries.size(), String.format("%.2f", total));

        cir.setReturnValue(weightedSelect(weightEntries, total, currentIsFakeTask));
    }

    /**
     * 判断当前是否处于"杀手双任务"模式：玩家是停电模式的杀手(BAD)阵营。
     */
    private boolean isKillerDualTaskMode(@Nullable GameMode activeMode) {
        if (!(player instanceof ServerPlayer sp)) return false;
        if (!(sp.level() instanceof ServerLevel level)) return false;
        if (activeMode == null) return false;
        if (!(activeMode instanceof BlackoutMode)) return false;
        try {
            return BlackoutRoleManager.getFaction(level, sp.getUUID())
                    == BlackoutRoleManager.Faction.BAD;
        } catch (Throwable t) {
            return false;
        }
    }

    private float addOriginalTasks(List<Map.Entry<Object, Float>> entries,
                                   float currentMood, Set<String> disabledTasks,
                                   String mapName, TaskManager mgr,
                                   @Nullable GameMode activeMode) {
        if (!shouldIncludeOriginalTasks(activeMode)) {
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

            // 防止 timesGotten.get(task)==0 时 1f/0f=Infinity 污染加权随机
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

        // forcedCategory 现在直接作为 getAvailableDlcTasks 的硬过滤参数；
        // 停电模式(isBlackout=true)下该函数会跳过三级 fallback，阵营池空就返回空列表。
        List<TaskDefinition> dlcCandidates = getAvailableDlcTasks(mgr, mapName, currentCategory, activeMode, forcedCategory);

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

            LOGGER.debug("[HabiDebug]   ADD DLC {}: baseWeight={} × autoBoost={} = finalWeight={}",
                    def.getFullId(), baseWeight, autoBoost, boostedWeight);
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

    /**
     * 获取可分配的 DLC 任务候选列表。
     * <p>
     * 阵营过滤规则（停电模式核心）：
     * <ul>
     *   <li>{@code forcedCategory != null}（停电模式所有玩家）→ 作为硬过滤，只返回该阵营池的任务，
     *       且<b>跳过三级 fallback</b>。阵营池空就直接返回空列表，绝不跨阵营兜底，
     *       避免好人玩家抽到"破坏线路/炸毁熔炉"这类坏人任务。</li>
     *   <li>{@code forcedCategory == null}（非停电模式）→ 保留原有三级 fallback 行为
     *       （currentCategory → MURDER → ALL → 忽略 category）。</li>
     * </ul>
     * 新增好人任务请用 {@code .category(BlackoutMode.BLACKOUT_GOOD)}，
     * 新增坏人任务请用 {@code .category(BlackoutMode.BLACKOUT_BAD)}。
     */
    private List<TaskDefinition> getAvailableDlcTasks(TaskManager mgr, String mapName,
                                                      TaskCategory currentCategory,
                                                      @Nullable GameMode activeMode,
                                                      @Nullable TaskCategory forcedCategory) {
        // 停电模式：阵营池作为硬过滤，禁用 fallback
        if (forcedCategory != null) {
            List<TaskDefinition> tasks = TaskRegistry.getAll().stream()
                    .filter(def -> !isBuiltinSreTask(def))
                    .filter(def -> isTaskMapEnabled(def.getFullId(), mapName))
                    .filter(def -> isTaskAllowedForPool(def, currentCategory, activeMode))
                    .filter(def -> forcedCategory.equals(def.getCategory()))
                    .collect(Collectors.toList());
            LOGGER.info("[HabiDebug] getAvailableDlcTasks: blackout faction filter={}, {} candidates (fallback disabled)",
                    forcedCategory, tasks.size());
            return tasks;
        }

        // 非停电模式：保留原有三级 fallback 逻辑
        List<TaskDefinition> tasks = TaskRegistry.getAll().stream()
                .filter(def -> !isBuiltinSreTask(def))
                .filter(def -> isTaskMapEnabled(def.getFullId(), mapName))
                .filter(def -> isTaskAllowedForPool(def, currentCategory, activeMode))
                .collect(Collectors.toList());
        if (!tasks.isEmpty()) {
            LOGGER.debug("[HabiDebug] getAvailableDlcTasks: {} via category {}", tasks.size(), currentCategory);
            return tasks;
        }

        if (currentCategory != TaskCategory.MURDER) {
            tasks = TaskRegistry.getAll().stream()
                    .filter(def -> !isBuiltinSreTask(def))
                    .filter(def -> isTaskMapEnabled(def.getFullId(), mapName))
                    .filter(def -> isTaskAllowedForPool(def, TaskCategory.MURDER, activeMode))
                    .collect(Collectors.toList());
            if (!tasks.isEmpty()) {
                LOGGER.warn("[HabiDebug] getAvailableDlcTasks: fallback {}->MURDER, {}", currentCategory, tasks.size());
                return tasks;
            }
        }

        if (currentCategory != TaskCategory.ALL) {
            tasks = TaskRegistry.getAll().stream()
                    .filter(def -> !isBuiltinSreTask(def))
                    .filter(def -> isTaskMapEnabled(def.getFullId(), mapName))
                    .filter(def -> isTaskAllowedForPool(def, TaskCategory.ALL, activeMode))
                    .collect(Collectors.toList());
            if (!tasks.isEmpty()) {
                LOGGER.warn("[HabiDebug] getAvailableDlcTasks: fallback {}->ALL, {}", currentCategory, tasks.size());
                return tasks;
            }
        }

        LOGGER.warn("[HabiDebug] getAvailableDlcTasks: ULTIMATE fallback (ignoring category)");
        tasks = TaskRegistry.getAll().stream()
                .filter(def -> !isBuiltinSreTask(def))
                .filter(def -> isTaskMapEnabled(def.getFullId(), mapName))
                .collect(Collectors.toList());
        LOGGER.warn("[HabiDebug] getAvailableDlcTasks: ultimate found {} tasks", tasks.size());
        return tasks;
    }

    private boolean shouldIncludeOriginalTasks(@Nullable GameMode activeMode) {
        if (activeMode == null) {
            return true;
        }
        if (!(player instanceof ServerPlayer sp)) {
            return true;
        }

        Set<String> allowedTaskIds = activeMode.filterAvailableTasks(new ArrayList<>(TaskRegistry.getAll()), sp).stream()
                .map(TaskDefinition::getTaskId)
                .collect(Collectors.toSet());

        for (String taskId : BUILTIN_SRE_TASK_IDS) {
            if (allowedTaskIds.contains(taskId)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTaskAllowedForPool(TaskDefinition def, TaskCategory currentCategory,
                                         @Nullable GameMode activeMode) {
        if (activeMode != null && player instanceof ServerPlayer sp) {
            if (activeMode.filterAvailableTasks(List.of(def), sp).isEmpty()) {
                return false;
            }
        }

        TaskCategory category = def.getCategory();
        if (TaskCategory.ALL.equals(category)
                || TaskCategory.CUSTOM.equals(category)
                || category.equals(currentCategory)) {
            return true;
        }

        return activeMode != null
                && activeMode.getTaskCategories().stream().anyMatch(category::equals);
    }

    private boolean isBuiltinSreTask(TaskDefinition def) {
        return BUILTIN_SRE_TASK_IDS.contains(def.getTaskId());
    }

    @Nullable
    private GameMode resolveActiveGameMode() {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        return GameModeRegistry.getActiveForLevel(level).orElse(null);
    }

    private boolean isTaskMapEnabled(String fullId, String mapName) {
        TaskConfigEntry entry = ConfigManager.getInstance().getTaskConfig(fullId);
        if (entry == null) return true;
        if (!entry.enabled) return false;
        if (entry.mapFilterMode == 0) return true;

        boolean listEmpty = entry.enabledMaps == null || entry.enabledMaps.isEmpty();
        boolean contained = !listEmpty && entry.enabledMaps.contains(mapName);

        if (entry.mapFilterMode == 1) return listEmpty || contained;
        return listEmpty || !contained;
    }

    private SREPlayerTaskComponent.TrainTask createAndTrackDlcTask(TaskDefinition def, boolean isFakeTask) {
        TaskManager mgr = TaskManager.getInstance();
        LOGGER.debug("[HabiDebug] createAndTrackDlcTask: {} for {} (fake={})",
                def.getFullId(), player.getName().getString(), isFakeTask);
        TaskInstance instance = new TaskInstance(def);
        def.onAssign(player, instance);

        if (isFakeTask) {
            // 杀手假任务单独追踪，不覆盖主任务。
            // 用 PRAY 原版枚举槽位（停电模式杀手不走原版任务池，PRAY 必定空闲），
            // 这样 SRE 的 tasks map 里主任务(CUSTOM)和假任务(PRAY)各占一个槽位，互不冲突。
            mgr.setFakeTask(player.getUUID(), instance);
            SREPlayerTaskComponent.Task fakeSlot = SREPlayerTaskComponent.Task.PRAY;
            if (player instanceof ServerPlayer sp) {
                ActiveTaskPayload.sendToPlayer(sp, def.getFullId());
            }
            return new SRETrainTaskWrapper(instance, fakeSlot);
        } else {
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
