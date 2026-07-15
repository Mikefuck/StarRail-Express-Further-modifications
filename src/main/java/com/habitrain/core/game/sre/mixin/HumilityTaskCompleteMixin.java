package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.modifier.virtue.HumilityVirtue;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SRE 原版任务完成时触发谦卑附近「谢谢」。
 * 注入 {@code notifyNearbyTaskComplete}（serverTick / completeManicTask 共用）。
 */
@Mixin(SREPlayerTaskComponent.class)
public abstract class HumilityTaskCompleteMixin {

    @Inject(
            method = "notifyNearbyTaskComplete",
            at = @At("HEAD"),
            remap = false
    )
    private static void habitrain$humilityOnSreTaskComplete(ServerPlayer completer, CallbackInfo ci) {
        HumilityVirtue.onTaskComplete(completer);
    }
}
