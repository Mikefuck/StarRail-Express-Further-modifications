package com.habitrain.core.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SRETrainWorldComponent;
import io.wifi.starrailexpress.client.SREClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Protect shared train queries used by wheels, scenery and ambient effects during disconnect. */
@Mixin(value = SREClient.class, remap = false)
public abstract class SREClientTrainStateMixin {
    @WrapOperation(method = {"getTrainSpeed", "isTrainMoving"}, at = @At(value = "INVOKE",
            target = "Lio/wifi/starrailexpress/cca/SRETrainWorldComponent;getSpeed()I"))
    private static int habitrain$speedDuringDisconnect(SRETrainWorldComponent train, Operation<Integer> original) {
        return train == null ? 0 : original.call(train);
    }

    @WrapOperation(method = "isTrainMoving", at = @At(value = "INVOKE",
            target = "Lio/wifi/starrailexpress/cca/SREGameWorldComponent;isRunning()Z"))
    private static boolean habitrain$runningDuringDisconnect(SREGameWorldComponent game, Operation<Boolean> original) {
        return game != null && original.call(game);
    }
}
