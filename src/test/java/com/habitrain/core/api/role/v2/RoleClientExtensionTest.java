package com.habitrain.core.role.client;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.InstinctDecision;
import com.habitrain.core.api.role.v2.client.InstinctPhase;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionRegistrar;
import com.habitrain.core.api.role.v2.client.RoleHudKind;
import com.habitrain.core.api.role.v2.client.RoleHudSpec;
import com.habitrain.core.api.role.v2.client.RoleInstinctRule;
import com.habitrain.core.api.role.v2.client.RoleRenderPhase;
import com.habitrain.core.api.role.v2.client.RoleScreenKind;
import com.habitrain.core.api.role.v2.client.RoleScreenSpec;
import com.habitrain.core.api.role.v2.client.RoleSkinKind;
import com.habitrain.core.api.role.v2.client.RoleSkinSpec;
import com.habitrain.core.api.role.v2.client.RoleNameRenderRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests HUD / instinct declarations and the pure instinct resolver.
 * Does not load Fabric client classes.
 */
class RoleClientExtensionTest {

    private static final RoleKey VIEWER = RoleKey.of("habitrain_core", "sin_lust");
    private static final RoleKey TARGET = RoleKey.of("habitrain_core", "flower_girl");

    private RoleClientExtensionRegistry store;

    @BeforeEach
    void setUp() {
        store = new RoleClientExtensionRegistry();
        ((RoleClientExtensionRegistry) RoleClientExtensionApi.instance()).clear();
    }

    @AfterEach
    void tearDown() {
        ((RoleClientExtensionRegistry) RoleClientExtensionApi.instance()).clear();
    }

    @Test
    void hudBuilderRequiresRole() {
        assertThrows(IllegalStateException.class,
                () -> RoleHudSpec.of("habitrain_core", "badge").kind(RoleHudKind.BADGE).build());
    }

    @Test
    void instinctBuilderRequiresColorOrHide() {
        assertThrows(IllegalStateException.class,
                () -> RoleInstinctRule.of("habitrain_core", "mark").viewerRole(VIEWER).build());
    }

    @Test
    void registryStoresHudAndInstinct() {
        register(r -> {
            r.hud(RoleHudSpec.of("habitrain_core", "lust_badge")
                    .role(VIEWER).kind(RoleHudKind.BADGE)
                    .textKey("hud.habitrain_core.lust").build());
            r.instinct(RoleInstinctRule.of("habitrain_core", "lust_pink")
                    .viewerRole(VIEWER).targetRole(TARGET).color(0xFF66AA).build());
        });
        assertEquals(1, store.hudsFor(VIEWER).size());
        assertEquals(1, store.instinctsFor(VIEWER).size());
        assertTrue(store.hudsFor(TARGET).isEmpty());
    }

    @Test
    void freezeRejectsScopedCommit() {
        ScopedRoleClientExtensionRegistrar registrar =
                new ScopedRoleClientExtensionRegistrar("habitrain_core", store);
        registrar.hud(RoleHudSpec.of("habitrain_core", "a").role(VIEWER).build());
        store.freeze();
        assertThrows(IllegalStateException.class, registrar::commit);
    }

    @Test
    void globalRegistryRejectsDirectRegistrationBypass() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> store.hud(RoleHudSpec.of("habitrain_core", "b").role(VIEWER).build()));
        assertTrue(error.getMessage().contains("role_client_extensions"));
    }

    @Test
    void activeProviderFilterHidesDisabledProviderExtensions() {
        register(r -> r.hud(RoleHudSpec.of("habitrain_core", "a").role(VIEWER).build()));
        store.setActiveProviders(java.util.Set.of("othermod"), java.util.Set.of());
        assertTrue(store.hudsFor(VIEWER).isEmpty(), "disabled provider HUD must not be consumed");
        store.setActiveProviders(java.util.Set.of("habitrain_core"),
                java.util.Set.of("habitrain_core:" + VIEWER.location()));
        assertEquals(1, store.hudsFor(VIEWER).size(), "active provider HUD must be consumed");
    }

    @Test
    void activeEntryFilterDisablesOnlyOneRoleEntry() {
        RoleKey other = RoleKey.of("habitrain_core", "other_role");
        register(r -> {
            r.hud(RoleHudSpec.of("habitrain_core", "a").entryKey("a").role(VIEWER).build());
            r.hud(RoleHudSpec.of("habitrain_core", "b").entryKey("b").role(other).build());
        });
        store.setActiveProviders(java.util.Set.of("habitrain_core"),
                java.util.Set.of("habitrain_core$b@habitrain_core:other_role"));
        assertTrue(store.hudsFor(VIEWER).isEmpty(), "disabled entry HUD must not be consumed");
        assertEquals(1, store.hudsFor(other).size(), "active entry HUD must be consumed");
    }

    @Test
    void sameProviderSameTargetDifferentEntryKeysResolveIndependently() {
        RoleKey civilian = RoleKey.of("sre", "civilian");
        register(r -> {
            r.hud(RoleHudSpec.of("habitrain_core", "armed")
                    .entryKey("armed_civilian").role(civilian).build());
            r.hud(RoleHudSpec.of("habitrain_core", "pacifist")
                    .entryKey("pacifist_civilian").role(civilian).build());
        });

        store.setActiveProviders(java.util.Set.of("habitrain_core"),
                java.util.Set.of("habitrain_core$armed_civilian@sre:civilian"));
        assertEquals(1, store.hudsFor(civilian).size(),
                "only the HUD bound to the active server entry must be consumed");
        assertEquals("habitrain_core:armed", store.hudsFor(civilian).getFirst().id().toString());

        store.setActiveProviders(java.util.Set.of("habitrain_core"),
                java.util.Set.of("habitrain_core$pacifist_civilian@sre:civilian"));
        assertEquals(1, store.hudsFor(civilian).size());
        assertEquals("habitrain_core:pacifist", store.hudsFor(civilian).getFirst().id().toString());
    }

    @Test
    void newIdWithAliasReplacementUsesRealServerEntryId() {
        RoleKey replacement = RoleKey.of("habitrain_core", "shadow_killer");
        register(r -> r.hud(RoleHudSpec.of("habitrain_core", "shadow_hud")
                .entryKey("shadow_killer").role(replacement).build()));

        store.setActiveProviders(java.util.Set.of("habitrain_core"),
                java.util.Set.of("habitrain_core$shadow_killer@sre:killer"));
        assertEquals(1, store.hudsFor(replacement).size(),
                "REPLACE NEW_ID_WITH_ALIAS must activate extensions bound to the replacement role");

        store.setActiveProviders(java.util.Set.of("habitrain_core"), java.util.Set.of());
        assertTrue(store.hudsFor(replacement).isEmpty(),
                "disabled replacement entry must hide its client extensions");
    }

    @Test
    void resolverFirstMatchingColorWins() {
        register(r -> {
            r.instinct(RoleInstinctRule.of("habitrain_core", "pink")
                    .viewerRole(VIEWER).targetRole(TARGET).color(0xFF66AA).build());
            r.instinct(RoleInstinctRule.of("habitrain_core", "red")
                    .viewerRole(VIEWER).color(0xFF0000).build());
        });
        InstinctDecision d = InstinctRuleResolver.resolve(
                store.instincts().stream().toList(),
                InstinctPhase.ALIVE_AFTER, VIEWER, TARGET);
        assertEquals(InstinctDecision.Kind.CUSTOM, d.kind());
        assertEquals(0xFF66AA, d.color());
    }

    @Test
    void resolverHideBeatsLaterColor() {
        register(r -> {
            r.instinct(RoleInstinctRule.of("habitrain_core", "hide")
                    .viewerRole(VIEWER).hide().build());
            r.instinct(RoleInstinctRule.of("habitrain_core", "color")
                    .viewerRole(VIEWER).color(0xFF0000).build());
        });
        InstinctDecision d = InstinctRuleResolver.resolve(
                store.instincts().stream().toList(),
                InstinctPhase.ALIVE_AFTER, VIEWER, TARGET);
        assertEquals(InstinctDecision.Kind.HIDE, d.kind());
    }

    @Test
    void resolverWrongPhasePasses() {
        register(r -> r.instinct(RoleInstinctRule.of("habitrain_core", "pink")
                .viewerRole(VIEWER)
                .phase(InstinctPhase.SPECTATOR)
                .color(0xFF66AA)
                .build()));
        InstinctDecision d = InstinctRuleResolver.resolve(
                store.instincts().stream().toList(),
                InstinctPhase.ALIVE_AFTER, VIEWER, TARGET);
        assertTrue(d.isPass());
    }

    @Test
    void resolverMissingViewerPasses() {
        assertTrue(InstinctRuleResolver.resolve(store.instincts().stream().toList(),
                InstinctPhase.ALIVE_AFTER, null, TARGET).isPass());
    }

    @Test
    void skinBuilderRequiresTexture() {
        assertThrows(IllegalStateException.class,
                () -> RoleSkinSpec.of("habitrain_core", "skin").role(VIEWER).build());
    }

    @Test
    void registryStoresSkinAndScreenAndWidget() {
        java.util.concurrent.atomic.AtomicInteger draws = new java.util.concurrent.atomic.AtomicInteger();
        register(r -> {
            r.skin(RoleSkinSpec.of("habitrain_core", "lust_normal")
                    .role(VIEWER).kind(RoleSkinKind.NORMAL)
                    .texture(net.minecraft.resources.ResourceLocation.parse(
                            "habitrain_core:textures/entity/lust.png")).build());
            r.screen(RoleScreenSpec.of("habitrain_core", "lust_pick")
                    .role(VIEWER).kind(RoleScreenKind.PLAYER_PICK)
                    .titleKey("screen.habitrain_core.lust").build());
            r.nameRender(RoleNameRenderRule.of("habitrain_core", "lust_hide")
                    .role(VIEWER).phase(RoleRenderPhase.NAMEPLATE).hide().build());
            r.hudWidget(net.minecraft.resources.ResourceLocation.parse("habitrain_core:lust_widget"),
                    "lust", VIEWER, (w, h, t) -> draws.incrementAndGet());
        });
        assertEquals(1, store.skinsFor(VIEWER).size());
        assertEquals(RoleSkinKind.NORMAL, store.skinFor(VIEWER, RoleSkinKind.NORMAL).kind());
        assertEquals(1, store.screensFor(VIEWER).size());
        assertEquals(1, store.nameRendersFor(VIEWER).size());
        assertEquals(1, store.hudWidgetsFor(VIEWER).size());
        store.hudWidgetsFor(VIEWER).iterator().next().render(100, 50, 0f);
        assertEquals(1, draws.get());
    }

    private void register(java.util.function.Consumer<RoleClientExtensionRegistrar> action) {
        ScopedRoleClientExtensionRegistrar registrar =
                new ScopedRoleClientExtensionRegistrar("habitrain_core", store);
        action.accept(registrar);
        registrar.commit();
    }
}
