package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SRERoleWorldComponent;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SRE 4.3.0 persisted only ResourceLocation#getPath. New writes keep the full
 * identifier, while reads accept both the full ID and old path-only data.
 */
@Mixin(value = SRERoleWorldComponent.class, remap = false)
public abstract class SRERoleWorldComponentIdMixin {
    @Redirect(
            method = "writeToNbt",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/resources/ResourceLocation;getPath()Ljava/lang/String;",
                    remap = true
            )
    )
    private String habitrain$writeFullRoleId(ResourceLocation id) {
        return id.toString();
    }

    @Inject(method = "getRoleFromPath", at = @At("HEAD"), cancellable = true)
    private void habitrain$readFullOrLegacyRoleId(
            String stored, CallbackInfoReturnable<SRERole> cir) {
        cir.setReturnValue(SreRoleOverrideResolver.resolveStoredId(stored));
    }
}
