package com.habitrain.core.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
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

    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|TaskDefinition");

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
    /**
     * 是否属于「可派发池」。默认 true。
     * <p>false 用于登记型定义（原版 SRE 任务的空壳镜像，见
     * {@code SREGameModeBase#registerBuiltin}）：它们需要出现在
     * {@link TaskRegistry} 里以便按 fullId 查询/配置，但绝不能进入 DLC 派发池。
     * <p>这是「注册表成员」与「派发池成员」两个分区的显式分界，取代原先仅靠
     * 硬编码 ID 清单（{@code GenerateTaskMixin.BUILTIN_SRE_TASK_IDS}）区分的做法。
     */
    private final boolean poolEligible;
    private final List<String> tags;
    /**
     * 旧扩展元数据，仅为兼容保留；<b>Core 不再消费此值，设置它不产生任何运行时效果</b>
     *（审核 B12）。
     */
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
    /** 审核 B5：无 TaskInstance 场景的专用谓词；为 null 时回退 canAssignPredicate。 */
    private final java.util.function.Predicate<Player> canAssignWithoutInstancePredicate;
    private final ProgressUpdateHandler onProgressUpdateHandler;

    @FunctionalInterface
    public interface ProgressUpdateHandler {
        void onProgressUpdate(Player player, TaskInstance task, int oldProgress);
    }

    /**
     * 旧模式的时间影响声明，仅为已有扩展源码兼容保留。
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
        this.poolEligible = builder.poolEligible;
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
        this.canAssignWithoutInstancePredicate = builder.canAssignWithoutInstancePredicate;
        this.onProgressUpdateHandler = builder.onProgressUpdateHandler;
    }

    /**
     * 去重 + 拒绝 {@code null} 的集合化辅助（审核 C6）。
     * 旧实现用 {@code Set.of(...)}，重复元素会抛出信息不明确的
     * {@code IllegalArgumentException: duplicate element}。
     */
    private static <T> Set<T> requireNoNulls(T[] values, String what) {
        LinkedHashSet<T> set = new LinkedHashSet<>();
        if (values == null) {
            return set;
        }
        for (T value : values) {
            if (value == null) {
                throw new IllegalArgumentException(what + " must not contain null elements");
            }
            set.add(value);
        }
        return set;
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
    /** @see #poolEligible 字段说明；false = 只登记、不派发。 */
    public boolean isPoolEligible() { return poolEligible; }
    public List<String> getTags() { return Collections.unmodifiableList(tags); }
    /**
     * 旧扩展元数据（可能为 {@code null}），不再影响 Core 任务权重或计时。
     *
     * @deprecated 审核 B12：该值不产生任何运行时效果，会在 2.0.12 移除。
     */
    @Deprecated(forRemoval = true, since = "2.0.11")
    public @org.jetbrains.annotations.Nullable TimeImpact getTimeImpact() { return timeImpact; }

    // --- Callback dispatch ---
    public void onAssign(Player player, TaskInstance instance) {
        if (instance != null) {
            instance.bindOwner(player);
        }
        if (onAssignHandler != null) {
            onAssignHandler.accept(player, instance);
        }
        if (instance != null && player instanceof ServerPlayer serverPlayer) {
            GameModeRegistry.getActiveForLevel(serverPlayer.serverLevel()).ifPresent(mode ->
                    mode.onTaskAssign(serverPlayer, instance));
        }
    }
    public void onComplete(Player player, TaskInstance instance) { if (onCompleteHandler != null) onCompleteHandler.accept(player, instance); }
    public void onRemove(Player player, TaskInstance instance) { if (onRemoveHandler != null) onRemoveHandler.accept(player, instance); }
    public void onFail(Player player, TaskInstance instance) { if (onFailHandler != null) onFailHandler.accept(player, instance); }
    /** 回收发放的物理道具。仅在任务被取消/隐藏路径调用，不在成功完成路径调用。 */
    public void onReclaim(Player player, TaskInstance instance) { if (onReclaimHandler != null) onReclaimHandler.accept(player, instance); }
    /**
     * 完成判定。扩展的 completionChecker 返回 null 时视为「未完成」，
     * 避免自动拆箱 NPE 在每个 tick 抛进 {@link TaskInstance#tick}。
     */
    public boolean checkCompletion(Player player, TaskInstance instance) {
        if (completionChecker == null) return instance.isFulfilled();
        Boolean result = completionChecker.apply(player, instance);
        return result != null && result;
    }
    public void onTick(Player player, TaskInstance instance) { if (tickHandler != null) tickHandler.accept(player, instance); }
    public boolean canAssign(Player player, TaskInstance instance) { if (canAssignPredicate != null) return canAssignPredicate.test(player, instance); return true; }

    /**
     * 无 {@link TaskInstance} 时的判定重载。
     *
     * <p><b>审核 B5</b>：旧注释称本重载「避免传入 null」，但实现恰恰是把 {@code null}
     * 交给下游的 {@link BiPredicate}——任何在谓词里直接用 {@code instance} 的实现都会 NPE。
     * 现在优先使用 {@link Builder#canAssignWithoutInstance(java.util.function.Predicate)}
     * 注册的专用谓词；未注册时<b>不再</b>用 null 调用二元谓词，而是保守返回 {@code true}
     * （没有 instance 时无法判定，交给调用方自行处理）。</p>
     */
    public boolean canAssign(Player player) {
        if (canAssignWithoutInstancePredicate != null) return canAssignWithoutInstancePredicate.test(player);
        if (canAssignPredicate != null) {
            LOGGER.warn("TaskDefinition.canAssign(player) called without an instance for {}; the BiPredicate is skipped. "
                    + "Register canAssignWithoutInstance(Predicate<Player>) instead.", getFullId());
        }
        return true;
    }
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
        private boolean poolEligible = true;
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
        private java.util.function.Predicate<Player> canAssignWithoutInstancePredicate;
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
        /**
         * 登记要扫描的方块。
         *
         * <p>审核 C6：旧实现用 {@code Set.of(...)}，传入重复元素会抛
         * {@code IllegalArgumentException: duplicate element}（信息不指向「你传了重复方块」），
         * 传入 {@code null} 元素抛 NPE。现在改为去重的 {@link java.util.LinkedHashSet}，
         * 并对 {@code null} 给出明确提示。</p>
         */
        public Builder scanBlocks(Block... blocks) {
            this.scanBlocks = requireNoNulls(blocks, "scanBlocks");
            return this;
        }

        /** 登记要扫描的方块 ID（同 {@link #scanBlocks(Block...)}：去重、拒绝 null）。 */
        public Builder scanBlockIds(String... ids) {
            this.scanBlockIds = requireNoNulls(ids, "scanBlockIds");
            return this;
        }
        public Builder timeLimit(int seconds) { this.timeLimit = seconds; return this; }
        public Builder canRepeat(boolean v) { this.canRepeat = v; return this; }
        public Builder shareProgress(boolean v) { this.shareProgress = v; return this; }
        /**
         * 排除出 DLC 派发池（默认 true = 可派发）。
         * 仅用于登记型定义：需要被 {@link TaskRegistry} 收录以便查询/配置，
         * 但不应被任务池选中派发给玩家（如原版 SRE 任务的空壳镜像）。
         */
        public Builder poolEligible(boolean v) { this.poolEligible = v; return this; }
        public Builder tags(String... t) { this.tags = List.of(t); return this; }
        /**
         * 兼容旧扩展的时间影响元数据。
         *
         * <p><b>本方法不产生任何运行时效果</b>（审核 B12）：Core 已不再消费该值。
         *
         * @deprecated 会在 2.0.12 移除；保留仅为已有扩展源码可编译。
         */
        @Deprecated(forRemoval = true, since = "2.0.11")
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

        /**
         * 无 {@link TaskInstance} 场景的判定谓词（审核 B5）。设置后
         * {@link TaskDefinition#canAssign(Player)} 使用它，避免把 {@code null} 交给二元谓词。
         */
        public Builder canAssignWithoutInstance(java.util.function.Predicate<Player> h) {
            this.canAssignWithoutInstancePredicate = h;
            return this;
        }
        public Builder onProgressUpdate(ProgressUpdateHandler h) { this.onProgressUpdateHandler = h; return this; }

        public TaskDefinition build() { return new TaskDefinition(this); }
    }
}
