package com.habitrain.core.role.snapshot;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.EffectiveRoleProfile;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.api.role.v2.RoleSnapshotId;
import com.habitrain.core.api.role.v2.definition.ReplacementIdentity;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import com.habitrain.core.role.catalog.MapRoleLookup;
import com.habitrain.core.role.catalog.RawRoleLookup;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.RoleExtensionCompiler;
import com.habitrain.core.role.behavior.RoleHookRegistry;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Compiles an immutable {@link RoleSnapshot} from the v2 role extension registry
 * and a raw role source.
 *
 * <p>Applies the same effective-view rules as the catalog: visible roles are
 * patched by v2 {@code MODIFY}, v2 {@code REPLACE} targets are hidden and their
 * compiled replacements surface, and v2 {@code ALIAS} entries become redirects.
 * Pure: never touches {@code TMMRoles} or writes an {@code SRERole}; the raw
 * source is injected as a {@link RawRoleLookup} and all MODIFY results are
 * captured in immutable {@link EffectiveRoleProfile}s for later activation.
 */
public final class RoleSnapshotCompiler {

    private RoleSnapshotCompiler() {}

    /**
     * Compiles a snapshot from the given raw role map.
     *
     * @param id       the snapshot id
     * @param rawRoles the raw upstream role map
     */
    public static RoleSnapshot compile(RoleSnapshotId id, Map<ResourceLocation, SRERole> rawRoles) {
        return compile(id, new MapRoleLookup(rawRoles));
    }

    /**
     * Compiles a snapshot from the given raw role source.
     *
     * @param id     the snapshot id
     * @param lookup the raw upstream role source
     */
    public static RoleSnapshot compile(RoleSnapshotId id, RawRoleLookup lookup) {
        Map<ResourceLocation, EffectiveRole> roles = compileEffectiveRoles(lookup);
        Map<ResourceLocation, ResourceLocation> aliases = compileAliases(roles);
        Set<ResourceLocation> replacedTargets = compileReplacedTargets(roles);
        Set<RoleSnapshot.BehaviorEntry> behavior = new LinkedHashSet<>();
        for (var byRole : RoleHookRegistry.INSTANCE.entryView().values()) {
            for (var entries : byRole.values()) {
                for (var entry : entries) {
                    if (RoleExtensionConfigService.INSTANCE.gateFor(entry.providerId(), entry.entryId())
                            == RoleExtensionConfigService.EntryGate.ENABLED) {
                        behavior.add(new RoleSnapshot.BehaviorEntry(entry.providerId(), entry.entryId()));
                    }
                }
            }
        }
        return new RoleSnapshot(id, roles, aliases, replacedTargets, behavior,
                RoleExtensionConfigService.INSTANCE.isAllowGlobalHooks());
    }

    /**
     * The single authoritative effective-directory compilation (fix-doc §6.1):
     * raw baselines minus hidden {@code REPLACE} targets, then v2 and v1
     * replacements surfaced exactly once each, keyed by canonical id. A
     * duplicate canonical id is a compile diagnostic, never a silent overwrite.
     */
    public static Map<ResourceLocation, EffectiveRole> compileEffectiveRoles(RawRoleLookup lookup) {
        Map<ResourceLocation, EffectiveRole> roles = new LinkedHashMap<>();
        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        for (SRERole role : lookup.all()) {
            if (role == null || role.identifier() == null) {
                continue;
            }
            ResourceLocation rid = role.identifier();
            if (isHiddenTarget(rid)) {
                continue; // active v1/v2 REPLACE target is hidden; its replacement surfaces below
            }
            if (RoleExtensionRegistry.INSTANCE.isActiveReplacementRoleId(rid)) {
                continue; // active v2 REPLACE replacement surfaces below
            }
            if (RoleExtensionRegistry.INSTANCE.isNewIdReplacementRoleId(rid)) {
                continue; // disabled NEW_ID_WITH_ALIAS replacement must not leak as a baseline role
            }
            if (RoleExtensionRegistry.INSTANCE.isAdded(rid)
                    && !RoleExtensionRegistry.INSTANCE.isAddedActive(rid)) {
                continue; // config-disabled v2 ADD stays out of the effective view
            }
            if (engine.isManagedReplacementId(rid) && !engine.isActiveReplacementId(rid)) {
                continue; // inactive v1 replacement stays in the raw registry but is hidden
            }
            if (engine.isActiveReplacementId(rid)) {
                continue; // v1 REPLACE replacement surfaces below
            }
            EffectiveRole.Source source = sourceFor(rid);
            CompiledModifyOverlay overlay = compileOverlay(role, rid, null);
            if (overlay != null) {
                source = EffectiveRole.Source.MODIFIED;
            }
            EffectiveRoleProfile profile = EffectiveRoleProfile.from(RoleKey.of(rid), role, source)
                    .withOverlay(overlay);
            roles.put(rid, new EffectiveRole(profile, role, overlay));
        }

        // v2 REPLACE replacements surface exactly once (config-enabled only), with
        // MODIFY folded on top. Hidden targets were skipped in the baseline loop,
        // so PRESERVE_TARGET_ID (replacement id == target id) needs no removal here.
        for (RoleReplacement repl : RoleExtensionRegistry.INSTANCE.activeReplacements()) {
            SRERole surfaced = RoleExtensionRegistry.INSTANCE.compiledReplacement(repl);
            if (surfaced == null || surfaced.identifier() == null) {
                continue;
            }
            ResourceLocation cid = surfaced.identifier();
            java.util.List<com.habitrain.core.role.extension.ConfiguredPatch> patches = new java.util.ArrayList<>();
            if (!repl.target().location().equals(cid)) {
                patches.addAll(RoleExtensionRegistry.INSTANCE.configuredPatchesFor(repl.target().location()));
            }
            patches.addAll(RoleExtensionRegistry.INSTANCE.configuredPatchesFor(cid));
            CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlayConfigured(
                    surfaced, patches, null);
            EffectiveRoleProfile profile = EffectiveRoleProfile
                    .from(RoleKey.of(cid), surfaced, EffectiveRole.Source.REPLACEMENT)
                    .withOverlay(overlay);
            roles.put(cid, new EffectiveRole(profile, surfaced, overlay));
        }

        // v1 REPLACE replacements from the legacy engine surface once.
        for (var entry : engine.getSnapshot().getActiveReplaces().entrySet()) {
            roles.remove(entry.getKey());
            SRERole replacement = entry.getValue().replacementRole();
            if (replacement != null && replacement.identifier() != null
                    && !roles.containsKey(replacement.identifier())) {
                ResourceLocation cid = replacement.identifier();
                roles.put(cid, new EffectiveRole(EffectiveRoleProfile.from(
                        RoleKey.of(cid), replacement, EffectiveRole.Source.REPLACEMENT), replacement));
            }
        }
        return roles;
    }

    /** Canonical-id redirects: v2 {@code ALIAS} entries and NEW_ID_WITH_ALIAS replaces. */
    public static Map<ResourceLocation, ResourceLocation> compileAliases(
            Map<ResourceLocation, EffectiveRole> roles) {
        Map<ResourceLocation, ResourceLocation> aliases = new LinkedHashMap<>();
        for (ResourceLocation from : RoleExtensionRegistry.INSTANCE.activeAliases().stream()
                .map(a -> a.from().location()).toList()) {
            ResourceLocation canonical = RoleExtensionRegistry.INSTANCE.resolveAlias(from);
            if (canonical != null) {
                aliases.put(from, canonical);
            }
        }
        for (RoleReplacement repl : RoleExtensionRegistry.INSTANCE.activeReplacements()) {
            if (repl.identity() == ReplacementIdentity.NEW_ID_WITH_ALIAS) {
                ResourceLocation cid = repl.replacement().key().location();
                if (roles.containsKey(cid)) {
                    aliases.put(repl.target().location(), cid);
                }
            }
        }
        return aliases;
    }

    /** Every active v1/v2 {@code REPLACE} target id, for the {@code includeReplaced} view. */
    public static Set<ResourceLocation> compileReplacedTargets(Map<ResourceLocation, EffectiveRole> roles) {
        Set<ResourceLocation> targets = new LinkedHashSet<>();
        for (RoleReplacement repl : RoleExtensionRegistry.INSTANCE.activeReplacements()) {
            targets.add(repl.target().location());
        }
        targets.addAll(RoleOverrideEngine.getInstance().getSnapshot().getActiveReplaces().keySet());
        return targets;
    }

    private static boolean isHiddenTarget(ResourceLocation rid) {
        return RoleExtensionRegistry.INSTANCE.isActiveReplaced(rid)
                || RoleOverrideEngine.getInstance().isReplaced(rid);
    }

    /** Folds configured v2 patches without materializing them onto {@code role}. */
    private static CompiledModifyOverlay compileOverlay(SRERole role, ResourceLocation id,
                                                         @org.jetbrains.annotations.Nullable
                                                         java.util.List<com.habitrain.core.role.extension.ConfiguredPatch> extra) {
        java.util.List<com.habitrain.core.role.extension.ConfiguredPatch> patches = new java.util.ArrayList<>();
        if (extra != null) {
            patches.addAll(extra);
        }
        patches.addAll(RoleExtensionRegistry.INSTANCE.configuredPatchesFor(id));
        return RoleExtensionCompiler.compileModifyOverlayConfigured(role, patches, null);
    }

    /**
     * Unified source priority (fix-doc §6.3): REPLACEMENT > ADDED > MODIFIED >
     * LEGACY > BASELINE. Replaced targets and replacement ids never reach this
     * from the baseline loop (they are skipped or surfaced with an explicit
     * {@code REPLACEMENT} source).
     */
    private static EffectiveRole.Source sourceFor(ResourceLocation id) {
        if (RoleExtensionRegistry.INSTANCE.isAdded(id)) {
            return EffectiveRole.Source.ADDED;
        }
        if (RoleExtensionRegistry.INSTANCE.isActiveModified(id)) {
            return EffectiveRole.Source.MODIFIED;
        }
        if (RoleOverrideEngine.getInstance().isActiveReplacementId(id)) {
            return EffectiveRole.Source.REPLACEMENT;
        }
        if (RoleOverrideEngine.getInstance().isModified(id)) {
            return EffectiveRole.Source.MODIFIED;
        }
        return EffectiveRole.Source.BASELINE;
    }
}
