package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleEconomyProfile;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RoleInventoryProfile;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.role.catalog.RoleCatalogImpl;
import com.habitrain.core.role.extension.ManagedSRERole;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the v2 {@code ADD} model: {@link RoleDefinition} validation,
 * {@link ManagedSRERole} compilation, and the catalog's {@code isAdded} /
 * {@code EffectiveRole.source} wiring.
 *
 * <p>Only the pure construction path ({@link ManagedSRERole#from}) is tested
 * here — the runtime {@code add()} path touches {@code TMMRoles} and
 * {@code FabricLoader}, which need a launched game. The added-id set is seeded
 * into {@link RoleExtensionRegistry} via reflection, mirroring how the other
 * catalog tests seed {@code RoleOverrideEngine.snapshot}.
 */
class RoleExtensionAddTest {

    private static final ResourceLocation ADDED_ID = ResourceLocation.parse("habitrain_core:test_added");
    private static final int COLOR = 0x785A3C;

    @AfterEach
    void resetRegistry() throws Exception {
        setManagedRoles(new LinkedHashMap<>());
        RoleSnapshotManager.INSTANCE.clear();
    }

    // ------------------------------------------------------------------
    // RoleDefinition builder validation
    // ------------------------------------------------------------------

    @Test
    void builderRejectsMissingFaction() {
        RoleDefinition.Builder b = RoleDefinition.builder(ADDED_ID)
                .presentation(presentation())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(compat())
                .maxSprintTime(20);
        assertThrows(IllegalStateException.class, b::build, "faction is required");
    }

    @Test
    void builderRejectsMissingMaxSprintTime() {
        RoleDefinition.Builder b = RoleDefinition.builder(ADDED_ID)
                .presentation(presentation())
                .faction(factionInnocent())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(compat());
        assertThrows(IllegalStateException.class, b::build, "maxSprintTime is required");
    }

    // ------------------------------------------------------------------
    // ManagedSRERole.from: profile compilation (pure, no TMMRoles)
    // ------------------------------------------------------------------

    @Test
    void managedRoleAppliesProfiles() {
        RoleDefinition def = RoleDefinition.builder(ADDED_ID)
                .presentation(presentation())
                .faction(factionInnocent())
                .spawn(RoleSpawnProfile.builder()
                        .defaultMax(1).enableChance(100).needPlayerCount(10).build())
                .compatibility(RoleCompatibilityProfile.builder()
                        .canSeeCoin().canPickUpRevolver().canBeRandomed().build())
                .maxSprintTime(20)
                .build();

        ManagedSRERole role = ManagedSRERole.from(def);

        assertEquals(ADDED_ID, role.identifier());
        assertEquals(COLOR, role.getColor());
        assertTrue(role.isInnocent());
        assertFalse(role.canUseKiller());
        assertEquals(SRERole.MoodType.REAL, role.getMoodType());
        assertTrue(role.canSeeCoin());
        assertTrue(role.canPickUpRevolver());
        assertTrue(role.canBeRandomedDefination(), "canBeRandomedDefination reads the field without SREDisableManager");
        assertEquals(1, role.defaultMaxCount);
        assertEquals(100, role.defaultEnableChance);
        assertEquals(10, role.defaultEnableNeedPlayerCount);
        // NormalRole derivation: innocent, non-killer -> not neutral, not vigilante.
        assertFalse(role.isNeutrals());
        assertFalse(role.isVigilanteTeam());
    }

    @Test
    void explicitNeutralOverridesConstructorDerivation() {
        RoleDefinition def = RoleDefinition.builder(ADDED_ID)
                .presentation(presentation())
                .faction(RoleFactionProfile.builder().innocent().neutral().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(compat())
                .maxSprintTime(20)
                .build();

        ManagedSRERole role = ManagedSRERole.from(def);

        assertTrue(role.isInnocent());
        assertTrue(role.isNeutrals(), "explicit neutral() must override the constructor derivation");
    }

    @Test
    void absentInventoryAndEconomyStayNull() {
        RoleDefinition def = RoleDefinition.builder(ADDED_ID)
                .presentation(presentation())
                .faction(factionInnocent())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(compat())
                .maxSprintTime(20)
                .build();

        assertNull(def.inventory());
        assertNull(def.economy());
    }

    @Test
    void inventoryAndEconomyProfilesAreCaptured() {
        RoleInventoryProfile inventory = RoleInventoryProfile.builder().build();
        RoleEconomyProfile economy = RoleEconomyProfile.builder().build();
        RoleDefinition def = RoleDefinition.builder(ADDED_ID)
                .presentation(presentation())
                .faction(factionInnocent())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(compat())
                .inventory(inventory)
                .economy(economy)
                .maxSprintTime(20)
                .build();

        assertEquals(inventory, def.inventory());
        assertEquals(economy, def.economy());
    }

    // ------------------------------------------------------------------
    // Catalog wiring: isAdded + EffectiveRole.source
    // ------------------------------------------------------------------

    @Test
    void addedRoleSurfacesWithAddedSource() throws Exception {
        ManagedSRERole role = ManagedSRERole.from(addedDefinition());
        setManagedRoles(Map.of(ADDED_ID, role));

        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(ADDED_ID, role);
        RoleCatalogImpl api = new RoleCatalogImpl(raw);

        EffectiveRole er = api.find(RoleKey.of(ADDED_ID)).orElseThrow();
        assertEquals(EffectiveRole.Source.ADDED, er.source());
        assertEquals(ADDED_ID, er.id());
        assertTrue(api.isAdded(RoleKey.of(ADDED_ID)));
        assertTrue(api.isActive(RoleKey.of(ADDED_ID)));
        assertFalse(api.isReplaced(RoleKey.of(ADDED_ID)));
    }

    @Test
    void baselineRoleIsNotAddedAndStaysBaseline() {
        ResourceLocation baseId = ResourceLocation.parse("habitrain_core:plain_base");
        SRERole plain = new NormalRole(baseId, COLOR, true, false, SRERole.MoodType.REAL, 20, false);
        Map<ResourceLocation, SRERole> raw = new LinkedHashMap<>();
        raw.put(baseId, plain);
        RoleCatalogImpl api = new RoleCatalogImpl(raw);

        EffectiveRole er = api.find(RoleKey.of(baseId)).orElseThrow();
        assertEquals(EffectiveRole.Source.BASELINE, er.source());
        assertFalse(api.isAdded(RoleKey.of(baseId)));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static RoleDefinition addedDefinition() {
        return RoleDefinition.builder(ADDED_ID)
                .presentation(presentation())
                .faction(factionInnocent())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(compat())
                .maxSprintTime(20)
                .build();
    }

    private static RolePresentation presentation() {
        return RolePresentation.builder().color(COLOR).build();
    }

    private static RoleFactionProfile factionInnocent() {
        return RoleFactionProfile.builder().innocent().build();
    }

    private static RoleCompatibilityProfile compat() {
        return RoleCompatibilityProfile.builder().build();
    }

    private static void setManagedRoles(Map<ResourceLocation, ManagedSRERole> map) throws Exception {
        Field field = RoleExtensionRegistry.class.getDeclaredField("managedRoles");
        field.setAccessible(true);
        field.set(RoleExtensionRegistry.INSTANCE, map);
    }
}
