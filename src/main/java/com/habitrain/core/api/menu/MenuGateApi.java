package com.habitrain.core.api.menu;

import com.habitrain.core.config.MenuGateService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Public server-side menu-gate query for addons (lottery config C2S, commands).
 *
 * <p>Dedicated servers with the gate enabled block unauthorized players.
 * Single-player / LAN never blocks. Addons must call this at compile time;
 * do not reflect {@code MenuGateService}.
 */
public final class MenuGateApi {
    private MenuGateApi() {}

    /**
     * {@code true} when the player must be denied menu/config writes.
     * Null player or non-dedicated server → not blocked.
     */
    public static boolean isBlocked(@Nullable ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return isBlocked(player, player.getServer());
    }

    /**
     * {@code true} when the player must be denied menu/config writes on this server.
     */
    public static boolean isBlocked(@Nullable ServerPlayer player, @Nullable MinecraftServer server) {
        if (server == null || !server.isDedicatedServer()) {
            return false;
        }
        if (player == null) {
            return false;
        }
        if (!MenuGateService.isEnabled()) {
            return false;
        }
        return !MenuGateService.isAllowed(player);
    }

    public static boolean isEnabled() {
        return MenuGateService.isEnabled();
    }

    public static boolean isAllowed(@Nullable ServerPlayer player) {
        return MenuGateService.isAllowed(player);
    }
}
