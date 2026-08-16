package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.skill.RoleSkillPatch;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import com.habitrain.core.role.extension.RoleRuntimeOverlayApplier;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-provider MODIFY restore matrix (audit P2-4): several providers patching
 * the same role must restore to the pristine baseline independently of apply
 * order; disabling one provider must leave only the surviving overlay; the
 * unified skill table and relation graphs must return to their captured
 * baselines.
 */
class ModifyCrossProviderRestoreTest {

    private static final ResourceLocation TARGET = ResourceLocation.parse("sre:vigilante");
    private static final int COLOR = 0xFFAA0000;

    private static final RoleSkill.Definition DASH = skillDef(ResourceLocation.parse("mod:dash"));
    private static final RoleSkill.Definition SMOKE = skillDef(ResourceLocation.parse("mod:smoke"));
    private static final RoleSkill.Definition GIFT = skillDef(ResourceLocation.parse("mod:gift"));

    @BeforeEach
    void reset() {
        RoleRuntimeOverlayApplier.clear();
    }

    @Test
    void crossProviderRestoreIsOrderIndependent() {
        RolePatch providerA = RolePatch.builder(TARGET)
                .defaultMax(RolePatch.IntPatch.set(5))
                .color(0x112233)
                .build();
        RolePatch providerB = RolePatch.builder(TARGET)
                .defaultMax(RolePatch.IntPatch.set(9))
                .innocent(RolePatch.BooleanPatch.set(true))
                .build();

        // Order (A, B): B's set wins the scalar, both field patches fold in.
        SRERole base = role();
        RoleRuntimeOverlayApplier.applyModifiesAndReturn(base, List.of(providerA, providerB));
        assertEquals(9, base.defaultMaxCount, "later set wins in (A, B) order");
        assertEquals(0x112233, base.getColor(), "A's color patch must still fold");
        assertTrue(base.isInnocent(), "B's innocent patch must fold");

        RoleRuntimeOverlayApplier.restoreAll();
        assertPristine(base);

        // Order (B, A): A's set wins; restore must land on the same baseline.
        SRERole reversed = role();
        RoleRuntimeOverlayApplier.applyModifiesAndReturn(reversed, List.of(providerB, providerA));
        assertEquals(5, reversed.defaultMaxCount, "later set wins in (B, A) order");
        assertEquals(0x112233, reversed.getColor());
        assertTrue(reversed.isInnocent());

        RoleRuntimeOverlayApplier.restoreAll();
        assertPristine(reversed);
    }

    @Test
    void disablingOneProviderLeavesOnlyTheSurvivor() {
        RolePatch providerA = RolePatch.builder(TARGET)
                .defaultMax(RolePatch.IntPatch.set(5))
                .build();
        RolePatch providerB = RolePatch.builder(TARGET)
                .color(0x112233)
                .build();

        SRERole base = role();
        RoleRuntimeOverlayApplier.applyModifiesAndReturn(base, List.of(providerA, providerB));
        assertEquals(5, base.defaultMaxCount);
        assertEquals(0x112233, base.getColor());

        // Simulate "provider A disabled": restore everything, then activate only B.
        RoleRuntimeOverlayApplier.restoreAll();
        RoleRuntimeOverlayApplier.applyModifiesAndReturn(base, List.of(providerB));

        assertEquals(1, base.defaultMaxCount, "A's defaultMax must not survive disablement");
        assertEquals(0x112233, base.getColor(), "B's overlay stays active");

        RoleRuntimeOverlayApplier.restoreAll();
        assertPristine(base);
    }

    @Test
    void crossProviderSkillReplacementRestoresToPristine() {
        Map<ResourceLocation, List<RoleSkill.Definition>> table = new LinkedHashMap<>();
        table.put(TARGET, List.of(DASH));
        RoleRuntimeOverlayApplier.setSkillBackend(skillBackend(table));

        RolePatch providerA = RolePatch.builder(TARGET)
                .skills(RoleSkillPatch.replaceAll(RoleSkillSpec.of(SMOKE)))
                .build();
        RolePatch providerB = RolePatch.builder(TARGET)
                .skills(RoleSkillPatch.append(RoleSkillSpec.of(GIFT)))
                .build();

        RoleRuntimeOverlayApplier.applyModifiesAndReturn(role(), List.of(providerA, providerB));
        assertEquals(List.of(SMOKE, GIFT), table.get(TARGET),
                "folded skill list must be written to the backend");

        RoleRuntimeOverlayApplier.restoreAll();
        assertEquals(List.of(DASH), table.get(TARGET),
                "skill table must return to the captured baseline, not the folded list");

        // Reverse order: the intermediate fold differs, the restored table must not.
        table.put(TARGET, List.of(DASH));
        RoleRuntimeOverlayApplier.applyModifiesAndReturn(role(), List.of(providerB, providerA));
        assertEquals(List.of(SMOKE), table.get(TARGET), "append then replaceAll -> only A's list");
        RoleRuntimeOverlayApplier.restoreAll();
        assertEquals(List.of(DASH), table.get(TARGET),
                "restore must be independent of the apply order");
    }

    @Test
    void crossProviderRelationsRestoreOnBothSides() {
        SRERole civilian = new NormalRole(ResourceLocation.parse("sre:civilian"), COLOR,
                false, true, SRERole.MoodType.FAKE, 20, true);
        SRERole guard = new NormalRole(ResourceLocation.parse("sre:guard"), COLOR,
                false, true, SRERole.MoodType.FAKE, 20, true);
        Map<com.habitrain.core.api.role.v2.RoleKey, SRERole> resolver = new LinkedHashMap<>();
        resolver.put(RoleKey.of(civilian.identifier()), civilian);
        resolver.put(RoleKey.of(guard.identifier()), guard);
        RoleRuntimeOverlayApplier.setRelationResolver(resolver::get);

        RolePatch providerA = RolePatch.builder(TARGET)
                .occupation(RolePatch.RoleKeyListPatch.append(RoleKey.of(civilian.identifier())))
                .build();
        RolePatch providerB = RolePatch.builder(TARGET)
                .opposing(RolePatch.RoleKeyListPatch.append(RoleKey.of(guard.identifier())))
                .build();

        SRERole base = role();
        RoleRuntimeOverlayApplier.applyModifiesAndReturn(base, List.of(providerA, providerB));
        assertTrue(base.occupationRoles.contains(civilian), "A linked occupation");
        assertTrue(civilian.occupationedRoles.contains(base), "A linked the reverse occupation");
        assertTrue(base.opposingRoles.contains(guard), "B linked opposing");
        assertTrue(guard.opposingRoles.contains(base), "B linked two-way opposing");

        RoleRuntimeOverlayApplier.restoreAll();
        assertTrue(base.occupationRoles.isEmpty(), "occupation restored on the original");
        assertTrue(civilian.occupationedRoles.isEmpty(), "reverse occupation restored on the counterpart");
        assertTrue(base.opposingRoles.isEmpty(), "opposing restored on the original");
        assertTrue(guard.opposingRoles.isEmpty(), "two-way opposing restored on the counterpart");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void assertPristine(SRERole base) {
        assertEquals(1, base.defaultMaxCount, "defaultMax back to pristine");
        assertEquals(COLOR, base.getColor(), "color back to pristine");
        assertFalse(base.isInnocent(), "innocent back to pristine");
    }

    private static SRERole role() {
        return new NormalRole(TARGET, COLOR, false, true, SRERole.MoodType.FAKE, 20, true);
    }

    private static RoleSkill.Definition skillDef(ResourceLocation id) {
        // Pure record construction — bootstrap-safe (no RoleSkill.skill()).
        return new RoleSkill.Definition(
                id, "skill." + id.getNamespace() + "." + id.getPath(), 0, 1, false, 0, true,
                RoleSkill.AnnounceInfo.none(), false, false, false, true, false,
                ctx -> true);
    }

    private static RoleRuntimeOverlayApplier.SkillBackend skillBackend(
            Map<ResourceLocation, List<RoleSkill.Definition>> table) {
        return new RoleRuntimeOverlayApplier.SkillBackend() {
            @Override
            public List<RoleSkill.Definition> definitions(ResourceLocation roleId) {
                return table.getOrDefault(roleId, List.of());
            }

            @Override
            public void replace(ResourceLocation roleId, List<RoleSkill.Definition> definitions) {
                table.put(roleId, definitions == null ? List.of() : List.copyOf(definitions));
            }
        };
    }
}
