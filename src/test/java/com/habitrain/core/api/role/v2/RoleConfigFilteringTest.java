package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.PatchPriority;
import com.habitrain.core.api.role.v2.definition.ReplacementIdentity;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.ConfiguredPatch;
import com.habitrain.core.role.extension.EntryStatus;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.ManagedSRERole;
import com.habitrain.core.role.extension.RoleConflictAnalyzer;
import com.habitrain.core.role.extension.RoleExtensionCompiler;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.extension.RoleOperation;
import com.habitrain.core.role.override.EffectiveSnapshot;
import com.habitrain.core.role.override.RoleOverrideEngine;
import com.habitrain.core.role.snapshot.RoleSnapshotCompiler;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase F: the v2 config section wired into the registry, the overlay compiler
 * (per-{@code target#field} conflict winners), the compiled entry statuses and
 * the effective snapshot (disabled ADD hidden, disabled REPLACE target visible).
 */
class RoleConfigFilteringTest {

    private static final ResourceLocation TARGET = ResourceLocation.parse("sre:vigilante");
    private static final String PROVIDER = "example";
    private static final String ENTRY_BUFF = "example$buff@sre:vigilante";
    private static final String ENTRY_NERF = "example$nerf@sre:vigilante";

    @BeforeEach
    @AfterEach
    void reset() throws Exception {
        RoleExtensionConfigService.INSTANCE.resetForTests();
        RoleSnapshotManager.INSTANCE.clear();
        setSnapshot(new EffectiveSnapshot(Map.of(), Map.of(), List.of()));
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "managedRoles", new LinkedHashMap<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "compiledReplacements", new LinkedHashMap<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "patches", new ArrayList<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "replacements", new ArrayList<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "aliases", new ArrayList<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "replacementByTarget", new LinkedHashMap<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "registeredEntryIds", new LinkedHashSet<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "frozen", false);
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "tmmAccessible", false);
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "compiledEntries", List.of());
    }

    // ------------------------------------------------------------------
    // configured patch filtering
    // ------------------------------------------------------------------

    @Test
    void configuredPatchesExcludeDisabledEntry() {
        RoleExtensionRegistry.INSTANCE.modify(PROVIDER,
                RolePatch.builder(TARGET).entryKey("buff").defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleExtensionRegistry.INSTANCE.modify(PROVIDER,
                RolePatch.builder(TARGET).entryKey("nerf").enableChance(RolePatch.IntPatch.set(1)).build());

        assertEquals(2, RoleExtensionRegistry.INSTANCE.configuredPatchesFor(TARGET).size());

        RoleExtensionConfigService.INSTANCE.setEntryEnabled(ENTRY_BUFF, false);
        List<ConfiguredPatch> configured = RoleExtensionRegistry.INSTANCE.configuredPatchesFor(TARGET);
        assertEquals(1, configured.size());
        assertEquals(ENTRY_NERF, configured.getFirst().entryId());
    }

    @Test
    void configuredPatchesExcludeDisabledProvider() {
        RoleExtensionRegistry.INSTANCE.modify(PROVIDER,
                RolePatch.builder(TARGET).entryKey("buff").defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleExtensionConfigService.INSTANCE.setProviderEnabled(PROVIDER, false);
        assertTrue(RoleExtensionRegistry.INSTANCE.configuredPatchesFor(TARGET).isEmpty());
    }

    @Test
    void globalDisableExcludesAllPatches() {
        RoleExtensionRegistry.INSTANCE.modify(PROVIDER,
                RolePatch.builder(TARGET).entryKey("buff").defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleExtensionConfigService.INSTANCE.setEnabled(false);
        assertTrue(RoleExtensionRegistry.INSTANCE.configuredPatchesFor(TARGET).isEmpty());
    }

    // ------------------------------------------------------------------
    // conflict winner masking
    // ------------------------------------------------------------------

    @Test
    void conflictWinnerMasksLosingEntriesField() {
        RolePatch early = RolePatch.builder(TARGET).entryKey("buff")
                .priority(PatchPriority.EARLY).defaultMax(RolePatch.IntPatch.set(2)).build();
        RolePatch normal = RolePatch.builder(TARGET).entryKey("nerf")
                .priority(PatchPriority.NORMAL).defaultMax(RolePatch.IntPatch.set(3)).build();
        List<ConfiguredPatch> patches = List.of(
                new ConfiguredPatch(early, ENTRY_BUFF),
                new ConfiguredPatch(normal, ENTRY_NERF));
        SRERole base = role(TARGET);

        // No winner -> normal order decides (later patch wins).
        CompiledModifyOverlay noWinner = RoleExtensionCompiler.compileModifyOverlayConfigured(base, patches, null);
        assertEquals(3, noWinner.defaultMax());

        // Winner = EARLY entry -> the NORMAL patch's defaultMax is masked.
        RoleExtensionConfigService.INSTANCE.setConflictWinner(
                "sre:vigilante#spawn.defaultMax", ENTRY_BUFF);
        CompiledModifyOverlay winner = RoleExtensionCompiler.compileModifyOverlayConfigured(base, patches, null);
        assertEquals(2, winner.defaultMax());
    }

    @Test
    void conflictWinnerOnlyMasksTheNamedField() {
        RolePatch a = RolePatch.builder(TARGET).entryKey("a")
                .priority(PatchPriority.EARLY).defaultMax(RolePatch.IntPatch.set(2))
                .maxSprintTime(RolePatch.IntPatch.set(10)).build();
        RolePatch b = RolePatch.builder(TARGET).entryKey("b")
                .priority(PatchPriority.NORMAL).defaultMax(RolePatch.IntPatch.set(3))
                .maxSprintTime(RolePatch.IntPatch.set(20)).build();
        List<ConfiguredPatch> patches = List.of(
                new ConfiguredPatch(a, ENTRY_BUFF),
                new ConfiguredPatch(b, ENTRY_NERF));
        RoleExtensionConfigService.INSTANCE.setConflictWinner(
                "sre:vigilante#spawn.defaultMax", ENTRY_BUFF);

        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlayConfigured(role(TARGET), patches, null);
        assertEquals(2, overlay.defaultMax());      // A wins spawn.defaultMax
        assertEquals(20, overlay.maxSprintTime()); // B still wins maxSprintTime
    }

    @Test
    void unresolvedSamePriorityScalarSetsAreExcludedAndDiagnosed() {
        RoleExtensionRegistry.INSTANCE.modify(PROVIDER,
                RolePatch.builder(TARGET).entryKey("buff")
                        .defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleExtensionRegistry.INSTANCE.modify(PROVIDER,
                RolePatch.builder(TARGET).entryKey("nerf")
                        .defaultMax(RolePatch.IntPatch.set(3)).build());

        assertTrue(RoleExtensionRegistry.INSTANCE.configuredPatchesFor(TARGET).isEmpty(),
                "an unresolved v2-v2 scalar SET conflict must not be silently ordered");

        List<ManagedRoleEntry<?>> entries = RoleConflictAnalyzer.analyze(List.of(), List.of());
        assertEquals(2, entries.stream()
                .filter(e -> e.operation() == RoleOperation.MODIFY
                        && e.status() == EntryStatus.CONFLICT)
                .count());
    }

    @Test
    void configuredWinnerResolvesSamePriorityScalarSetConflict() {
        RoleExtensionRegistry.INSTANCE.modify(PROVIDER,
                RolePatch.builder(TARGET).entryKey("buff")
                        .defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleExtensionRegistry.INSTANCE.modify(PROVIDER,
                RolePatch.builder(TARGET).entryKey("nerf")
                        .defaultMax(RolePatch.IntPatch.set(3)).build());
        RoleExtensionConfigService.INSTANCE.setConflictWinner(
                "sre:vigilante#spawn.defaultMax", ENTRY_BUFF);

        assertEquals(2, RoleExtensionRegistry.INSTANCE.configuredPatchesFor(TARGET).size());
        assertTrue(RoleConflictAnalyzer.analyze(List.of(), List.of()).stream()
                .filter(e -> e.operation() == RoleOperation.MODIFY)
                .allMatch(e -> e.status() == EntryStatus.ACTIVE));
    }

    // ------------------------------------------------------------------
    // compiled entry statuses reflect config
    // ------------------------------------------------------------------

    @Test
    void recomputedEntriesMarkDisabled() {
        RoleExtensionRegistry.INSTANCE.modify(PROVIDER,
                RolePatch.builder(TARGET).entryKey("buff").defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleExtensionRegistry.INSTANCE.freeze();
        assertTrue(RoleExtensionRegistry.INSTANCE.getCompiledEntries().stream()
                .anyMatch(e -> e.operation() == RoleOperation.MODIFY
                        && e.status() == EntryStatus.ACTIVE));

        RoleExtensionConfigService.INSTANCE.setEntryEnabled(ENTRY_BUFF, false);
        RoleExtensionRegistry.INSTANCE.recomputeCompiledEntries();
        ManagedRoleEntry<?> entry = RoleExtensionRegistry.INSTANCE.getCompiledEntries().stream()
                .filter(e -> e.operation() == RoleOperation.MODIFY).findFirst().orElseThrow();
        assertEquals(EntryStatus.DISABLED, entry.status());
        assertTrue(entry.statusMessage().contains("entry"));
    }

    // ------------------------------------------------------------------
    // snapshot filtering
    // ------------------------------------------------------------------

    @Test
    void disabledAddRoleIsHiddenFromSnapshot() {
        RoleDefinition def = definition("habitrain_core", "plague_doctor");
        ManagedSRERole managed = ManagedSRERole.from(def);
        seedManaged(managed);

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(managed.identifier(), managed);

        RoleSnapshot enabled = RoleSnapshotCompiler.compile(new RoleSnapshotId(1), raw);
        assertTrue(enabled.isActive(managed.identifier()));

        RoleExtensionConfigService.INSTANCE.setEntryEnabled("habitrain_core:plague_doctor", false);
        RoleSnapshot disabled = RoleSnapshotCompiler.compile(new RoleSnapshotId(2), raw);
        assertFalse(disabled.isActive(managed.identifier()));
    }

    @Test
    void disabledReplacementLeavesTargetVisible() throws Exception {
        RoleReplacement replacement = RoleReplacement.builder(
                RoleKey.of(TARGET), definition(PROVIDER, "shadow_killer"))
                .entryKey("repl").identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build();
        RoleExtensionRegistry.INSTANCE.replace(PROVIDER, replacement);
        // Compile the replacement role and seed the compiled map (mirrors freeze()).
        ManagedSRERole compiled = RoleExtensionCompiler.compileReplacement(replacement);
        Map<ResourceLocation, ManagedSRERole> compiledMap = new LinkedHashMap<>();
        compiledMap.put(compiled.identifier(), compiled);
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE,
                "compiledReplacements", compiledMap);

        SRERole target = role(TARGET);
        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(TARGET, target);

        RoleExtensionConfigService.INSTANCE.setEntryEnabled("example$repl@sre:vigilante", false);
        RoleSnapshot disabled = RoleSnapshotCompiler.compile(new RoleSnapshotId(1), raw);
        assertTrue(disabled.isActive(TARGET), "disabled replacement target stays visible");
        assertFalse(disabled.isActive(compiled.identifier()));

        RoleExtensionConfigService.INSTANCE.setEntryEnabled("example$repl@sre:vigilante", true);
        RoleSnapshot enabled = RoleSnapshotCompiler.compile(new RoleSnapshotId(2), raw);
        assertFalse(enabled.isActive(TARGET), "enabled replacement hides the target");
        assertTrue(enabled.isActive(compiled.identifier()));
    }

    @Test
    void replacementNotCompiledStillVisibleWhenDisabled() {
        // Disabled replacement whose compiled role map is empty: target stays raw-visible.
        RoleExtensionRegistry.INSTANCE.replace(PROVIDER,
                RoleReplacement.builder(RoleKey.of(TARGET), definition(PROVIDER, "shadow_killer"))
                        .entryKey("repl").identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        RoleExtensionConfigService.INSTANCE.setEntryEnabled("example$repl@sre:vigilante", false);
        RoleSnapshot snap = RoleSnapshotCompiler.compile(new RoleSnapshotId(1), Map.of(TARGET, role(TARGET)));
        assertTrue(snap.isActive(TARGET));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void seedManaged(ManagedSRERole managed) {
        try {
            Map<ResourceLocation, ManagedSRERole> m = new LinkedHashMap<>();
            m.put(managed.identifier(), managed);
            setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "managedRoles", m);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static SRERole role(ResourceLocation id) {
        return new NormalRole(id, 0xFFAA0000, false, true, SRERole.MoodType.FAKE, 20, true);
    }

    private static RoleDefinition definition(String ns, String path) {
        return RoleDefinition.builder(ns, path)
                .presentation(RolePresentation.builder().color(0xFFAA0000).build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .maxSprintTime(20)
                .build();
    }

    private static void setSnapshot(EffectiveSnapshot snap) throws Exception {
        Field field = RoleOverrideEngine.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        field.set(RoleOverrideEngine.getInstance(), snap);
    }

    private static void setField(Class<?> clazz, Object target, String name, Object value) throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
