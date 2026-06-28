package com.habitrain.taskapi.impl.mixin;

import com.habitrain.taskapi.HabiTrainTaskAPI;
import com.habitrain.taskapi.api.HabiTaskCategory;
import com.habitrain.taskapi.api.HabiTaskDefinition;
import com.habitrain.taskapi.api.HabiTaskInstance;
import com.habitrain.taskapi.api.HabiTaskRegistry;
import com.habitrain.taskapi.impl.HabiTaskManager;
import com.habitrain.taskapi.impl.config.HabiConfigManager;
import com.habitrain.taskapi.impl.config.HabiTaskConfigEntry;
import com.habitrain.taskapi.impl.network.ActiveCustomTaskPayload;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import io.wifi.starrailexpress.game.GameConstants;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mixin - 接管 {@link SREPlayerTaskComponent#generateTaskInternal()} 方法
 *
 * ★ ★ ★ 自适应平衡系统（v3） ★ ★ ★
 *
 * 核心逻辑：
 *   每次构建权重池时，统计实际可用的原版和DLC任务数量，
 *   自动计算权重乘数，使DLC集体概率始终平衡在目标比例（默认50%）。
 *
 *   计算公式（构建池时实时计算）：
 *     autoBoost = target / (1-target) × origCount / dlcCount
 *
 *   优点：
 *   ① 基于实际进池的任务数（考虑地图禁用、canAssign、分类过滤等）
 *   ② 加新DLC模组后自动适应，无需修改配置
 *   ③ 不影响原版任务的情绪/次数权重系统
 *
 * 其他保护：
 *   ① 防止并行DLC任务覆盖已有DLC任务
 *   ② 浮点精度回退
 */
@Mixin(SREPlayerTaskComponent.class)
public abstract class GenerateTaskMixin {

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
     * ★ 完全接管任务生成逻辑 — 自适应平衡权重池
     *
     * 流程：
     *   1. 把原版任务加入池（情绪/次数调整权重）
     *   2. 统计实际进了池的原版任务数
     *   3. 统计可用的DLC任务数（地图/分类/canAssign过滤后）
     *   4. 自动计算 boost = target/(1-target) × origCount / dlcCount
     *   5. DLC任务以 ×boost 的权重加入同一池
     *   6. 加权随机选择
     */
    @Overwrite(remap = false)
    @Nullable
    public SREPlayerTaskComponent.TrainTask generateTaskInternal() {
        HabiTrainTaskAPI.LOGGER.info("[HabiDebug] ===== genTask CALLED! tasks.size={}, timesGotten={} =====",
                tasks.size(), timesGotten.size());

        float currentMood = (playerMoodComponent != null) ? playerMoodComponent.getMood() : 1f;
        Set<String> disabledTasks = getDisabledTasks();
        HabiTaskManager mgr = HabiTaskManager.getInstance();
        String mapName = mgr.getCurrentMapName(player);
        HabiTaskCategory currentCategory = mgr.getCurrentGameModeCategory(player);

        HabiTrainTaskAPI.LOGGER.info("[HabiDebug] mapName='{}', currentMood={}, disabledTasks={}, category={}",
                mapName, currentMood, disabledTasks, currentCategory);

        // ====== 构建统一的扁平权重池 ======
        List<Map.Entry<Object, Float>> weightEntries = new ArrayList<>();
        float total = 0f;

        // 1. 加入原版任务（含情绪/次数权重调整）
        total += addOriginalTasks(weightEntries, currentMood, disabledTasks, mapName, mgr);

        // 2. 加入DLC任务（自动平衡权重）
        total += addDlcTasks(weightEntries, mgr, mapName, currentCategory, disabledTasks);

        HabiTrainTaskAPI.LOGGER.info("[HabiDebug] Flat pool built: {} entries, total weight={}",
                weightEntries.size(), String.format("%.2f", total));

        // ====== 加权随机选择 ======
        return weightedSelect(weightEntries, total);
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 将原版任务加入权重池
     *
     * 包含11个基础任务（SLEEP, EAT, DRINK, EXERCISE, READ_BOOK, BATHE,
     * TOILET, CHAIR, NOTE_BLOCK, MEDITATE, BREATHE），
     * 并根据完成次数（timesGotten）和当前情绪调整权重。
     *
     * @return 加入的总权重
     */
    private float addOriginalTasks(List<Map.Entry<Object, Float>> entries,
                                   float currentMood, Set<String> disabledTasks,
                                   String mapName, HabiTaskManager mgr) {
        float total = 0f;
        int added = 0;

        for (SREPlayerTaskComponent.Task task : SREPlayerTaskComponent.Task.getAvailableTasksList()) {
            if (this.tasks.containsKey(task)) {
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   skip original {}: already in tasks", task.name());
                continue;
            }
            if (disabledTasks.contains(task.name())) {
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   skip original {}: disabled by map", task.name());
                continue;
            }
            if (mgr.isOriginalTaskDisabled(task.name(), mapName)) {
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   skip original {}: disabled in config", task.name());
                continue;
            }

            // 基础权重 = 1/完成次数（完成越多权重越低，促进任务多样性）
            float weight = 1f / this.timesGotten.getOrDefault(task, 1);

            // 情绪驱动权重调整（与原版SRE逻辑一致）
            if (currentMood < GameConstants.MID_MOOD_THRESHOLD) {
                if (task == SREPlayerTaskComponent.Task.MEDITATE
                        || task == SREPlayerTaskComponent.Task.SLEEP
                        || task == SREPlayerTaskComponent.Task.CHAIR) {
                    weight *= 2f;   // 情绪低时：安抚性任务权重翻倍
                }
                if (task == SREPlayerTaskComponent.Task.EXERCISE
                        || task == SREPlayerTaskComponent.Task.OUTSIDE
                        || task == SREPlayerTaskComponent.Task.BREATHE) {
                    weight *= 0.5f; // 情绪低时：活跃性任务权重减半
                }
            } else if (currentMood > GameConstants.ANGRY_MOOD_THRESHOLD) {
                if (task == SREPlayerTaskComponent.Task.EXERCISE
                        || task == SREPlayerTaskComponent.Task.OUTSIDE
                        || task == SREPlayerTaskComponent.Task.NOTE_BLOCK) {
                    weight *= 1.5f; // 情绪高时：活跃性任务权重提升
                }
                if (task == SREPlayerTaskComponent.Task.SLEEP
                        || task == SREPlayerTaskComponent.Task.MEDITATE) {
                    weight *= 0.5f; // 情绪高时：静态任务权重减半
                }
            }

            HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   ADD original {}: weight={}", task.name(), weight);
            entries.add(new AbstractMap.SimpleEntry<>(task, weight));
            total += weight;
            added++;
        }
        HabiTrainTaskAPI.LOGGER.info("[HabiDebug] Original tasks added: {}, weight total={}", added, String.format("%.2f", total));
        return total;
    }

    /**
     * ★ ★ ★ 自适应DLC任务权重（核心改进） ★ ★ ★
     *
     * 不再使用固定的 dlcWeightBoost，而是：
     *   1. 先统计哪些DLC任务真正能进入池（地图禁用/canAssign等过滤后）
     *   2. 统计已进入池的原版任务数量
     *   3. 自动计算平衡权重乘数
     *      autoBoost = target/(1-target) × origCount / dlcCount
     *   4. 用计算出的乘数加权DLC任务
     *
     * 效果：无论注册了多少DLC任务、多少被地图禁用，
     *       DLC集体概率始终稳定在目标比例（默认50%）
     *
     * @return 加入的总权重
     */
    private float addDlcTasks(List<Map.Entry<Object, Float>> entries,
                              HabiTaskManager mgr, String mapName,
                              HabiTaskCategory currentCategory, Set<String> disabledTasks) {
        // ====== ① 防止DLC任务覆盖 ======
        if (mgr.getActiveCustomTask(player.getUUID()) != null) {
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] Player already has an active DLC task, skipping DLC pool");
            return 0f;
        }

        // ====== ② 获取可用DLC任务列表 ======
        List<HabiTaskDefinition> dlcCandidates = getAvailableDlcTasks(mgr, mapName, currentCategory);
        if (dlcCandidates.isEmpty()) return 0f;

        // ====== ③ 统计实际能进池的DLC任务数 ======
        // 先走一遍所有过滤条件，但不加权重
        List<HabiTaskDefinition> filteredDlc = new ArrayList<>();
        for (HabiTaskDefinition def : dlcCandidates) {
            if (mgr.hasCustomTaskWithId(player.getUUID(), def.getFullId())) {
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   skip DLC {}: already has this task", def.getFullId());
                continue;
            }
            if (disabledTasks.contains(def.getFullId())) {
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   skip DLC {}: disabled by map", def.getFullId());
                continue;
            }
            if (!def.canAssign(player, null)) {
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   skip DLC {}: canAssign returned false", def.getFullId());
                continue;
            }
            filteredDlc.add(def);
        }

        int dlcCount = filteredDlc.size();
        if (dlcCount == 0) return 0f;

        // ====== ④ 统计实际进了池的原版任务数 ======
        int origCount = 0;
        for (Map.Entry<Object, Float> entry : entries) {
            if (entry.getKey() instanceof SREPlayerTaskComponent.Task) {
                origCount++;
            }
        }

        // ====== ⑤ 自动计算平衡权重乘数 ======
        // 根据实际进入池的任务数量来计算，而不是注册总数
        // 公式: autoBoost = target/(1-target) × origCount / dlcCount
        // 确保 DLC集体权重 / (DLC集体权重 + 原版集体权重) = target
        float target = getTargetRatio();
        float autoBoost;
        if (dlcCount > 0 && origCount > 0) {
            autoBoost = (target / (1f - target)) * ((float) origCount / (float) dlcCount);
            autoBoost = Math.max(0.0f, Math.min(10.0f, autoBoost));
        } else {
            autoBoost = 1.0f;
        }

        float dlcTotalWeight = autoBoost * dlcCount;
        float origTotalWeight = 1.0f * origCount; // 近似值，实际情绪调整后有偏差
        float actualPct = (dlcTotalWeight + origTotalWeight) > 0
                ? dlcTotalWeight / (dlcTotalWeight + origTotalWeight) * 100f : 0;

        HabiTrainTaskAPI.LOGGER.info("[HabiDebug] ★ 自适应平衡: 目标={}%, {}个可用原版 + {}个可用DLC → autoBoost={}, 预期DLC占比≈{:.1f}%",
                Math.round(target * 100), origCount, dlcCount,
                String.format("%.2f", autoBoost), actualPct);

        // ====== ⑥ 用自动计算的boost加入权重池 ======
        float total = 0f;
        for (HabiTaskDefinition def : filteredDlc) {
            float baseWeight = getEffectiveWeight(def);
            float boostedWeight = baseWeight * autoBoost;

            HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   ADD DLC {}: baseWeight={} × autoBoost={} = finalWeight={}",
                    def.getFullId(), baseWeight, autoBoost, boostedWeight);
            entries.add(new AbstractMap.SimpleEntry<>(def, boostedWeight));
            total += boostedWeight;
        }

        HabiTrainTaskAPI.LOGGER.info("[HabiDebug] DLC tasks added: {}, total weight={}",
                dlcCount, String.format("%.2f", total));
        return total;
    }

    /**
     * ★ 获取目标占比（从配置读取，默认 0.5 = 50%）
     */
    private float getTargetRatio() {
        return HabiConfigManager.getInstance().getDlcProbabilityTarget();
    }

    // ========================================================================
    // 加权随机选择
    // ========================================================================

    /**
     * 在权重池中执行加权随机选择
     *
     * 标准算法：random = nextFloat() × total，遍历条目扣除权重，首次 ≤0 即选中。
     * 含浮点精度回退。
     */
    @Nullable
    private SREPlayerTaskComponent.TrainTask weightedSelect(List<Map.Entry<Object, Float>> pool, float total) {
        if (pool.isEmpty() || total <= 0) {
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] weightedSelect: pool empty or total<=0, returning null");
            return null;
        }

        float random = this.player.getRandom().nextFloat() * total;
        Collections.shuffle(pool); // 消除顺序偏差

        HabiTrainTaskAPI.LOGGER.info("[HabiDebug] weightedSelect: random={}/{}",
                String.format("%.4f", random), String.format("%.4f", total));

        for (Map.Entry<Object, Float> entry : pool) {
            random -= entry.getValue();
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug]   roll: key={}, value={}, after={}",
                    formatKey(entry.getKey()),
                    String.format("%.4f", entry.getValue()),
                    String.format("%.4f", random));
            if (random <= 0) {
                return instantiateTask(entry.getKey());
            }
        }

        // 浮点精度回退
        HabiTrainTaskAPI.LOGGER.info("[HabiDebug] Fallback (float): selecting from remaining entries");
        for (Map.Entry<Object, Float> entry : pool) {
            Object key = entry.getKey();
            if (key instanceof HabiTaskDefinition defKey) {
                HabiTrainTaskAPI.LOGGER.info("[HabiDebug] Fallback selected DLC: {}", defKey.getFullId());
                return createAndTrackDlcTask(defKey);
            }
        }
        Object firstKey = pool.get(0).getKey();
        if (firstKey instanceof SREPlayerTaskComponent.Task taskKey) {
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] Fallback selected original: {}", taskKey.name());
            return createTaskInstance(taskKey);
        }
        return null;
    }

    /**
     * 根据条目键创建对应的 TrainTask 实例
     */
    @Nullable
    private SREPlayerTaskComponent.TrainTask instantiateTask(Object key) {
        if (key instanceof SREPlayerTaskComponent.Task taskKey) {
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] SELECTED original task: {}", taskKey.name());
            return createTaskInstance(taskKey);
        } else if (key instanceof HabiTaskDefinition defKey) {
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] SELECTED DLC task: {}", defKey.getFullId());
            return createAndTrackDlcTask(defKey);
        }
        HabiTrainTaskAPI.LOGGER.warn("[HabiDebug] Unknown key type: {}", key.getClass().getName());
        return null;
    }

    private String formatKey(Object key) {
        if (key instanceof SREPlayerTaskComponent.Task t) return t.name();
        if (key instanceof HabiTaskDefinition d) return d.getFullId();
        return key.toString();
    }

    // ========================================================================
    // DLC任务获取与创建
    // ========================================================================

    /**
     * 获取可用的DLC自定义任务列表
     * 按分类匹配→回退→终极回退的顺序查找可用任务。
     */
    private List<HabiTaskDefinition> getAvailableDlcTasks(HabiTaskManager mgr, String mapName, HabiTaskCategory currentCategory) {
        // 1. 当前分类
        List<HabiTaskDefinition> tasks = mgr.getAvailableTasks(mapName, currentCategory).stream()
                .filter(def -> !"habitrain_taskapi".equals(def.getModId()))
                .collect(Collectors.toList());
        if (!tasks.isEmpty()) {
            HabiTrainTaskAPI.LOGGER.info("[HabiDebug] getAvailableDlcTasks: {} via category {}", tasks.size(), currentCategory);
            return tasks;
        }

        // 2. 回退MURDER
        if (currentCategory != HabiTaskCategory.MURDER) {
            tasks = mgr.getAvailableTasks(mapName, HabiTaskCategory.MURDER).stream()
                    .filter(def -> !"habitrain_taskapi".equals(def.getModId()))
                    .collect(Collectors.toList());
            if (!tasks.isEmpty()) {
                HabiTrainTaskAPI.LOGGER.warn("[HabiDebug] getAvailableDlcTasks: fallback {}->MURDER, {}", currentCategory, tasks.size());
                return tasks;
            }
        }

        // 3. 回退ALL
        if (currentCategory != HabiTaskCategory.ALL) {
            tasks = mgr.getAvailableTasks(mapName, HabiTaskCategory.ALL).stream()
                    .filter(def -> !"habitrain_taskapi".equals(def.getModId()))
                    .collect(Collectors.toList());
            if (!tasks.isEmpty()) {
                HabiTrainTaskAPI.LOGGER.warn("[HabiDebug] getAvailableDlcTasks: fallback {}->ALL, {}", currentCategory, tasks.size());
                return tasks;
            }
        }

        // 4. 终极回退：忽略分类
        HabiTrainTaskAPI.LOGGER.warn("[HabiDebug] getAvailableDlcTasks: ULTIMATE fallback (ignoring category)");
        tasks = HabiTaskRegistry.getAll().stream()
                .filter(def -> !"habitrain_taskapi".equals(def.getModId()))
                .filter(def -> isTaskMapEnabled(def.getFullId(), mapName))
                .collect(Collectors.toList());
        HabiTrainTaskAPI.LOGGER.warn("[HabiDebug] getAvailableDlcTasks: ultimate found {} tasks", tasks.size());
        return tasks;
    }

    /**
     * 检查任务的 mapFilterMode + enabledMaps 是否允许在当前地图出现
     */
    private boolean isTaskMapEnabled(String fullId, String mapName) {
        HabiTaskConfigEntry entry = HabiConfigManager.getInstance().getTaskConfig(fullId);
        if (entry == null) return true;
        if (!entry.enabled) return false;
        if (entry.mapFilterMode == 0) return true; // 不启用筛选

        boolean listEmpty = entry.enabledMaps == null || entry.enabledMaps.isEmpty();
        boolean contained = !listEmpty && entry.enabledMaps.contains(mapName);

        if (entry.mapFilterMode == 1) return listEmpty || contained;  // 白名单
        return listEmpty || !contained; // 黑名单
    }

    /**
     * 创建DLC任务实例并存入独立跟踪系统
     * 同时同步活跃任务信息到客户端（多人模式下透视渲染需要）
     */
    private SREPlayerTaskComponent.TrainTask createAndTrackDlcTask(HabiTaskDefinition def) {
        HabiTaskManager mgr = HabiTaskManager.getInstance();
        HabiTrainTaskAPI.LOGGER.info("[HabiDebug] createAndTrackDlcTask: {} for {}", def.getFullId(), player.getName().getString());
        HabiTaskInstance instance = new HabiTaskInstance(def);
        def.onAssign(player, instance);
        mgr.setActiveCustomTask(player.getUUID(), instance);

        // ★ 同步活跃任务到客户端（用于多人模式透视渲染）
        if (player instanceof ServerPlayer sp) {
            ActiveCustomTaskPayload.sendToPlayer(sp, def.getFullId());
        }

        return instance;
    }

    /**
     * 获取任务在配置中设置的刷新权重
     */
    private float getEffectiveWeight(HabiTaskDefinition def) {
        var entry = HabiConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (entry != null && entry.refreshWeight >= 0f) {
            return entry.refreshWeight;
        }
        return def.getWeight() > 0 ? def.getWeight() : 1.0f;
    }
}
