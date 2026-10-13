package com.habitrain.core.client.role;

import com.habitrain.core.game.sre.role.SreRoleRefreshService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Refreshes open role-book and selection screens after a v2 snapshot update. */
public final class RoleRefreshDispatcher {
    private RoleRefreshDispatcher() {}

    /** Called after a role snapshot or its display configuration is synchronized. */
    public static void refresh() {
        // Refresh open screens if needed
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        mc.execute(() -> {
            if (mc.level != null) {
                SreRoleRefreshService.refreshLevel(mc.level);
            }
            Screen screen = mc.screen;
            if (screen instanceof RoleIntroduceScreenRefreshAccess roleBook) {
                roleBook.habitrain$refreshRoleExtensions();
            }
        });
    }
}
