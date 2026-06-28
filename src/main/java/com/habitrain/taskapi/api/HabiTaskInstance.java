package com.habitrain.taskapi.api;

import com.habitrain.taskapi.impl.HabiTaskManager;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent.TrainTask;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent.Task;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 任务运行时实例 - 每个玩家被分配任务后创建
 * 实现原版 {@link TrainTask} 接口以便集成到原版任务系统中
 *
 * 生命周期:
 * 1. 创建: 由 GenerateTaskMixin 或 DLC模组创建
 * 2. Tick: 服务端每tick调用 tick() 方法 (通过 serverTick → TrainTask.tick)
 * 3. 完成: tick() 中检测到完成条件满足 → 触发完成回调 → 标记 fulfilled
 * 4. 回收: 原版 serverTick 检测到 fulfilled → 发放奖励 → 移除任务
 */
public class HabiTaskInstance implements TrainTask {
    private final HabiTaskDefinition definition;
    private boolean fulfilled = false;
    private int progress = 0;
    private int maxProgress = 1;

    public HabiTaskInstance(HabiTaskDefinition definition) {
        this.definition = definition;
    }

    public HabiTaskDefinition getDefinition() {
        return definition;
    }

    public String getFullId() {
        return definition.getFullId();
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public int getMaxProgress() {
        return maxProgress;
    }

    public void setMaxProgress(int maxProgress) {
        this.maxProgress = maxProgress;
    }

    /**
     * 设置任务完成状态 (通过外部逻辑调用)
     * 注意: 这不覆盖 {@link TrainTask} 接口，是独立的自定义方法
     */
    public void setFulfilled(boolean fulfilled) {
        this.fulfilled = fulfilled;
    }

    public boolean isFulfilled() {
        return fulfilled;
    }

    @Override
    public void tick(@NotNull Player player) {
        if (fulfilled) return;

        // 调用DLC模组注册的tick回调 (自定义进度逻辑)
        definition.onTick(player, this);

        // 调用DLC模组注册的完成检测
        if (definition.checkCompletion(player, this)) {
            this.fulfilled = true;

            // 立即触发DLC模组的完成回调
            if (player instanceof ServerPlayer sp) {
                definition.onComplete(sp, this);
                HabiTaskManager.getInstance().handleTaskCompletion(sp, this);
            }
        }
    }

    @Override
    public boolean isFulfilled(Player player) {
        return fulfilled;
    }

    @Override
    public String getName() {
        // 返回 taskId（不含 modId 前缀），用于 SRE 的任务弹出窗口：
        // Component.translatable("task." + getName())
        // 例：taskId="look_my_eyes" → 翻译键 "task.look_my_eyes"
        return definition.getTaskId();
    }

    @Override
    public String getCustomTaskId() {
        return definition.getFullId();
    }

    /** 缓存CUSTOM枚举值，避免反复反射查找 */
    private static Task customEnumCache = null;
    private static boolean customEnumChecked = false;

    /** 动态获取Task.CUSTOM枚举值，兼容不同SRE版本 */
    private static Task getCustomTaskEnum() {
        if (!customEnumChecked) {
            try {
                customEnumCache = Task.valueOf("CUSTOM");
            } catch (IllegalArgumentException e) {
                customEnumCache = null;
            }
            customEnumChecked = true;
        }
        return customEnumCache;
    }

    @Override
    public Task getType() {
        Task custom = getCustomTaskEnum();
        // 防御性返回：如果CUSTOM不存在(旧版SRE)，不应创建此实例
        return custom != null ? custom : Task.SLEEP;
    }

    @Override
    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        Task custom = getCustomTaskEnum();
        // 使用 SLEEP.ordinal() 代替 -1，确保客户端能正确反序列化
        // 否则 readFromSyncNbt 中 type < 0 会跳过该任务，导致HUD不显示
        nbt.putInt("type", custom != null ? custom.ordinal() : Task.SLEEP.ordinal());
        nbt.putString("customId", definition.getFullId());
        nbt.putString("customName", definition.getDisplayName());
        nbt.putBoolean("fulfilled", this.fulfilled);
        nbt.putInt("progress", this.progress);
        nbt.putInt("maxProgress", this.maxProgress);
        return nbt;
    }

    /**
     * 从NBT恢复任务实例
     */
    public static HabiTaskInstance fromNbt(CompoundTag nbt) {
        String customId = nbt.getString("customId");
        HabiTaskDefinition def = HabiTaskRegistry.get(customId);
        if (def == null) return null;

        HabiTaskInstance instance = new HabiTaskInstance(def);
        instance.fulfilled = nbt.getBoolean("fulfilled");
        instance.progress = nbt.getInt("progress");
        instance.maxProgress = nbt.getInt("maxProgress");
        return instance;
    }
}
