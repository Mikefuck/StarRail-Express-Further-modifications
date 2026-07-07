package com.habitrain.core.api;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * 任务定义 — 取代 HabiTaskDefinition。
 * 新增: timeLimit、canRepeat、tags 等扩展字段。
 */
public class TaskDefinition {

    private final String modId;
    private final String taskId;
    private final String fullId;
    private final String displayName;

    // 分类：标准 TaskCategory 或自定义 customCategory
    private final TaskCategory category;
    private final String gameModeId;
    private final TaskCategory customCategory;

    private final float weight;
    private final int blockTypeId;
    private final int instinctColor;
    private final boolean canDirectlyWin;
    private final Set<Block> scanBlocks;
    private final Set<String> scanBlockIds;

    // 新增字段
    private final int timeLimit;           // 0 = 不限时
    private final boolean canRepeat;
    private final boolean shareProgress;
    private final List<String> tags;
    /** 任务对停电计时器的影响（可选，供自适应刷新概率使用）。null 表示无时间影响。 */
    private final TimeImpact timeImpact;

    // 回调函数
    private final BiConsumer<Player, TaskInstance> onAssignHandler;
    private final BiConsumer<Player, TaskInstance> onCompleteHandler;
    private final BiConsumer<Player, TaskInstance> onRemoveHandler;
    private final BiConsumer<Player, TaskInstance> onFailHandler;
    /** 任务被取消/隐藏时回收发放的物理道具。区别于 onRemove（清效果），仅在取消路径调用。 */
    private final BiConsumer<Player, TaskInstance> onReclaimHandler;
    private final BiFunction<Player, TaskInstance, Boolean> completionChecker;
    private final BiConsumer<Player, TaskInstance> tickHandler;
    private final BiPredicate<Player, TaskInstance> canAssignPredicate;
    private final ProgressUpdateHandler onProgressUpdateHandler;

    @FunctionalInterface
    public interface ProgressUpdateHandler {
        void onProgressUpdate(Player player, TaskInstance task, int oldProgress);
    }

    /**
     * 任务对停电计时器的影响声明。
     *  axis: 影响哪个时间轴
     *  deltaSeconds: 增减秒数（正=增加停电时间，负=减少）
     *
     * 用于自适应刷新概率：computeUrgencyMultiplier 从 delta 派生阈值，
     * 未来改 delta 自动调整概率曲线，无需改概率逻辑代码。
     */
    public record TimeImpact(TimeAxis axis, int deltaSeconds) {
        public enum TimeAxis {
            /** 增加/减少 停电倒计时或维护时间（delayMaintenanceOrCountdown / reduceMaintenanceOrCountdown） */
            MAINTENANCE_OR_COUNTDOWN,
            /** 增加/减少 对局总时间（addTime / reduceTime） */
            TOTAL_TIME,
            /** 触发恢复供电（restorePower，从停电拉回维护期） */
            RESTORE_POWER,
            /** 触发瞬时停电惩罚（triggerTransientBlackout） */
            TRANSIENT
        }
    }

    private TaskDefinition(Builder builder) {
        this.modId = builder.modId;
        this.taskId = builder.taskId;
        this.fullId = builder.modId + ":" + builder.taskId;
        this.displayName = builder.displayName;
        this.category = builder.category;
        this.gameModeId = builder.gameModeId;
        this.customCategory = builder.customCategory;
        this.weight = builder.weight;
        this.blockTypeId = builder.blockTypeId;
        this.instinctColor = builder.instinctColor;
        this.canDirectlyWin = builder.canDirectlyWin;
        this.scanBlocks = Set.copyOf(builder.scanBlocks);
        this.scanBlockIds = Set.copyOf(builder.scanBlockIds);
        this.timeLimit = builder.timeLimit;
        this.canRepeat = builder.canRepeat;
        this.shareProgress = builder.shareProgress;
        this.tags = List.copyOf(builder.tags);
        this.timeImpact = builder.timeImpact;
        this.onAssignHandler = builder.onAssignHandler;
        this.onCompleteHandler = builder.onCompleteHandler;
        this.onRemoveHandler = builder.onRemoveHandler;
        this.onFailHandler = builder.onFailHandler;
        this.onReclaimHandler = builder.onReclaimHandler;
        this.completionChecker = builder.completionChecker;
        this.tickHandler = builder.tickHandler;
        this.canAssignPredicate = builder.canAssignPredicate;
        this.onProgressUpdateHandler = builder.onProgressUpdateHandler;
    }

    // --- Getters ---
    public String getFullId() { return fullId; }
    public String getModId() { return modId; }
    public String getTaskId() { return taskId; }
    public String getDisplayName() { return displayName; }
    public TaskCategory getCategory() { return category; }
    public String getGameModeId() { return gameModeId; }
    public TaskCategory getCustomCategory() { return customCategory; }
    public float getWeight() { return weight; }
    public int getBlockTypeId() { return blockTypeId; }
    public int getInstinctColorRGB() { return instinctColor; }
    public boolean canDirectlyWin() { return canDirectlyWin; }
    public Set<Block> getScanBlocks() { return Collections.unmodifiableSet(scanBlocks); }
    public Set<String> getScanBlockIds() { return Collections.unmodifiableSet(scanBlockIds); }
    public int getTimeLimit() { return timeLimit; }
    public boolean canRepeat() { return canRepeat; }
    public boolean isShareProgress() { return shareProgress; }
    public List<String> getTags() { return Collections.unmodifiableList(tags); }
    /** 任务对停电计时器的影响（可能为 null）。供自适应刷新概率使用。 */
    public TimeImpact getTimeImpact() { return timeImpact; }

    // --- Callback dispatch ---
    public void onAssign(Player player, TaskInstance instance) { if (onAssignHandler != null) onAssignHandler.accept(player, instance); }
    public void onComplete(Player player, TaskInstance instance) { if (onCompleteHandler != null) onCompleteHandler.accept(player, instance); }
    public void onRemove(Player player, TaskInstance instance) { if (onRemoveHandler != null) onRemoveHandler.accept(player, instance); }
    public void onFail(Player player, TaskInstance instance) { if (onFailHandler != null) onFailHandler.accept(player, instance); }
    /** 回收发放的物理道具。仅在任务被取消/隐藏路径调用，不在成功完成路径调用。 */
    public void onReclaim(Player player, TaskInstance instance) { if (onReclaimHandler != null) onReclaimHandler.accept(player, instance); }
    public boolean checkCompletion(Player player, TaskInstance instance) { if (completionChecker != null) return completionChecker.apply(player, instance); return instance.isFulfilled(); }
    public void onTick(Player player, TaskInstance instance) { if (tickHandler != null) tickHandler.accept(player, instance); }
    public boolean canAssign(Player player, TaskInstance instance) { if (canAssignPredicate != null) return canAssignPredicate.test(player, instance); return true; }
    /** 不需要 TaskInstance 的安全重载 — 当调用方没有 instance 时使用，避免传入 null */
    public boolean canAssign(Player player) { if (canAssignPredicate != null) return canAssignPredicate.test(player, null); return true; }
    public void onProgressUpdate(Player player, TaskInstance instance, int oldProgress) { if (onProgressUpdateHandler != null) onProgressUpdateHandler.onProgressUpdate(player, instance, oldProgress); }

    // --- Builder ---
    public static class Builder {
        private final String modId;
        private final String taskId;
        private String displayName;
        private TaskCategory category = TaskCategory.ALL;
        private String gameModeId = "sre:base";
        private TaskCategory customCategory;
        private float weight = 1.0f;
        private int blockTypeId = -1;
        private int instinctColor = 0xB4C8C8C8;
        private boolean canDirectlyWin = false;
        private Set<Block> scanBlocks = Set.of();
        private Set<String> scanBlockIds = Set.of();
        private int timeLimit = 0;
        private boolean canRepeat = false;
        private boolean shareProgress = false;
        private List<String> tags = List.of();
        private TimeImpact timeImpact = null;

        private BiConsumer<Player, TaskInstance> onAssignHandler;
        private BiConsumer<Player, TaskInstance> onCompleteHandler;
        private BiConsumer<Player, TaskInstance> onRemoveHandler;
        private BiConsumer<Player, TaskInstance> onFailHandler;
        private BiConsumer<Player, TaskInstance> onReclaimHandler;
        private BiFunction<Player, TaskInstance, Boolean> completionChecker;
        private BiConsumer<Player, TaskInstance> tickHandler;
        private BiPredicate<Player, TaskInstance> canAssignPredicate;
        private ProgressUpdateHandler onProgressUpdateHandler;

        public Builder(String modId, String taskId) {
            this.modId = modId;
            this.taskId = taskId;
            this.displayName = taskId;
        }

        public Builder displayName(String name) { this.displayName = name; return this; }
        public Builder category(TaskCategory cat) { this.category = cat; return this; }
        public Builder customCategory(TaskCategory cat) { this.customCategory = cat; return this; }
        public Builder gameMode(String gameModeId) { this.gameModeId = gameModeId; return this; }
        public Builder weight(float w) { this.weight = w; return this; }
        public Builder blockTypeId(int id) { this.blockTypeId = id; return this; }
        public Builder instinctColor(int argb) { this.instinctColor = argb; return this; }
        public Builder instinctColor(int r, int g, int b, int a) { this.instinctColor = (a << 24) | (r << 16) | (g << 8) | b; return this; }
        public Builder canDirectlyWin(boolean v) { this.canDirectlyWin = v; return this; }
        public Builder scanBlocks(Block... blocks) { this.scanBlocks = Set.of(blocks); return this; }
        public Builder scanBlockIds(String... ids) { this.scanBlockIds = Set.of(ids); return this; }
        public Builder timeLimit(int seconds) { this.timeLimit = seconds; return this; }
        public Builder canRepeat(boolean v) { this.canRepeat = v; return this; }
        public Builder shareProgress(boolean v) { this.shareProgress = v; return this; }
        public Builder tags(String... t) { this.tags = List.of(t); return this; }
        /**
         * 声明任务对停电计时器的影响。供自适应刷新概率使用：
         * computeUrgencyMultiplier 从 deltaSeconds 派生阈值，未来改 delta 自动调整曲线。
         * 维护约定：时间 delta 必须在注册时声明，不要在 onComplete 写魔法数字。
         */
        public Builder timeImpact(TimeImpact.TimeAxis axis, int deltaSeconds) {
            this.timeImpact = new TimeImpact(axis, deltaSeconds);
            return this;
        }

        public Builder onAssign(BiConsumer<Player, TaskInstance> h) { this.onAssignHandler = h; return this; }
        public Builder onComplete(BiConsumer<Player, TaskInstance> h) { this.onCompleteHandler = h; return this; }
        public Builder onRemove(BiConsumer<Player, TaskInstance> h) { this.onRemoveHandler = h; return this; }
        public Builder onFail(BiConsumer<Player, TaskInstance> h) { this.onFailHandler = h; return this; }
        /** 注册任务道具回收回调。在任务被取消/隐藏时调用，用于扫描玩家背包移除带
         *  habitrain_grant 标签的道具。成功完成路径不调用（玩家保留道具作为奖励）。 */
        public Builder onReclaim(BiConsumer<Player, TaskInstance> h) { this.onReclaimHandler = h; return this; }
        public Builder completionChecker(BiFunction<Player, TaskInstance, Boolean> h) { this.completionChecker = h; return this; }
        public Builder onTick(BiConsumer<Player, TaskInstance> h) { this.tickHandler = h; return this; }
        public Builder canAssign(BiPredicate<Player, TaskInstance> h) { this.canAssignPredicate = h; return this; }
        public Builder onProgressUpdate(ProgressUpdateHandler h) { this.onProgressUpdateHandler = h; return this; }

        public TaskDefinition build() { return new TaskDefinition(this); }
    }
}
