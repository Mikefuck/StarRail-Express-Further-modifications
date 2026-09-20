package com.habitrain.core.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime task instance that stores progress and lifecycle state.
 */
public class TaskInstance {

    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|TaskInstance");

    private final TaskDefinition definition;
    private boolean fulfilled = false;
    private int progress = 0;
    private int maxProgress = 1;
    private int elapsedTicks = 0;
    private boolean failed = false;
    // 审核 B4：回调期重入守卫。setProgress 会同步触发下游回调，若回调里再调用
    // setProgress 旧实现会无限递归（无保护）。
    private boolean inCallback = false;
    // tick 外调用 setProgress() 时用作 onProgressUpdate 回调的 player。
    // tick 内会临时覆盖为当前 tick 的 player，并在 finally 中恢复为 owner。
    private Player progressUpdatePlayer = null;
    // 任务归属玩家（由 onAssign/tick 设置），保证 tick 外 setProgress 也能派发回调。
    private Player ownerPlayer = null;
    // 任务所在维度，用于按维度清理（避免一个世界结束清掉另一个世界的任务）。
    private ResourceKey<Level> dimension = null;

    public TaskInstance(TaskDefinition definition) {
        this.definition = definition;
    }

    public TaskDefinition getDefinition() { return definition; }
    public String getFullId() { return definition.getFullId(); }
    public @org.jetbrains.annotations.Nullable ResourceKey<Level> getDimension() { return dimension; }
    public void setDimension(@org.jetbrains.annotations.Nullable ResourceKey<Level> dimension) { this.dimension = dimension; }
    public int getProgress() { return progress; }
    public int getMaxProgress() { return maxProgress; }
    public boolean isFulfilled() { return fulfilled; }
    public boolean isFailed() { return failed; }

    void bindOwner(Player player) {
        this.ownerPlayer = player;
        this.progressUpdatePlayer = player;
    }

    public void unbindOwner() {
        this.ownerPlayer = null;
        this.progressUpdatePlayer = null;
    }

    /**
     * 写入进度并在变化时同步派发回调。
     *
     * <p><b>审核 B4</b>：回调期间的重入会被忽略（{@code DEBUG} 记录），
     * 避免下游在 {@code onProgressUpdate} 里再调 {@code setProgress} 造成无限递归。
     * 另外当任务没有 owner（{@link #unbindOwner()} 之后）时，回调会被跳过并记录 DEBUG——
     * 旧实现是静默丢弃，下游无法感知。</p>
     */
    public void setProgress(int progress) {
        int old = this.progress;
        this.progress = progress;
        if (old != progress) {
            // tick 内 progressUpdatePlayer 是当前 tick 的 player；
            // tick 外调用时回退到 ownerPlayer，避免回调静默丢失。
            Player p = progressUpdatePlayer != null ? progressUpdatePlayer : ownerPlayer;
            if (p == null) {
                LOGGER.debug("TaskInstance.setProgress({}) on {} has no owner; callbacks skipped",
                        progress, getFullId());
                return;
            }
            if (inCallback) {
                LOGGER.debug("TaskInstance.setProgress({}) on {} ignored: re-entrant during callback",
                        progress, getFullId());
                return;
            }
            inCallback = true;
            try {
                definition.onProgressUpdate(p, this, old);
                if (p instanceof ServerPlayer serverPlayer) {
                    GameModeRegistry.getActiveForLevel(serverPlayer.serverLevel()).ifPresent(mode ->
                            mode.onTaskProgressChange(serverPlayer, this, old));
                }
            } finally {
                inCallback = false;
            }
        }
    }

    /**
     * 写入完成进度阈值。
     *
     * <p><b>审核 B3</b>：{@code maxProgress} 会被静默钳制到 {@code >= 1}
     * （0 → 立即完成、负数 → 永不完成，都会破坏 {@code progress >= maxProgress} 判定）。
     * 现在钳制行为保持不变，但会记录 DEBUG 日志，且 javadoc 显式声明该范围。</p>
     *
     * @param maxProgress 目标进度；小于 1 的值会被钳制为 1
     */
    public void setMaxProgress(int maxProgress) {
        if (maxProgress < 1) {
            LOGGER.debug("TaskInstance.setMaxProgress({}) on {} clamped to 1",
                    maxProgress, definition != null ? definition.getFullId() : "?");
        }
        this.maxProgress = Math.max(1, maxProgress);
    }
    public void setFulfilled(boolean fulfilled) { this.fulfilled = fulfilled; }

    public void markFailed() {
        this.failed = true;
        this.fulfilled = true;
    }

    /**
     * Called once per server tick.
     */
    public void tick(Player player) {
        if (fulfilled) return;

        // 记录归属玩家，tick 外 setProgress 可回退使用
        bindOwner(player);
        try {
            if (definition.getTimeLimit() > 0) {
                elapsedTicks++;
                if (elapsedTicks >= definition.getTimeLimit() * 20) {
                    markFailed();
                    definition.onFail(player, this);
                    return;
                }
            }

            definition.onTick(player, this);

            ServerPlayer serverPlayer = null;
            GameMode activeMode = null;
            if (player instanceof ServerPlayer sp) {
                serverPlayer = sp;
                activeMode = GameModeRegistry.getActiveForLevel(sp.serverLevel()).orElse(null);
                if (activeMode != null) {
                    activeMode.onTaskTick(sp, this);
                }
            }

            boolean completed = definition.checkCompletion(player, this);
            if (serverPlayer != null && activeMode != null) {
                completed = activeMode.overrideCompletionCheck(serverPlayer, this).orElse(completed);
            }

            if (completed) {
                this.fulfilled = true;
                // 与 onFail 保持对称：对所有 Player 调用 onComplete。
                // DLC 回调内部可自行用 instanceof ServerPlayer 判断是否在服务端上下文。
                definition.onComplete(player, this);
            } else if (!failed) {
                // completion override=false 时撤销本 tick 内由任务逻辑写入的 fulfilled，
                // 确保模式覆盖结果真正控制完成状态。
                this.fulfilled = false;
            }
        } finally {
            // 恢复为 owner，tick 外 setProgress 仍可派发回调
            progressUpdatePlayer = ownerPlayer;
        }
    }

    public String getName() { return definition.getDisplayName(); }

    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("customId", definition.getFullId());
        nbt.putString("customName", definition.getDisplayName());
        nbt.putBoolean("fulfilled", this.fulfilled);
        nbt.putBoolean("failed", this.failed);
        nbt.putInt("progress", this.progress);
        nbt.putInt("maxProgress", this.maxProgress);
        nbt.putInt("elapsedTicks", this.elapsedTicks);
        return nbt;
    }

    public static TaskInstance fromNbt(CompoundTag nbt) {
        String customId = nbt.getString("customId");
        TaskDefinition def = TaskRegistry.get(customId);
        if (def == null) return null;

        TaskInstance instance = new TaskInstance(def);
        instance.fulfilled = nbt.getBoolean("fulfilled");
        instance.failed = nbt.getBoolean("failed");
        instance.setMaxProgress(nbt.getInt("maxProgress"));
        instance.progress = Math.max(0, Math.min(instance.getMaxProgress(), nbt.getInt("progress")));
        instance.elapsedTicks = Math.max(0, nbt.getInt("elapsedTicks"));
        if (instance.failed) {
            instance.fulfilled = true;
        }
        return instance;
    }
}
