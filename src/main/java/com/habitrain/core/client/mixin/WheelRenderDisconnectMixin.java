package com.habitrain.core.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.wifi.starrailexpress.cca.SRETrainWorldComponent;
import io.wifi.starrailexpress.client.render.block_entity.WheelBlockEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A disconnect can clear the SRE cache while the last world frame is still rendering. */
@Mixin(value = WheelBlockEntityRenderer.class, remap = false)
public abstract class WheelRenderDisconnectMixin {
    @WrapOperation(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/cca/SRETrainWorldComponent;getTime()F"))
    private static float habitrain$timeDuringDisconnect(SRETrainWorldComponent train, Operation<Float> original) {
        // Guard the captured receiver, not the static field at method entry: the
        // Netty disconnect callback can clear that field between check and use.
        return train == null ? 0.0F : original.call(train);
    }
}
