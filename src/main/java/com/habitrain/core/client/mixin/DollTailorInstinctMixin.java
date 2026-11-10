package com.habitrain.core.client.mixin;

import com.habitrain.core.client.tailor.DollTailorClient;
import com.habitrain.core.game.sre.role.tailor.TailorDollEntity;
import io.wifi.starrailexpress.client.SREClient;
import net.minecraft.world.entity.Entity;
import org.agmas.noellesroles.client.utils.InstinctManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.OptionalInt;

/** Runs before role-specific instinct rules and before stale per-UUID caches can expose the original. */
@Mixin(value = {InstinctManager.class, SREClient.class}, remap = false)
public abstract class DollTailorInstinctMixin {
    @Inject(method = "getInstinctHighlight", at = @At("HEAD"), cancellable = true)
    private static void habitrain$substitute(Entity entity, CallbackInfoReturnable<OptionalInt> cir) {
        if (DollTailorClient.resolving()) return;
        if (DollTailorClient.hidden(entity)) cir.setReturnValue(OptionalInt.empty());
        else if (entity instanceof TailorDollEntity doll) cir.setReturnValue(DollTailorClient.highlight(doll));
    }
}
