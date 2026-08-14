package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.client.InstinctDecision;
import com.habitrain.core.api.role.v2.client.InstinctPhase;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.api.role.v2.client.RoleHudKind;
import com.habitrain.core.api.role.v2.client.RoleHudSpec;
import com.habitrain.core.api.role.v2.client.RoleInstinctRule;
import com.habitrain.core.api.role.v2.client.RoleRenderPhase;
import com.habitrain.core.api.role.v2.client.RoleScreenKind;
import com.habitrain.core.api.role.v2.client.RoleScreenSpec;
import com.habitrain.core.api.role.v2.client.RoleSkinKind;
import com.habitrain.core.api.role.v2.client.RoleSkinSpec;
import com.habitrain.core.api.role.v2.client.RoleNameRenderRule;
import com.habitrain.core.role.client.InstinctRuleResolver;
import com.habitrain.core.role.client.RoleClientExtensionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        store.hud(RoleHudSpec.of("habitrain_core", "lust_badge")
                .role(VIEWER)
                .kind(RoleHudKind.BADGE)
                .textKey("hud.habitrain_core.lust")
                .build());
        store.instinct(RoleInstinctRule.of("habitrain_core", "lust_pink")
                .viewerRole(VIEWER)
                .targetRole(TARGET)
                .color(0xFF66AA)
                .build());
        assertEquals(1, store.hudsFor(VIEWER).size());
        assertEquals(1, store.instinctsFor(VIEWER).size());
        assertTrue(store.hudsFor(TARGET).isEmpty());
    }

    @Test
    void freezeRejectsFurtherHud() {
        store.hud(RoleHudSpec.of("habitrain_core", "a").role(VIEWER).build());
        store.freeze();
        assertThrows(IllegalStateException.class,
                () -> store.hud(RoleHudSpec.of("habitrain_core", "b").role(VIEWER).build()));
    }

    @Test
    void resolverFirstMatchingColorWins() {
        store.instinct(RoleInstinctRule.of("habitrain_core", "pink")
                .viewerRole(VIEWER).targetRole(TARGET).color(0xFF66AA).build());
        store.instinct(RoleInstinctRule.of("habitrain_core", "red")
                .viewerRole(VIEWER).color(0xFF0000).build());
        InstinctDecision d = InstinctRuleResolver.resolve(
                store.instincts().stream().toList(),
                InstinctPhase.ALIVE_AFTER, VIEWER, TARGET);
        assertEquals(InstinctDecision.Kind.CUSTOM, d.kind());
        assertEquals(0xFF66AA, d.color());
    }

    @Test
    void resolverHideBeatsLaterColor() {
        store.instinct(RoleInstinctRule.of("habitrain_core", "hide")
                .viewerRole(VIEWER).hide().build());
        store.instinct(RoleInstinctRule.of("habitrain_core", "color")
                .viewerRole(VIEWER).color(0xFF0000).build());
        InstinctDecision d = InstinctRuleResolver.resolve(
                store.instincts().stream().toList(),
                InstinctPhase.ALIVE_AFTER, VIEWER, TARGET);
        assertEquals(InstinctDecision.Kind.HIDE, d.kind());
    }

    @Test
    void resolverWrongPhasePasses() {
        store.instinct(RoleInstinctRule.of("habitrain_core", "pink")
                .viewerRole(VIEWER)
                .phase(InstinctPhase.SPECTATOR)
                .color(0xFF66AA)
                .build());
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
        store.skin(RoleSkinSpec.of("habitrain_core", "lust_normal")
                .role(VIEWER)
                .kind(RoleSkinKind.NORMAL)
                .texture(net.minecraft.resources.ResourceLocation.parse("habitrain_core:textures/entity/lust.png"))
                .build());
        store.screen(RoleScreenSpec.of("habitrain_core", "lust_pick")
                .role(VIEWER)
                .kind(RoleScreenKind.PLAYER_PICK)
                .titleKey("screen.habitrain_core.lust")
                .build());
        store.nameRender(RoleNameRenderRule.of("habitrain_core", "lust_hide")
                .role(VIEWER)
                .phase(RoleRenderPhase.NAMEPLATE)
                .hide()
                .build());
        java.util.concurrent.atomic.AtomicInteger draws = new java.util.concurrent.atomic.AtomicInteger();
        store.hudWidget(VIEWER, (w, h, t) -> draws.incrementAndGet());
        assertEquals(1, store.skinsFor(VIEWER).size());
        assertEquals(RoleSkinKind.NORMAL, store.skinFor(VIEWER, RoleSkinKind.NORMAL).kind());
        assertEquals(1, store.screensFor(VIEWER).size());
        assertEquals(1, store.nameRendersFor(VIEWER).size());
        assertEquals(1, store.hudWidgetsFor(VIEWER).size());
        store.hudWidgetsFor(VIEWER).iterator().next().render(100, 50, 0f);
        assertEquals(1, draws.get());
    }
}
