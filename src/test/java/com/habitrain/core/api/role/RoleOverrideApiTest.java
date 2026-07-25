package com.habitrain.core.api.role;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RoleOverrideApiTest {
    @Test
    public void replaceDefinitionCapturesTargetAndRole() {
        SRERole role = new NormalRole(
            ResourceLocation.fromNamespaceAndPath("test", "new_killer"),
            0xFF0000, false, true, SRERole.MoodType.FAKE, 20, true
        );
        ReplaceRoleDefinition def = ReplaceRoleDefinition.builder()
            .sourceModId("test")
            .displayName(Component.literal("New Killer"))
            .targetRoleId(ResourceLocation.parse("sre:killer"))
            .replacementRole(role)
            .build();
        assertEquals("test", def.sourceModId());
        assertEquals(RoleOverrideKind.REPLACE, def.kind());
        assertEquals(ResourceLocation.parse("sre:killer"), def.targetRoleId());
        assertSame(role, def.replacementRole());
    }

    @Test
    public void apiExposesEffectiveEntriesBeforeFreeze() {
        assertNotNull(RoleOverrideApi.getEffectiveEntries());
    }
}
