package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.role.extension.ManagedSRERole;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression: an omitted compatibility flag must not open killer/neutral gun pickup. */
class RoleRevolverPickupPolicyTest {
    @ParameterizedTest
    @CsvSource({"true,false,true", "false,true,false", "false,false,false"})
    void omittedPermissionKeepsUpstreamFactionDefault(boolean innocent, boolean killer, boolean expected) {
        var compatibility = RoleCompatibilityProfile.builder().build();
        var role = ManagedSRERole.compile(definition(innocent, killer, compatibility).build());

        assertFalse(compatibility.canPickUpRevolverExplicit());
        assertEquals(expected, role.canPickUpRevolver());
    }

    @ParameterizedTest
    @CsvSource({"false,true,true", "false,false,true", "true,false,false"})
    void omittedPermissionPreservesFactoryExceptions(boolean innocent, boolean killer, boolean factoryValue) {
        var definition = definition(innocent, killer, RoleCompatibilityProfile.builder().build())
                .roleFactory(d -> factoryRole(d, factoryValue)).build();

        assertEquals(factoryValue, ManagedSRERole.compile(definition).canPickUpRevolver());
    }

    @ParameterizedTest
    @CsvSource({"true,false,false", "false,true,true", "false,false,true"})
    void explicitPermissionOverridesFactionDefault(boolean innocent, boolean killer, boolean explicitValue) {
        var compatibility = RoleCompatibilityProfile.builder().canPickUpRevolver(explicitValue).build();

        assertTrue(compatibility.canPickUpRevolverExplicit());
        assertEquals(explicitValue,
                ManagedSRERole.compile(definition(innocent, killer, compatibility).build()).canPickUpRevolver());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void explicitPermissionOverridesOppositeFactoryValue(boolean value) {
        var definition = definition(false, true,
                RoleCompatibilityProfile.builder().canPickUpRevolver(value).build())
                .roleFactory(d -> factoryRole(d, !value)).build();

        assertEquals(value, ManagedSRERole.compile(definition).canPickUpRevolver());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void originalConstructorStillAppliesItsSuppliedPermission(boolean value) {
        var compatibility = new RoleCompatibilityProfile(null, true, value, true,
                false, SRERole.SpecialMapRoleMap.ALL, false, 1);

        assertTrue(compatibility.canPickUpRevolverExplicit());
        assertEquals(value, ManagedSRERole.compile(definition(!value, value, compatibility).build())
                .canPickUpRevolver());
    }

    @Test
    void noArgumentGrantIsExplicitAndAllowsNeutralPickup() {
        var compatibility = RoleCompatibilityProfile.builder().canPickUpRevolver().build();

        assertTrue(compatibility.canPickUpRevolverExplicit());
        assertTrue(ManagedSRERole.compile(definition(false, false, compatibility).build()).canPickUpRevolver());
    }

    private static RoleDefinition.Builder definition(boolean innocent, boolean killer,
                                                     RoleCompatibilityProfile compatibility) {
        return RoleDefinition.builder(ResourceLocation.parse("habitrain_core:pickup_policy_test"))
                .presentation(RolePresentation.builder().build())
                .faction(RoleFactionProfile.builder().innocent(innocent).killer(killer).build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(compatibility)
                .maxSprintTime(20);
    }

    private static SRERole factoryRole(RoleDefinition definition, boolean pickup) {
        return new NormalRole(definition.key().location(), definition.presentation().color(),
                definition.faction().innocent(), definition.faction().canUseKiller(),
                definition.presentation().moodType(), definition.maxSprintTime(), definition.canSeeTime())
                .setCanPickUpRevolver(pickup);
    }
}
