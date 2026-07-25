package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.ReplaceRoleDefinition;

/**
 * Stub — will be fully implemented in Task 2.
 */
public class RoleOverrideRegistry {
    public static final RoleOverrideRegistry INSTANCE = new RoleOverrideRegistry();

    private RoleOverrideRegistry() {}

    public static void init() {
        // Stub: no-op until Task 2
    }

    public void registerReplace(ReplaceRoleDefinition def) {
        // Stub: no-op until Task 2
    }

    public void registerModify(ModifyRoleDefinition def) {
        // Stub: no-op until Task 2
    }
}
