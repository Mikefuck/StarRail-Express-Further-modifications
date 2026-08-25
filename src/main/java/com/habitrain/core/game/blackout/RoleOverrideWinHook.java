package com.habitrain.core.game.blackout;

import com.habitrain.core.api.WinResult;
import com.habitrain.core.api.role.BlackoutWinCheckContext;
import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.v2.behavior.WinPatch;
import com.habitrain.core.api.role.v2.behavior.WinPatchOp;
import com.habitrain.core.role.extension.RoleV2WinHookSupport;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Side-effect-free v1 / RolePatch win-hook overlay for {@link
 * com.habitrain.core.role.behavior.RoleEventDispatcher#foldWin}. First
 * non-null {@link WinResult} becomes a {@link WinPatch}; it is not a second
 * victory authority.
 */
public final class RoleOverrideWinHook {
    private RoleOverrideWinHook() {}

    /**
     * Evaluates active MODIFY win-condition hooks then {@link RoleV2WinHookSupport}.
     * @return {@link WinPatch#noChange()} when nothing hijacks
     */
    public static WinPatch evaluateAsPatch(@Nullable ServerLevel level) {
        OverlayHit hit = firstHit(level);
        if (hit == null || hit.result() == null) {
            return WinPatch.noChange();
        }
        return toPatch(hit.role(), hit.result());
    }

    /**
     * Check all active MODIFY win-condition hooks.
     * @param level the current game level
     * @return a WinResult if any hook hijacks the game, null otherwise
     */
    public static WinResult check(ServerLevel level) {
        WinPatch patch = evaluateAsPatch(level);
        if (patch == null || patch.op() == WinPatchOp.NO_CHANGE) {
            return null;
        }
        String reason = patch.reason() != null ? patch.reason()
                : (patch.customId() != null ? patch.customId() : "v1 win");
        return new WinResult(patch.winners(), reason);
    }

    private static @Nullable OverlayHit firstHit(@Nullable ServerLevel level) {
        if (level == null) {
            return null;
        }
        try {
            for (var entry : RoleOverrideEngine.getInstance().getSnapshot().getActiveModifies().entrySet()) {
                ModifyRoleDefinition def = entry.getValue();
                if (def.winConditionHook().isEmpty()) {
                    continue;
                }
                if (!com.habitrain.core.game.sre.roleoverride.SreRoleOverrideWinBridge
                        .hasAssignedRole(level, entry.getKey())) {
                    continue;
                }
                SRERole role = TMMRoles.getRole(entry.getKey());
                if (role == null) {
                    continue;
                }
                BlackoutWinCheckContext ctx = new BlackoutWinCheckContext(
                        level, role, true, false);
                try {
                    WinResult result = def.winConditionHook().get().check(ctx);
                    if (result != null) {
                        return new OverlayHit(role, result);
                    }
                } catch (Throwable t) {
                    com.habitrain.core.HabiTrainCore.LOGGER.warn(
                            "[RoleOverrideWinHook] hook for {} threw", entry.getKey(), t);
                }
            }
        } catch (Throwable t) {
            com.habitrain.core.HabiTrainCore.LOGGER.warn(
                    "[RoleOverrideWinHook] v1 overlay evaluation failed", t);
        }
        RoleV2WinHookSupport.V2WinCheck v2 = RoleV2WinHookSupport.checkWithRole(level);
        if (v2 != null) {
            return new OverlayHit(v2.role(), v2.result());
        }
        return null;
    }

    private static WinPatch toPatch(@Nullable SRERole role, WinResult result) {
        if (result == null) {
            return WinPatch.noChange();
        }
        String customId = (role != null && role.identifier() != null)
                ? role.identifier().toString()
                : (result.getReason() != null ? result.getReason() : "v1 win");
        return WinPatch.declareCustom(customId, result.getWinners(), result.getReason());
    }

    private record OverlayHit(@Nullable SRERole role, WinResult result) {}
}
