package com.habitrain.core.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Coordinates shared with the upstream role hints, player heads and their page buttons.
 *
 * <p>Whether the shop must keep the role-selector row clear is derived from the widgets that are
 * actually on the screen ({@link #hasRoleSelection(Screen)}), not from a reservation made while
 * {@code init} is still running. Role screens add their heads from their own {@code init} mixins,
 * so the old "reserve during init" flag could be cleared again when the injections ran in a
 * different order; the shop then believed no player row existed, moved the hotbar down and the
 * heads stayed behind right on top of it. Tracking the real widgets makes the decision independent
 * of mixin ordering.</p>
 */
public interface InventoryShopLayoutAccess {
    /**
     * Screen → player selector widgets that must stay reachable below the hotbar. The screen keys are
     * weak and the values only hold weak references, so closing an inventory never keeps it alive.
     */
    Map<Screen, Set<WeakReference<Button>>> ROLE_WIDGETS = Collections.synchronizedMap(new WeakHashMap<>());

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

    /** Remember the player heads/page buttons a pagination helper just added to {@code screen}. */
    static void trackRoleWidgets(Screen screen, List<? extends Button> widgets) {
        if (widgets.isEmpty()) return;
        synchronized (ROLE_WIDGETS) {
            Set<WeakReference<Button>> tracked = ROLE_WIDGETS.computeIfAbsent(screen, key -> new HashSet<>());
            for (Button widget : widgets) {
                boolean known = false;
                for (WeakReference<Button> reference : tracked) {
                    if (reference.get() == widget) { known = true; break; }
                }
                if (!known) tracked.add(new WeakReference<>(widget));
            }
        }
    }

    /** Forget widgets the pagination helper removed, so a new page re-registers only its own row. */
    static void untrackRoleWidgets(Screen screen, List<? extends Button> widgets) {
        if (widgets.isEmpty()) return;
        synchronized (ROLE_WIDGETS) {
            Set<WeakReference<Button>> tracked = ROLE_WIDGETS.get(screen);
            if (tracked == null) return;
            tracked.removeIf(reference -> {
                Button widget = reference.get();
                return widget == null || widgets.contains(widget);
            });
            if (tracked.isEmpty()) ROLE_WIDGETS.remove(screen);
        }
    }

    /** True while the screen still owns at least one player head or page button of a role selector. */
    static boolean hasRoleSelection(Screen screen) {
        synchronized (ROLE_WIDGETS) {
            Set<WeakReference<Button>> tracked = ROLE_WIDGETS.get(screen);
            if (tracked == null || tracked.isEmpty()) return false;
            var children = screen.children();
            tracked.removeIf(reference -> {
                Button widget = reference.get();
                return widget == null || !children.contains(widget);
            });
            if (tracked.isEmpty()) {
                ROLE_WIDGETS.remove(screen);
                return false;
            }
            return true;
        }
    }
}
