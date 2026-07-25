package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.role.override.RoleOverrideFilter;
import io.wifi.starrailexpress.api.SRERole;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mixin into Noellesroles.getAllRolesSorted to filter out replaced roles
 * and inject replacement roles. This affects the role introduction screen,
 * role roster, and any other UI that displays the full role list.
 */
@Mixin(targets = "org.agmas.noellesroles.Noellesroles", remap = false)
public class NoellesrolesGetAllRolesMixin {

    @Inject(method = "getAllRolesSorted(Z)Ljava/util/List;", at = @At("RETURN"), cancellable = true)
    private static void filterReplacedRoles(boolean includeDisabled, CallbackInfoReturnable<List<SRERole>> cir) {
        List<SRERole> original = cir.getReturnValue();
        if (original == null || original.isEmpty()) return;
        cir.setReturnValue(RoleOverrideFilter.apply(original));
    }
}
