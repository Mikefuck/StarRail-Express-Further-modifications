package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.PatchPriority;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.diag.RoleConfigCommands;
import com.habitrain.core.role.diag.RoleDiagnosticsCommands;
import com.habitrain.core.role.diag.RoleDiagnosticsImpl;
import com.habitrain.core.role.diag.RoleFieldTrace;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.override.EffectiveSnapshot;
import com.habitrain.core.role.override.RoleOverrideEngine;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase F §13.2/§13.3: diagnostics read compiled statuses (config-aware, never
 * guessed ACTIVE) and the per-field trace renders baseline → patches → final.
 */
class RoleDiagnosticsConfigTest {

    private static final ResourceLocation TARGET = ResourceLocation.parse("sre:vigilante");
    private static final int COLOR = 0xFFAA0000;

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
        com.habitrain.core.role.legacy.LegacyRoleScan.INSTANCE.clear();
        com.habitrain.core.role.legacy.LegacyRoleScan.INSTANCE.start();
    }

    @Test
    void entriesReportDisabledFromConfigNotGuessedActive() {
        seedTarget();
        RoleExtensionRegistry.INSTANCE.modify("example",
                RolePatch.builder(TARGET).entryKey("buff").defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleExtensionRegistry.INSTANCE.freeze();

        RoleExtensionConfigService.INSTANCE.setEntryEnabled("example$buff@sre:vigilante", false);
        RoleExtensionRegistry.INSTANCE.recomputeCompiledEntries();

        DiagnosticEntry modify = new RoleDiagnosticsImpl().entries().stream()
                .filter(e -> e.kind().equals("MODIFY")).findFirst().orElseThrow();
        assertEquals(DiagnosticStatus.DISABLED, modify.status());
        assertEquals("example", modify.providerId());
        assertEquals("example$buff@sre:vigilante", modify.entryId());
        assertEquals("entry", modify.enabledSource());
        assertTrue(modify.message().contains("disabled"));
    }

    @Test
    void entriesReportActiveWithRichFields() {
        seedTarget();
        RoleExtensionRegistry.INSTANCE.modify("example",
                RolePatch.builder(TARGET).entryKey("buff").defaultMax(RolePatch.IntPatch.set(2)).build());
        DiagnosticEntry modify = new RoleDiagnosticsImpl().entries().stream()
                .filter(e -> e.kind().equals("MODIFY")).findFirst().orElseThrow();
        assertEquals(DiagnosticStatus.ACTIVE, modify.status());
        assertEquals("active", modify.enabledSource());
        assertEquals("example", modify.providerId());
    }

    @Test
    void listFilterEffectiveOnlyShowsActive() {
        RoleExtensionRegistry.INSTANCE.modify("example",
                RolePatch.builder(TARGET).entryKey("buff").defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleExtensionConfigService.INSTANCE.setEntryEnabled("example$buff@sre:vigilante", false);
        List<String> lines = RoleDiagnosticsCommands.list("effective");
        assertTrue(lines.getFirst().startsWith("entries [effective]"));
        assertTrue(lines.stream().noneMatch(l -> l.contains("MODIFY") && l.contains("ACTIVE")));
        assertTrue(lines.stream().noneMatch(l -> l.contains("MODIFY") && l.contains("DISABLED")),
                "effective filter excludes disabled entries");
    }

    @Test
    void traceRendersBaselinePatchesFinalStatusSnapshot() {
        seedTarget();
        RolePatch early = RolePatch.builder(TARGET).entryKey("buff")
                .priority(PatchPriority.EARLY).defaultMax(RolePatch.IntPatch.set(2)).build();
        RolePatch normal = RolePatch.builder(TARGET).entryKey("nerf")
                .priority(PatchPriority.NORMAL).defaultMax(RolePatch.IntPatch.set(3)).build();
        RoleExtensionRegistry.INSTANCE.modify("example", early);
        RoleExtensionRegistry.INSTANCE.modify("example", normal);

        RoleKey key = RoleKey.of(TARGET);
        RoleSnapshot snap = new RoleSnapshot(new RoleSnapshotId(42),
                Map.of(TARGET, new EffectiveRole(key, role(TARGET), EffectiveRole.Source.BASELINE)),
                Map.of(), Set.of());
        RoleSnapshotManager.INSTANCE.setLobby(snap);

        List<String> lines = RoleFieldTrace.trace(key, "spawn.defaultMax");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("baseline:")), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.equals("[EARLY] example$buff@sre:vigilante SET 2")), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.equals("[NORMAL] example$nerf@sre:vigilante SET 3")), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("final: 3")), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.equals("status: ACTIVE")), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.equals("snapshot: role-snapshot-v42")), lines::toString);
    }

    @Test
    void traceWithConflictWinnerShowsWinnerValueAsFinal() {
        RolePatch early = RolePatch.builder(TARGET).entryKey("buff")
                .priority(PatchPriority.EARLY).defaultMax(RolePatch.IntPatch.set(2)).build();
        RolePatch normal = RolePatch.builder(TARGET).entryKey("nerf")
                .priority(PatchPriority.NORMAL).defaultMax(RolePatch.IntPatch.set(3)).build();
        RoleExtensionRegistry.INSTANCE.modify("example", early);
        RoleExtensionRegistry.INSTANCE.modify("example", normal);
        RoleExtensionConfigService.INSTANCE.setConflictWinner("sre:vigilante#spawn.defaultMax",
                "example$buff@sre:vigilante");

        RoleKey key = RoleKey.of(TARGET);
        RoleSnapshotManager.INSTANCE.setLobby(new RoleSnapshot(new RoleSnapshotId(1),
                Map.of(TARGET, new EffectiveRole(key, role(TARGET), EffectiveRole.Source.BASELINE)),
                Map.of(), Set.of()));

        List<String> lines = RoleFieldTrace.trace(key, "spawn.defaultMax");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("final: 2")), lines::toString);
        // The losing patch is still traced but the winner controls the final value.
        assertTrue(lines.stream().anyMatch(l -> l.equals("[NORMAL] example$nerf@sre:vigilante SET 3")), lines::toString);
    }

    @Test
    void traceShowsDisabledModifyOnActiveAddRole() {
        // The seeded ADD makes the ROLE active; the MODIFY entry itself is
        // disabled by config, so its patches must not fold into the trace.
        seedTarget();
        RoleExtensionRegistry.INSTANCE.modify("example",
                RolePatch.builder(TARGET).entryKey("buff").defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleExtensionRegistry.INSTANCE.freeze();
        RoleExtensionConfigService.INSTANCE.setEntryEnabled("example$buff@sre:vigilante", false);
        RoleExtensionRegistry.INSTANCE.recomputeCompiledEntries();

        // The MODIFY entry reports DISABLED with its config source.
        DiagnosticEntry modify = new RoleDiagnosticsImpl().entries().stream()
                .filter(e -> e.kind().equals("MODIFY")).findFirst().orElseThrow();
        assertEquals(DiagnosticStatus.DISABLED, modify.status(),
                "the disabled MODIFY entry must report DISABLED, never ACTIVE/INVALID");
        assertEquals("entry", modify.enabledSource());

        RoleKey key = RoleKey.of(TARGET);
        RoleSnapshotManager.INSTANCE.setLobby(new RoleSnapshot(new RoleSnapshotId(1),
                Map.of(TARGET, new EffectiveRole(key, role(TARGET), EffectiveRole.Source.BASELINE)),
                Map.of(), Set.of()));

        List<String> lines = RoleFieldTrace.trace(key, "spawn.defaultMax");
        // The role is active (via its ADD entry); the field trace shows no
        // enabled patches and the pristine final value.
        assertTrue(lines.stream().anyMatch(l -> l.equals("status: ACTIVE")), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.equals("  (no enabled patches set spawn.defaultMax)")),
                lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.equals("final: 1")), lines::toString);
    }

    @Test
    void configStatusFormatsProvidersEntriesAndWinners() {
        seedTarget();
        RoleExtensionRegistry.INSTANCE.modify("example",
                RolePatch.builder(TARGET).entryKey("buff").defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleExtensionConfigService.INSTANCE.setEntryEnabled("example$buff@sre:vigilante", false);
        RoleExtensionConfigService.INSTANCE.setConflictWinner(
                "sre:vigilante#spawn.defaultMax", "example$buff@sre:vigilante");

        List<String> lines = RoleConfigCommands.status();
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("roleExtensionsV2 enabled=true")), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("providers (")), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.contains("example$buff@sre:vigilante: off")), lines::toString);
        assertTrue(lines.stream().anyMatch(l ->
                l.contains("sre:vigilante#spawn.defaultMax -> example$buff@sre:vigilante")), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.contains("DISABLED=1")), lines::toString);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Seeds a managed ADD role for {@link #TARGET} so it counts as a known role id. */
    private static void seedTarget() {
        try {
            setField(RoleExtensionRegistry.class, RoleExtensionRegistry.INSTANCE, "managedRoles",
                    new LinkedHashMap<>(Map.of(TARGET,
                            com.habitrain.core.role.extension.ManagedSRERole.from(
                                    RoleDefinition.builder("sre", "vigilante")
                                            .presentation(RolePresentation.builder().color(COLOR).build())
                                            .faction(RoleFactionProfile.builder().innocent().build())
                                            .spawn(RoleSpawnProfile.builder().build())
                                            .compatibility(RoleCompatibilityProfile.builder().build())
                                            .maxSprintTime(20)
                                            .build()))));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static SRERole role(ResourceLocation id) {
        return new NormalRole(id, COLOR, false, true, SRERole.MoodType.FAKE, 20, true);
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
