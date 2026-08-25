package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.extension.EntryStatus;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.ManagedSRERole;
import com.habitrain.core.role.extension.RoleConflictAnalyzer;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import net.minecraft.network.chat.Component;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-G10-015: a config-enabled v2 ADD owns its id the same way REPLACE/MODIFY
 * do, so a v1 MODIFY on that id is CONFLICT and never a tick/shop candidate.
 *
 * <p>{@code RoleOverrideEngine.rebuild} cannot run here ({@code TMMRoles} static
 * init); rebuild uses {@link RoleOverrideEngine#v2OwnsTarget} which is what
 * this test seeds and asserts, together with the analyzer's same predicate.
 */
class V2OwnsAddConflictTest {

    private static final ResourceLocation ADDED_ID =
            ResourceLocation.parse("habitrain_core:test_added_v2owns");
    private static final ResourceLocation UNOWNED = ResourceLocation.parse("sre:killer");

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
        RoleExtensionConfigService.INSTANCE.resetForTests();
    }

    @AfterEach
    void cleanup() throws Exception {
        setField("managedRoles", new LinkedHashMap<>());
        RoleExtensionConfigService.INSTANCE.resetForTests();
    }

    @Test
    void v2OwnsTargetIncludesActiveAdd() throws Exception {
        assertFalse(RoleOverrideEngine.v2OwnsTarget(ADDED_ID));
        seedAdded(ADDED_ID);
        assertTrue(RoleOverrideEngine.v2OwnsTarget(ADDED_ID));
        assertFalse(RoleOverrideEngine.v2OwnsTarget(UNOWNED));
    }

    @Test
    void analyzerMarksV1ModifyOnAddedRoleAsConflict() throws Exception {
        seedAdded(ADDED_ID);
        ModifyRoleDefinition v1 = ModifyRoleDefinition.builder()
                .sourceModId("example_mod")
                .entryKey("patch_added")
                .displayName(Component.literal("patch added"))
                .targetRoleId(ADDED_ID)
                .build();

        List<ManagedRoleEntry<?>> entries = RoleConflictAnalyzer.analyze(List.of(), List.of(v1));
        ManagedRoleEntry<?> legacy = entries.stream()
                .filter(ManagedRoleEntry::legacy)
                .findFirst()
                .orElseThrow();
        assertEquals(EntryStatus.CONFLICT, legacy.status());
        assertTrue(legacy.statusMessage().contains("ADD"));
        assertTrue(RoleOverrideEngine.v2OwnsTarget(ADDED_ID),
                "rebuild uses v2OwnsTarget; conflicted v1 modifies never enter active modifies");
    }

    @Test
    void analyzerLeavesUnownedV1ModifyUnmanaged() {
        ModifyRoleDefinition v1 = ModifyRoleDefinition.builder()
                .sourceModId("example_mod")
                .entryKey("patch_unowned")
                .displayName(Component.literal("patch unowned"))
                .targetRoleId(UNOWNED)
                .build();

        List<ManagedRoleEntry<?>> entries = RoleConflictAnalyzer.analyze(List.of(), List.of(v1));
        ManagedRoleEntry<?> legacy = entries.stream()
                .filter(ManagedRoleEntry::legacy)
                .findFirst()
                .orElseThrow();
        assertEquals(EntryStatus.LEGACY_UNMANAGED, legacy.status());
        assertFalse(RoleOverrideEngine.v2OwnsTarget(UNOWNED));
    }

    private static void seedAdded(ResourceLocation id) throws Exception {
        RoleDefinition def = RoleDefinition.builder(id)
                .presentation(RolePresentation.builder().color(0x785A3C).build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .maxSprintTime(20)
                .build();
        setField("managedRoles", new LinkedHashMap<>(Map.of(id, ManagedSRERole.from(def))));
    }

    private static void setField(String name, Object value) throws Exception {
        Field field = RoleExtensionRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(RoleExtensionRegistry.INSTANCE, value);
    }
}
