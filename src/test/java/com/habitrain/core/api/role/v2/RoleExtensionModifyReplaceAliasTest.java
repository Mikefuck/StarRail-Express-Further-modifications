package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.PatchPriority;
import com.habitrain.core.api.role.v2.definition.ReplacementIdentity;
import com.habitrain.core.api.role.v2.definition.RoleAlias;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.role.catalog.RoleCatalogImpl;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.ManagedSRERole;
import com.habitrain.core.role.extension.RoleExtensionCompiler;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.extension.RoleRuntimeOverlayApplier;
import com.habitrain.core.role.extension.RoleOperation;
import com.habitrain.core.role.extension.EntryStatus;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the v2 {@code MODIFY}/{@code REPLACE}/{@code ALIAS} registration model:
 * {@link RolePatch} builder validation, {@link RoleExtensionCompiler} merge
 * operations, {@link RoleReplacement} identity validation, {@link RoleAlias}
 * cycle detection, and the catalog integration for all three operations.
 *
 * <p>The registry's {@code modify}/{@code replace}/{@code alias} and
 * {@code freeze()} paths are pure (no {@code TMMRoles}/{@code FabricLoader}), so
 * they are exercised directly; state is reset between tests via reflection.
 */
class RoleExtensionModifyReplaceAliasTest {

    private static final ResourceLocation TARGET = ResourceLocation.parse("sre:vigilante");
    private static final int COLOR = 0xFFAA0000;

    @BeforeEach
    void resetRegistry() throws Exception {
        setField("managedRoles", new LinkedHashMap<>());
        setField("compiledReplacements", new LinkedHashMap<>());
        setField("patches", new ArrayList<>());
        setField("replacements", new ArrayList<>());
        setField("aliases", new ArrayList<>());
        setField("replacementByTarget", new LinkedHashMap<>());
        setField("registeredEntryIds", new LinkedHashSet<>());
        setField("frozen", false);
        setField("tmmAccessible", false);
        setField("aliasSourceOwners", new LinkedHashMap<>());
        setField("aliasSourcesByFrom", new LinkedHashMap<>());
        com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE.resetForTests();
        RoleSnapshotManager.INSTANCE.clear();
        RoleRuntimeOverlayApplier.clear();
    }

    // ------------------------------------------------------------------
    // RolePatch builder validation
    // ------------------------------------------------------------------

    @Test
    void builderRejectsEmptyPatch() {
        RolePatch.Builder b = RolePatch.builder(TARGET);
        assertThrows(IllegalStateException.class, b::build, "at least one field operation required");
    }

    @Test
    void builderDefaultsToNormalPriority() {
        RolePatch patch = RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build();
        assertEquals(PatchPriority.NORMAL, patch.priority());
        assertEquals(TARGET, patch.target().location());
    }

    // ------------------------------------------------------------------
    // RoleExtensionCompiler merge operations (pure fold -> CompiledModifyOverlay)
    // ------------------------------------------------------------------

    @Test
    void numericSetOverridesValue() {
        SRERole base = role(TARGET);
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlay(base,
                List.of(RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(5)).build()), null);
        assertEquals(5, overlay.defaultMax());
    }

    @Test
    void numericAddAccumulates() {
        SRERole base = role(TARGET);
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlay(base,
                List.of(RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.add(2)).build()), null);
        assertEquals(1 + 2, overlay.defaultMax());
    }

    @Test
    void numericMaxTakesLarger() {
        SRERole base = role(TARGET);
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlay(base,
                List.of(RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.max(10)).build()), null);
        assertEquals(10, overlay.defaultMax());
    }

    @Test
    void numericMinTakesSmaller() {
        SRERole base = role(TARGET);
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlay(base,
                List.of(RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.min(0)).build()), null);
        assertEquals(0, overlay.defaultMax());
    }

    @Test
    void booleanAndOrMerge() {
        SRERole base = role(TARGET); // innocent=false, canUseKiller=true
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlay(base, List.of(
                RolePatch.builder(TARGET)
                        .innocent(RolePatch.BooleanPatch.and(true))
                        .canUseKiller(RolePatch.BooleanPatch.or(false))
                        .build()), null);
        assertFalse(overlay.innocent(), "false AND true = false");
        assertTrue(overlay.canUseKiller(), "true OR false = true");
    }

    @Test
    void multiplePatchesFoldInOrder() {
        SRERole base = role(TARGET);
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlay(base, List.of(
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(3)).build(),
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.add(4)).build()), null);
        assertEquals(7, overlay.defaultMax(), "second patch folds on the first's result");
    }

    @Test
    void patchedRolePreservesBaseFactionWhenUnpatched() {
        // innocent=false, canUseKiller=false -> NormalRole derives neutrals=true.
        SRERole base = new NormalRole(TARGET, COLOR, false, false, SRERole.MoodType.REAL, 20, false);
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlay(base,
                List.of(RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build()), null);
        assertTrue(overlay.neutral(), "base neutral derivation must be preserved");
    }

    @Test
    void noPatchesReturnsNullOverlay() {
        SRERole base = role(TARGET);
        assertNull(RoleExtensionCompiler.compileModifyOverlay(base, List.of(), null),
                "no patches -> no overlay");
    }

    // ------------------------------------------------------------------
    // RoleReplacement identity validation
    // ------------------------------------------------------------------

    @Test
    void preserveTargetIdRequiresSameId() {
        RoleDefinition def = definition("habitrain_core", "shadow_killer");
        RoleReplacement repl = RoleReplacement.builder(RoleKey.of(TARGET), def)
                .identity(ReplacementIdentity.PRESERVE_TARGET_ID)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> RoleExtensionRegistry.INSTANCE.replace("habitrain_core", repl),
                "PRESERVE_TARGET_ID replacement must use the target id");
    }

    @Test
    void newIdWithAliasRequiresProviderNamespace() {
        RoleDefinition def = definition("othermod", "shadow_killer");
        RoleReplacement repl = RoleReplacement.builder(RoleKey.of(TARGET), def)
                .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> RoleExtensionRegistry.INSTANCE.replace("habitrain_core", repl),
                "NEW_ID_WITH_ALIAS replacement id must be in the provider namespace");
    }

    @Test
    void newIdWithAliasRequiresDistinctId() {
        RoleDefinition def = definition("sre", "vigilante");
        RoleReplacement repl = RoleReplacement.builder(RoleKey.of(TARGET), def)
                .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> RoleExtensionRegistry.INSTANCE.replace("habitrain_core", repl),
                "NEW_ID_WITH_ALIAS replacement id must differ from target");
    }

    @Test
    void replaceRejectsSecondOwnerForSameTarget() {
        RoleDefinition def1 = definition("habitrain_core", "shadow_killer");
        RoleDefinition def2 = definition("habitrain_core", "shadow_killer2");
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(TARGET), def1).identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        assertThrows(IllegalArgumentException.class,
                () -> RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                        RoleReplacement.builder(RoleKey.of(TARGET), def2).identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build()),
                "only one replacement may own a target");
    }

    @Test
    void danglingModifyReplaceTargetsAreReported() {
        ResourceLocation missingReplaceTarget = ResourceLocation.parse("sre:missing_role");
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleDefinition def = definition("habitrain_core", "shadow_killer");
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(missingReplaceTarget), def)
                        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());

        List<String> dangling = RoleExtensionRegistry.INSTANCE
                .danglingModifyReplaceTargets(java.util.Set.of(), java.util.Set.of());
        assertEquals(2, dangling.size());
        assertTrue(dangling.stream().anyMatch(s -> s.startsWith("REPLACE target " + missingReplaceTarget)));
        assertTrue(dangling.stream().anyMatch(s -> s.startsWith("MODIFY target " + TARGET)));

        List<String> resolved = RoleExtensionRegistry.INSTANCE
                .danglingModifyReplaceTargets(java.util.Set.of(TARGET, missingReplaceTarget),
                        java.util.Set.of(TARGET, missingReplaceTarget));
        assertTrue(resolved.isEmpty(), "known targets must not be reported as dangling");
    }

    // ------------------------------------------------------------------
    // RoleAlias validation + cycle detection
    // ------------------------------------------------------------------

    @Test
    void aliasRejectsSelfMapping() {
        assertThrows(IllegalArgumentException.class,
                () -> RoleAlias.of("habitrain_core", "doctor", "habitrain_core", "doctor"));
    }

    @Test
    void aliasRequiresCanonicalInProviderNamespace() {
        RoleAlias alias = RoleAlias.of("oldmod", "doctor", "othermod", "doctor");
        assertThrows(IllegalArgumentException.class,
                () -> RoleExtensionRegistry.INSTANCE.alias("habitrain_core", alias));
    }

    @Test
    void freezeRejectsAliasCycle() {
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("habitrain_core", "a", "habitrain_core", "b"));
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("habitrain_core", "b", "habitrain_core", "a"));
        assertThrows(IllegalStateException.class, RoleExtensionRegistry.INSTANCE::freeze,
                "alias ring must be rejected at freeze");
    }


    // ------------------------------------------------------------------
    // ALIAS duplicate-source conflicts (audit P0-3)
    // ------------------------------------------------------------------

    @Test
    void duplicateAliasSourceIsConflictedRegardlessOfOrder() {
        // Same provider, same from -> different to. Registration order must not decide.
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "doctor", "habitrain_core", "plague_doctor"));
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "doctor", "habitrain_core", "shadow_doctor"));

        assertEquals(2, RoleExtensionRegistry.INSTANCE.getAliases().size(),
                "both declarations stay registered for the diagnostic view");
        assertEquals(2, RoleExtensionRegistry.INSTANCE.conflictingAliasEntryIds().size(),
                "both sides of a duplicate source must be CONFLICT");
        assertNull(RoleExtensionRegistry.INSTANCE.resolveAlias(ResourceLocation.parse("oldmod:doctor")),
                "a conflicted source must not resolve by registration order");
        assertTrue(RoleExtensionRegistry.INSTANCE.activeAliases().isEmpty(),
                "conflicted aliases must never reach snapshot compilation");
    }

    @Test
    void duplicateAliasSourceAcrossProvidersIsConflicted() {
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "doctor", "habitrain_core", "plague_doctor"));
        RoleExtensionRegistry.INSTANCE.alias("othermod",
                RoleAlias.of("oldmod", "doctor", "othermod", "real_doctor"));

        assertEquals(2, RoleExtensionRegistry.INSTANCE.conflictingAliasEntryIds().size(),
                "cross-provider duplicate source is a conflict too");
        assertNull(RoleExtensionRegistry.INSTANCE.resolveAlias(ResourceLocation.parse("oldmod:doctor")));
    }

    @Test
    void v2EntriesMarkDuplicateAliasAsConflict() {
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "doctor", "habitrain_core", "plague_doctor"));
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "doctor", "habitrain_core", "shadow_doctor"));

        long conflicts = RoleExtensionRegistry.INSTANCE.v2Entries().stream()
                .filter(e -> e.operation() == RoleOperation.ALIAS)
                .filter(e -> e.status() == EntryStatus.CONFLICT)
                .count();
        assertEquals(2, conflicts, "both ALIAS entries must surface as CONFLICT in diagnostics");
    }

    @Test
    void configuredWinnerResolvesDuplicateAlias() {
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "doctor", "habitrain_core", "plague_doctor"));
        RoleExtensionRegistry.INSTANCE.alias("othermod",
                RoleAlias.of("oldmod", "doctor", "othermod", "real_doctor"));

        String winnerEntry = "habitrain_core$oldmod:doctor->habitrain_core:plague_doctor";
        com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE
                .setConflictWinner("oldmod:doctor#alias", winnerEntry);

        assertEquals(1, RoleExtensionRegistry.INSTANCE.conflictingAliasEntryIds().size(),
                "only the non-winner stays conflicted");
        assertEquals(RoleExtensionRegistry.INSTANCE.resolveAlias(ResourceLocation.parse("oldmod:doctor")),
                ResourceLocation.parse("habitrain_core:plague_doctor"),
                "resolution must follow the configured winner deterministically");
        assertEquals(1, RoleExtensionRegistry.INSTANCE.activeAliases().size(),
                "only the winning alias is active");
    }

    @Test
    void disablingOneSideRecoversTheOther() {
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "doctor", "habitrain_core", "plague_doctor"));
        RoleExtensionRegistry.INSTANCE.alias("othermod",
                RoleAlias.of("oldmod", "doctor", "othermod", "real_doctor"));
        assertEquals(2, RoleExtensionRegistry.INSTANCE.conflictingAliasEntryIds().size());

        // Disable the second provider's entry -> the first is no longer conflicted.
        String secondEntry = "othermod$oldmod:doctor->othermod:real_doctor";
        com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE
                .setEntryEnabled(secondEntry, false);

        assertTrue(RoleExtensionRegistry.INSTANCE.conflictingAliasEntryIds().isEmpty(),
                "disabling one side must restore the other without a restart");
        assertEquals(ResourceLocation.parse("habitrain_core:plague_doctor"),
                RoleExtensionRegistry.INSTANCE.resolveAlias(ResourceLocation.parse("oldmod:doctor")));
    }

    @Test
    void distinctSourcesAreNeverConflicted() {
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "doctor", "habitrain_core", "plague_doctor"));
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "nurse", "habitrain_core", "shadow_doctor"));
        assertTrue(RoleExtensionRegistry.INSTANCE.conflictingAliasEntryIds().isEmpty());
        assertEquals(ResourceLocation.parse("habitrain_core:plague_doctor"),
                RoleExtensionRegistry.INSTANCE.resolveAlias(ResourceLocation.parse("oldmod:doctor")));
        assertEquals(ResourceLocation.parse("habitrain_core:shadow_doctor"),
                RoleExtensionRegistry.INSTANCE.resolveAlias(ResourceLocation.parse("oldmod:nurse")));
    }

    // ------------------------------------------------------------------
    // Catalog integration: MODIFY
    // ------------------------------------------------------------------

    @Test
    void modifySurfacesPatchedRoleWithModifiedSource() {
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(TARGET, role(TARGET));
        RoleCatalogImpl api = new RoleCatalogImpl(raw);

        EffectiveRole er = api.find(RoleKey.of(TARGET)).orElseThrow();
        assertEquals(EffectiveRole.Source.MODIFIED, er.source());
        // The pre-snapshot catalog is pure: the MODIFY is folded into the
        // immutable profile; the live role is materialized only at activation.
        assertEquals(2, er.profile().defaultMax());
        assertSame(raw.get(TARGET), er.role(), "MODIFY must preserve the original role object");
        assertTrue(api.isModified(RoleKey.of(TARGET)));
        assertFalse(api.isReplaced(RoleKey.of(TARGET)));
    }

    // ------------------------------------------------------------------
    // Catalog integration: REPLACE
    // ------------------------------------------------------------------

    @Test
    void replaceHidesTargetAndSurfacesReplacement() {
        RoleDefinition def = definition("habitrain_core", "shadow_killer");
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(TARGET), def).identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        RoleExtensionRegistry.INSTANCE.freeze();

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(TARGET, role(TARGET));
        RoleCatalogImpl api = new RoleCatalogImpl(raw);

        ResourceLocation replacementId = ResourceLocation.parse("habitrain_core:shadow_killer");
        assertFalse(api.find(RoleKey.of(TARGET)).isEmpty(), "target resolves under replacement identity");
        assertEquals(RoleKey.of(replacementId), api.canonicalize(TARGET));
        EffectiveRole er = api.find(RoleKey.of(TARGET)).orElseThrow();
        assertEquals(replacementId, er.id());
        assertEquals(EffectiveRole.Source.REPLACEMENT, er.source());
        assertTrue(api.isReplaced(RoleKey.of(TARGET)));
    }

    @Test
    void preserveTargetIdReplacementKeepsTargetKey() {
        RoleDefinition def = definition("sre", "vigilante");
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(TARGET), def).identity(ReplacementIdentity.PRESERVE_TARGET_ID).build());
        RoleExtensionRegistry.INSTANCE.freeze();

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(TARGET, role(TARGET));
        RoleCatalogImpl api = new RoleCatalogImpl(raw);

        assertEquals(RoleKey.of(TARGET), api.canonicalize(TARGET));
        EffectiveRole er = api.find(RoleKey.of(TARGET)).orElseThrow();
        assertEquals(TARGET, er.id());
        assertEquals(EffectiveRole.Source.REPLACEMENT, er.source());
    }

    @Test
    void modifyOnReplacementIdFoldsOntoReplacement() {
        RoleDefinition def = definition("habitrain_core", "shadow_killer");
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(TARGET), def)
                        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        ResourceLocation replacementId = ResourceLocation.parse("habitrain_core:shadow_killer");
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(replacementId).defaultMax(RolePatch.IntPatch.set(7)).build());
        RoleExtensionRegistry.INSTANCE.freeze();

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(TARGET, role(TARGET));
        RoleCatalogImpl api = new RoleCatalogImpl(raw);

        EffectiveRole er = api.find(RoleKey.of(TARGET)).orElseThrow();
        assertEquals(EffectiveRole.Source.REPLACEMENT, er.source());
        assertEquals(7, er.profile().defaultMax(), "MODIFY of the replacement id must fold onto the surfaced role");
    }

    @Test
    void freezeRejectsNewIdCollisionWithManagedAdd() throws Exception {
        ResourceLocation collision = ResourceLocation.parse("habitrain_core:shadow_killer");
        ManagedSRERole existing = ManagedSRERole.from(definition("habitrain_core", "shadow_killer"));
        setField("managedRoles", new LinkedHashMap<>(Map.of(collision, existing)));

        RoleDefinition def = definition("habitrain_core", "shadow_killer");
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(TARGET), def)
                        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        assertThrows(IllegalStateException.class, RoleExtensionRegistry.INSTANCE::freeze,
                "NEW_ID_WITH_ALIAS must not collide with an ADD id");
    }

    // ------------------------------------------------------------------
    // Catalog integration: ALIAS
    // ------------------------------------------------------------------

    @Test
    void aliasRedirectsCanonicalization() {
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "doctor", "habitrain_core", "plague_doctor"));

        RoleCatalogImpl api = new RoleCatalogImpl(Map.of());
        assertEquals(RoleKey.of("habitrain_core", "plague_doctor"),
                api.canonicalize(ResourceLocation.parse("oldmod:doctor")));
    }

    @Test
    void resolverHidesV2ReplaceTargetAndSurfacesReplacement() {
        RoleDefinition def = definition("habitrain_core", "shadow_killer");
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(TARGET), def)
                        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        RoleExtensionRegistry.INSTANCE.freeze();

        SRERole target = role(TARGET);
        assertFalse(com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver.isVisible(target));
        SRERole resolved = com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver.resolve(target);
        assertEquals(ResourceLocation.parse("habitrain_core:shadow_killer"), resolved.identifier());

        java.util.List<SRERole> visible =
                com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver.visibleRegistryRoles(
                        java.util.List.of(target));
        assertEquals(1, visible.size());
        assertEquals(ResourceLocation.parse("habitrain_core:shadow_killer"), visible.getFirst().identifier());
    }

    // ------------------------------------------------------------------
    // Phase A regressions: injected catalog must be TMMRoles-safe and
    // surface a compiled replacement exactly once on the live path.
    // ------------------------------------------------------------------

    @Test
    void roleCatalogModifyLivePathIsTestSafe() {
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(TARGET, role(TARGET));
        RoleCatalogImpl api = new RoleCatalogImpl(raw);

        // canonicalize() must not reach the global TMMRoles static initializer
        // in a bare JUnit run; resolving through the injected raw map is enough.
        EffectiveRole er = api.find(RoleKey.of(TARGET)).orElseThrow();
        assertEquals(EffectiveRole.Source.MODIFIED, er.source());
        assertEquals(2, er.profile().defaultMax());
    }

    @Test
    void liveCatalogReplacementAppearsOnce() {
        RoleDefinition def = definition("habitrain_core", "shadow_killer");
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(TARGET), def)
                        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        RoleExtensionRegistry.INSTANCE.freeze();

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(TARGET, role(TARGET));
        RoleCatalogImpl api = new RoleCatalogImpl(raw);

        ResourceLocation replacementId = ResourceLocation.parse("habitrain_core:shadow_killer");
        List<EffectiveRole> matches = api.effectiveRoles().stream()
                .filter(er -> er.key().location().equals(replacementId))
                .toList();
        assertEquals(1, matches.size(), "the compiled replacement must surface exactly once");
        assertEquals(EffectiveRole.Source.REPLACEMENT, matches.getFirst().source());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static SRERole role(ResourceLocation id) {
        return new NormalRole(id, COLOR, false, true, SRERole.MoodType.FAKE, 20, true);
    }

    private static RoleDefinition definition(String ns, String path) {
        return RoleDefinition.builder(ns, path)
                .presentation(RolePresentation.builder().color(COLOR).build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .maxSprintTime(20)
                .build();
    }

    private static void setField(String name, Object value) throws Exception {
        Field field = RoleExtensionRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(RoleExtensionRegistry.INSTANCE, value);
    }
}