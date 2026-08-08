package com.habitrain.core.client.role;

import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.role.override.RoleOverrideEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Client-side dispatcher that refreshes UI when role override configuration changes.
 * Currently handles:
 * - Rebuilding the RoleOverrideTabScreen row list when config changes
 * - Refreshing any open role-related screens (future: RoleIntroduceScreen, etc.)
 */
public final class RoleOverrideRefreshDispatcher {
    private RoleOverrideRefreshDispatcher() {}

    /**
     * Called after config sync or OP save to refresh client-side state.
     * Rebuilds the engine snapshot and refreshes any open UI.
     */
    public static void refresh() {
        // Rebuild engine snapshot from current config
        RoleOverrideEngine.getInstance().rebuild(ConfigManager.getInstance().getRoleOverrides());

        // Refresh open screens if needed
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        mc.execute(() -> {
            Screen screen = mc.screen;
            if (screen instanceof ConfigMenuScreen configScreen) {
                // The role-override list will be rebuilt on the next render cycle
                // by refreshRoleOverrideTab(), which rebuilds ModeRolesPage rows.
                try {
                    configScreen.refreshRoleOverrideTab();
                } catch (Throwable t) {
                    // Rebuild is best-effort; ignore transient UI state.
                }
            }
        });
    }
}
