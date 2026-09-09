package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RolePresentation;
import com.habitrain.core.api.role.v2.definition.RoleFactionProfile;
import com.habitrain.core.api.role.v2.definition.RoleSpawnProfile;
import com.habitrain.core.api.role.v2.definition.RoleCompatibilityProfile;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.NormalRole;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FactoryRoleSkillRegistrationTest {
    @Test
    void factorySkillsAreAvailableBeforeServerFreezeAndRepeatedPublicationIsIdempotent() throws Exception {
        var roleId = ResourceLocation.parse("habitrain_core:test_factory_skill");
        var skillId = ResourceLocation.parse("habitrain_core:test_factory_drowsiness");
        var skill = RoleSkill.skill(skillId, "test.drowsiness", ctx -> true)
                .cooldownSeconds(30).showOnHud(true).build();
        var definition = definition(roleId)
                .skill(RoleSkillSpec.of(skill))
                .roleFactory(d -> new NormalRole(d.key().location(), 0x64648c,
                        false, false, SRERole.MoodType.FAKE, Integer.MAX_VALUE, false))
                .build();
        SRERole factoryRole = ManagedSRERole.compile(definition);
        assertFalse(factoryRole instanceof ManagedSRERole);
        var constructor = RoleExtensionRegistry.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var registry = constructor.newInstance();
        fieldMap(registry, "managedRoles").put(roleId, factoryRole);
        fieldMap(registry, "addedDefinitions").put(roleId, definition);
        var accessible = RoleExtensionRegistry.class.getDeclaredField("tmmAccessible");
        accessible.setAccessible(true);
        accessible.setBoolean(registry, true);

        registry.registerDeclaredSkills();
        registry.registerDeclaredSkills();
        assertEquals(java.util.List.of(skill), RoleSkill.getDefinitions(factoryRole));
        assertEquals(roleId, RoleSkill.getSkillEntry(skillId).orElseThrow().roleId());
        assertTrue(RoleSkill.getDefinitions(factoryRole).getFirst().showOnHud());
    }

    @Test
    void rollbackRestoresFactoryDefinitionsAlongsideRoleObjects() throws Exception {
        var constructor = RoleExtensionRegistry.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var registry = constructor.newInstance();
        var snapshot = registry.snapshotForTransaction(false);
        var id = ResourceLocation.parse("habitrain_core:test_rolled_back_factory");
        fieldMap(registry, "addedDefinitions").put(id, definition(id).build());
        registry.restoreTransactionSnapshot(snapshot);
        assertTrue(fieldMap(registry, "addedDefinitions").isEmpty());
    }

    private static RoleDefinition.Builder definition(ResourceLocation id) {
        return RoleDefinition.builder(id)
                .presentation(RolePresentation.builder().build())
                .faction(RoleFactionProfile.builder().neutral().build())
                .spawn(RoleSpawnProfile.builder().build())
                .compatibility(RoleCompatibilityProfile.builder().build())
                .maxSprintTime(Integer.MAX_VALUE);
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, Object> fieldMap(RoleExtensionRegistry registry, String name)
            throws ReflectiveOperationException {
        var field = RoleExtensionRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<ResourceLocation, Object>) field.get(registry);
    }
}
