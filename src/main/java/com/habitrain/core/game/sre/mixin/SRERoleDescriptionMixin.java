package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.role.override.RoleOverrideEngine;
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
        ModifyRoleDefinition definition = active(role);
        if (definition == null || definition.descriptionPatch().isEmpty()) {
            return;
        }
        cir.setReturnValue(Objects.requireNonNull(
                definition.descriptionPatch().get().apply(role, cir.getReturnValue()),
                "descriptionPatch returned null"));
    }

    @Inject(method = "getSimpleDescription", at = @At("RETURN"), cancellable = true)
    private void habitrain$patchSimpleDescription(CallbackInfoReturnable<Component> cir) {
        SRERole role = (SRERole) (Object) this;
        ModifyRoleDefinition definition = active(role);
        if (definition == null || definition.simpleDescriptionPatch().isEmpty()) {
            return;
        }
        cir.setReturnValue(Objects.requireNonNull(
                definition.simpleDescriptionPatch().get().apply(role, cir.getReturnValue()),
                "simpleDescriptionPatch returned null"));
    }

    @Inject(method = "hasSimpleDescription", at = @At("RETURN"), cancellable = true)
    private void habitrain$hasPatchedSimpleDescription(CallbackInfoReturnable<Boolean> cir) {
        ModifyRoleDefinition definition = active((SRERole) (Object) this);
        if (definition != null && definition.simpleDescriptionPatch().isPresent()) {
            cir.setReturnValue(true);
        }
    }

    private static ModifyRoleDefinition active(SRERole role) {
        return role.identifier() == null
                ? null
                : RoleOverrideEngine.getInstance().getActiveModify(role.identifier());
    }
}
