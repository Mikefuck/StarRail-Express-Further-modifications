package com.habitrain.core.client.role;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideRefreshService;
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
            if (mc.level != null) {
                SreRoleOverrideRefreshService.refreshLevel(mc.level);
            }
            Screen screen = mc.screen;
            if (screen instanceof com.habitrain.core.client.gui.menu.ConfigMenuScreen configScreen) {
                configScreen.refreshRoleOverrideTab();
            }
            if (screen instanceof RoleIntroduceScreenRefreshAccess roleBook) {
                roleBook.habitrain$refreshRoleOverrides();
            }
        });
    }
}
