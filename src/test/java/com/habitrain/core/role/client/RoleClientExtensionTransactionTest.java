package com.habitrain.core.role.client;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.RoleHudSpec;
import com.habitrain.core.api.role.v2.client.RoleInstinctRule;
import com.habitrain.core.api.role.v2.client.RoleSkinKind;
import com.habitrain.core.api.role.v2.client.RoleSkinSpec;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review 2026-08-14: client-extension registration must be transactional across
 * the whole provider batch. Duplicate IDs inside one staged batch must fail
 * before any entry is written, so rollback cannot leave partial HUDs/skins.
 */
class RoleClientExtensionTransactionTest {

    private static final RoleKey CIVILIAN = RoleKey.of("sre", "civilian");

    private RoleClientExtensionRegistry store;

    @BeforeEach
    void setUp() {
        store = new RoleClientExtensionRegistry();
    }

    @Test
    void duplicateHudIdInSameBatchLeavesNoResidual() {
        ScopedRoleClientExtensionRegistrar registrar = new ScopedRoleClientExtensionRegistrar("example", store);
        registrar.hud(RoleHudSpec.of("example", "same").entryKey("armed").role(CIVILIAN).build());
        registrar.hud(RoleHudSpec.of("example", "same").entryKey("armed").role(CIVILIAN).build());

        assertThrows(IllegalArgumentException.class, registrar::commit);
        assertTrue(store.huds().isEmpty(), "duplicate staged HUD id must leave the provider batch empty");
    }

    @Test
    void duplicateInstinctIdInSameBatchLeavesNoResidual() {
        ScopedRoleClientExtensionRegistrar registrar = new ScopedRoleClientExtensionRegistrar("example", store);
        registrar.instinct(RoleInstinctRule.of("example", "mark")
                .entryKey("mark").viewerRole(CIVILIAN).color(0xFF0000).build());
        registrar.instinct(RoleInstinctRule.of("example", "mark")
                .entryKey("mark").viewerRole(CIVILIAN).color(0x00FF00).build());

        assertThrows(IllegalArgumentException.class, registrar::commit);
        assertTrue(store.instincts().isEmpty(), "duplicate staged instinct id must leave no residual");
    }

    @Test
    void validHudThenDuplicateSkinLeavesHudUndone() {
        ScopedRoleClientExtensionRegistrar registrar = new ScopedRoleClientExtensionRegistrar("example", store);
        registrar.hud(RoleHudSpec.of("example", "valid").entryKey("armed").role(CIVILIAN).build());
        registrar.skin(RoleSkinSpec.of("example", "dup")
                .role(CIVILIAN).kind(RoleSkinKind.NORMAL)
                .texture(ResourceLocation.parse("example:textures/entity/civilian.png")).build());
        registrar.skin(RoleSkinSpec.of("example", "dup")
                .role(CIVILIAN).kind(RoleSkinKind.NORMAL)
                .texture(ResourceLocation.parse("example:textures/entity/civilian.png")).build());

        assertThrows(IllegalArgumentException.class, registrar::commit);
        assertTrue(store.huds().isEmpty(), "valid HUD before a failing skin must not remain committed");
        assertTrue(store.skins().isEmpty(), "duplicate skin must not remain committed");
    }

    @Test
    void namespaceNotOwnedByProviderRollsBackWholeBatch() {
        ScopedRoleClientExtensionRegistrar registrar = new ScopedRoleClientExtensionRegistrar("example", store);
        registrar.hud(RoleHudSpec.of("example", "valid").entryKey("armed").role(CIVILIAN).build());
        registrar.hud(RoleHudSpec.of("other", "forged").role(CIVILIAN).build());

        assertThrows(IllegalArgumentException.class, registrar::commit);
        assertTrue(store.huds().isEmpty(), "namespace violation must roll the whole batch back");
    }

    @Test
    void duplicateWidgetIdInSameBatchLeavesNoResidual() {
        ScopedRoleClientExtensionRegistrar registrar = new ScopedRoleClientExtensionRegistrar("example", store);
        registrar.hudWidget(ResourceLocation.parse("example:civilian_status"), "armed", CIVILIAN,
                (w, h, t) -> { });
        registrar.hudWidget(ResourceLocation.parse("example:civilian_status"), "armed", CIVILIAN,
                (w, h, t) -> { });

        assertThrows(IllegalArgumentException.class, registrar::commit);
        assertTrue(store.hudWidgetsFor(CIVILIAN).isEmpty(), "duplicate widget id must leave no residual");
    }

    @Test
    void thirdPartyCanAddWidgetToUpstreamRoleWithProviderOwnedId() {
        ScopedRoleClientExtensionRegistrar registrar = new ScopedRoleClientExtensionRegistrar("civilian_plus", store);
        java.util.concurrent.atomic.AtomicInteger draws = new java.util.concurrent.atomic.AtomicInteger();
        registrar.hudWidget(ResourceLocation.parse("civilian_plus:civilian_status"),
                "armed_civilian", CIVILIAN, (w, h, t) -> draws.incrementAndGet());
        registrar.commit();

        store.setActiveProviders(java.util.Set.of("civilian_plus"),
                java.util.Set.of("civilian_plus$armed_civilian@sre:civilian"));
        assertEquals(1, store.hudWidgetsFor(CIVILIAN).size(),
                "third-party provider must be able to extend an upstream role widget");
        store.hudWidgetsFor(CIVILIAN).iterator().next().render(100, 50, 0f);
        assertEquals(1, draws.get());

        store.setActiveProviders(java.util.Set.of("civilian_plus"),
                java.util.Set.of("civilian_plus$other@sre:civilian"));
        assertTrue(store.hudWidgetsFor(CIVILIAN).isEmpty(),
                "widget must not be consumed when its entry is not active");
    }

    @Test
    void globalRegistryConflictRollsBackWholeBatch() {
        ScopedRoleClientExtensionRegistrar seed =
                new ScopedRoleClientExtensionRegistrar("habitrain_core", store);
        seed.hud(RoleHudSpec.of("habitrain_core", "existing").role(CIVILIAN).build());
        seed.commit();
        ScopedRoleClientExtensionRegistrar registrar = new ScopedRoleClientExtensionRegistrar("habitrain_core", store);
        registrar.hud(RoleHudSpec.of("habitrain_core", "new").entryKey("new").role(CIVILIAN).build());
        registrar.hud(RoleHudSpec.of("habitrain_core", "existing").role(CIVILIAN).build());

        assertThrows(IllegalArgumentException.class, registrar::commit);
        assertEquals(1, store.huds().size(),
                "pre-existing HUD must remain, staged batch must not leak new entries");
    }
}
