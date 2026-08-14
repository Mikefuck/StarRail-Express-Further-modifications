package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleRelationProfile;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.api.role.v2.definition.RoleVisibilityProfile;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.ManagedSRERole;
import com.habitrain.core.role.extension.RoleExtensionCompiler;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the P1 increment-1 static-profile expansion: visibility / relation /
 * faction extras / compatibility extras compile through {@link ManagedSRERole#from},
 * and the matching {@link RolePatch} merge ops fold through
 * {@link RoleExtensionCompiler}. Pure construction only — no {@code TMMRoles}.
 */
class RoleProfileParityTest {

    private static final ResourceLocation ID = ResourceLocation.parse("habitrain_core:parity");
    private static final ResourceLocation TARGET = ResourceLocation.parse("sre:vigilante");

    @Test
    void managedRoleAppliesVisibilityAndFactionExtras() {
        RoleDefinition def = RoleDefinition.builder(ID)
                .presentation(RolePresentation.builder().color(0x11).build())
                .faction(RoleFactionProfile.builder().innocent().neutralForKiller().mafiaTeam().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder()
                        .otherModeRole().hiddenForRotation().occupiedRoleCount(2).build())
                .visibility(RoleVisibilityProfile.builder()
                        .canUseInstinct().instinctNightVision().canSeeTeammateKiller().build())
                .maxSprintTime(20)
                .build();

        ManagedSRERole role = ManagedSRERole.from(def);

        assertTrue(role.isNeutralForKiller());
        assertTrue(role.isMafiaTeam());
        assertTrue(role.canUseInstinct());
        assertTrue(role.haveInstinctNightVision());
        assertTrue(role.canSeeTeammateKillerRole());
        assertTrue(role.isOtherModeRole());
        assertTrue(role.getFlags().contains("inner.role_rotation.hidden"));
        assertEquals(2, role.getOccupiedRoleCount());
    }

    @Test
    void absentVisibilityLeavesUpstreamInstinctDefaults() {
        ManagedSRERole role = ManagedSRERole.from(minimalDef(RoleKey.of(ID)));
        assertFalse(role.canUseInstinct());
        assertFalse(role.haveInstinctNightVision());
        assertNull(role.relationProfile());
    }

    @Test
    void definitionAllowsAbsentVisibilityAndRelations() {
        RoleDefinition def = minimalDef(RoleKey.of(ID));
        assertNull(def.visibility());
        assertNull(def.relations());
    }

    @Test
    void managedRoleAppliesCompatibilityExtras() {
        RoleDefinition def = RoleDefinition.builder(ID)
                .presentation(RolePresentation.builder().color(0x11).build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder()
                        .otherModeRole()
                        .specialMapRole(SRERole.SpecialMapRoleMap.QIYUCUN)
                        .hiddenForRotation()
                        .occupiedRoleCount(2)
                        .build())
                .maxSprintTime(20)
                .build();

        ManagedSRERole role = ManagedSRERole.from(def);
        assertTrue(role.isOtherModeRole());
        assertEquals(SRERole.SpecialMapRoleMap.QIYUCUN, role.getSpecialMapRole());
        assertTrue(role.getFlags().contains("inner.role_rotation.hidden"));
        assertEquals(2, role.getOccupiedRoleCount());
    }

    @Test
    void managedRoleStoresRelationKeysWithoutLinking() {
        RoleKey opp = RoleKey.of("sre", "killer");
        RoleDefinition def = RoleDefinition.builder(ID)
                .presentation(RolePresentation.builder().color(0x11).build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .relations(RoleRelationProfile.builder().opposing(opp).build())
                .maxSprintTime(20)
                .build();

        ManagedSRERole role = ManagedSRERole.from(def);
        assertEquals(List.of(opp), role.opposingRoleKeys());
        assertTrue(role.getOpposingRoles().isEmpty(), "from() must not resolve counterpart roles");
    }

    @Test
    void linkRelationsAppendsOpposingViaResolver() {
        SRERole self = ManagedSRERole.from(minimalDef(RoleKey.of("mod", "a")));
        SRERole other = ManagedSRERole.from(minimalDef(RoleKey.of("mod", "b")));
        Map<RoleKey, SRERole> map = Map.of(RoleKey.of("mod", "b"), other);
        RoleRelationProfile rel = RoleRelationProfile.builder()
                .opposing(RoleKey.of("mod", "b")).build();
        RoleExtensionCompiler.linkRelations(self, rel, map::get);
        assertTrue(self.getOpposingRoles().contains(other));
    }

    @Test
    void linkRelationsSkipsUnknownKeys() {
        SRERole self = ManagedSRERole.from(minimalDef(RoleKey.of("mod", "a")));
        RoleRelationProfile rel = RoleRelationProfile.builder()
                .opposing(RoleKey.of("mod", "missing")).build();
        RoleExtensionCompiler.linkRelations(self, rel, k -> null);
        assertTrue(self.getOpposingRoles().isEmpty());
    }

    @Test
    void booleanPatchOrMergesInstinct() {
        SRERole base = new NormalRole(TARGET, 0, true, false, SRERole.MoodType.REAL, 20, false);
        RolePatch a = RolePatch.builder(TARGET).canUseInstinct(RolePatch.BooleanPatch.set(true)).build();
        RolePatch b = RolePatch.builder(TARGET).instinctNightVision(RolePatch.BooleanPatch.or(true)).build();
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlay(base, List.of(a, b), null);
        assertTrue(overlay.canUseInstinct());
        assertTrue(overlay.instinctNightVision());
    }

    @Test
    void booleanFlagsPatchAppliesInstinctAndNeutralExtras() {
        SRERole base = new NormalRole(TARGET, 0, false, true, SRERole.MoodType.FAKE, 20, false);
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlay(base, List.of(
                RolePatch.builder(TARGET)
                        .canUseInstinct(RolePatch.BooleanPatch.set(true))
                        .instinctNightVision(RolePatch.BooleanPatch.set(true))
                        .canSeeTeammateKiller(RolePatch.BooleanPatch.set(false))
                        .neutralForKiller(RolePatch.BooleanPatch.set(true))
                        .mafiaTeam(RolePatch.BooleanPatch.set(true))
                        .otherModeRole(RolePatch.BooleanPatch.set(true))
                        .hiddenForRotation(RolePatch.BooleanPatch.set(true))
                        .occupiedRoleCount(RolePatch.IntPatch.set(3))
                        .build()), null);

        assertTrue(overlay.canUseInstinct());
        assertTrue(overlay.instinctNightVision());
        assertFalse(overlay.canSeeTeammateKiller());
        assertTrue(overlay.neutralForKiller());
        assertTrue(overlay.mafiaTeam());
        assertTrue(overlay.otherModeRole());
        assertTrue(overlay.hiddenForRotation());
        assertEquals(3, overlay.occupiedRoleCount());
    }

    @Test
    void listPatchAppendThenRemove() {
        List<RoleKey> keys = List.of(RoleKey.of("sre", "killer"), RoleKey.of("sre", "civilian"));
        List<RoleKey> folded = RoleExtensionCompiler.applyList(
                RolePatch.RoleKeyListPatch.remove(RoleKey.of("sre", "civilian")),
                RoleExtensionCompiler.applyList(
                        RolePatch.RoleKeyListPatch.append(keys.toArray(RoleKey[]::new)), List.of()));
        assertEquals(List.of(RoleKey.of("sre", "killer")), folded);
    }

    @Test
    void roleKeyListAppendThenRemoveOnPatchedRole() {
        RoleKey a = RoleKey.of("sre", "a");
        RoleKey b = RoleKey.of("sre", "b");
        SRERole base = new NormalRole(TARGET, 0, true, false, SRERole.MoodType.REAL, 20, false);
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlay(base, List.of(
                RolePatch.builder(TARGET)
                        .opposing(RolePatch.RoleKeyListPatch.append(a, b))
                        .build(),
                RolePatch.builder(TARGET)
                        .opposing(RolePatch.RoleKeyListPatch.remove(a))
                        .build()), null);
        assertEquals(List.of(b), overlay.opposingKeys());
    }

    private static RoleDefinition minimalDef(RoleKey key) {
        return RoleDefinition.builder(key)
                .presentation(RolePresentation.builder().color(0x11).build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .maxSprintTime(20)
                .build();
    }
}
