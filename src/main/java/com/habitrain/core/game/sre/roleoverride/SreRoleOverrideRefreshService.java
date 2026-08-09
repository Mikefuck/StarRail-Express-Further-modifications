package com.habitrain.core.game.sre.roleoverride;

import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/** Refreshes long-lived SRE role-card state after an override snapshot rebuild. */
public final class SreRoleOverrideRefreshService {
    private SreRoleOverrideRefreshService() {}

    public static void refreshServer(MinecraftServer server) {
        if (server == null) return;
        server.getAllLevels().forEach(SreRoleOverrideRefreshService::refreshLevel);
    }

    public static void refreshLevel(Level level) {
        if (level == null) return;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.getInstance(level);
            if (game == null) {
                return;
            }
            Object mode = game.getGameMode();
            if (mode instanceof RoleRotationOverrideAccess access) {
                access.habitrain$refreshRoleOverrides(level);
            }
        } catch (Throwable ignored) {
            // The game mode is optional outside SRE-enabled worlds.
        }
    }
}
