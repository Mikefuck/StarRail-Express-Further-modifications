package com.habitrain.core.role.book;

import com.habitrain.core.api.role.v2.book.RoleBookPage;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.book.RoleBookPatch;
import com.habitrain.core.api.role.v2.book.RoleBookView;
import com.habitrain.core.api.role.v2.definition.ListOp;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.role.extension.ManagedSRERole;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import io.wifi.starrailexpress.api.SRERole;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Resolves the v2 role-book view for a surfaced role id.
 *
 * <p>ADD content (if any) is the baseline. {@code MODIFY} patches targeting
 * the same id are folded in registration order. {@link ListOp#REPLACE_ALL}
 * owns the complete tab set; otherwise the result is an appendix.
 */
public final class RoleBookResolver {

    private static final java.util.Map<com.habitrain.core.api.role.v2.RoleSnapshot,
            java.util.Map<ResourceLocation, RoleBookView>> FROZEN_BOOKS = new java.util.WeakHashMap<>();

    /** Captures book content at compilation, alongside the effective role profile. */
    public static synchronized void freeze(com.habitrain.core.api.role.v2.RoleSnapshot snapshot) {
        java.util.Map<ResourceLocation, RoleBookView> books = new java.util.LinkedHashMap<>();
        for (var effective : snapshot.effectiveRoles()) {
            books.put(effective.key().location(), resolveUnfrozen(effective.key().location(), snapshot));
        }
        FROZEN_BOOKS.put(snapshot, java.util.Map.copyOf(books));
    }

    private RoleBookResolver() {}

    public static RoleBookView resolve(@Nullable ResourceLocation roleId) {
        if (roleId == null) {
            return RoleBookView.none();
        }
        var snapshot = com.habitrain.core.role.snapshot.RoleSnapshotManager.INSTANCE.current();
        if (snapshot != null) {
            synchronized (RoleBookResolver.class) {
                var books = FROZEN_BOOKS.get(snapshot);
                if (books != null) return books.getOrDefault(snapshot.canonicalize(roleId).location(), RoleBookView.none());
            }
        }
        return resolveUnfrozen(roleId, snapshot);
    }

    private static RoleBookView resolveUnfrozen(ResourceLocation roleId,
            @Nullable com.habitrain.core.api.role.v2.RoleSnapshot snapshot) {
        RoleExtensionRegistry registry = RoleExtensionRegistry.INSTANCE;
        ResourceLocation canonicalId = roleId;
        if (snapshot != null) {
            canonicalId = snapshot.canonicalize(roleId).location();
        } else {
            ResourceLocation alias = registry.resolveAlias(roleId);
            if (alias != null) {
                canonicalId = alias;
            }
        }
        List<RoleBookPage> pages = List.of();
        boolean replaceAll = false;

        SRERole managed = null;
        if (snapshot != null) {
            managed = snapshot.find(RoleKey.of(canonicalId))
                    .map(com.habitrain.core.api.role.v2.EffectiveRole::role)
                    .orElse(null);
        }
        if (managed == null) {
            managed = registry.getManagedRole(canonicalId);
        }
        com.habitrain.core.api.role.v2.definition.RoleReplacement activeReplacement = null;
        if (!(managed instanceof ManagedSRERole)) {
            activeReplacement = registry.replacementFor(canonicalId);
            if (activeReplacement == null && !canonicalId.equals(roleId)) {
                activeReplacement = registry.replacementFor(roleId);
            }
            if (activeReplacement != null) {
                managed = registry.compiledReplacement(activeReplacement);
            } else {
                for (com.habitrain.core.api.role.v2.definition.RoleReplacement repl : registry.activeReplacements()) {
                    ManagedSRERole compiled = registry.compiledReplacement(repl);
                    if (compiled != null && canonicalId.equals(compiled.identifier())) {
                        activeReplacement = repl;
                        managed = compiled;
                        break;
                    }
                }
            }
        }
        if (managed instanceof ManagedSRERole mm && mm.book() != null
                && (activeReplacement != null || isManagedRoleEnabled(mm, canonicalId, snapshot))) {
            pages = mm.book().pages();
            replaceAll = true;
        } else if (!(managed instanceof ManagedSRERole)) {
            var definition = registry.getAddedDefinition(canonicalId);
            boolean active = snapshot != null ? snapshot.isActive(canonicalId)
                    : RoleExtensionConfigService.INSTANCE.gateFor(canonicalId.getNamespace(), canonicalId.toString())
                    == RoleExtensionConfigService.EntryGate.ENABLED;
            if (definition != null && definition.book() != null && active) {
                pages = definition.book().pages();
                replaceAll = true;
            }
        }

        List<com.habitrain.core.role.extension.ConfiguredPatch> patches = activeReplacement != null
                ? registry.configuredPatchesForReplacement(
                        activeReplacement.target().location(), canonicalId)
                : registry.configuredPatchesFor(canonicalId);
        for (var configured : patches) {
            RolePatch patch = configured.patch();
            RoleBookPatch book = patch.book();
            if (book == null) {
                continue;
            }
            pages = book.apply(pages);
            if (book.op() == ListOp.REPLACE_ALL) {
                replaceAll = true;
            }
        }
        if (pages.isEmpty() && !replaceAll) {
            return RoleBookView.none();
        }
        return replaceAll ? RoleBookView.replaceAll(pages) : RoleBookView.append(pages);
    }

    /** ADD book declarations are owned by the role's snapshot/config gate. */
    private static boolean isManagedRoleEnabled(
            ManagedSRERole managed,
            ResourceLocation roleId,
            @Nullable com.habitrain.core.api.role.v2.RoleSnapshot snapshot) {
        if (managed == null || roleId == null) {
            return false;
        }
        if (snapshot != null) {
            return snapshot.isActive(roleId);
        }
        return RoleExtensionConfigService.INSTANCE.gateFor(roleId.getNamespace(), roleId.toString())
                == RoleExtensionConfigService.EntryGate.ENABLED;
    }

    public static RoleBookView resolve(@Nullable RoleKey key) {
        return key == null ? RoleBookView.none() : resolve(key.location());
    }

    /** Remote clients fold declarations using the server's frozen active entry ids. */
    public static RoleBookView resolveForEntries(ResourceLocation roleId, java.util.Set<String> activeEntries) {
        if (roleId == null) return RoleBookView.none();
        var entries = RoleExtensionRegistry.INSTANCE.v2Entries(java.util.Set.of()).stream()
                .filter(e -> activeEntries.contains(e.entryId())).toList();
        java.util.Map<ResourceLocation, ResourceLocation> redirects = new java.util.LinkedHashMap<>();
        java.util.Map<ResourceLocation, com.habitrain.core.api.role.v2.definition.RoleDefinition> definitions =
                new java.util.LinkedHashMap<>();
        java.util.Map<ResourceLocation, ResourceLocation> replacements = new java.util.LinkedHashMap<>();
        for (var entry : entries) {
            if (entry.declaration() instanceof com.habitrain.core.api.role.v2.definition.RoleAlias alias) {
                redirects.put(alias.from().location(), alias.to().location());
            } else if (entry.declaration() instanceof com.habitrain.core.api.role.v2.definition.RoleReplacement replacement) {
                var target = replacement.target().location();
                var surfaced = replacement.identity() == com.habitrain.core.api.role.v2.definition.ReplacementIdentity.KEEP_CANONICAL_ID
                        ? target : replacement.replacement().key().location();
                definitions.put(surfaced, replacement.replacement());
                replacements.put(target, surfaced);
                if (!target.equals(surfaced)) redirects.put(target, surfaced);
            } else if (entry.operation() == com.habitrain.core.role.extension.RoleOperation.ADD && entry.target() != null) {
                var definition = RoleExtensionRegistry.INSTANCE.getAddedDefinition(entry.target().location());
                if (definition != null) definitions.put(entry.target().location(), definition);
            }
        }
        ResourceLocation canonical = roleId;
        java.util.Set<ResourceLocation> visited = new java.util.LinkedHashSet<>();
        while (visited.add(canonical) && redirects.containsKey(canonical)) canonical = redirects.get(canonical);
        java.util.List<ResourceLocation> chain = new java.util.ArrayList<>();
        collectAncestors(canonical, replacements, chain, new java.util.HashSet<>());
        chain.add(canonical);
        var definition = definitions.get(canonical);
        List<RoleBookPage> pages = definition == null || definition.book() == null ? List.of() : definition.book().pages();
        boolean replaceAll = definition != null && definition.book() != null;
        var patches = entries.stream().filter(e -> e.declaration() instanceof RolePatch patch
                        && patch.book() != null && chain.contains(patch.target().location()))
                .sorted(java.util.Comparator
                        .comparingInt((com.habitrain.core.role.extension.ManagedRoleEntry<?> e) ->
                                chain.indexOf(((RolePatch) e.declaration()).target().location()))
                        .thenComparingInt(e -> ((RolePatch) e.declaration()).priority().value())
                        .thenComparing(com.habitrain.core.role.extension.ManagedRoleEntry::providerId)
                        .thenComparing(com.habitrain.core.role.extension.ManagedRoleEntry::entryId)).toList();
        for (var entry : patches) {
            RoleBookPatch patch = ((RolePatch) entry.declaration()).book();
            pages = patch.apply(pages);
            replaceAll |= patch.op() == ListOp.REPLACE_ALL;
        }
        return replaceAll ? RoleBookView.replaceAll(pages) : RoleBookView.append(pages);
    }

    private static void collectAncestors(ResourceLocation role,
            java.util.Map<ResourceLocation, ResourceLocation> replacements,
            java.util.List<ResourceLocation> chain, java.util.Set<ResourceLocation> visited) {
        if (!visited.add(role)) return;
        replacements.forEach((from, to) -> {
            if (to.equals(role) && !from.equals(role)) {
                collectAncestors(from, replacements, chain, visited);
                if (!chain.contains(from)) chain.add(from);
            }
        });
    }
}
