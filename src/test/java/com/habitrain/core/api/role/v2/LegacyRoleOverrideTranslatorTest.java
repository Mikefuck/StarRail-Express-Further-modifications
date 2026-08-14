package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.ReplaceRoleDefinition;
import com.habitrain.core.api.role.v2.definition.ReplacementIdentity;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.role.extension.EntryStatus;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.RoleConflictAnalyzer;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.extension.RoleOperation;
import com.habitrain.core.role.extension.RolePatchBundle;
import com.habitrain.core.role.legacy.LegacyRoleOverrideTranslator;
import com.habitrain.core.role.extension.RoleRuntimeOverlayApplier;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase B: first-version v1 translator. Legacy {@code RoleOverrideApi}
 * definitions translate into the unified {@link ManagedRoleEntry} shape so v1/v2
 * same-target conflicts become diagnosable without changing the v1 engine.
 */
class LegacyRoleOverrideTranslatorTest {

    private static final ResourceLocation TARGET = ResourceLocation.parse("sre:vigilante");

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
        RoleSnapshotManager.INSTANCE.clear();
        RoleRuntimeOverlayApplier.clear();
    }

    @Test
    void translatorMapsV1ModifyToPatchBundle() {
        ModifyRoleDefinition def = ModifyRoleDefinition.builder()
                .sourceModId("some_mod")
                .displayName(Component.literal("legacy modify"))
                .targetRoleId(TARGET)
                .build();
        ManagedRoleEntry<RolePatchBundle> entry = LegacyRoleOverrideTranslator.translateModify(def);
        assertEquals(RoleOperation.MODIFY, entry.operation());
        assertEquals(RoleKey.of(TARGET), entry.target());
        assertEquals("some_mod", entry.providerId());
        assertTrue(entry.legacy());
        assertFalse(entry.declaration().hasRawRegistrar(), "no skillRegistrar -> reversible bundle");
        assertEquals(RoleKey.of(TARGET), entry.declaration().target());
    }

    @Test
    void translatorMarksRawRegistrarModifyInvalid() {
        ModifyRoleDefinition def = ModifyRoleDefinition.builder()
                .sourceModId("some_mod")
                .displayName(Component.literal("legacy modify"))
                .targetRoleId(TARGET)
                .skillRegistrar(original -> List.of())
                .build();
        ManagedRoleEntry<RolePatchBundle> entry = LegacyRoleOverrideTranslator.translateModify(def);
        assertEquals(EntryStatus.INVALID, entry.status());
        assertTrue(entry.declaration().hasRawRegistrar(), "legacy skillRegistrar is flagged un-reversible");
    }

    @Test
    void v1V2SameTargetConflictIsDiagnosable() {
        // v2 REPLACE owns the target.
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(TARGET), definition("habitrain_core", "shadow_killer"))
                        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        RoleExtensionRegistry.INSTANCE.freeze();

        // A legacy v1 REPLACE on the same target must surface as CONFLICT.
        ReplaceRoleDefinition v1 = ReplaceRoleDefinition.builder()
                .sourceModId("some_mod")
                .displayName(Component.literal("legacy replace"))
                .targetRoleId(TARGET)
                .replacementRole(new NormalRole(ResourceLocation.parse("some_mod:legacy_repl"),
                        0xFFAA0000, false, true, SRERole.MoodType.FAKE, 20, true))
                .build();

        List<ManagedRoleEntry<?>> entries =
                RoleConflictAnalyzer.analyze(List.of(v1), List.of());
        ManagedRoleEntry<?> v1Entry = entries.stream()
                .filter(ManagedRoleEntry::legacy)
                .findFirst()
                .orElseThrow();
        assertEquals(EntryStatus.CONFLICT, v1Entry.status());
        assertEquals(RoleKey.of(TARGET), v1Entry.target());
    }

    @Test
    void v2EntryShellsCarryProviderAndOperation() {
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                com.habitrain.core.api.role.v2.definition.RolePatch.builder(TARGET)
                        .defaultMax(com.habitrain.core.api.role.v2.definition.RolePatch.IntPatch.set(2))
                        .build());
        RoleExtensionRegistry.INSTANCE.freeze();

        List<ManagedRoleEntry<?>> entries = RoleConflictAnalyzer.analyze(List.of(), List.of());
        ManagedRoleEntry<?> modify = entries.stream()
                .filter(e -> e.operation() == RoleOperation.MODIFY)
                .findFirst()
                .orElseThrow();
        assertEquals("habitrain_core", modify.providerId());
        assertEquals(EntryStatus.ACTIVE, modify.status());
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

    private static void setField(String name, Object value) throws Exception {
        Field field = RoleExtensionRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(RoleExtensionRegistry.INSTANCE, value);
    }
}
