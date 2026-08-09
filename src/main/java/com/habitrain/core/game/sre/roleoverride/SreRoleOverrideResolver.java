package com.habitrain.core.game.sre.roleoverride;

import com.habitrain.core.api.role.ReplaceRoleDefinition;
import com.habitrain.core.role.override.RoleOverrideEngine;
import com.habitrain.core.role.override.RoleOverrideRegistry;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single runtime resolver for every SRE path that may still hold a vanilla role
 * or a replacement from an older override snapshot.
 */
public final class SreRoleOverrideResolver {
    private SreRoleOverrideResolver() {}

    /**
     * Resolves a role held by a live SRE structure to the role effective in the
     * current override snapshot. An inactive managed replacement falls back to
     * its original target (or to another currently active replacement).
     */
    public static @Nullable SRERole resolve(@Nullable SRERole role) {
        if (role == null || role.identifier() == null) return role;

        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        SRERole directReplacement = engine.getReplacement(role.identifier());
        if (directReplacement != null) return directReplacement;

        ResourceLocation targetId = engine.getManagedTargetId(role.identifier());
        if (targetId == null) return role;

        SRERole activeForTarget = engine.getReplacement(targetId);
        if (activeForTarget != null) return activeForTarget;
        return TMMRoles.getRole(targetId);
    }

    public static SRERole resolveOrOriginal(SRERole role) {
        SRERole resolved = resolve(role);
        return resolved != null ? resolved : role;
    }

    /**
     * True only when a role should be exposed in registries, books, commands,
     * and newly-created assignment pools.
     */
    public static boolean isVisible(@Nullable SRERole role) {
        if (role == null || role.identifier() == null) return false;

        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        ResourceLocation id = role.identifier();
        if (engine.isReplaced(id)) return false;

        if (!engine.isManagedReplacementId(id)) return true;
        return engine.isActiveReplacementId(id);
    }

    /**
     * Filters a complete registry-like collection and appends every active
     * replacement exactly once.
     */
    public static List<SRERole> visibleRegistryRoles(Collection<SRERole> roles) {
        Map<ResourceLocation, SRERole> result = new LinkedHashMap<>();
        if (roles != null) {
            for (SRERole role : roles) {
                if (isVisible(role)) {
                    result.putIfAbsent(role.identifier(), role);
                }
            }
        }
        for (ReplaceRoleDefinition def :
                RoleOverrideEngine.getInstance().getSnapshot().getActiveReplaces().values()) {
            SRERole replacement = def.replacementRole();
            if (replacement != null && replacement.identifier() != null) {
                result.put(replacement.identifier(), replacement);
            }
        }
        return new ArrayList<>(result.values());
    }

    /**
     * Resolves an already-selected subset without appending unrelated active
     * replacements.
     */
    public static List<SRERole> resolveSelection(Collection<SRERole> roles) {
        List<SRERole> result = new ArrayList<>();
        if (roles == null) return result;
        for (SRERole role : roles) {
            SRERole resolved = resolve(role);
            if (resolved != null && resolved.identifier() != null && isVisible(resolved)) {
                result.add(resolved);
            }
        }
        return result;
    }

    /**
     * Reads the new full {@code namespace:path} format and the legacy
     * path-only format used by SRE 4.3.0.
     */
    public static @Nullable SRERole resolveStoredId(@Nullable String stored) {
        if (stored == null || stored.isBlank()) return null;

        if (stored.indexOf(':') >= 0) {
            ResourceLocation id = ResourceLocation.tryParse(stored);
            if (id == null) return null;
            SRERole exact = TMMRoles.getRole(id);
            if (exact != null) return resolve(exact);
            SRERole active = RoleOverrideEngine.getInstance().getReplacement(id);
            return active;
        }

        // Old saves containing the target path must follow an active replacement.
        List<Map.Entry<ResourceLocation, ReplaceRoleDefinition>> active =
                new ArrayList<>(RoleOverrideEngine.getInstance()
                        .getSnapshot().getActiveReplaces().entrySet());
        active.sort(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)));
        for (Map.Entry<ResourceLocation, ReplaceRoleDefinition> entry : active) {
            SRERole replacement = entry.getValue().replacementRole();
            if (entry.getKey().getPath().equals(stored)
                    || replacement.identifier().getPath().equals(stored)) {
                return replacement;
            }
        }

        // An old save may contain the path of a replacement that is now disabled.
        for (ReplaceRoleDefinition def : RoleOverrideRegistry.INSTANCE.getReplaces()) {
            if (RoleOverrideEngine.getInstance().isManagedReplacementId(
                    def.replacementRole().identifier())
                    && def.replacementRole().identifier().getPath().equals(stored)) {
                SRERole resolved = resolve(def.replacementRole());
                if (resolved != null) return resolved;
            }
        }

        return TMMRoles.ROLES.values().stream()
                .filter(SreRoleOverrideResolver::isVisible)
                .filter(role -> role.identifier().getPath().equals(stored))
                .sorted(Comparator.comparing(role -> role.identifier().toString()))
                .findFirst()
                .orElse(null);
    }
}
