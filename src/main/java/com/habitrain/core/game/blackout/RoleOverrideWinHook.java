package com.habitrain.core.game.blackout;

import com.habitrain.core.api.WinResult;
import com.habitrain.core.api.role.BlackoutWinCheckContext;
import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.server.level.ServerLevel;

/**
 * Bridge that checks all active MODIFY win-condition hooks during blackout victory checks.
 * Each active MODIFY definition with a WinConditionHook is evaluated;
 * the first non-null result hijacks the game end.
 */
public final class RoleOverrideWinHook {
    private RoleOverrideWinHook() {}

    /**
     * Check all active MODIFY win-condition hooks.
     * @param level the current game level
     * @return a WinResult if any hook hijacks the game, null otherwise
     */
    public static WinResult check(ServerLevel level) {
        if (level == null) return null;
        for (var entry : RoleOverrideEngine.getInstance().getSnapshot().getActiveModifies().entrySet()) {
            ModifyRoleDefinition def = entry.getValue();
            if (def.winConditionHook().isEmpty()) continue;
            SRERole role = TMMRoles.getRole(entry.getKey());
            if (role == null) continue;
            BlackoutWinCheckContext ctx = new BlackoutWinCheckContext(
                level, role, true, false
            );
            try {
                WinResult result = def.winConditionHook().get().check(ctx);
                if (result != null) return result;
            } catch (Throwable t) {
                com.habitrain.core.HabiTrainCore.LOGGER.warn(
                    "[RoleOverrideWinHook] hook for {} threw", entry.getKey(), t);
            }
        }
        return null;
    }
}
