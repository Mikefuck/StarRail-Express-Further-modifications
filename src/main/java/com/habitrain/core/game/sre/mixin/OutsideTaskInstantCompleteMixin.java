package com.habitrain.core.game.sre.mixin;

import io.wifi.starrailexpress.game.GameConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hardens SRE {@code OutsideTask} against instant / near-instant completion.
 *
 * <p>Root causes this guards against:
 * <ul>
 *   <li>NBT factory {@code new OutsideTask(nbt.getInt("timer"))} with missing key →
 *       {@code time=0} → constructor sets {@code timer = 0 + 6} (~0.3s).</li>
 *   <li>Any path that constructs OutsideTask with a non-positive duration.</li>
 * </ul>
 *
 * <p>The hollow DLC shell {@code habitrain_core:outside} is filtered out via
 * {@link GenerateTaskMixin}; this mixin only protects the real SRE OutsideTask path.
 */
@Mixin(targets = "io.wifi.starrailexpress.cca.SREPlayerTaskComponent$OutsideTask", remap = false)
public class OutsideTaskInstantCompleteMixin {

    @Shadow(remap = false)
    private int timer;

    /**
     * After construction, ensure timer is at least a full outdoor duration.
     * Constructor does {@code timer = time + 6}. If {@code time} was 0 (missing NBT key),
     * force a full {@link GameConstants#OUTSIDE_TASK_DURATION} (+ the usual +6 slack).
     */
    @Inject(method = "<init>(I)V", at = @At("RETURN"), remap = false)
    private void habitrain$ensureMinimumTimer(int time, CallbackInfo ci) {
        int minTimer = GameConstants.OUTSIDE_TASK_DURATION + 6;
        if (this.timer < minTimer) {
            // NBT reconstruct with remaining timer can be smaller than full duration —
            // only force floor when construction used a non-positive base time.
            if (time <= 0) {
                this.timer = minTimer;
            }
        }
    }
}
