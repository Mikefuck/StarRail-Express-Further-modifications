package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.RoleOverrideEntry;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;

/**
 * Stub — will be fully implemented in Task 2.
 */
public class RoleOverrideEngine {
    private static final RoleOverrideEngine INSTANCE = new RoleOverrideEngine();

    private RoleOverrideEngine() {}

    public static RoleOverrideEngine getInstance() {
        return INSTANCE;
    }

    public Collection<RoleOverrideEntry> getEffectiveEntries() {
        return Collections.emptyList();
    }

    public boolean isReplaced(ResourceLocation targetRoleId) {
        return false;
    }

    public @Nullable SRERole getReplacement(ResourceLocation targetRoleId) {
        return null;
    }

    public boolean isModified(ResourceLocation targetRoleId) {
        return false;
    }

    public @Nullable ModifyRoleDefinition getActiveModify(ResourceLocation targetRoleId) {
        return null;
    }
}
