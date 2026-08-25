package com.habitrain.core.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Public server-side Mod-menu gate for DLC (lottery config C2S, commands).
 *
 * <p>Delegates to {@link com.habitrain.core.api.menu.MenuGateApi} (the compile
 * surface lottery already uses). Client vote-config UI is not opened from this
 * class; DLC should call {@code ConfigMenuScreen.openVote(Screen)} on the client.
 */
public final class MenuGateApi {
    private MenuGateApi() {}

    /**
     * {@code true} when the player must be denied gated menu/config writes.
     * Null player is never blocked.
     */
    public static boolean isBlocked(@Nullable ServerPlayer player) {
        return com.habitrain.core.api.menu.MenuGateApi.isBlocked(player);
    }

    /**
     * Same predicate with an explicit server (lottery C2S already has one).
     */
    public static boolean isBlocked(@Nullable ServerPlayer player, @Nullable MinecraftServer server) {
        return com.habitrain.core.api.menu.MenuGateApi.isBlocked(player, server);
    }

    public static boolean isEnabled() {
        return com.habitrain.core.api.menu.MenuGateApi.isEnabled();
    }

    public static boolean isAllowed(@Nullable ServerPlayer player) {
        return com.habitrain.core.api.menu.MenuGateApi.isAllowed(player);
    }
}
