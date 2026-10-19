package com.habitrain.core.client.gui;

import net.minecraft.client.gui.screens.Screen;

/** Coordinates shared with the upstream role hints, player heads and their page buttons. */
public interface InventoryShopLayoutAccess {
    int habitrain$inventoryCenterY();

    void habitrain$reserveRoleSelection();

    static int centerY(Screen screen) {
        return screen instanceof InventoryShopLayoutAccess access
                ? access.habitrain$inventoryCenterY() : (screen.height - TicketShopLayout.HOTBAR_HEIGHT) / 2;
    }

    static int offset(Screen screen) {
        return centerY(screen) - (screen.height - TicketShopLayout.HOTBAR_HEIGHT) / 2;
    }

    static void reserveRoleSelection(Screen screen) {
        if (screen instanceof InventoryShopLayoutAccess access) access.habitrain$reserveRoleSelection();
    }
}
