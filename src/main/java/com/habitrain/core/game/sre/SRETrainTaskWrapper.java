package com.habitrain.core.game.sre;

import com.habitrain.core.api.TaskInstance;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent.TrainTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * 适配器 — 将 {@link TaskInstance} 包装为 SRE 的 {@link TrainTask}。
 * 使 API 层的 TaskInstance 不直接依赖 SRE 接口，保持 API 层干净。
 */
public class SRETrainTaskWrapper implements TrainTask {

    private final TaskInstance instance;

    public SRETrainTaskWrapper(TaskInstance instance) {
        this.instance = instance;
    }

    public TaskInstance unwrap() {
        return instance;
    }

    @Override
    public boolean isFulfilled(Player player) {
        return instance.isFulfilled();
    }

    @Override
    public String getCustomTaskId() {
        return instance.getFullId();
    }

    @Override
    public String getName() {
        return instance.getName();
    }

    @Override
    public SREPlayerTaskComponent.Task getType() {
        SREPlayerTaskComponent.Task custom = TaskEnumHelper.getCustom();
        return custom != null ? custom : SREPlayerTaskComponent.Task.SLEEP;
    }

    public CompoundTag toNbt() {
        CompoundTag nbt = instance.toNbt();
        var customType = TaskEnumHelper.getCustom();
        if (customType != null) {
            nbt.putInt("type", customType.ordinal());
        }
        return nbt;
    }
}
