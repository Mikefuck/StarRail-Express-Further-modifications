package com.habitrain.core.game.sre.roleoverride;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SRERoleWorldComponent;
import net.minecraft.server.level.ServerLevel;

/**
 * Helpers for MODIFY win-hook overlay in standard SRE murder mode.
 * {@code AllowGameEnd} is owned solely by {@code RoleEventDispatcher#foldWin};
 * this class is no longer a competing Fabric listener. Custom-winner writes
 * ({@code RoleUtils.customWinnerWin} / {@code CustomWinnerID}) live next to
 * {@code applyWinPatch}.
 */
public final class SreRoleOverrideWinBridge {
    private static boolean registered;

    private SreRoleOverrideWinBridge() {}

    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
    }

    /** Win hooks are scoped to rounds where the target role is actually assigned. */
    public static boolean hasAssignedRole(
            ServerLevel level, net.minecraft.resources.ResourceLocation targetId) {
        if (level == null || targetId == null) {
            return false;
        }
        try {
            SRERoleWorldComponent roles = SRERoleWorldComponent.KEY.get(level);
            if (roles == null) {
                return false;
            }
            for (SRERole assigned : roles.getRoles().values()) {
                if (assigned != null && targetId.equals(assigned.identifier())) {
                    return true;
                }
            }
        } catch (Throwable throwable) {
            HabiTrainCore.LOGGER.debug(
                    "[RoleOverride] assigned-role check failed for {}", targetId, throwable);
        }
        return false;
    }
}
