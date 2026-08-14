package com.habitrain.core.api.role.v2.client;

/**
 * Custom HUD draw escape hatch (design §16.3).
 *
 * <p>No {@code MinecraftClient} / {@code GuiGraphics} in this signature so
 * a dedicated server can load the type. The physical client adapter
 * supplies screen size and tick; providers that need more reach for
 * client-only helpers from their own client source set.
 */
@FunctionalInterface
public interface RoleHudWidget {

    void render(int screenWidth, int screenHeight, float tickDelta);
}
