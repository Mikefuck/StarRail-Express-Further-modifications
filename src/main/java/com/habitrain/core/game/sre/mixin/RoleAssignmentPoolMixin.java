package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.Collection;

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
}
