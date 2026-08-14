package com.habitrain.core.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Runtime task instance that stores progress and lifecycle state.
 */
public class TaskInstance {

    private final TaskDefinition definition;
    private boolean fulfilled = false;
    private int progress = 0;
    private int maxProgress = 1;
    private int elapsedTicks = 0;
    private boolean failed = false;
    // tick 外调用 setProgress() 时用作 onProgressUpdate 回调的 player。
    // tick 内会临时覆盖为当前 tick 的 player，并在 finally 中恢复为 owner。
    private Player progressUpdatePlayer = null;
    // 任务归属玩家（由 onAssign/tick 设置），保证 tick 外 setProgress 也能派发回调。
    private Player ownerPlayer = null;

    public TaskInstance(TaskDefinition definition) {
        this.definition = definition;
    }

    public TaskDefinition getDefinition() { return definition; }
    public String getFullId() { return definition.getFullId(); }
    public int getProgress() { return progress; }
    public int getMaxProgress() { return maxProgress; }
    public boolean isFulfilled() { return fulfilled; }
    public boolean isFailed() { return failed; }

    void bindOwner(Player player) {
        this.ownerPlayer = player;
        this.progressUpdatePlayer = player;
    }

    public void setProgress(int progress) {
        int old = this.progress;
        this.progress = progress;
        if (old != progress) {
            // tick 内 progressUpdatePlayer 是当前 tick 的 player；
            // tick 外调用时回退到 ownerPlayer，避免回调静默丢失。
            Player p = progressUpdatePlayer != null ? progressUpdatePlayer : ownerPlayer;
            if (p != null) {
                definition.onProgressUpdate(p, this, old);
                if (p instanceof ServerPlayer serverPlayer) {
                    GameModeRegistry.getActiveForLevel(serverPlayer.serverLevel()).ifPresent(mode ->
                            mode.onTaskProgressChange(serverPlayer, this, old));
                }
            }
        }
    }

    public void setMaxProgress(int maxProgress) {
        // 防止 0/负值导致 completionChecker 里 progress >= maxProgress 逻辑错乱：
        //   maxProgress=0 → 任务一分配就立即完成
        //   maxProgress<0 → 永不完成
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
        instance.progress = nbt.getInt("progress");
        instance.setMaxProgress(nbt.getInt("maxProgress"));
        instance.elapsedTicks = Math.max(0, nbt.getInt("elapsedTicks"));
        if (instance.failed) {
            instance.fulfilled = true;
        }
        return instance;
    }
}
