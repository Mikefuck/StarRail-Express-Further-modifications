package com.habitrain.core.game.sre.role;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.role.catalog.MapRoleLookup;
import com.habitrain.core.role.catalog.RawRoleLookup;
import com.habitrain.core.role.catalog.TmmRoleLookup;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
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
public final class SreRoleResolver {
    private SreRoleResolver() {}

    /**
     * Resolves a role held by a live SRE structure to the role effective in the
     * current role snapshot. An inactive managed replacement falls back to
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

        ResourceLocation targetId = RoleExtensionRegistry.INSTANCE.getReplacements().stream()
                .filter(r -> r.identity() == com.habitrain.core.api.role.v2.definition.ReplacementIdentity.NEW_ID_WITH_ALIAS)
                .filter(r -> r.replacement().key().location().equals(role.identifier()))
                .map(r -> r.target().location()).findFirst().orElse(null);
        if (targetId != null) {
            SRERole baseline = lookup.find(targetId);
            SRERole active = resolveV2(targetId, baseline, lookup);
            return active != null ? active : (baseline == null ? role : applyV2Modify(baseline));
        }
        return applyV2Modify(role);
    }

    /**
     * Resolves a v2 REPLACE / ALIAS / MODIFY overlay. Returns {@code null} when
     * the id is not owned by the v2 registry so the caller can retain the upstream baseline.
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
            return snapshot.isActive(role.identifier());
        }

        ResourceLocation id = role.identifier();
        RoleExtensionRegistry registry = RoleExtensionRegistry.INSTANCE;
        if (registry.isActiveReplaced(id)) {
            return registry.getCompiledReplacements().get(id) == role
                    && registry.isActiveReplacementRoleId(id);
        }
        if (registry.isNewIdReplacementRoleId(id)) {
            return registry.isActiveReplacementRoleId(id);
        }
        return !registry.isAdded(id) || registry.isAddedActive(id);
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
            SRERole effective = resolveV2(id, exact, lookup);
            return effective != null ? effective : (exact == null ? null : resolve(exact, lookup));
        }

        // Upstream saves store paths only. Follow active v2 redirects first.
        var snapshot = RoleSnapshotManager.INSTANCE.current();
        List<ResourceLocation> redirects = new ArrayList<>();
        if (snapshot != null) {
            redirects.addAll(snapshot.aliases().keySet());
        } else {
            RoleExtensionRegistry.INSTANCE.activeAliases().forEach(a -> redirects.add(a.from().location()));
            RoleExtensionRegistry.INSTANCE.activeReplacements().forEach(r -> redirects.add(r.target().location()));
        }
        redirects.sort(Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation id : redirects) {
            if (id.getPath().equals(stored)) {
                SRERole resolved = resolveV2(id, lookup.find(id), lookup);
                if (resolved != null && isVisible(resolved)) return resolved;
            }
        }
        return visibleRegistryRoles(lookup.all()).stream()
                .filter(role -> role.identifier().getPath().equals(stored))
                .sorted(Comparator.comparing(role -> role.identifier().toString()))
                .findFirst().orElse(null);
    }
}

