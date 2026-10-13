package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.RoleOverlayAccessor;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

/** Applies provider-owned full and simple description transforms for MODIFY. */
@Mixin(SRERole.class)
public class SRERoleDescriptionMixin {

    @Inject(method = "getDescription", at = @At("RETURN"), cancellable = true)
    private void habitrain$patchDescription(CallbackInfoReturnable<Component> cir) {
        SRERole role = (SRERole) (Object) this;
        CompiledModifyOverlay overlay = RoleOverlayAccessor.currentOverlay(role);
        if (overlay != null && overlay.descriptionPatch() != null) {
            cir.setReturnValue(Objects.requireNonNull(
                    overlay.descriptionPatch().apply(role, cir.getReturnValue()),
                    "v2 descriptionPatch returned null"));
            return;
        }
    }

    @Inject(method = "getSimpleDescription", at = @At("RETURN"), cancellable = true)
    private void habitrain$patchSimpleDescription(CallbackInfoReturnable<Component> cir) {
        SRERole role = (SRERole) (Object) this;
        CompiledModifyOverlay overlay = RoleOverlayAccessor.currentOverlay(role);
        if (overlay != null && overlay.simpleDescriptionPatch() != null) {
            cir.setReturnValue(Objects.requireNonNull(
                    overlay.simpleDescriptionPatch().apply(role, cir.getReturnValue()),
                    "v2 simpleDescriptionPatch returned null"));
            return;
        }
    }

    @Inject(method = "hasSimpleDescription", at = @At("RETURN"), cancellable = true)
    private void habitrain$hasPatchedSimpleDescription(CallbackInfoReturnable<Boolean> cir) {
        SRERole role = (SRERole) (Object) this;
        CompiledModifyOverlay overlay = RoleOverlayAccessor.currentOverlay(role);
        if (overlay != null && overlay.simpleDescriptionPatch() != null) {
            cir.setReturnValue(true);
            return;
        }
    }

}
