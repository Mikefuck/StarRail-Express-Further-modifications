package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.definition.PatchPriority;
import com.habitrain.core.api.role.v2.definition.RoleAlias;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registration registry for the v2 role extension model ({@code ADD},
 * {@code MODIFY}, {@code REPLACE}, {@code ALIAS}).
 *
 * <p>Collects definitions during the registration phase (before the registry is
 * frozen on server start), validates each, and compiles the effective view:
 * {@code ADD} roles are compiled and registered once into {@code TMMRoles};
 * {@code MODIFY} patches are stored for reversible overlay; {@code REPLACE}
 * definitions are compiled into replacement roles; {@code ALIAS} entries are
 * stored for id redirects.
 *
 * <p>Because upstream {@code TMMRoles} has no safe removal, added/replaced roles
 * stay in the raw registry after deactivation; the catalog's visibility/source
 * logic (rather than deletion) controls whether they surface.
 */
public final class RoleExtensionRegistry {

    public static final RoleExtensionRegistry INSTANCE = new RoleExtensionRegistry();
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleExtensionRegistry");

    // Not final so unit tests can seed the managed/compiled sets via reflection
    // (mirroring how tests seed RoleOverrideEngine.snapshot).
    private Map<ResourceLocation, SRERole> managedRoles = new LinkedHashMap<>();
    private Map<ResourceLocation, ManagedSRERole> compiledReplacements = new LinkedHashMap<>();

    /**
     * Compiled replacement instances cached by target id, then by the exact
     * {@code RoleReplacement} source, so a recompute (config toggle, freeze's
     * second pass through {@code recomputeCompiledEntries()}) reuses the SAME
     * object instead of compiling a fresh one. Object identity matters:
     * {@code TMMRoles}, live snapshots and player components all hold
     * references, and recompiling an unchanged entry would fork one role id
     * into two live objects, silently reverting holders of the role to
     * baseline values (audit S2). Stale entries stay unreachable
     * (identity-keyed by source) and are harmless.
     */
    private final Map<ResourceLocation, Map<RoleReplacement, ManagedSRERole>> replacementCompileCache =
            new LinkedHashMap<>();

    /** Lazily probed {@code TMMRoles} reachability; see {@link #canTouchTmmRoles()}. */
    private Boolean tmmReachable;

    private final List<ManagedPatch> patches = new ArrayList<>();
    private final List<ManagedReplacement> replacements = new ArrayList<>();
    private final List<ManagedAlias> aliases = new ArrayList<>();
    /** First-registered entryId per alias source (audit P0-3 conflict key). */
    private final Map<ResourceLocation, String> aliasSourceOwners = new LinkedHashMap<>();
    /** Every registered entryId per alias source, in registration order. */
    private final Map<ResourceLocation, List<String>> aliasSourcesByFrom = new LinkedHashMap<>();
    /**
     * REPLACE candidates per target (audit P2-1): all declarations are kept —
     * no registration-order throw — and the effective winner is resolved by
     * config gate + the {@code <target>#replace} conflict winner. An unresolved
     * multi-candidate target activates none of them and reports CONFLICT.
     */
    private final Map<ResourceLocation, List<String>> replacementByTarget = new LinkedHashMap<>();

    /**
     * The authoritative provider set (audit P1-4): every provider whose
     * registration transaction committed — covering ALL declaration types
     * (ADD/MODIFY/REPLACE/ALIAS, hooks, state, action, voice, chat). The
     * manifest provider list comes from here, never from reverse-inferring
     * static role entries.
     */
    private final Set<String> providers = new LinkedHashSet<>();

    /** Providers that explicitly declared a client-side requirement (audit P1-4). */
    private final Set<String> requiredClientProviders = new LinkedHashSet<>();
    private final Set<String> registeredEntryIds = new HashSet<>();
    private volatile List<ManagedRoleEntry<?>> compiledEntries = List.of();
    private boolean frozen;
    /** Set once {@link #add} has touched {@code TMMRoles}; freeze linking then may consult it. */
    private boolean tmmAccessible;

    private RoleExtensionRegistry() {}

    public static void init() {
        LOGGER.info("RoleExtensionRegistry initialized");
    }

    /**
     * Opens a provider-scoped registration transaction. All declarations are
     * staged and validated inside the transaction; nothing is written to this
     * registry or to {@code TMMRoles} until {@code commit()}. A throwing provider
     * rolls the transaction back with zero leakage.
     */
    public synchronized ProviderRegistrationTransaction begin(String providerId) {
        if (frozen) {
            throw new IllegalStateException("Role extension registry is frozen");
        }
        return new ProviderRegistrationTransaction(providerId, this);
    }

    // ------------------------------------------------------------------
    // ADD
    // ------------------------------------------------------------------

    /**
     * Validates, compiles and registers an {@code ADD} role. Runtime-only path:
     * touches {@code TMMRoles}, so it must not be called from a bare unit test.
     *
     * @return the compiled, registered {@link ManagedSRERole}
     */
    public synchronized SRERole add(RoleDefinition def) {
        if (frozen) {
            throw new IllegalStateException("Role extension registry is frozen");
        }
        ResourceLocation id = def.key().location();
        validateNamespace(id);
        if (managedRoles.containsKey(id)) {
            throw new IllegalArgumentException("ADD role already registered: " + id);
        }
        if (TMMRoles.getRole(id) != null) {
            throw new IllegalArgumentException("Role id already exists in TMMRoles: " + id);
        }
        SRERole role = ManagedSRERole.compile(def);
        // Record before TMMRoles so the legacy-scan mixin skips this ADD.
        managedRoles.put(id, role);
        TMMRoles.registerRole(role);
        tmmAccessible = true;
        LOGGER.info("Registered ADD role {}", id);
        return role;
    }

    /**
     * Registers an already-compiled {@link SRERole} produced by a provider
     * transaction. The transaction pre-validates ownership, duplicates and
     * {@code TMMRoles} collisions, so this is the single physical write.
     * The same instance is stored and registered so object identity
     * ({@code HabiRoles.X == TMMRoles.getRole(id)}) is preserved.
     */
    synchronized SRERole registerAdd(SRERole role) {
        ResourceLocation id = role.identifier();
        if (frozen) {
            throw new IllegalStateException("Role extension registry is frozen");
        }
        if (managedRoles.containsKey(id)) {
            throw new IllegalArgumentException("ADD role already registered: " + id);
        }
        managedRoles.put(id, role);
        TMMRoles.registerRole(role);
        tmmAccessible = true;
        LOGGER.info("Registered ADD role {}", id);
        return role;
    }

    /**
     * Captures the mutable registration tables before a provider transaction
     * commits.  The transaction restores this snapshot if any later declaration
     * (including hooks/state/action/capability) fails, so no partial provider
     * definition is exposed to the next provider.
     */
    synchronized RegistrationSnapshot snapshotForTransaction() {
        return snapshotForTransaction(tmmAccessible);
    }

    /**
     * Captures the mutable registration tables before a provider transaction
     * commits. {@code captureTmm} must be true whenever the transaction can
     * physically write to {@code TMMRoles} (ADD or NEW_ID_WITH_ALIAS REPLACE),
     * even if this is the first time the upstream table is touched. A
     * hooks/state/action-only transaction must not initialize TMMRoles (which
     * requires the Minecraft bootstrap), and it never mutates TMMRoles anyway,
     * so there is nothing to restore.
     */
    synchronized RegistrationSnapshot snapshotForTransaction(boolean captureTmm) {
        boolean capture = captureTmm || tmmAccessible;
        return new RegistrationSnapshot(
                new LinkedHashMap<>(managedRoles),
                new LinkedHashMap<>(compiledReplacements),
                new ArrayList<>(patches),
                new ArrayList<>(replacements),
                new ArrayList<>(aliases),
                new LinkedHashMap<>(replacementByTarget),
                new HashSet<>(registeredEntryIds),
                new ArrayList<>(compiledEntries),
                new LinkedHashMap<>(aliasSourceOwners),
                new LinkedHashMap<>(aliasSourcesByFrom),
                new LinkedHashSet<>(providers),
                new LinkedHashSet<>(requiredClientProviders),
                frozen,
                tmmAccessible,
                capture ? new LinkedHashMap<>(TMMRoles.ROLES) : null,
                capture ? new ArrayList<>(TMMRoles.CACHE.MAFIA_ROLES) : null,
                capture ? new ArrayList<>(TMMRoles.COMPONENT_KEYS) : null);
    }

    /** Restores a snapshot captured by {@link #snapshotForTransaction()}. */
    synchronized void restoreTransactionSnapshot(RegistrationSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        // TMMRoles has no unregister API.  Capture and restore every mutable
        // table it maintains rather than trying to infer derived-cache entries
        // from the managed role set (which would accidentally remove upstream
        // component keys too).
        if (snapshot.tmmRoles() != null) {
            TMMRoles.ROLES.clear();
            TMMRoles.ROLES.putAll(snapshot.tmmRoles());
        }
        if (snapshot.mafiaRoles() != null) {
            TMMRoles.CACHE.MAFIA_ROLES.clear();
            TMMRoles.CACHE.MAFIA_ROLES.addAll(snapshot.mafiaRoles());
        }
        if (snapshot.componentKeys() != null) {
            TMMRoles.COMPONENT_KEYS.clear();
            TMMRoles.COMPONENT_KEYS.addAll(snapshot.componentKeys());
        }
        this.managedRoles = new LinkedHashMap<>(snapshot.managedRoles());
        this.compiledReplacements = new LinkedHashMap<>(snapshot.compiledReplacements());
        this.patches.clear();
        this.patches.addAll(snapshot.patches());
        this.replacements.clear();
        this.replacements.addAll(snapshot.replacements());
        this.aliases.clear();
        this.aliases.addAll(snapshot.aliases());
        this.aliasSourceOwners.clear();
        this.aliasSourceOwners.putAll(snapshot.aliasSourceOwners());
        this.aliasSourcesByFrom.clear();
        this.aliasSourcesByFrom.putAll(snapshot.aliasSourcesByFrom());
        this.replacementByTarget.clear();
        this.replacementByTarget.putAll(snapshot.replacementByTarget());
        this.registeredEntryIds.clear();
        this.registeredEntryIds.addAll(snapshot.registeredEntryIds());
        this.providers.clear();
        this.providers.addAll(snapshot.providers());
        this.requiredClientProviders.clear();
        this.requiredClientProviders.addAll(snapshot.requiredClientProviders());
        this.compiledEntries = List.copyOf(snapshot.compiledEntries());
        this.frozen = snapshot.frozen();
        this.tmmAccessible = snapshot.tmmAccessible();
    }

    // ------------------------------------------------------------------
    // MODIFY
    // ------------------------------------------------------------------

    /**
     * Registers a reversible {@code MODIFY} patch. The provider mod id is captured
     * by the registrar from the entrypoint.
     */
    public synchronized void modify(String provider, RolePatch patch) {
        if (frozen) {
            throw new IllegalStateException("Role extension registry is frozen");
        }
        requireProvider(provider);
        validateEntryKey(provider, patch.entryKey());
        String entryId = entryId(provider, patch);
        if (!registeredEntryIds.add(entryId)) {
            throw new IllegalArgumentException("Duplicate MODIFY entryId: " + entryId
                    + "; set a distinct entryKey for each declaration");
        }
        patches.add(new ManagedPatch(provider, patch));
        LOGGER.info("Registered MODIFY {} by {}", patch.target(), provider);
    }

    // ------------------------------------------------------------------
    // REPLACE
    // ------------------------------------------------------------------

    /**
     * Registers a {@code REPLACE} operation. Multiple providers may claim the
     * same target (audit P2-1): every declaration is kept for the diagnostic
     * view, the target enters {@code CONFLICT} until exactly one candidate is
     * enabled or an administrator picks a {@code <target>#replace} winner, and
     * no unrelated declaration from the provider transaction is rolled back.
     */
    public synchronized void replace(String provider, RoleReplacement replacement) {
        if (frozen) {
            throw new IllegalStateException("Role extension registry is frozen");
        }
        requireProvider(provider);
        validateReplacementIdentity(provider, replacement);
        validateEntryKey(provider, replacement.entryKey());
        String entryId = entryId(provider, replacement);
        if (!registeredEntryIds.add(entryId)) {
            throw new IllegalArgumentException("Duplicate REPLACE entryId: " + entryId
                    + "; set a distinct entryKey for each declaration");
        }
        ResourceLocation target = replacement.target().location();
        List<String> owners = replacementByTarget.computeIfAbsent(target, ignored -> new ArrayList<>());
        if (!owners.isEmpty()) {
            LOGGER.warn("REPLACE target {} is claimed by both {} and {}; marked CONFLICT until a winner is configured",
                    target, owners, entryId);
        }
        owners.add(entryId);
        replacements.add(new ManagedReplacement(provider, replacement));
        LOGGER.info("Registered REPLACE {} by {}", target, provider);
    }

    // ------------------------------------------------------------------
    // ALIAS
    // ------------------------------------------------------------------

    /**
     * Registers an {@code ALIAS} redirecting an old id to a canonical id. The
     * canonical ({@code to}) id must live in the provider's namespace.
     */
    public synchronized void alias(String provider, RoleAlias alias) {
        if (frozen) {
            throw new IllegalStateException("Role extension registry is frozen");
        }
        requireProvider(provider);
        if (!alias.to().namespace().equals(provider)) {
            throw new IllegalArgumentException("Alias target " + alias.to()
                    + " must be in the provider's namespace " + provider);
        }
        String entryId = entryId(provider, alias);
        if (!registeredEntryIds.add(entryId)) {
            throw new IllegalArgumentException("Duplicate ALIAS entryId: " + entryId);
        }
        // The alias SOURCE (from) is the exclusive conflict key (audit P0-3): two
        // declarations redirecting the same old id to different canonical ids would
        // otherwise resolve by registration order. Both are kept registered so the
        // diagnostic view can mark them CONFLICT; resolution uses the effective
        // (winner or non-conflicted) mapping below.
        String previousOwner = aliasSourceOwners.putIfAbsent(alias.from().location(), entryId);
        aliasSourcesByFrom.computeIfAbsent(alias.from().location(), k -> new ArrayList<>()).add(entryId);
        if (previousOwner != null) {
            LOGGER.warn("ALIAS source {} is claimed by both {} and {}; marked CONFLICT until a winner is configured",
                    alias.from().location(), previousOwner, entryId);
        }
        aliases.add(new ManagedAlias(provider, alias));
        LOGGER.info("Registered ALIAS {} -> {} by {}", alias.from(), alias.to(), provider);
    }

    // ------------------------------------------------------------------
    // Freeze + cross-validation
    // ------------------------------------------------------------------

    /**
     * Prevents further registrations, validates alias rings and compiles the
     * replacement definitions into their effective roles. Called on server start.
     */
    public synchronized void freeze() {
        if (frozen) {
            return;
        }
        validateAliasCycles();
        compileReplacements();
        // Relations are intentionally NOT linked here. Snapshot activation owns
        // all relation writes so ADD/REPLACE/MODIFY relations follow the current
        // effective snapshot and are restored/reapplied in a fixed order.
        registerDeclaredSkills();
        // Unified v1+v2 diagnostic view; failures here must not break server start.
        recomputeCompiledEntries();
        // Report (never throw on) MODIFY/REPLACE targets that resolve to no role.
        warnDanglingTargets();
        this.frozen = true;
        LOGGER.info("RoleExtensionRegistry frozen: {} patches, {} replacements, {} aliases",
                patches.size(), replacements.size(), aliases.size());
    }

    /**
     * Recomputes the unified v1+v2 entry view and the compiled replacement
     * surfaces. Called at freeze and again after any {@code roleExtensionsV2}
     * config change so DISABLED/CONFLICT statuses and the REPLACE winner
     * (audit P2-1) reflect the live toggles without a server restart.
     */
    public void recomputeCompiledEntries() {
        try {
            compileReplacements();
        } catch (RuntimeException e) {
            // A mid-recompute failure must leave the previously compiled
            // replacements intact (audit M2); compileReplacements only swaps
            // the published map after every entry validated.
            LOGGER.error("Replacement compilation failed; keeping the previous compiled replacements", e);
        }
        try {
            this.compiledEntries = RoleConflictAnalyzer.analyze();
        } catch (RuntimeException e) {
            LOGGER.error("Role conflict analysis failed; falling back to v2-only entries", e);
            this.compiledEntries = v2Entries();
        }
    }

    /**
     * The unified v1+v2 entry view. Frozen: the cached compile (refreshed by
     * {@link #recomputeCompiledEntries()} after a config change). Pre-freeze:
     * the live v2-only view so diagnostics always reflect current registrations
     * (no v1 registry exists until server start).
     */
    public List<ManagedRoleEntry<?>> getCompiledEntries() {
        if (!frozen) {
            return v2Entries();
        }
        return compiledEntries;
    }

    /**
     * Returns every alias whose canonical target is not present in {@code knownIds}.
     * Used for diagnostics and tests; a dangling alias simply does not resolve.
     */
    public List<RoleAlias> validateAliasTargets(Set<ResourceLocation> knownIds) {
        List<RoleAlias> dangling = new ArrayList<>();
        for (ManagedAlias ma : aliases) {
            if (!knownIds.contains(ma.alias().to().location())) {
                dangling.add(ma.alias());
            }
        }
        return dangling;
    }

    /**
     * Returns diagnostic strings for MODIFY/REPLACE declarations whose target
     * does not resolve to any known role id. REPLACE targets must pre-exist
     * ({@code preExistingIds}: upstream + v2 ADD); MODIFY targets may also point
     * at a compiled replacement id ({@code liveIds} = pre-existing + compiled).
     * Diagnostic-only: a dangling target simply never applies.
     */
    public List<String> danglingModifyReplaceTargets(Set<ResourceLocation> preExistingIds,
                                                      Set<ResourceLocation> liveIds) {
        List<String> dangling = new ArrayList<>();
        for (ManagedReplacement mr : replacements) {
            ResourceLocation target = mr.replacement().target().location();
            if (!preExistingIds.contains(target)) {
                dangling.add("REPLACE target " + target
                        + " (provider " + mr.provider() + ") does not exist");
            }
        }
        for (ManagedPatch mp : patches) {
            ResourceLocation target = mp.patch().target().location();
            if (!liveIds.contains(target)) {
                dangling.add("MODIFY target " + target
                        + " (provider " + mp.provider() + ") does not exist");
            }
        }
        return dangling;
    }

    /** Logs (does not throw) any MODIFY/REPLACE target that resolves to no known role. */
    private void warnDanglingTargets() {
        Set<ResourceLocation> preExisting = preExistingRoleIds();
        Set<ResourceLocation> live = knownRoleIds();
        for (String message : danglingModifyReplaceTargets(preExisting, live)) {
            LOGGER.warn(message);
        }
    }

    private void validateAliasCycles() {
        // Only the EFFECTIVE mapping is validated: conflicted duplicate-source
        // aliases are inert (audit P0-3), so they cannot mask or create cycles.
        Map<ResourceLocation, ResourceLocation> map = effectiveAliasMap();
        for (ResourceLocation start : map.keySet()) {
            Set<ResourceLocation> seen = new HashSet<>();
            ResourceLocation cur = start;
            while (cur != null && map.containsKey(cur)) {
                if (!seen.add(cur)) {
                    throw new IllegalStateException("Alias cycle detected at " + start);
                }
                cur = map.get(cur);
            }
        }
    }

    /**
     * TMMRoles' static initializer touches vanilla registries ({@code MobEffects}),
     * so a first access without the Minecraft bootstrap (unit tests) throws and
     * permanently poisons the class. Production always bootstraps long before
     * freeze, so probe once and cache. This replaces the old {@code tmmAccessible}
     * gate that required a prior ADD before a NEW_ID_WITH_ALIAS replacement
     * could ever reach the upstream registry (audit L12).
     */
    private boolean canTouchTmmRoles() {
        if (tmmAccessible) {
            return true;
        }
        Boolean reachable = tmmReachable;
        if (reachable == null) {
            try {
                TMMRoles.getRole(ResourceLocation.parse("habitrain_core:tmm_reachability_probe"));
                reachable = true;
            } catch (Throwable t) {
                reachable = false;
            }
            tmmReachable = reachable;
        }
        return reachable;
    }

    private void compileReplacements() {
        Map<ResourceLocation, ManagedSRERole> compiled = new LinkedHashMap<>();
        Set<ResourceLocation> claimed = new HashSet<>(managedRoles.keySet());
        List<ManagedSRERole> pendingTmmRegisters = new ArrayList<>();
        boolean tmmReachableNow = canTouchTmmRoles();
        for (ManagedReplacement mr : replacements) {
            // Audit P2-1: only the effective winner per target compiles a surface
            // role. Conflicted/disabled candidates keep their diagnostics but
            // never surface (and never register into TMMRoles).
            if (effectiveReplacementFor(mr.replacement().target().location()) != mr) {
                continue;
            }
            RoleReplacement replacement = mr.replacement();
            ResourceLocation target = replacement.target().location();
            // Reuse the instance compiled for this exact source so recomputes never
            // fork the role id into two live objects (audit S2).
            ManagedSRERole role = replacementCompileCache
                    .getOrDefault(target, Map.of())
                    .get(replacement);
            if (role == null) {
                role = RoleExtensionCompiler.compileReplacement(replacement);
                replacementCompileCache
                        .computeIfAbsent(target, k -> new LinkedHashMap<>())
                        .put(replacement, role);
            }
            ResourceLocation rid = role.identifier();
            if (replacement.identity() == com.habitrain.core.api.role.v2.definition.ReplacementIdentity.NEW_ID_WITH_ALIAS) {
                if (!claimed.add(rid) || compiled.containsKey(rid)) {
                    throw new IllegalStateException(
                            "NEW_ID_WITH_ALIAS replacement id collides with an existing role: " + rid);
                }
                if (tmmReachableNow) {
                    SRERole existing = TMMRoles.getRole(rid);
                    if (existing != null && existing != role) {
                        throw new IllegalStateException(
                                "NEW_ID_WITH_ALIAS replacement id already exists in TMMRoles: " + rid);
                    }
                    if (existing == null) {
                        pendingTmmRegisters.add(role);
                    }
                }
            }
            compiled.put(rid, role);
        }
        // Publish the fully built map only after every entry validated: a failure
        // mid-loop must leave the previous compile intact (audit M2).
        this.compiledReplacements = compiled;
        // Audit L12: register NEW_ID_WITH_ALIAS roles whenever the upstream table
        // is reachable, not only after some ADD already touched it.
        for (ManagedSRERole role : pendingTmmRegisters) {
            if (TMMRoles.getRole(role.identifier()) == null) {
                TMMRoles.registerRole(role);
                tmmAccessible = true;
            }
        }
    }

    /**
     * The compiled replacement with any {@code MODIFY} patches folded on top.
     * Patches targeting the hidden base apply first, then patches targeting the
     * replacement's own id, so a REPLACE+MODIFY pair composes onto the surfaced
     * role instead of the hidden baseline. The overlay is materialized onto the
     * compiled replacement object itself, preserving its identity.
     */
    public SRERole applyModifiesToReplacement(RoleReplacement replacement) {
        ManagedSRERole compiled = compiledReplacement(replacement);
        if (compiled == null) {
            return null;
        }
        ResourceLocation targetId = replacement.target().location();
        ResourceLocation replacementId = compiled.identifier();
        List<ConfiguredPatch> combined = new ArrayList<>();
        if (targetId != null && !targetId.equals(replacementId)) {
            combined.addAll(configuredPatchesFor(targetId));
        }
        combined.addAll(configuredPatchesFor(replacementId));
        return RoleRuntimeOverlayApplier.applyModifiesAndReturnConfigured(compiled, combined);
    }

    /**
     * Resolves stored relation keys onto compiled ADD / REPLACE roles. Counterpart
     * lookup uses the managed maps first; {@code TMMRoles} is only consulted after
     * an {@code ADD} has already initialized it, so unit tests that freeze without
     * calling {@link #add} stay bootstrap-safe.
     */
    private void linkStoredRelations() {
        for (SRERole managed : managedRoles.values()) {
            if (managed instanceof ManagedSRERole mm && mm.relationProfile() != null) {
                RoleBaselineStore.captureRelationGraph(managed, mm.relationProfile(), this::resolveForLink);
                RoleExtensionCompiler.linkRelations(managed, mm.relationProfile(), this::resolveForLink);
            }
        }
        for (ManagedSRERole replacement : compiledReplacements.values()) {
            if (replacement.relationProfile() != null) {
                RoleBaselineStore.captureRelationGraph(replacement, replacement.relationProfile(), this::resolveForLink);
                RoleExtensionCompiler.linkRelations(replacement, replacement.relationProfile(), this::resolveForLink);
            }
        }
        for (ManagedPatch mp : patches) {
            RolePatch patch = mp.patch();
            if (patch.occupation() == null && patch.opposing() == null && patch.related() == null) {
                continue;
            }
            // MODIFY relation keys are folded into the overlay and linked onto the
            // ORIGINAL object by RoleRuntimeOverlayApplier (which captures the
            // baseline first), so nothing happens here at freeze time.
        }
    }

    private @Nullable SRERole resolveForLink(com.habitrain.core.api.role.v2.RoleKey key) {
        ResourceLocation id = key.location();
        SRERole managed = managedRoles.get(id);
        if (managed != null) {
            return managed;
        }
        ManagedSRERole replacement = compiledReplacements.get(id);
        if (replacement != null) {
            return replacement;
        }
        if (tmmAccessible) {
            return TMMRoles.getRole(id);
        }
        return null;
    }

    /**
     * Registers ADD / REPLACE skills that carry a finished
     * {@link io.wifi.starrailexpress.api.RoleSkill.Definition}. Skipped in unit
     * tests that never touched {@code TMMRoles}.
     */
    private void registerDeclaredSkills() {
        if (!tmmAccessible) {
            return;
        }
        for (SRERole managed : managedRoles.values()) {
            if (managed instanceof ManagedSRERole mm) {
                registerSkills(managed.identifier(), mm.skills());
            }
        }
        for (ManagedSRERole replacement : compiledReplacements.values()) {
            registerSkills(replacement.identifier(), replacement.skills());
        }
    }

    private static void registerSkills(ResourceLocation roleId,
                                       java.util.List<com.habitrain.core.api.role.v2.skill.RoleSkillSpec> skills) {
        if (roleId == null || skills == null || skills.isEmpty()) {
            return;
        }
        io.wifi.starrailexpress.api.RoleSkill.Definition[] defs = skills.stream()
                .map(com.habitrain.core.api.role.v2.skill.RoleSkillSpec::definition)
                .filter(java.util.Objects::nonNull)
                .toArray(io.wifi.starrailexpress.api.RoleSkill.Definition[]::new);
        if (defs.length == 0) {
            return;
        }
        io.wifi.starrailexpress.api.RoleSkill.register(roleId, defs);
    }

    // ------------------------------------------------------------------
    // ALIAS conflict detection (audit P0-3)
    // ------------------------------------------------------------------

    /**
     * The ALIAS entry ids participating in an unresolved duplicate-source
     * conflict. Only config-ENABLED entries compete for a source; a configured
     * winner ({@code RoleExtensionConfigService.winnerFor(from, "alias")})
     * resolves the conflict and only the non-winner entries stay conflicted.
     * Disabling one side removes it from the set, so the other recovers without
     * a restart. Resolution ({@link #resolveAlias}) and snapshot aliasing
     * ({@link #activeAliases}) never see conflicted entries.
     */
    public Set<String> conflictingAliasEntryIds() {
        Map<ResourceLocation, List<ManagedAlias>> byFrom = new LinkedHashMap<>();
        for (ManagedAlias ma : aliases) {
            String entryId = entryId(ma.provider(), ma.alias());
            if (RoleExtensionConfigService.INSTANCE.gateFor(ma.provider(), entryId)
                    != RoleExtensionConfigService.EntryGate.ENABLED) {
                continue;
            }
            byFrom.computeIfAbsent(ma.alias().from().location(), k -> new ArrayList<>()).add(ma);
        }
        Set<String> conflicting = new HashSet<>();
        for (var e : byFrom.entrySet()) {
            List<ManagedAlias> list = e.getValue();
            if (list.size() < 2) {
                continue;
            }
            String winner = RoleExtensionConfigService.INSTANCE.winnerFor(e.getKey(), "alias");
            if (winner != null && list.stream()
                    .anyMatch(ma -> winner.equals(entryId(ma.provider(), ma.alias())))) {
                for (ManagedAlias ma : list) {
                    if (!winner.equals(entryId(ma.provider(), ma.alias()))) {
                        conflicting.add(entryId(ma.provider(), ma.alias()));
                    }
                }
            } else {
                for (ManagedAlias ma : list) {
                    conflicting.add(entryId(ma.provider(), ma.alias()));
                }
            }
        }
        return Set.copyOf(conflicting);
    }

    /**
     * The deterministic redirect map used by {@link #resolveAlias} and
     * {@link #validateAliasCycles}: enabled, non-conflicted (winner-resolved)
     * aliases only, so a duplicate source can never depend on registration order.
     */
    private Map<ResourceLocation, ResourceLocation> effectiveAliasMap() {
        Set<String> conflicted = conflictingAliasEntryIds();
        Map<ResourceLocation, ResourceLocation> map = new LinkedHashMap<>();
        for (ManagedAlias ma : aliases) {
            String entryId = entryId(ma.provider(), ma.alias());
            if (conflicted.contains(entryId)) {
                continue;
            }
            if (RoleExtensionConfigService.INSTANCE.gateFor(ma.provider(), entryId)
                    != RoleExtensionConfigService.EntryGate.ENABLED) {
                continue;
            }
            map.put(ma.alias().from().location(), ma.alias().to().location());
        }
        return map;
    }

    // ------------------------------------------------------------------
    // Resolution helpers (used by the catalog)
    // ------------------------------------------------------------------

    /** Whether the given id was added through the v2 {@code ADD} model. */
    public boolean isAdded(ResourceLocation id) {
        return managedRoles.containsKey(id);
    }

    /** The compiled managed role for an added id, or {@code null} if not added. */
    public @Nullable SRERole getManagedRole(ResourceLocation id) {
        return managedRoles.get(id);
    }

    /** Whether the given id is the target of an active v2 {@code REPLACE}. */
    public boolean isReplaced(ResourceLocation id) {
        return replacementFor(id) != null;
    }

    /**
     * Whether the given id is the {@code NEW_ID_WITH_ALIAS} (or
     * {@code PRESERVE_TARGET_ID}) replacement role surfaced by a compiled v2
     * {@code REPLACE}. Distinct from {@link #isReplaced}, which asks about the
     * hidden <em>target</em>: a replacement id is not itself a replaced target.
     */
    public boolean isReplacementRoleId(ResourceLocation id) {
        return compiledReplacements.containsKey(id);
    }

    /**
     * Whether the given id is a compiled {@code NEW_ID_WITH_ALIAS} replacement id
     * (a synthetic id that must only surface while its owning REPLACE is active).
     * A disabled NEW_ID_WITH_ALIAS replacement is still registered in TMMRoles and
     * would otherwise leak into the effective view as an ordinary baseline role.
     */
    public boolean isNewIdReplacementRoleId(ResourceLocation id) {
        for (ManagedReplacement mr : replacements) {
            RoleReplacement repl = mr.replacement();
            if (repl.identity() != com.habitrain.core.api.role.v2.definition.ReplacementIdentity.NEW_ID_WITH_ALIAS) {
                continue;
            }
            if (id.equals(repl.replacement().key().location())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an {@code ADD} has already initialized the upstream {@code TMMRoles}
     * registry in this process. Only then may resolvers consult {@code TMMRoles}
     * directly; a bare unit test that never called {@link #add} stays
     * bootstrap-safe.
     */
    public boolean isTmmAccessible() {
        return tmmAccessible;
    }

    /**
     * The effective v2 {@code REPLACE} for a target under the audit P2-1
     * conflict model, or {@code null} when none may activate:
     * <ul>
     *   <li>exactly one config-enabled candidate → that candidate;</li>
     *   <li>a configured {@code <target>#replace} winner that is enabled → the winner;</li>
     *   <li>multiple enabled candidates without a valid winner → {@code null}
     *       (all stay CONFLICT; the original target remains visible);</li>
     *   <li>no enabled candidate → {@code null} (target stays visible).</li>
     * </ul>
     */
    public @Nullable ManagedReplacement effectiveReplacementFor(ResourceLocation target) {
        List<ManagedReplacement> enabled = replacements.stream()
                .filter(mr -> mr.replacement().target().location().equals(target))
                .filter(mr -> RoleExtensionConfigService.INSTANCE.gateFor(
                        mr.provider(), entryId(mr.provider(), mr.replacement()))
                        == RoleExtensionConfigService.EntryGate.ENABLED)
                .toList();
        if (enabled.isEmpty()) {
            return null;
        }
        if (enabled.size() == 1) {
            return enabled.get(0);
        }
        String winner = RoleExtensionConfigService.INSTANCE.winnerFor(target, "replace");
        if (winner != null) {
            for (ManagedReplacement mr : enabled) {
                if (entryId(mr.provider(), mr.replacement()).equals(winner)) {
                    return mr;
                }
            }
        }
        return null; // unresolved multi-candidate conflict: nothing activates
    }

    /** The effective v2 {@code REPLACE} declaration owning the target, or {@code null}. */
    public @Nullable RoleReplacement replacementFor(ResourceLocation target) {
        ManagedReplacement effective = effectiveReplacementFor(target);
        return effective == null ? null : effective.replacement();
    }

    /** The compiled replacement role for a replacement definition, or {@code null}. */
    public @Nullable ManagedSRERole compiledReplacement(RoleReplacement replacement) {
        return compiledReplacements.get(replacement.replacement().key().location());
    }

    /** Whether the given id has at least one active v2 {@code MODIFY} patch. */
    public boolean isModified(ResourceLocation id) {
        return !patchesFor(id).isEmpty();
    }

    /**
     * The v2 {@code MODIFY} patches targeting the given id, sorted by priority,
     * provider mod id, then entryKey (stable application order).
     */
    public List<RolePatch> patchesFor(ResourceLocation id) {
        return patches.stream()
                .filter(mp -> mp.patch().target().location().equals(id))
                .sorted(Comparator
                        .comparingInt((ManagedPatch mp) -> mp.patch().priority().value())
                        .thenComparing(mp -> mp.provider())
                        .thenComparing(mp -> mp.patch().entryKey() == null ? "" : mp.patch().entryKey()))
                .map(ManagedPatch::patch)
                .toList();
    }

    /**
     * The {@code MODIFY} patches enabled by the v2 config (fix-doc §13.1),
     * paired with their core entryId for conflict-winner resolution. Same
     * ordering as {@link #patchesFor}; disabled providers/entries are excluded.
     */
    public List<ConfiguredPatch> configuredPatchesFor(ResourceLocation id) {
        List<ConfiguredPatch> out = new ArrayList<>();
        Set<String> conflicts = RoleConflictAnalyzer.conflictingV2ModifyEntryIds();
        for (ManagedPatch mp : patches) {
            RolePatch patch = mp.patch();
            if (!patch.target().location().equals(id)) {
                continue;
            }
            String entryId = entryId(mp.provider(), patch);
            if (conflicts.contains(entryId)) {
                continue;
            }
            if (RoleExtensionConfigService.INSTANCE.gateFor(mp.provider(), entryId)
                    != RoleExtensionConfigService.EntryGate.ENABLED) {
                continue;
            }
            out.add(new ConfiguredPatch(patch, entryId));
        }
        out.sort(Comparator
                .comparingInt((ConfiguredPatch cp) -> cp.patch().priority().value())
                .thenComparing(cp -> entryIdProvider(cp))
                .thenComparing(cp -> cp.patch().entryKey() == null ? "" : cp.patch().entryKey()));
        return out;
    }

    /** The provider mod id for a configured patch (from the entry id prefix). */
    private static String entryIdProvider(ConfiguredPatch cp) {
        String entryId = cp.entryId();
        int idx = entryId.indexOf('$');
        return idx > 0 ? entryId.substring(0, idx) : "";
    }

    /** Whether any config-enabled {@code MODIFY} patch targets the id. */
    public boolean isActiveModified(ResourceLocation id) {
        // Keep this aligned with the compiler: an unresolved v2-v2 conflict
        // does not own a target and therefore must not suppress a legacy entry.
        return !configuredPatchesFor(id).isEmpty();
    }

    /** Whether an active (config-enabled) v2 {@code REPLACE} owns the target. */
    public boolean isActiveReplaced(ResourceLocation id) {
        return replacementFor(id) != null && isReplacementActive(id);
    }

    /** Whether the effective v2 {@code REPLACE} for {@code target} is active (audit P2-1). */
    public boolean isReplacementActive(ResourceLocation target) {
        return effectiveReplacementFor(target) != null;
    }

    /**
     * Whether {@code id} is the surfaced role of the effective compiled v2
     * {@code REPLACE}. A disabled or conflicted replacement leaves its raw id
     * (and the hidden target) visible in the effective view.
     */
    public boolean isActiveReplacementRoleId(ResourceLocation id) {
        for (RoleReplacement repl : activeReplacements()) {
            ManagedSRERole compiled = compiledReplacements.get(repl.replacement().key().location());
            if (compiled != null && compiled.identifier() != null
                    && compiled.identifier().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a v2 {@code ADD} role exists and its config gate is enabled. */
    public boolean isAddedActive(ResourceLocation id) {
        return managedRoles.containsKey(id)
                && RoleExtensionConfigService.INSTANCE.gateFor(id.getNamespace(), id.toString())
                == RoleExtensionConfigService.EntryGate.ENABLED;
    }

    /**
     * The active ADD/REPLACE relation links that snapshot activation must write.
     * MODIFY relations are handled separately from each effective role's overlay.
     */
    public List<RoleRelationLink> activeRelationLinks() {
        List<RoleRelationLink> out = new ArrayList<>();
        for (SRERole managed : managedRoles.values()) {
            if (managed instanceof ManagedSRERole mm
                    && mm.relationProfile() != null && isAddedActive(managed.identifier())) {
                out.add(new RoleRelationLink(managed, mm.relationProfile()));
            }
        }
        for (RoleReplacement repl : activeReplacements()) {
            ManagedSRERole compiled = compiledReplacement(repl);
            if (compiled != null && compiled.relationProfile() != null) {
                out.add(new RoleRelationLink(compiled, compiled.relationProfile()));
            }
        }
        return out;
    }

    /**
     * The effective v2 {@code REPLACE} operations (audit P2-1): only the winner
     * (or the single enabled candidate) per target surfaces in snapshots; an
     * unresolved multi-candidate conflict surfaces nothing.
     */
    public List<RoleReplacement> activeReplacements() {
        List<RoleReplacement> out = new ArrayList<>();
        for (ManagedReplacement mr : replacements) {
            if (effectiveReplacementFor(mr.replacement().target().location()) == mr) {
                out.add(mr.replacement());
            }
        }
        return out;
    }

    /**
     * The effective v2 {@code ALIAS} entries: config-enabled and not part of an
     * unresolved duplicate-source conflict (audit P0-3). Snapshot compilation
     * and diagnostics must only ever see this deterministic set.
     */
    public List<RoleAlias> activeAliases() {
        Set<String> conflicted = conflictingAliasEntryIds();
        List<RoleAlias> out = new ArrayList<>();
        for (ManagedAlias ma : aliases) {
            String entryId = entryId(ma.provider(), ma.alias());
            if (conflicted.contains(entryId)) {
                continue;
            }
            if (RoleExtensionConfigService.INSTANCE.gateFor(ma.provider(), entryId)
                    == RoleExtensionConfigService.EntryGate.ENABLED) {
                out.add(ma.alias());
            }
        }
        return out;
    }

    /**
     * Applies the active v2 {@code MODIFY} patches to a role, returning the
     * ORIGINAL object with its public spawn fields materialized and the folded
     * values registered in the overlay accessor (fix-doc §5.1).
     */
    public SRERole applyModifies(SRERole role) {
        return RoleRuntimeOverlayApplier.applyModifiesAndReturn(role);
    }

    /**
     * Resolves an id through the v2 {@code ALIAS} chain to its canonical id, or
     * {@code null} if the id is not an alias source.
     */
    public @Nullable ResourceLocation resolveAlias(ResourceLocation id) {
        // Deterministic resolution (audit P0-3): only enabled, non-conflicted
        // (winner-resolved) sources contribute; registration order is irrelevant.
        Map<ResourceLocation, ResourceLocation> map = effectiveAliasMap();
        ResourceLocation cur = id;
        Set<ResourceLocation> seen = new HashSet<>();
        while (cur != null && map.containsKey(cur)) {
            if (!seen.add(cur)) {
                return null; // cycle guard; freeze() should have rejected this
            }
            cur = map.get(cur);
        }
        return (cur == null || cur.equals(id)) ? null : cur;
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    /** Unmodifiable view of the added roles for diagnostics. */
    public Map<ResourceLocation, SRERole> getManagedRoles() {
        return Collections.unmodifiableMap(managedRoles);
    }

    /** Unmodifiable view of the compiled replacement roles for diagnostics. */
    public Map<ResourceLocation, ManagedSRERole> getCompiledReplacements() {
        return Collections.unmodifiableMap(compiledReplacements);
    }

    /** Unmodifiable view of the registered {@code MODIFY} patches. */
    public List<RolePatch> getPatches() {
        return patches.stream().map(ManagedPatch::patch).toList();
    }

    /** Unmodifiable view of the registered {@code REPLACE} operations. */
    public List<RoleReplacement> getReplacements() {
        return replacements.stream().map(ManagedReplacement::replacement).toList();
    }

    /** Unmodifiable view of the registered {@code ALIAS} entries. */
    public List<RoleAlias> getAliases() {
        return aliases.stream().map(ManagedAlias::alias).toList();
    }

    /** The set of provider mod ids that registered v2 entries. */
    public Set<String> getProviders() {
        Set<String> providers = new LinkedHashSet<>();
        for (ResourceLocation id : managedRoles.keySet()) {
            if (id.getNamespace() != null && !id.getNamespace().isBlank()) {
                providers.add(id.getNamespace());
            }
        }
        for (ManagedPatch mp : patches) {
            providers.add(mp.provider());
        }
        for (ManagedReplacement mr : replacements) {
            providers.add(mr.provider());
        }
        for (ManagedAlias ma : aliases) {
            providers.add(ma.provider());
        }
        return providers;
    }

    /**
     * The authoritative provider set (audit P1-4): every provider whose
     * registration transaction committed, covering all declaration types.
     */
    public Set<String> providerIds() {
        return Set.copyOf(providers);
    }

    /** Providers that explicitly declared a client-side requirement (audit P1-4). */
    public Set<String> requiredClientProviderIds() {
        return Set.copyOf(requiredClientProviders);
    }

    /**
     * Publishes a provider after its transaction committed. Called by
     * {@code ProviderRegistrationTransaction.commit} for every declaration
     * type, so a hooks/state/action-only provider shows up in the manifest.
     */
    synchronized void noteProvider(String providerId, boolean requiresClient) {
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        providers.add(providerId);
        if (requiresClient) {
            requiredClientProviders.add(providerId);
        }
    }

    /**
     * Shells every registered v2 declaration into the unified
     * {@link ManagedRoleEntry} shape (fix-doc §4.2), including the provider mod
     * id captured at registration. Status reflects the live v2 config
     * (fix-doc §13.1): structural invalidity (dangling MODIFY/REPLACE target,
     * missing ALIAS destination) reports {@code INVALID} first, then the
     * global/provider/entry gate reports {@code ACTIVE}/{@code DISABLED}.
     */
    public List<ManagedRoleEntry<?>> v2Entries() {
        List<ManagedRoleEntry<?>> out = new ArrayList<>();
        Set<ResourceLocation> known = knownRoleIds();
        Set<ResourceLocation> preExisting = preExistingRoleIds();
        for (ResourceLocation id : managedRoles.keySet()) {
            out.add(configured(
                    id.toString(),
                    id.getNamespace(),
                    id.getPath(),
                    RoleOperation.ADD,
                    RoleKey.of(id),
                    PatchPriority.NORMAL,
                    managedRoles.get(id),
                    "v2 ADD"));
        }
        for (ManagedPatch mp : patches) {
            RolePatch patch = mp.patch();
            ManagedRoleEntry<RolePatch> entry = configured(
                    entryId(mp.provider(), patch),
                    mp.provider(),
                    patch.entryKey() == null ? patch.target().path() : patch.entryKey(),
                    RoleOperation.MODIFY,
                    patch.target(),
                    patch.priority(),
                    patch,
                    "v2 MODIFY");
            ResourceLocation target = patch.target().location();
            if (!known.contains(target)) {
                entry = entry.withStatus(EntryStatus.INVALID,
                        "v2 MODIFY target " + target + " does not exist (provider " + mp.provider() + ")");
            }
            out.add(entry);
        }
        for (ManagedReplacement mr : replacements) {
            RoleReplacement replacement = mr.replacement();
            ResourceLocation target = replacement.target().location();
            ManagedRoleEntry<RoleReplacement> entry = configured(
                    entryId(mr.provider(), replacement),
                    mr.provider(),
                    replacement.entryKey() == null
                            ? target.getPath() : replacement.entryKey(),
                    RoleOperation.REPLACE,
                    replacement.target(),
                    PatchPriority.NORMAL,
                    replacement,
                    "v2 REPLACE");
            if (!preExisting.contains(target)) {
                entry = entry.withStatus(EntryStatus.INVALID,
                        "v2 REPLACE target " + target + " does not exist (provider " + mr.provider() + ")");
            } else {
                long enabled = replacements.stream()
                        .filter(m -> m.replacement().target().location().equals(target))
                        .filter(m -> RoleExtensionConfigService.INSTANCE.gateFor(
                                m.provider(), entryId(m.provider(), m.replacement()))
                                == RoleExtensionConfigService.EntryGate.ENABLED)
                        .count();
                if (enabled >= 2) {
                    String winner = RoleExtensionConfigService.INSTANCE.winnerFor(target, "replace");
                    if (winner == null || !winner.equals(entry.entryId())) {
                        entry = entry.withStatus(EntryStatus.CONFLICT,
                                "v2 REPLACE target " + target + " is claimed by multiple declarations; "
                                        + "configure a <target>#replace winner");
                    }
                }
            }
            out.add(entry);
        }
        Set<String> aliasConflicts = conflictingAliasEntryIds();
        for (ManagedAlias ma : aliases) {
            RoleAlias alias = ma.alias();
            String entryId = entryId(ma.provider(), alias);
            ManagedRoleEntry<RoleAlias> entry = configured(
                    entryId,
                    ma.provider(),
                    alias.from().toString(),
                    RoleOperation.ALIAS,
                    alias.to(),
                    PatchPriority.NORMAL,
                    alias,
                    "v2 ALIAS");
            if (aliasConflicts.contains(entryId)) {
                entry = entry.withStatus(EntryStatus.CONFLICT,
                        "v2 ALIAS source " + alias.from().location()
                                + " is claimed by multiple declarations; configure a conflict winner");
            } else if (!known.contains(alias.to().location())) {
                // A missing `from` is legal (migrating a retired id); a missing
                // destination can never resolve, so it is structurally invalid.
                entry = entry.withStatus(EntryStatus.INVALID,
                        "v2 ALIAS destination " + alias.to().location()
                                + " does not exist (provider " + ma.provider() + ")");
            }
            out.add(entry);
        }
        return out;
    }

    /**
     * Every role id the current process knows: v2 ADD roles, compiled v2
     * REPLACE surfaces, and the upstream {@code TMMRoles} registry (bootstrap-
     * safe for bare unit-test JVMs). Drives the INVALID diagnostics.
     */
    public Set<ResourceLocation> knownRoleIds() {
        Set<ResourceLocation> known = new HashSet<>(managedRoles.keySet());
        known.addAll(compiledReplacements.keySet());
        try {
            known.addAll(TMMRoles.ROLES.keySet());
        } catch (Throwable ignored) {
            // TMMRoles not bootstrapped (bare unit test): upstream ids unknown.
        }
        return known;
    }

    /** Ids that must pre-exist for REPLACE: upstream roles + v2 ADD. */
    public Set<ResourceLocation> preExistingRoleIds() {
        Set<ResourceLocation> preExisting = new HashSet<>(managedRoles.keySet());
        try {
            preExisting.addAll(TMMRoles.ROLES.keySet());
        } catch (Throwable ignored) {
            // TMMRoles not bootstrapped (bare unit test): upstream ids unknown.
        }
        return preExisting;
    }

    /** Applies the live config gate to a v2 entry shell. */
    private static <T> ManagedRoleEntry<T> configured(String entryId, String providerId, String entryKey,
                                                      RoleOperation op, RoleKey target, PatchPriority priority,
                                                      T declaration, String baseMessage) {
        RoleExtensionConfigService.EntryGate gate =
                RoleExtensionConfigService.INSTANCE.gateFor(providerId, entryId);
        if (gate == RoleExtensionConfigService.EntryGate.ENABLED) {
            return new ManagedRoleEntry<>(entryId, providerId, entryKey, op, target, priority,
                    declaration, EntryStatus.ACTIVE, baseMessage, false);
        }
        return new ManagedRoleEntry<>(entryId, providerId, entryKey, op, target, priority,
                declaration, EntryStatus.DISABLED,
                baseMessage + " (disabled by " + gate.name().toLowerCase(Locale.ROOT) + ")", false);
    }

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    private static void requireProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider mod id required");
        }
    }

    static void validateReplacementIdentity(String provider, RoleReplacement replacement) {
        ResourceLocation target = replacement.target().location();
        ResourceLocation replacementId = replacement.replacement().key().location();
        switch (replacement.identity()) {
            case KEEP_CANONICAL_ID, PRESERVE_TARGET_ID -> {
                if (!target.equals(replacementId)) {
                    throw new IllegalArgumentException(
                            "KEEP_CANONICAL_ID replacement must use the target id " + target
                                    + " but got " + replacementId);
                }
            }
            case NEW_ID_WITH_ALIAS -> {
                if (target.equals(replacementId)) {
                    throw new IllegalArgumentException(
                            "NEW_ID_WITH_ALIAS replacement must use a new id distinct from target " + target);
                }
                if (!replacementId.getNamespace().equals(provider)) {
                    throw new IllegalArgumentException(
                            "NEW_ID_WITH_ALIAS replacement id " + replacementId
                                    + " must be in the provider's namespace " + provider);
                }
            }
        }
    }

    static void validateEntryKey(String provider, @Nullable String entryKey) {
        if (entryKey == null || entryKey.isBlank()) {
            return;
        }
        try {
            ResourceLocation.fromNamespaceAndPath(provider, entryKey);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid entryKey for " + provider + ": " + entryKey, e);
        }
    }

    private static void validateNamespace(ResourceLocation id) {
        String ns = id.getNamespace();
        if (ns == null || ns.isBlank()) {
            throw new IllegalArgumentException("ADD role id must have a namespace");
        }
        if (FabricLoader.getInstance().getModContainer(ns).isEmpty()) {
            throw new IllegalArgumentException(
                    "ADD role namespace " + ns + " is not a loaded mod (id: " + id + ")");
        }
    }

    static String entryId(String provider, RolePatch patch) {
        String key = patch.entryKey() == null ? patch.target().path() : patch.entryKey();
        return provider + "$" + key + "@" + patch.target();
    }

    static String entryId(String provider, RoleReplacement replacement) {
        String key = replacement.entryKey() == null
                ? replacement.target().path() : replacement.entryKey();
        return provider + "$" + key + "@" + replacement.target();
    }

    static String entryId(String provider, RoleAlias alias) {
        return provider + "$" + alias.from() + "->" + alias.to();
    }

    private record ManagedPatch(String provider, RolePatch patch) {}
    private record ManagedReplacement(String provider, RoleReplacement replacement) {}
    private record ManagedAlias(String provider, RoleAlias alias) {}
    public record RoleRelationLink(SRERole role, com.habitrain.core.api.role.v2.definition.RoleRelationProfile profile) {}

    record RegistrationSnapshot(
            Map<ResourceLocation, SRERole> managedRoles,
            Map<ResourceLocation, ManagedSRERole> compiledReplacements,
            List<ManagedPatch> patches,
            List<ManagedReplacement> replacements,
            List<ManagedAlias> aliases,
            Map<ResourceLocation, List<String>> replacementByTarget,
            Set<String> registeredEntryIds,
            List<ManagedRoleEntry<?>> compiledEntries,
            Map<ResourceLocation, String> aliasSourceOwners,
            Map<ResourceLocation, List<String>> aliasSourcesByFrom,
            Set<String> providers,
            Set<String> requiredClientProviders,
            boolean frozen,
            boolean tmmAccessible,
            @Nullable Map<ResourceLocation, SRERole> tmmRoles,
            @Nullable List<SRERole> mafiaRoles,
            @Nullable List<ComponentKey<? extends RoleComponent>> componentKeys) {}
}