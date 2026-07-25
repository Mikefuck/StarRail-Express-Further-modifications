package com.habitrain.core.api.role;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.habitrain.core.role.override.EffectiveSnapshot;
import java.util.Collection;

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

    @Test
    public void engineRebuildsWithEmptySnapshotByDefault() {
        EffectiveSnapshot snapshot = com.habitrain.core.role.override.RoleOverrideEngine.getInstance().getSnapshot();
        assertTrue(snapshot.getActiveReplaces().isEmpty());
        assertTrue(snapshot.getActiveModifies().isEmpty());
    }

    @Test
    public void duplicateReplaceSameTargetCreatesConflict() {
        SRERole r1 = new NormalRole(ResourceLocation.fromNamespaceAndPath("a", "x"), 0, false, true, SRERole.MoodType.FAKE, 20, true);
        SRERole r2 = new NormalRole(ResourceLocation.fromNamespaceAndPath("a", "y"), 0, false, true, SRERole.MoodType.FAKE, 20, true);
        RoleOverrideApi.registerReplace(ReplaceRoleDefinition.builder()
            .sourceModId("a").displayName(Component.literal("X"))
            .targetRoleId(ResourceLocation.parse("sre:killer")).replacementRole(r1).build());
        RoleOverrideApi.registerReplace(ReplaceRoleDefinition.builder()
            .sourceModId("a").displayName(Component.literal("Y"))
            .targetRoleId(ResourceLocation.parse("sre:killer")).replacementRole(r2).build());
        com.habitrain.core.role.override.RoleOverrideEngine.getInstance().rebuild();
        // With two replaces for the same target, neither should be active (conflict)
        assertFalse(com.habitrain.core.role.override.RoleOverrideEngine.getInstance().isReplaced(ResourceLocation.parse("sre:killer")));
    }

    @Test
    public void conflictPreventsActivation() {
        SRERole r1 = new NormalRole(ResourceLocation.fromNamespaceAndPath("a", "x"), 0, false, true, SRERole.MoodType.FAKE, 20, true);
        SRERole r2 = new NormalRole(ResourceLocation.fromNamespaceAndPath("a", "y"), 0, false, true, SRERole.MoodType.FAKE, 20, true);
        RoleOverrideApi.registerReplace(ReplaceRoleDefinition.builder()
            .sourceModId("a").displayName(Component.literal("X"))
            .targetRoleId(ResourceLocation.parse("sre:killer")).replacementRole(r1).build());
        RoleOverrideApi.registerReplace(ReplaceRoleDefinition.builder()
            .sourceModId("a").displayName(Component.literal("Y"))
            .targetRoleId(ResourceLocation.parse("sre:killer")).replacementRole(r2).build());
        com.habitrain.core.role.override.RoleOverrideEngine.getInstance().rebuild();
        Collection<RoleOverrideEntry> entries = RoleOverrideApi.getEffectiveEntries();
        boolean anyActive = entries.stream().anyMatch(e -> e.status() == OverrideStatus.ACTIVE);
        assertFalse(anyActive);
    }
}
