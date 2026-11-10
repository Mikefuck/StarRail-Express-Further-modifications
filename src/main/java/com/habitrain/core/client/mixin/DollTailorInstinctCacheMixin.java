package com.habitrain.core.client.mixin;

import com.habitrain.core.client.tailor.DollTailorClient;
import com.habitrain.core.game.sre.role.tailor.TailorDollEntity;
import io.wifi.starrailexpress.client.SREClient;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.OptionalInt;

@Mixin(value = SREClient.class, remap = false)
public abstract class DollTailorInstinctCacheMixin {
    @Inject(method = "getCachedInstinctHighlight", at = @At("HEAD"), cancellable = true)
    private static void habitrain$bypassPropWhitelist(Entity entity, CallbackInfoReturnable<OptionalInt> cir) {
        if (DollTailorClient.hidden(entity)) cir.setReturnValue(OptionalInt.empty());
        else if (entity instanceof TailorDollEntity doll) cir.setReturnValue(DollTailorClient.highlight(doll));
    }
}
