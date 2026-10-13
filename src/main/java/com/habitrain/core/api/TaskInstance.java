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
    public enum State { ACTIVE, SUCCEEDED, FAILED, CANCELLED }
    private State state = State.ACTIVE;
    // Legacy setFulfilled is a completion request; the mode may still veto it this tick.
    private boolean completionRequested;
    private boolean terminalNotified;
    private boolean completionClaimed;
    private boolean ticking;
    private int progress = 0;
    private int maxProgress = 1;
    private int elapsedTicks = 0;
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
    /** Legacy ended/requested flag; use isSucceeded() before issuing rewards. */
    public boolean isFulfilled() { return state != State.ACTIVE || completionRequested; }
    public boolean isFailed() { return state == State.FAILED; }
    public boolean isSucceeded() { return state == State.SUCCEEDED; }
    public State getState() { return state; }

    /** Claims server settlement exactly once, including reentrant mode callbacks. */
    public boolean claimCompletion() {
        if (state != State.SUCCEEDED || completionClaimed) return false;
        completionClaimed = true;
        return true;
    }

    public void cancel() {
        if (state == State.ACTIVE) {
            state = State.CANCELLED;
            completionRequested = false;
        }
    }

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
     * <p>回调期间的重入仍写入进度，只抑制再次通知（{@code DEBUG} 记录），
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
                LOGGER.debug("TaskInstance.setProgress({}) on {} wrote progress without notifying: re-entrant callback",
                        progress, getFullId());
                return;
            }
            notifyProgress(p, old, () -> p instanceof ServerPlayer sp
                    ? GameModeRegistry.getActiveForLevel(sp.serverLevel()).orElse(null) : null);
        }
    }

    void notifyProgress(Player player, int oldProgress, java.util.function.Supplier<GameMode> modeLookup) {
        if (inCallback) return;
        inCallback = true;
        try {
            definition.onProgressUpdate(player, this, oldProgress);
            if (state != State.ACTIVE) return;
            GameMode mode = modeLookup.get();
            if (mode != null) mode.onTaskProgressChange(player instanceof ServerPlayer sp ? sp : null, this, oldProgress);
        } finally {
            inCallback = false;
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
    public void setFulfilled(boolean fulfilled) {
        if (state == State.ACTIVE) this.completionRequested = fulfilled;
    }

    public void markFailed() {
        if (state != State.ACTIVE) return;
        this.state = State.FAILED;
        this.completionRequested = false;
        if (!ticking && ownerPlayer != null) notifyTerminal(ownerPlayer);
    }

    /**
     * Called once per server tick.
     */
    public void tick(Player player) {
        tick(player, () -> player instanceof ServerPlayer sp
                ? GameModeRegistry.getActiveForLevel(sp.serverLevel()).orElse(null) : null);
    }

    void tick(Player player, java.util.function.Supplier<GameMode> modeLookup) {
        if (ticking) return;
        if (state != State.ACTIVE) {
            notifyTerminal(player);
            return;
        }

        // 记录归属玩家，tick 外 setProgress 可回退使用
        bindOwner(player);
        ticking = true;
        try {
            if (definition.getTimeLimit() > 0) {
                elapsedTicks++;
                if (elapsedTicks >= (long) definition.getTimeLimit() * 20L) {
                    markFailed();
                    return;
                }
            }

            definition.onTick(player, this);
            if (state != State.ACTIVE) return;

            ServerPlayer serverPlayer = player instanceof ServerPlayer sp ? sp : null;
            GameMode activeMode = modeLookup.get();
            if (activeMode != null) {
                activeMode.onTaskTick(serverPlayer, this);
                if (state != State.ACTIVE) return;
            }

            boolean completed = definition.checkCompletion(player, this);
            if (state != State.ACTIVE) return;
            if (activeMode != null) {
                completed = activeMode.overrideCompletionCheck(serverPlayer, this).orElse(completed);
                if (state != State.ACTIVE) return;
            }

            if (completed) {
                this.state = State.SUCCEEDED;
                this.completionRequested = false;
            } else {
                // completion override=false 时撤销本 tick 内由任务逻辑写入的 fulfilled，
                // 确保模式覆盖结果真正控制完成状态。
                this.completionRequested = false;
            }
        } finally {
            notifyTerminal(player);
            ticking = false;
            // 恢复为 owner，tick 外 setProgress 仍可派发回调
            progressUpdatePlayer = ownerPlayer;
        }
    }

    private void notifyTerminal(Player player) {
        if (terminalNotified || (state != State.SUCCEEDED && state != State.FAILED)) return;
        terminalNotified = true;
        try {
            if (state == State.FAILED) definition.onFail(player, this);
            else definition.onComplete(player, this);
        } catch (Throwable t) {
            LOGGER.error("Task terminal callback failed: {} state={}", getFullId(), state, t);
        }
    }

    public String getName() { return definition.getDisplayName(); }

    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("customId", definition.getFullId());
        nbt.putString("customName", definition.getDisplayName());
        nbt.putString("state", state.name());
        nbt.putBoolean("fulfilled", isFulfilled());
        nbt.putBoolean("failed", isFailed());
        nbt.putBoolean("completionRequested", completionRequested);
        nbt.putBoolean("terminalNotified", terminalNotified);
        nbt.putBoolean("completionClaimed", completionClaimed);
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
        if (nbt.contains("state")) {
            try { instance.state = State.valueOf(nbt.getString("state")); }
            catch (IllegalArgumentException ignored) { instance.state = State.CANCELLED; }
            instance.completionRequested = instance.state == State.ACTIVE && nbt.getBoolean("completionRequested");
            instance.terminalNotified = nbt.getBoolean("terminalNotified");
            instance.completionClaimed = nbt.getBoolean("completionClaimed");
        } else {
            instance.state = nbt.getBoolean("failed") ? State.FAILED
                    : nbt.getBoolean("fulfilled") ? State.SUCCEEDED : State.ACTIVE;
            // Old terminal snapshots have already run their definition callback.
            instance.terminalNotified = instance.state != State.ACTIVE;
        }
        instance.setMaxProgress(nbt.getInt("maxProgress"));
        instance.progress = Math.max(0, Math.min(instance.getMaxProgress(), nbt.getInt("progress")));
        instance.elapsedTicks = Math.max(0, nbt.getInt("elapsedTicks"));
        return instance;
    }
}
