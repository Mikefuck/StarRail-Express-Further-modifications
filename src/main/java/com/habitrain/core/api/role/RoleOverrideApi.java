package com.habitrain.core.api.role;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;

/**
 * The v1 role-override API (REPLACE / MODIFY).
 *
 * <p><b>This is the current stable compatibility API.</b> The v2 Role Extension
 * API ({@code com.habitrain.core.api.role.v2}) is a preview: it covers
 * ADD/MODIFY/REPLACE/ALIAS declaratively but is NOT yet feature-equivalent to
 * v1 (name, descriptions, shops, initial items and win hooks have no v2
 * equivalent — see the v1→v2 migration matrix in {@code docs/API参考手册.md}).
 * Audit 2026-08-15 therefore keeps v1 supported and undeprecated; v2 may
 * supersede it once feature parity and real integration validation land.
 *
 * <p>Partial migration guidance:
 * <ul>
 *   <li>{@link #registerReplace} → {@code REPLACE(NEW_ID_WITH_ALIAS)} (see
 *       {@code com.habitrain.core.api.role.v2.definition.RoleReplacement});</li>
 *   <li>{@link #registerModify} → {@code MODIFY}
 *       ({@code com.habitrain.core.api.role.v2.definition.RolePatch}) for the
 *       covered fields only;</li>
 *   <li>query methods → {@code com.habitrain.core.api.role.v2.RoleCatalogApi}.</li>
 * </ul>
 * Declarations are translated into the v2 unified diagnostic/conflict model by
 * {@code LegacyRoleOverrideTranslator}.
 */
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
