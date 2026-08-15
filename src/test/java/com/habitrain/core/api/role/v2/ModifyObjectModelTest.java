package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.ReplacementIdentity;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.role.catalog.RoleCatalogImpl;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.extension.RoleOverlayAccessor;
import com.habitrain.core.role.extension.RoleRuntimeOverlayApplier;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C: P0 MODIFY object-model. A modified role keeps its ORIGINAL object —
 * identity, component key and subclass behavior intact — while the four public
 * spawn fields are materialized onto it, getter values are reachable through the
 * overlay accessor, and everything restores without accumulation.
 */
class ModifyObjectModelTest {

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
        RoleSnapshotManager.INSTANCE.clear();
        RoleRuntimeOverlayApplier.clear();
    }

    @Test
    void catalogModifyReturnsOriginalObject() {
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());
        RoleExtensionRegistry.INSTANCE.freeze();

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(TARGET, role(TARGET));
        RoleCatalogImpl api = new RoleCatalogImpl(raw);

        EffectiveRole er = api.find(RoleKey.of(TARGET)).orElseThrow();
        assertSame(raw.get(TARGET), er.role(), "catalog MODIFY must surface the original object");
        // The pre-snapshot catalog folds the MODIFY into the immutable profile;
        // the public spawn field is materialized onto the role only at activation.
        assertEquals(2, er.profile().defaultMax(), "MODIFY folded into the immutable profile");
        assertSame(raw.get(TARGET), api.find(RoleKey.of(TARGET)).orElseThrow().role());
    }

    @Test
    void modifyKeepsObjectIdentity() {
        SRERole base = role(TARGET);
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());

        SRERole out = RoleRuntimeOverlayApplier.applyModifiesAndReturn(base);
        assertSame(base, out, "the same object instance must be returned");
    }

    @Test
    void modifyPreservesComponentKey() {
        SRERole base = role(TARGET);
        Object componentKey = base.getComponentKey();
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());

        RoleRuntimeOverlayApplier.applyModifiesAndReturn(base);
        assertSame(componentKey, base.getComponentKey(), "component key must not be replaced");
        RoleRuntimeOverlayApplier.restoreAll();
        assertSame(componentKey, base.getComponentKey(), "component key survives restore");
    }

    @Test
    void modifyPreservesSubclassCallbacks() {
        RecordingRole base = new RecordingRole();
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());

        RoleRuntimeOverlayApplier.applyModifiesAndReturn(base);
        assertTrue(base.isKiller(), "subclass override must still be the live behavior");
        assertTrue(base.killerOverrideCalled, "override must have been invoked on the original subclass");
    }

    @Test
    void overlayIsRegisteredInAccessor() {
        SRERole base = role(TARGET);
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.set(2)).build());

        RoleRuntimeOverlayApplier.applyModifiesAndReturn(base);
        CompiledModifyOverlay overlay = RoleOverlayAccessor.currentOverlay(base);
        assertNotNull(overlay, "folded values must be reachable through the accessor");
        assertEquals(2, overlay.defaultMax());
        assertEquals(2, base.defaultMaxCount, "spawn field written onto the original");
    }

    @Test
    void disableRestoresScalarsAndRelations() {
        SRERole civilian = new NormalRole(ResourceLocation.parse("sre:civilian"), COLOR,
                false, true, SRERole.MoodType.FAKE, 20, true);
        RoleKey civilianKey = RoleKey.of(civilian.identifier());
        Map<RoleKey, SRERole> resolver = new LinkedHashMap<>();
        resolver.put(civilianKey, civilian);
        RoleRuntimeOverlayApplier.setRelationResolver(resolver::get);

        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET)
                        .defaultMax(RolePatch.IntPatch.set(5))
                        .occupation(RolePatch.RoleKeyListPatch.append(civilianKey))
                        .build());
        RoleExtensionRegistry.INSTANCE.freeze();

        SRERole base = role(TARGET);
        assertTrue(base.occupationRoles.isEmpty(), "baseline has no occupation");
        RoleRuntimeOverlayApplier.applyModifiesAndReturn(base);
        assertEquals(5, base.defaultMaxCount);
        assertTrue(base.occupationRoles.contains(civilian), "patch relation linked onto the original");

        RoleRuntimeOverlayApplier.restoreAll();
        assertEquals(1, base.defaultMaxCount, "scalar restored to baseline");
        assertTrue(base.occupationRoles.isEmpty(), "relation restored to baseline");
        assertTrue(civilian.occupationedRoles.isEmpty(),
                "reverse occupation reference on the counterpart must also be restored");
    }

    @Test
    void restoreRemovesTwoWayOpposingAndRelatedCounterparts() {
        SRERole civilian = new NormalRole(ResourceLocation.parse("sre:civilian"), COLOR,
                false, true, SRERole.MoodType.FAKE, 20, true);
        SRERole related = new NormalRole(ResourceLocation.parse("sre:related"), COLOR,
                false, true, SRERole.MoodType.FAKE, 20, true);
        Map<RoleKey, SRERole> resolver = new LinkedHashMap<>();
        resolver.put(RoleKey.of(civilian.identifier()), civilian);
        resolver.put(RoleKey.of(related.identifier()), related);
        RoleRuntimeOverlayApplier.setRelationResolver(resolver::get);

        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET)
                        .opposing(RolePatch.RoleKeyListPatch.append(RoleKey.of(civilian.identifier())))
                        .related(RolePatch.RoleKeyListPatch.append(RoleKey.of(related.identifier())))
                        .build());
        RoleExtensionRegistry.INSTANCE.freeze();

        SRERole base = role(TARGET);
        RoleRuntimeOverlayApplier.applyModifiesAndReturn(base);
        assertTrue(base.opposingRoles.contains(civilian), "two-way opposing linked onto the original");
        assertTrue(civilian.opposingRoles.contains(base), "two-way opposing linked onto the counterpart");
        assertTrue(base.relatedRoles.contains(related), "related linked onto the original");
        assertTrue(related.relatedRoles.contains(base), "related linked onto the counterpart");

        RoleRuntimeOverlayApplier.restoreAll();
        assertTrue(base.opposingRoles.isEmpty(), "opposing restored on the original");
        assertTrue(civilian.opposingRoles.isEmpty(), "opposing restored on the counterpart");
        assertTrue(base.relatedRoles.isEmpty(), "related restored on the original");
        assertTrue(related.relatedRoles.isEmpty(), "related restored on the counterpart");
    }

    @Test
    void toggleHundredTimesNoAccumulation() {
        // ADD op is the accumulation hazard: seeding must come from the captured
        // baseline, not from the already-materialized value.
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(TARGET).defaultMax(RolePatch.IntPatch.add(1)).build());
        RoleExtensionRegistry.INSTANCE.freeze();

        SRERole base = role(TARGET);
        for (int i = 0; i < 100; i++) {
            RoleRuntimeOverlayApplier.applyModifiesAndReturn(base);
            assertEquals(2, base.defaultMaxCount, "1 + 1 must stay 2 across toggles");
        }
        RoleRuntimeOverlayApplier.restoreAll();
        assertEquals(1, base.defaultMaxCount, "restore returns to the pristine baseline");
    }

    @Test
    void modifyReplacementFoldsWithoutAccumulation() {
        RoleExtensionRegistry.INSTANCE.replace("habitrain_core",
                RoleReplacement.builder(RoleKey.of(TARGET), definition("habitrain_core", "shadow_killer"))
                        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS).build());
        ResourceLocation replacementId = ResourceLocation.parse("habitrain_core:shadow_killer");
        RoleExtensionRegistry.INSTANCE.modify("habitrain_core",
                RolePatch.builder(replacementId).defaultMax(RolePatch.IntPatch.add(1)).build());
        RoleExtensionRegistry.INSTANCE.freeze();

        var repl = RoleExtensionRegistry.INSTANCE.getReplacements().getFirst();
        SRERole surfaced = RoleExtensionRegistry.INSTANCE.applyModifiesToReplacement(repl);
        assertSame(repl.replacement().key().location(), surfaced.identifier());
        int expected = surfaced.defaultMaxCount; // base default + 1
        for (int i = 0; i < 100; i++) {
            SRERole again = RoleExtensionRegistry.INSTANCE.applyModifiesToReplacement(repl);
            assertEquals(expected, again.defaultMaxCount, "replacement MODIFY must not accumulate");
        }
        RoleRuntimeOverlayApplier.restoreAll();
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

    /** Subclass whose override must survive MODIFY. */
    private static final class RecordingRole extends NormalRole {
        boolean killerOverrideCalled;

        RecordingRole() {
            super(TARGET, COLOR, false, true, SRERole.MoodType.FAKE, 20, true);
        }

        @Override
        public boolean isKiller() {
            killerOverrideCalled = true;
            return true;
        }
    }

    private static void setField(String name, Object value) throws Exception {
        Field field = RoleExtensionRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(RoleExtensionRegistry.INSTANCE, value);
    }
}
