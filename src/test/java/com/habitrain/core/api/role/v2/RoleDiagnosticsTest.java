package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.ReplacementIdentity;
import com.habitrain.core.api.role.v2.definition.RoleAlias;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.role.diag.RoleDiagnosticsCommands;
import com.habitrain.core.role.diag.RoleDiagnosticsImpl;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the v2 diagnostics service: entry statuses, alias validity and snapshot
 * summary generation from the registry and snapshot manager.
 */
class RoleDiagnosticsTest {

    private static final ResourceLocation TARGET = ResourceLocation.parse("sre:vigilante");
    private static final int COLOR = 0xFFAA0000;

    @BeforeEach
    void reset() throws Exception {
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "managedRoles", new LinkedHashMap<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "compiledReplacements", new LinkedHashMap<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "patches", new ArrayList<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "replacements", new ArrayList<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "aliases", new ArrayList<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "replacementByTarget", new LinkedHashMap<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "registeredEntryIds", new LinkedHashSet<>());
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "frozen", false);
        setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "tmmAccessible", false);
        setField(RoleSnapshotManager.class, RoleSnapshotManager.INSTANCE, "lobby", null);
        setField(RoleSnapshotManager.class, RoleSnapshotManager.INSTANCE, "round", null);
        setField(RoleSnapshotManager.class, RoleSnapshotManager.INSTANCE, "pending", null);
        com.habitrain.core.role.legacy.LegacyRoleScan.INSTANCE.clear();
        com.habitrain.core.role.legacy.LegacyRoleScan.INSTANCE.start();
    }

    @Test
    void entriesReportModifyAsActive() {
        seedTarget();
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());

        List<DiagnosticEntry> entries = new RoleDiagnosticsImpl().entries();
        DiagnosticEntry modify = entries.stream()
                .filter(e -> e.kind().equals("MODIFY")).findFirst().orElseThrow();
        assertEquals(DiagnosticStatus.ACTIVE, modify.status());
        assertEquals(TARGET, modify.target().location());
    }

    @Test
    void entriesReportReplaceInvalidWhenNotCompiled() {
        RoleDefinition def = definition("habitrain_core", "shadow_killer");
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(TARGET), def)
                        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        // No freeze() -> replacement not compiled.

        List<DiagnosticEntry> entries = new RoleDiagnosticsImpl().entries();
        DiagnosticEntry replace = entries.stream()
                .filter(e -> e.kind().equals("REPLACE")).findFirst().orElseThrow();
        assertEquals(DiagnosticStatus.INVALID, replace.status());
    }

    @Test
    void entriesReportReplaceActiveWhenCompiled() {
        seedTarget();
        RoleDefinition def = definition("habitrain_core", "shadow_killer");
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(TARGET), def)
                        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        RoleExtensionRegistry.INSTANCE.freeze();

        List<DiagnosticEntry> entries = new RoleDiagnosticsImpl().entries();
        DiagnosticEntry replace = entries.stream()
                .filter(e -> e.kind().equals("REPLACE")).findFirst().orElseThrow();
        assertEquals(DiagnosticStatus.ACTIVE, replace.status());
    }

    @Test
    void aliasesReportValidity() {
        // Valid: target exists in the lobby snapshot.
        RoleKey canonical = RoleKey.of("habitrain_core", "plague_doctor");
        RoleSnapshot snap = new RoleSnapshot(new RoleSnapshotId(1),
                Map.of(canonical.location(), new EffectiveRole(canonical, role(canonical.location()))),
                Map.of(), Set.of());
        RoleSnapshotManager.INSTANCE.setLobby(snap);

        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "doctor", "habitrain_core", "plague_doctor"));
        RoleExtensionRegistry.INSTANCE.alias("habitrain_core",
                RoleAlias.of("oldmod", "ghost", "habitrain_core", "missing_role"));

        List<DiagnosticAlias> aliases = new RoleDiagnosticsImpl().aliases();
        DiagnosticAlias valid = aliases.stream()
                .filter(a -> a.from().path().equals("doctor")).findFirst().orElseThrow();
        DiagnosticAlias dangling = aliases.stream()
                .filter(a -> a.from().path().equals("ghost")).findFirst().orElseThrow();
        assertTrue(valid.valid());
        assertFalse(dangling.valid());
    }

    @Test
    void snapshotInfoReportsCounts() {
        RoleKey canonical = RoleKey.of("habitrain_core", "plague_doctor");
        RoleSnapshot snap = new RoleSnapshot(new RoleSnapshotId(7),
                Map.of(canonical.location(), new EffectiveRole(canonical, role(canonical.location()))),
                Map.of(ResourceLocation.parse("oldmod:doctor"), canonical.location()),
                Set.of(ResourceLocation.parse("sre:vigilante")));
        RoleSnapshotManager.INSTANCE.setLobby(snap);

        DiagnosticSnapshot info = new RoleDiagnosticsImpl().snapshotInfo();
        assertEquals(new RoleSnapshotId(7), info.id());
        assertEquals(1, info.roleCount());
        assertEquals(1, info.replacedCount());
        assertEquals(1, info.aliasCount());
    }

    @Test
    void reportCombinesProvidersAndSections() {
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());

        DiagnosticReport report = new RoleDiagnosticsImpl().report();
        assertTrue(report.providers().contains("habitrain_core"));
        assertFalse(report.entries().isEmpty());
        assertTrue(report.snapshot() != null);
    }

    @Test
    void commandListShowsActiveModify() {
        seedTarget();
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());
        List<String> lines = RoleDiagnosticsCommands.list("effective");
        assertTrue(lines.getFirst().startsWith("entries [effective]"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("MODIFY") && l.contains("ACTIVE")));
    }

    @Test
    void commandSnapshotSummarizesLobby() {
        RoleKey canonical = RoleKey.of("habitrain_core", "plague_doctor");
        RoleSnapshot snap = new RoleSnapshot(new RoleSnapshotId(7),
                Map.of(canonical.location(), new EffectiveRole(canonical, role(canonical.location()))),
                Map.of(), Set.of());
        RoleSnapshotManager.INSTANCE.setLobby(snap);
        List<String> lines = RoleDiagnosticsCommands.snapshot();
        assertTrue(lines.getFirst().contains("snapshot 7"));
        assertTrue(lines.getFirst().contains("roles=1"));
    }

    @Test
    void legacyScanSurfacesUnmanagedRegistrations() {
        com.habitrain.core.role.legacy.LegacyRoleScan.INSTANCE.record(
                ResourceLocation.parse("foreign:role"), "com.example.ForeignMod");
        List<DiagnosticEntry> entries = new RoleDiagnosticsImpl().entries();
        DiagnosticEntry legacy = entries.stream()
                .filter(e -> e.kind().equals("LEGACY")).findFirst().orElseThrow();
        assertEquals(DiagnosticStatus.LEGACY_UNMANAGED, legacy.status());
        assertTrue(RoleDiagnosticsCommands.legacy().stream()
                .anyMatch(l -> l.contains("foreign:role")));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Seeds a managed ADD role for {@link #TARGET} so it counts as a known role id. */
    private static void seedTarget() {
        try {
            setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "managedRoles",
                    new LinkedHashMap<>(Map.of(TARGET,
                            com.habitrain.core.role.extension.ManagedSRERole.from(definition("sre", "vigilante")))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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

    private static void setField(Class<?> clazz, Object target, String name, Object value)
            throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
