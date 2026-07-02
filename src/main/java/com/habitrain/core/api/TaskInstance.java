package com.habitrain.core.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 任务运行时实例 — 取代 HabiTaskInstance。
 * 新增: 计时器支持、进度回调分发。
 *
 * 注意: 不直接实现 SRE 的 TrainTask 接口，而是通过 {@code SRETrainTaskWrapper} 适配。
 */
public class TaskInstance {

    private final TaskDefinition definition;
    private boolean fulfilled = false;
    private int progress = 0;
    private int maxProgress = 1;

    // 限时任务计时 (tick 数)
    private int elapsedTicks = 0;
    private boolean failed = false;

    public TaskInstance(TaskDefinition definition) {
        this.definition = definition;
    }

    public TaskDefinition getDefinition() { return definition; }
    public String getFullId() { return definition.getFullId(); }
    public int getProgress() { return progress; }
    public int getMaxProgress() { return maxProgress; }
    public int getElapsedTicks() { return elapsedTicks; }
    public boolean isFulfilled() { return fulfilled; }
    public boolean isFailed() { return failed; }

    public void setProgress(int progress) {
        int old = this.progress;
        this.progress = progress;
        if (old != progress) {
            definition.onProgressUpdate(null, this, old);
        }
    }

    public void setMaxProgress(int maxProgress) { this.maxProgress = maxProgress; }
    public void setFulfilled(boolean fulfilled) { this.fulfilled = fulfilled; }

    public void markFailed() {
        this.failed = true;
        this.fulfilled = true;
    }

    /**
     * 每个服务端 tick 调用一次。
     * 处理: tick 回调 → 计时器 → 完成检测 → 超时检测。
     */
    public void tick(Player player) {
        if (fulfilled) return;

        // 限时检测
        if (definition.getTimeLimit() > 0) {
            elapsedTicks++;
            if (elapsedTicks >= definition.getTimeLimit() * 20) {
                markFailed();
                definition.onFail(player, this);
                return;
            }
        }

        // 调用 tick 回调
        definition.onTick(player, this);

        // 完成检测
        if (definition.checkCompletion(player, this)) {
            this.fulfilled = true;
            if (player instanceof ServerPlayer sp) {
                definition.onComplete(sp, this);
            }
        }
    }

    public String getName() { return definition.getDisplayName(); }
    public String getCustomTaskId() { return definition.getFullId(); }

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
        instance.maxProgress = nbt.getInt("maxProgress");
        instance.elapsedTicks = nbt.getInt("elapsedTicks");
        return instance;
    }
}
