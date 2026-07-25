package com.habitrain.core.role.override;

import io.wifi.starrailexpress.api.SRERole;

import java.util.ArrayList;
import java.util.List;

public final class RoleOverrideFilter {
    private RoleOverrideFilter() {}

    /** Returns a list where replaced targets are removed and replacement roles are appended if not already present. */
    public static List<SRERole> apply(List<SRERole> roles) {
        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        List<SRERole> result = new ArrayList<>(roles.size());
        for (SRERole role : roles) {
            if (engine.isReplaced(role.identifier())) continue;
            result.add(role);
        }
        for (SRERole replacement : engine.getSnapshot().getActiveReplaces().values().stream()
                .map(com.habitrain.core.api.role.ReplaceRoleDefinition::replacementRole).toList()) {
            if (!result.contains(replacement)) {
                result.add(replacement);
            }
        }
        return result;
    }
}
