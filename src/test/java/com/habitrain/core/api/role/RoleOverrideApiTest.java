package com.habitrain.core.api.role;

import com.habitrain.core.config.RoleOverrideConfigSection;
import com.habitrain.core.api.role.book.RoleBookContent;
import com.habitrain.core.api.role.book.RoleBookPage;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.role.override.EffectiveSnapshot;
import com.habitrain.core.role.override.RoleOverrideEngine;
import com.habitrain.core.role.override.RoleOverrideTickApplier;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleOverrideApiTest {
    private static final ResourceLocation TARGET = ResourceLocation.parse("sre:killer");

    @Test
    void sameRoleMatchesByIdentifierNotInstance() {
        SRERole first = role("habitrain_core:sin_envy");
        SRERole replacement = role("habitrain_core:sin_envy");
        assertTrue(HabiRoles.sameRole(first, replacement));
        assertFalse(HabiRoles.sameRole(first, role("habitrain_core:sin_pride")));
        assertFalse(HabiRoles.sameRole(first, null));
    }

    @Test
    void coreBuildsCanonicalProviderRoleId() {
        assertEquals(ResourceLocation.parse("example_mod:shadow_killer"),
                RoleOverrideApi.roleId("Example_Mod", "Shadow_Killer"));
        assertThrows(IllegalArgumentException.class,
                () -> RoleOverrideApi.roleId("example_mod", " "));
    }

    @Test
    void replacementIdMustMatchTheRoleObject() {
        SRERole role = role("example_mod:shadow_killer");
        assertThrows(IllegalArgumentException.class, () ->
                ReplaceRoleDefinition.builder()
                        .sourceModId("example_mod")
                        .displayName(Component.literal("Shadow Killer"))
                        .targetRoleId(TARGET)
                        .replacementRole(role)
                        .replacementId(ResourceLocation.parse("example_mod:other"))
                        .build());
    }

    @Test
    void explicitEntryKeysKeepSameTargetModificationsIndependent() {
        ModifyRoleDefinition first = modify("first");
        ModifyRoleDefinition second = modify("second");

        assertNotEquals(RoleOverrideApi.getEntryId(first), RoleOverrideApi.getEntryId(second));
        assertTrue(RoleOverrideApi.getEntryId(first).contains("$first@"));
        assertTrue(RoleOverrideApi.getEntryId(second).contains("$second@"));
    }

    @Test
    void replacementEntryIdIsStableAndProviderOwned() {
        ReplaceRoleDefinition definition = ReplaceRoleDefinition.builder()
                .sourceModId("example_mod")
                .entryKey("shadow_killer")
                .displayName(Component.literal("Shadow Killer"))
                .targetRoleId(TARGET)
                .replacementRole(role("example_mod:shadow_killer"))
                .build();

        assertEquals("example_mod$shadow_killer@sre:killer",
                RoleOverrideApi.getEntryId(definition));
        assertEquals(ResourceLocation.parse("example_mod:shadow_killer"),
                definition.replacementId().orElseThrow());
    }

    @Test
    void roleOverrideSwitchesRoundTripThroughJson() {
        RoleOverrideConfigSection original = RoleOverrideConfigSection.createDefault();
        original.setGlobalEnabled(false);
        original.setEnabled("example_mod$shadow_killer@sre:killer", false);

        RoleOverrideConfigSection restored =
                RoleOverrideConfigSection.fromJson(original.toJson());

        assertEquals(false, restored.isGlobalEnabled());
        assertEquals(false, restored.isEnabled("example_mod$shadow_killer@sre:killer"));
    }

    @Test
    void replacementRoleBookContentRoundTripsThroughDefinition() {
        RoleBookContent content = RoleBookContent.of(
                RoleBookPage.of(
                        Component.literal("职业介绍"),
                        Component.literal("完全由接入模组提供")
                )
        );

        ReplaceRoleDefinition definition = ReplaceRoleDefinition.builder()
                .sourceModId("example_mod")
                .displayName(Component.literal("替换"))
                .targetRoleId(TARGET)
                .replacementRole(role("example_mod:shadow_killer"))
                .roleBookContent(content)
                .build();

        assertEquals(content, definition.roleBookContent().orElseThrow());
    }

    @Test
    void modifyRoleBookAppendicesPreserveProviderOrder() {
        RoleBookPage first = RoleBookPage.of(
                Component.literal("第一项"),
                Component.literal("第一段")
        );
        RoleBookPage second = RoleBookPage.of(
                Component.literal("第二项"),
                Component.literal("第二段")
        );

        ModifyRoleDefinition definition = ModifyRoleDefinition.builder()
                .sourceModId("example_mod")
                .displayName(Component.literal("调整"))
                .targetRoleId(TARGET)
                .roleBookAppendix(first)
                .roleBookAppendix(second)
                .build();

        assertEquals(java.util.List.of(first, second), definition.roleBookAppendices());
    }

    @Test
    void roleBookModelsRejectEmptyOrNullContent() {
        assertThrows(IllegalArgumentException.class, RoleBookContent::of);
        assertThrows(IllegalArgumentException.class,
                () -> RoleBookPage.of(Component.literal("空页面")));
        assertThrows(NullPointerException.class,
                () -> RoleBookContent.of((RoleBookPage) null));
        assertThrows(NullPointerException.class,
                () -> RoleBookPage.of(Component.literal("空段落"), (Component) null));
    }

    @Test
    void getActiveModifyStaysLiveWhileGameplayModifyIsRoundFrozen() throws Exception {
        ModifyRoleDefinition captured = modify("captured");
        ModifyRoleDefinition live = modify("live");
        Field field = RoleOverrideEngine.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        Object previous = field.get(engine);
        try {
            field.set(engine, new EffectiveSnapshot(Map.of(), Map.of(TARGET, captured), List.of()));
            RoleOverrideTickApplier.captureRound();
            field.set(engine, new EffectiveSnapshot(Map.of(), Map.of(TARGET, live), List.of()));

            assertEquals(live, RoleOverrideApi.getActiveModify(TARGET));
            assertEquals(captured, engine.getGameplayModify(TARGET));
        } finally {
            RoleOverrideTickApplier.discardRoundFreeze();
            field.set(engine, previous);
        }
    }

    private static ModifyRoleDefinition modify(String entryKey) {
        return ModifyRoleDefinition.builder()
                .sourceModId("example_mod")
                .entryKey(entryKey)
                .displayName(Component.literal(entryKey))
                .targetRoleId(TARGET)
                .build();
    }

    private static SRERole role(String id) {
        return new NormalRole(
                ResourceLocation.parse(id),
                0xFFAA0000,
                false,
                true,
                SRERole.MoodType.FAKE,
                20,
                true
        );
    }
}
