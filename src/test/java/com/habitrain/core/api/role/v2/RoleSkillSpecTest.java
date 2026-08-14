package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.definition.ListOp;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.api.role.v2.skill.RoleSkillPatch;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.role.extension.ManagedSRERole;
import com.habitrain.core.role.extension.RoleExtensionCompiler;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the v2 {@link RoleSkillSpec}/{@link RoleSkillPatch} merge model. Specs are
 * id-only so the suite stays bootstrap-safe (no {@code RoleSkill.skill()}).
 */
class RoleSkillSpecTest {

    private static final ResourceLocation TARGET = ResourceLocation.parse("sre:vigilante");
    private static final RoleSkillSpec DASH = RoleSkillSpec.of(ResourceLocation.parse("mod:dash"));
    private static final RoleSkillSpec SMOKE = RoleSkillSpec.of(ResourceLocation.parse("mod:smoke"));
    private static final RoleSkillSpec GIFT = RoleSkillSpec.of(ResourceLocation.parse("mod:gift"));

    @Test
    void appendThenRemoveMatchingIds() {
        List<RoleSkillSpec> folded = RoleSkillPatch.removeMatchingIds(SMOKE)
                .apply(RoleSkillPatch.append(DASH, SMOKE).apply(List.of()));
        assertEquals(List.of(DASH), folded);
    }

    @Test
    void replaceAllDiscardsBaseline() {
        List<RoleSkillSpec> folded = RoleSkillPatch.replaceAll(GIFT).apply(List.of(DASH, SMOKE));
        assertEquals(List.of(GIFT), folded);
    }

    @Test
    void appendRejectsDuplicateIds() {
        assertThrows(IllegalStateException.class,
                () -> RoleSkillPatch.append(DASH).apply(List.of(DASH)));
    }

    @Test
    void managedRoleStoresDeclaredSkills() {
        RoleDefinition def = RoleDefinition.builder("habitrain_core", "skilled")
                .presentation(RolePresentation.builder().color(0x11).build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .skill(DASH)
                .skill(SMOKE)
                .maxSprintTime(20)
                .build();
        ManagedSRERole role = ManagedSRERole.from(def);
        assertEquals(List.of(DASH, SMOKE), role.skills());
    }

    @Test
    void skillPatchFoldsThroughCompiler() {
        SRERole base = new NormalRole(TARGET, 0, true, false, SRERole.MoodType.REAL, 20, false);
        CompiledModifyOverlay overlay = RoleExtensionCompiler.compileModifyOverlay(base, List.of(
                RolePatch.builder(TARGET).skills(RoleSkillPatch.append(DASH, SMOKE)).build(),
                RolePatch.builder(TARGET).skills(RoleSkillPatch.removeMatchingIds(SMOKE)).build()), null);
        assertEquals(List.of(DASH), overlay.skills());
        assertEquals(ListOp.APPEND, RoleSkillPatch.append(DASH).op());
        assertTrue(overlay.skills().getFirst().id().equals(DASH.id()));
    }
}
