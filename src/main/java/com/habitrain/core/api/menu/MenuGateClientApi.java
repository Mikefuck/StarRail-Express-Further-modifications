package com.habitrain.core.api.menu;

import com.habitrain.core.client.menu.MenuAccessGuard;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Public client-side menu-gate query for addon config screens.
 *
 * <p>Client-only. Dedicated servers must not call this. Addons should compile
 * against this type instead of reflecting {@code MenuAccessGuard}.
 */
@Environment(EnvType.CLIENT)
public final class MenuGateClientApi {
    private MenuGateClientApi() {}

    /**
     * {@code true} when the local player may open and edit gated Mod Menu pages.
     * Non-dedicated / gate-off / authorized → true.
     */
    public static boolean isScreenAllowed() {
        return MenuAccessGuard.isScreenAllowed();
    }
}
