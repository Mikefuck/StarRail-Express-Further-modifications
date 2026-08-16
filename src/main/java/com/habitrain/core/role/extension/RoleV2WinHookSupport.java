package com.habitrain.core.role.extension;

import com.habitrain.core.api.WinResult;
import com.habitrain.core.api.role.BlackoutWinCheckContext;
import com.habitrain.core.api.role.v2.CompiledModifyOverlay;
import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SRERoleWorldComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Bridge for v2 {@code RolePatch#winConditionHook()} into the existing SRE /
 * blackout win paths. The v2 MODIFY overlay is applied onto the original role
 * object; this support scans currently overlaid roles and evaluates their
 * declarative win hook, matching the v1 bridge semantics.
 */
public final class RoleV2WinHookSupport {

    private RoleV2WinHookSupport() {}

    /**
     * Returns the first non-null {@link WinResult} produced by an active v2
     * MODIFY win hook, or {@code null} when no v2 hook hijacks the game.
     */
    public static @Nullable WinResult check(ServerLevel level) {
        V2WinCheck found = checkWithRole(level);
        return found == null ? null : found.result();
    }

    /** Like {@link #check}, but also returns the role that produced the result. */
    public static @Nullable V2WinCheck checkWithRole(ServerLevel level) {
        if (level == null) {
            return null;
        }
        try {
            for (SRERole role : TMMRoles.ROLES.values()) {
                if (role == null || role.identifier() == null) {
                    continue;
                }
                CompiledModifyOverlay overlay = RoleOverlayAccessor.currentOverlay(role);
                if (overlay == null || overlay.winConditionHook() == null) {
                    continue;
                }
                if (!hasAssignedRole(level, role.identifier())) {
                    continue;
                }
                BlackoutWinCheckContext ctx = new BlackoutWinCheckContext(
                        level, role, true, false);
                WinResult result = overlay.winConditionHook().check(ctx);
                if (result != null) {
                    return new V2WinCheck(role, result);
                }
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[RoleV2WinHookSupport] v2 win hook evaluation failed", t);
        }
        return null;
    }

    /** A v2 win-hook hit: the evaluated role and its result. */
    public record V2WinCheck(SRERole role, WinResult result) {}

    private static boolean hasAssignedRole(ServerLevel level, ResourceLocation targetId) {
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
                    "[RoleV2WinHookSupport] assigned-role check failed for {}", targetId, throwable);
        }
        return false;
    }
}
