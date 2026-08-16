package com.habitrain.core.api.role.v2.client;

import com.habitrain.core.api.role.v2.RoleKey;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.List;

/**
 * Client-extension registrar offered to {@link RoleClientExtensionEntrypoint}
 * providers. Types here are dedicated-server-safe (no {@code MinecraftClient}).
 */
public interface RoleClientExtensionRegistrar {

    void hud(RoleHudSpec spec);

    void instinct(RoleInstinctRule rule);

    void skin(RoleSkinSpec spec);

    void nameRender(RoleNameRenderRule rule);

    void hudWidget(ResourceLocation id, String entryKey, RoleKey role, RoleHudWidget widget);

    default void hudWidget(RoleKey role, RoleHudWidget widget) {
        hudWidget(null, null, role, widget);
    }

    void screen(RoleScreenSpec spec);

    Collection<RoleHudSpec> huds();

    List<RoleHudSpec> hudsFor(RoleKey role);

    Collection<RoleInstinctRule> instincts();

    List<RoleInstinctRule> instinctsFor(RoleKey viewerRole);

    Collection<RoleSkinSpec> skins();

    List<RoleSkinSpec> skinsFor(RoleKey role);

    Collection<RoleNameRenderRule> nameRenders();

    List<RoleNameRenderRule> nameRendersFor(RoleKey role);

    Collection<RoleHudWidget> hudWidgetsFor(RoleKey role);

    Collection<RoleScreenSpec> screens();

    List<RoleScreenSpec> screensFor(RoleKey role);
}
