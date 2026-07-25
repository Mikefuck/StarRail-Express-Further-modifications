package com.habitrain.core.game.sre.mixin;

import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Removes SRE's blackout-only fixed task interval special-case so blackout uses the
 * same dynamic cooldown path as vanilla train / murder mode.
 *
 * <p>SRE originally did:
 * <pre>
 *   boolean isBlackout = "habitrain:blackout".equals(identifier);
 *   min = isBlackout ? MIN_TASK_COOLDOWN : getDynamicMinTaskCooldown(elapsed);
 *   max = isBlackout ? MAX_TASK_COOLDOWN : getDynamicMaxTaskCooldown(elapsed);
 *   // and skipped 0.7f minigame speedup + rotation slowdown when isBlackout
 * </pre>
 *
 * <p>Redirects the {@code "habitrain:blackout".equals(...)} check used for that flag
 * so it always evaluates false; other String.equals calls are unchanged.
 */
@Mixin(SREPlayerTaskComponent.class)
public abstract class BlackoutTaskIntervalMixin {

    @Redirect(
            method = "serverTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/String;equals(Ljava/lang/Object;)Z"
            ),
            remap = false
    )
    private boolean habitrain$disableBlackoutTaskIntervalSpecialCase(String self, Object other) {
        // Force the blackout task-interval special-case to false.
        // Pattern in SRE: "habitrain:blackout".equals(gameMode.identifier.toString())
        if ("habitrain:blackout".equals(self)) {
            return false;
        }
        return self.equals(other);
    }
}
