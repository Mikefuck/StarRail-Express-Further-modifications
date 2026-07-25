package com.habitrain.core.game.sre;

import com.habitrain.core.api.TaskInstance;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent.TrainTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 适配器 — 将 {@link TaskInstance} 包装为 SRE 的 {@link TrainTask}。
 * 使 API 层的 TaskInstance 不直接依赖 SRE 接口，保持 API 层干净。
 */
public class SRETrainTaskWrapper implements TrainTask {

    private static final Logger LOGGER = LoggerFactory.getLogger("SRETrainTaskWrapper");

    private final TaskInstance instance;
    private final SREPlayerTaskComponent.Task typeOverride;

    public SRETrainTaskWrapper(TaskInstance instance) {
        this(instance, null);
    }

    /**
     * @param typeOverride 非空时用指定 Task 枚举槽位，避免与主 DLC 任务(CUSTOM)冲突。
     *                     杀手假任务用此构造函数传一个空闲的原版枚举（如 PRAY）。
     */
    public SRETrainTaskWrapper(TaskInstance instance, SREPlayerTaskComponent.Task typeOverride) {
        this.instance = instance;
        this.typeOverride = typeOverride;
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
        if (typeOverride != null) {
            return typeOverride;
        }
        // S6-004: When CUSTOM isn't available (older SRE version), return null instead of SLEEP.
        // SLEEP conflicts with the vanilla sleep-task slot; null tells callers
        // "no slot available" and they must handle it (e.g. fall back to display-only).
        return TaskEnumHelper.getCustom();
    }

    @Override
    public CompoundTag toNbt() {
        // TaskInstance 写入 customId/customName/progress 等字段；
        // type 必须由 wrapper 补上，否则客户端 readFromSyncNbt 因缺少 type 丢弃该任务，
        // 导致左上角任务栏不显示（服务端完成路径不受影响）。
        CompoundTag nbt = instance.toNbt();
        SREPlayerTaskComponent.Task type = getType();
        if (type != null) {
            nbt.putInt("type", type.ordinal());
        } else {
            LOGGER.warn("SRETrainTaskWrapper.toNbt: getType() is null for task {} — client HUD will drop it",
                    instance.getFullId());
        }
        return nbt;
    }
}
