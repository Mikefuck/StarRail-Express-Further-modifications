package com.habitrain.core.game.sre.roleoverride;

import com.habitrain.core.api.role.ReplaceRoleDefinition;
import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.role.catalog.MapRoleLookup;
import com.habitrain.core.role.catalog.RawRoleLookup;
import com.habitrain.core.role.catalog.TmmRoleLookup;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.override.RoleOverrideEngine;
import com.habitrain.core.role.override.RoleOverrideRegistry;
import com.habitrain.core.role.snapshot.RoleSnapshotCompiler;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.api.SRERole;
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
        return resolve(role, TmmRoleLookup.INSTANCE);
    }

    /** {@link #resolve(SRERole)} against an injected raw-role lookup. */
    public static @Nullable SRERole resolve(@Nullable SRERole role, RawRoleLookup lookup) {
        if (role == null || role.identifier() == null) return role;

        SRERole v2 = resolveV2(role.identifier(), role, lookup);
        if (v2 != null) {
            return v2;
        }

        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        SRERole directReplacement = engine.getReplacement(role.identifier());
        if (directReplacement != null) return directReplacement;

        ResourceLocation targetId = engine.getManagedTargetId(role.identifier());
        if (targetId == null) {
            return applyV2Modify(role);
        }

        SRERole activeForTarget = engine.getReplacement(targetId);
        if (activeForTarget != null) return activeForTarget;
        SRERole baseline = lookup.find(targetId);
        return baseline == null ? role : applyV2Modify(baseline);
    }

    /**
     * Resolves a v2 REPLACE / ALIAS / MODIFY overlay. Returns {@code null} when
     * the id is not owned by the v2 registry so the caller can fall back to v1.
     */
    public static @Nullable SRERole resolveV2(ResourceLocation id) {
        return resolveV2(id, null, TmmRoleLookup.INSTANCE);
    }

    /**
     * {@code held} is the role object the caller already has in hand (usually the
     * very role being resolved). A v2 MODIFY re-uses it instead of re-fetching
     * from {@code TMMRoles}, so a bare unit test that never bootstrapped the game
     * stays safe; {@code TMMRoles} is only consulted when an {@code ADD} already
     * initialized it.
     */
    public static @Nullable SRERole resolveV2(ResourceLocation id, @Nullable SRERole held) {
        return resolveV2(id, held, TmmRoleLookup.INSTANCE);
    }

    /** {@link #resolveV2(ResourceLocation, SRERole)} against an injected lookup. */
    public static @Nullable SRERole resolveV2(ResourceLocation id, @Nullable SRERole held, RawRoleLookup lookup) {
        if (id == null) {
            return null;
        }
        // Once a lobby/round snapshot is published it is the sole authority for
        // v2 alias/replace/modify resolution.  Reading the live registry here
        // would make a NEXT_ROUND config edit change the current game.
        var snapshot = RoleSnapshotManager.INSTANCE.current();
        if (snapshot != null) {
            var effective = snapshot.find(com.habitrain.core.api.role.v2.RoleKey.of(id)).orElse(null);
            if (effective == null) {
                return null;
            }
            SRERole runtime = effective.role();
            if (runtime != null) {
                return runtime;
            }
            ResourceLocation canonical = effective.id();
            return heldFor(canonical, held) != null ? held
                    : (lookup == null ? null : lookup.find(canonical));
        }
        RoleExtensionRegistry registry = RoleExtensionRegistry.INSTANCE;
        ResourceLocation alias = registry.resolveAlias(id);
        ResourceLocation canonical = alias == null ? id : alias;
        RoleReplacement repl = registry.replacementFor(canonical);
        if (repl == null && alias != null) {
            repl = registry.replacementFor(id);
        }
        if (repl != null) {
            SRERole surfaced = registry.applyModifiesToReplacement(repl);
            if (surfaced != null) {
                return surfaced;
            }
        }
        if (registry.isModified(canonical)) {
            SRERole raw = heldFor(canonical, held);
            if (raw == null && registry.isTmmAccessible()) {
                raw = lookup.find(canonical);
            }
            if (raw != null) {
                return registry.applyModifies(raw);
            }
        }
        if (alias == null) {
            return null;
        }
        SRERole aliasTarget = heldFor(canonical, held);
        return aliasTarget != null ? aliasTarget
                : (registry.isTmmAccessible() ? lookup.find(canonical) : null);
    }

    private static @Nullable SRERole heldFor(ResourceLocation canonical, @Nullable SRERole held) {
        return (held != null && held.identifier() != null
                && held.identifier().equals(canonical)) ? held : null;
    }

    private static SRERole applyV2Modify(SRERole role) {
        if (role == null || role.identifier() == null) {
            return role;
        }
        var snapshot = RoleSnapshotManager.INSTANCE.current();
        if (snapshot != null) {
            return snapshot.find(com.habitrain.core.api.role.v2.RoleKey.of(role.identifier()))
                    .map(EffectiveRole::role).orElse(role);
        }
        if (!RoleExtensionRegistry.INSTANCE.isModified(role.identifier())) {
            return role;
        }
        return RoleExtensionRegistry.INSTANCE.applyModifies(role);
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

        var snapshot = RoleSnapshotManager.INSTANCE.current();
        if (snapshot != null) {
            return snapshot.find(com.habitrain.core.api.role.v2.RoleKey.of(role.identifier())).isPresent();
        }

        ResourceLocation id = role.identifier();
        RoleExtensionRegistry registry = RoleExtensionRegistry.INSTANCE;
        if (registry.isReplaced(id)) {
            return false;
        }
        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        if (engine.isReplaced(id)) return false;

        if (!engine.isManagedReplacementId(id)) return true;
        return engine.isActiveReplacementId(id);
    }

    /**
     * Filters a complete registry-like collection and appends every active
     * replacement exactly once. Derived from the single authoritative
     * compilation (fix-doc §6.1) so every caller agrees on one answer.
     */
    public static List<SRERole> visibleRegistryRoles(Collection<SRERole> roles) {
        var snapshot = RoleSnapshotManager.INSTANCE.current();
        if (snapshot != null) {
            List<SRERole> frozen = new ArrayList<>();
            for (EffectiveRole er : snapshot.effectiveRoles()) {
                if (er.role() != null && er.role().identifier() != null) {
                    frozen.add(er.role());
                }
            }
            return frozen;
        }
        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        if (roles != null) {
            for (SRERole role : roles) {
                if (role != null && role.identifier() != null) {
                    raw.putIfAbsent(role.identifier(), role);
                }
            }
        }
        Map<ResourceLocation, EffectiveRole> effective =
                RoleSnapshotCompiler.compileEffectiveRoles(new MapRoleLookup(raw));
        List<SRERole> out = new ArrayList<>(effective.size());
        for (EffectiveRole er : effective.values()) {
            if (er.role() != null && er.role().identifier() != null) {
                out.add(er.role());
            }
        }
        return out;
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
        return resolveStoredId(stored, TmmRoleLookup.INSTANCE);
    }

    /** {@link #resolveStoredId(String)} against an injected raw-role lookup. */
    public static @Nullable SRERole resolveStoredId(@Nullable String stored, RawRoleLookup lookup) {
        if (stored == null || stored.isBlank()) return null;

        if (stored.indexOf(':') >= 0) {
            ResourceLocation id = ResourceLocation.tryParse(stored);
            if (id == null) return null;
            SRERole exact = lookup.find(id);
            if (exact != null) return resolve(exact, lookup);
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

        return lookup.all().stream()
                .filter(SreRoleOverrideResolver::isVisible)
                .filter(role -> role.identifier().getPath().equals(stored))
                .sorted(Comparator.comparing(role -> role.identifier().toString()))
                .findFirst()
                .orElse(null);
    }
}
