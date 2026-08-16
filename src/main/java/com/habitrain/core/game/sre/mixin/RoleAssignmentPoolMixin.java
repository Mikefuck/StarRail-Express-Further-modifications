package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import io.wifi.starrailexpress.api.SRERole;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Mixin into SRE's RoleAssignmentPool to filter out replaced roles
 * from the assignment pool. Wraps the predicate in createInternal
 * to also exclude roles that have been replaced via the override API.
 */
@Mixin(targets = "org.agmas.harpymodloader.modded_murder.RoleAssignmentPool", remap = false)
public class RoleAssignmentPoolMixin {

    /**
     * Intercept the role collection after it's built from TMMRoles.ROLES.values()
     * and before it's filtered by the predicate. Remove replaced roles and add replacements.
     */
    @ModifyVariable(
        method = "createInternal",
        at = @At(value = "STORE", ordinal = 0),
        ordinal = 0
    )
    private static ArrayList<SRERole> filterReplacedRoles(ArrayList<SRERole> roles) {
        if (roles == null || roles.isEmpty()) return roles;
        return new ArrayList<>(com.habitrain.core.role.override.RoleOverrideFilter.apply(roles));
    }

    /**
     * Existing pools are SRE snapshots. Resolve again when they are consumed so
     * a runtime config change cannot leak an old target or inactive replacement.
     */
    @Inject(method = "selectRole", at = @At("RETURN"), cancellable = true)
    private void habitrain$resolveSelectedRole(CallbackInfoReturnable<SRERole> cir) {
        cir.setReturnValue(SreRoleOverrideResolver.resolve(cir.getReturnValue()));
    }

    @Inject(method = "selectRoles", at = @At("RETURN"), cancellable = true)
    private void habitrain$resolveSelectedRoles(int count, CallbackInfoReturnable<List<SRERole>> cir) {
        cir.setReturnValue(SreRoleOverrideResolver.resolveSelection(cir.getReturnValue()));
    }
}
