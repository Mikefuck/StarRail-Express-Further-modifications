package com.habitrain.core.game.sre.role.sins;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Shared, explicit alignment rules used by sin and virtue mechanics. */
public final class SinRoleRules {
    private SinRoleRules() {}

    public static boolean isGoodAligned(ServerLevel level, ServerPlayer player) {
        if (level == null || player == null) return false;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            SRERole role = game != null ? game.getRole(player) : null;
            return role != null && role.isInnocent()
                    && !role.isNeutrals()
                    && !role.isNeutralForKiller()
                    && !role.isNeutralForInnocent()
                    && !role.isKillerTeam();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
