package com.habitrain.core.role.override;

import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import io.wifi.starrailexpress.api.SRERole;

import java.util.List;

public final class RoleOverrideFilter {
    private RoleOverrideFilter() {}

    /** Returns a list where replaced targets are removed and replacement roles are appended if not already present. */
    public static List<SRERole> apply(List<SRERole> roles) {
        return SreRoleOverrideResolver.visibleRegistryRoles(roles);
    }
}
