package com.habitrain.core.client.mixin;

import io.wifi.starrailexpress.SREClientConfig;
import io.wifi.starrailexpress.client.util.TaskInstinctManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;

/**
 * 将上游 TaskInstinctManager 的任务高亮默认值由 true 改为 false（默认不启用任务高亮）。
 */
@Mixin(value = TaskInstinctManager.class, remap = false)
public abstract class TaskInstinctDefaultDisableMixin {

    @Inject(method = "isTaskInstinctTypeShowable", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$defaultDisable(int type, CallbackInfoReturnable<Boolean> cir) {
        HashMap<Integer, Boolean> status = TaskInstinctManager.TASK_STATUS;
        if (status == null) {
            cir.setReturnValue(SREClientConfig.instance().taskStatus.getOrDefault(type, false));
        } else {
            cir.setReturnValue(status.getOrDefault(type, false));
        }
    }
}
