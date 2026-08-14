package com.habitrain.core.role.config;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.definition.PatchPriority;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.role.config.RoleExtensionConfigService.EntryGate;
import com.habitrain.core.role.extension.EntryStatus;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.RoleOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the v2 role-extension config section: additive enablement
 * resolution (global → provider → entry), conflict-winner lookup and the
 * versioned JSON persistence round-trip.
 */
class RoleExtensionConfigServiceTest {

    private static final String PROVIDER = "example";
    private static final String ENTRY_A = "example$buff@sre:vigilante";
    private static final String ENTRY_B = "example$nerf@sre:vigilante";

    @TempDir
    Path tmp;

    @BeforeEach
    void reset() {
        RoleExtensionConfigService.INSTANCE.resetForTests();
    }

    private static ManagedRoleEntry<?> entry(String entryId, String provider) {
        return new ManagedRoleEntry<>(entryId, provider, "buff",
                RoleOperation.MODIFY, RoleKey.of("sre", "vigilante"), PatchPriority.NORMAL,
                RolePatch.builder(RoleKey.of("sre", "vigilante")).defaultMax(RolePatch.IntPatch.set(2)).build(),
                EntryStatus.ACTIVE, "test", false);
    }

    @Test
    void defaultsEnableEverything() {
        assertTrue(RoleExtensionConfigService.INSTANCE.isEnabled());
        assertEquals(EntryGate.ENABLED, RoleExtensionConfigService.INSTANCE.gateFor(entry(ENTRY_A, PROVIDER)));
        assertTrue(RoleExtensionConfigService.INSTANCE.isProviderEnabled("any_mod"));
        assertTrue(RoleExtensionConfigService.INSTANCE.isEntryEnabled("anything$any"));
    }

    @Test
    void providerDisableGatesItsEntriesOnly() {
        RoleExtensionConfigService.INSTANCE.setProviderEnabled(PROVIDER, false);
        assertEquals(EntryGate.PROVIDER_DISABLED,
                RoleExtensionConfigService.INSTANCE.gateFor(entry(ENTRY_A, PROVIDER)));
        // Other providers unaffected.
        assertEquals(EntryGate.ENABLED,
                RoleExtensionConfigService.INSTANCE.gateFor(entry("other$x@sre:vigilante", "other")));
    }

    @Test
    void entryDisableGatesJustThatEntry() {
        RoleExtensionConfigService.INSTANCE.setEntryEnabled(ENTRY_A, false);
        assertEquals(EntryGate.ENTRY_DISABLED,
                RoleExtensionConfigService.INSTANCE.gateFor(entry(ENTRY_A, PROVIDER)));
        assertEquals(EntryGate.ENABLED,
                RoleExtensionConfigService.INSTANCE.gateFor(entry(ENTRY_B, PROVIDER)));
    }

    @Test
    void globalDisableGatesEverything() {
        RoleExtensionConfigService.INSTANCE.setEnabled(false);
        assertEquals(EntryGate.GLOBAL_DISABLED,
                RoleExtensionConfigService.INSTANCE.gateFor(entry(ENTRY_A, PROVIDER)));
        assertFalse(RoleExtensionConfigService.INSTANCE.isEnabled());
    }

    @Test
    void entryTrueCannotOverrideProviderDisable() {
        RoleExtensionConfigService.INSTANCE.setProviderEnabled(PROVIDER, false);
        RoleExtensionConfigService.INSTANCE.setEntryEnabled(ENTRY_A, true);
        assertEquals(EntryGate.PROVIDER_DISABLED,
                RoleExtensionConfigService.INSTANCE.gateFor(entry(ENTRY_A, PROVIDER)));
    }

    @Test
    void conflictWinnerLookupByTargetField() {
        assertNull(RoleExtensionConfigService.INSTANCE.winnerFor("sre:vigilante#spawn.defaultMax"));
        RoleExtensionConfigService.INSTANCE.setConflictWinner(
                "sre:vigilante#spawn.defaultMax", ENTRY_A);
        assertEquals(ENTRY_A,
                RoleExtensionConfigService.INSTANCE.winnerFor(
                        net.minecraft.resources.ResourceLocation.parse("sre:vigilante"),
                        "spawn.defaultMax"));
        RoleExtensionConfigService.INSTANCE.setConflictWinner("sre:vigilante#spawn.defaultMax", null);
        assertNull(RoleExtensionConfigService.INSTANCE.winnerFor(
                net.minecraft.resources.ResourceLocation.parse("sre:vigilante"), "spawn.defaultMax"));
    }

    @Test
    void jsonRoundTripPreservesSection() {
        RoleExtensionConfigService.INSTANCE.setProviderEnabled(PROVIDER, false);
        RoleExtensionConfigService.INSTANCE.setEntryEnabled(ENTRY_B, false);
        RoleExtensionConfigService.INSTANCE.setConflictWinner("sre:vigilante#spawn.defaultMax", ENTRY_A);
        RoleExtensionConfigService.INSTANCE.setAllowGlobalHooks(true);

        String json = RoleExtensionConfigService.INSTANCE.toJsonString();
        assertTrue(json.contains("roleExtensionsV2"));
        assertTrue(json.contains("\"version\": 1"));

        RoleExtensionConfigService.INSTANCE.resetForTests();
        assertTrue(RoleExtensionConfigService.INSTANCE.applyFromJson(json));
        assertFalse(RoleExtensionConfigService.INSTANCE.isProviderEnabled(PROVIDER));
        assertFalse(RoleExtensionConfigService.INSTANCE.isEntryEnabled(ENTRY_B));
        assertTrue(RoleExtensionConfigService.INSTANCE.isAllowGlobalHooks());
        assertEquals(ENTRY_A, RoleExtensionConfigService.INSTANCE.winnerFor("sre:vigilante#spawn.defaultMax"));
    }

    @Test
    void invalidJsonIsRejectedAndSectionUntouched() {
        RoleExtensionConfigService.INSTANCE.setProviderEnabled(PROVIDER, false);
        boolean ok = RoleExtensionConfigService.INSTANCE.applyFromJson("not json {");
        assertFalse(ok);
        assertFalse(RoleExtensionConfigService.INSTANCE.isProviderEnabled(PROVIDER));
    }

    @Test
    void persistsToRedirectedFile() throws Exception {
        File cfg = tmp.resolve("role_v2_test.json").toFile();
        RoleExtensionConfigService.INSTANCE.setConfigFileForTests(cfg);
        RoleExtensionConfigService.INSTANCE.setProviderEnabled(PROVIDER, false);
        assertTrue(Files.size(cfg.toPath()) > 0);

        RoleExtensionConfigService.INSTANCE.resetForTests();
        // Reset also clears the file override; point back at the same file before load.
        RoleExtensionConfigService.INSTANCE.setConfigFileForTests(cfg);
        RoleExtensionConfigService.INSTANCE.load();
        assertFalse(RoleExtensionConfigService.INSTANCE.isProviderEnabled(PROVIDER));
    }

    @Test
    void loadCreatesFileWhenMissing() throws Exception {
        File cfg = tmp.resolve("missing_role_v2.json").toFile();
        RoleExtensionConfigService.INSTANCE.setConfigFileForTests(cfg);
        RoleExtensionConfigService.INSTANCE.load();
        assertTrue(cfg.exists());
        assertTrue(Files.size(cfg.toPath()) > 0);
    }
}
