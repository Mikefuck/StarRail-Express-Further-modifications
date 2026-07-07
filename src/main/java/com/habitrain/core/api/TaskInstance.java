package com.habitrain.core.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runtime task instance that stores progress and lifecycle state.
 *
 * 性能/可维护性：本类还维护一份"任务发放给玩家的物理道具"清单（grantedItems），
 * 供 ItemReclaimHelper 在任务被取消/隐藏时回收。
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

    /** 任务发放给玩家的物理道具清单（仅服务端维护，NBT 打过 habitrain_grant 标签）。
     *  供 ItemReclaimHelper 在任务被取消/隐藏时扫描回收。 */
    private final List<ItemStack> grantedItems = new ArrayList<>();

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

    /** 记录任务发放给玩家的物理道具。在 onComplete 调用 giveRandomBackpackItem 后存入。 */
    public void addGrantedItem(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) grantedItems.add(stack.copy());
    }
    /** 只读视图，供 ItemReclaimHelper 使用。 */
    public List<ItemStack> getGrantedItems() { return Collections.unmodifiableList(grantedItems); }

    public void setProgress(int progress) {
        int old = this.progress;
        this.progress = progress;
        if (old != progress) {
            // tick 内 progressUpdatePlayer 是当前 tick 的 player；
            // tick 外调用时回退到 ownerPlayer，避免回调静默丢失。
            Player p = progressUpdatePlayer != null ? progressUpdatePlayer : ownerPlayer;
            if (p != null) {
                definition.onProgressUpdate(p, this, old);
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
        this.ownerPlayer = player;
        progressUpdatePlayer = player;
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

            if (definition.checkCompletion(player, this)) {
                this.fulfilled = true;
                // 与 onFail 保持对称：对所有 Player 调用 onComplete。
                // DLC 回调内部可自行用 instanceof ServerPlayer 判断是否在服务端上下文。
                definition.onComplete(player, this);
            }
        } finally {
            // 恢复为 owner，tick 外 setProgress 仍可派发回调
            progressUpdatePlayer = ownerPlayer;
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
