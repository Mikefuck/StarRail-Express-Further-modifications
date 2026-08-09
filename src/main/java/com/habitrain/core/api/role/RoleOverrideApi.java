package com.habitrain.core.api.role;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;

public final class RoleOverrideApi {
    private RoleOverrideApi() {}

    public static void registerReplace(ReplaceRoleDefinition def) {
        com.habitrain.core.role.override.RoleOverrideRegistry.INSTANCE.registerReplace(def);
    }

    public static void registerModify(ModifyRoleDefinition def) {
        com.habitrain.core.role.override.RoleOverrideRegistry.INSTANCE.registerModify(def);
    }

    /**
     * Builds the canonical role id owned by an integrating mod.
     */
    public static ResourceLocation roleId(String sourceModId, String roleName) {
        Objects.requireNonNull(sourceModId, "sourceModId");
        Objects.requireNonNull(roleName, "roleName");
        String namespace = sourceModId.trim().toLowerCase(Locale.ROOT);
        String path = roleName.trim().toLowerCase(Locale.ROOT);
        if (namespace.isEmpty() || path.isEmpty()) {
            throw new IllegalArgumentException("sourceModId and roleName must not be blank");
        }
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    public static String getEntryId(ReplaceRoleDefinition def) {
        return com.habitrain.core.role.override.RoleOverrideRegistry.entryId(def);
    }

    public static String getEntryId(ModifyRoleDefinition def) {
        return com.habitrain.core.role.override.RoleOverrideRegistry.entryId(def);
    }

    public static Collection<RoleOverrideEntry> getEffectiveEntries() {
        return com.habitrain.core.role.override.RoleOverrideEngine.getInstance().getEffectiveEntries();
    }

    public static boolean isReplaced(ResourceLocation targetRoleId) {
        return com.habitrain.core.role.override.RoleOverrideEngine.getInstance().isReplaced(targetRoleId);
    }

    public static @Nullable io.wifi.starrailexpress.api.SRERole getReplacement(ResourceLocation targetRoleId) {
        return com.habitrain.core.role.override.RoleOverrideEngine.getInstance().getReplacement(targetRoleId);
    }

    public static boolean isModified(ResourceLocation targetRoleId) {
        return com.habitrain.core.role.override.RoleOverrideEngine.getInstance().isModified(targetRoleId);
    }

    public static @Nullable ModifyRoleDefinition getActiveModify(ResourceLocation targetRoleId) {
        return com.habitrain.core.role.override.RoleOverrideEngine.getInstance().getActiveModify(targetRoleId);
    }
}
