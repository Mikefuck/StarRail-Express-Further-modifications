package com.habitrain.core.game.sre.roleoverride;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.WinResult;
import com.habitrain.core.api.role.BlackoutWinCheckContext;
import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SRERoleWorldComponent;
import io.wifi.starrailexpress.event.AllowGameEnd;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.agmas.noellesroles.utils.RoleUtils;

import java.util.ArrayList;
import java.util.Map;

/**
 * Safe public-event bridge for MODIFY win hooks in standard SRE murder mode.
 * Blackout continues to use its own victory checker.
 */
public final class SreRoleOverrideWinBridge {
    private static boolean registered;

    private SreRoleOverrideWinBridge() {}

    public static void init() {
        if (registered) return;
        registered = true;
        AllowGameEnd.EVENT.register(SreRoleOverrideWinBridge::onAllowGameEnd);
    }

    private static GameUtils.WinStatus onAllowGameEnd(
            ServerLevel level, GameUtils.WinStatus proposed, boolean loose) {
        if (level == null || proposed == null) {
            return GameUtils.WinStatus.NOT_MODIFY;
        }

        for (Map.Entry<net.minecraft.resources.ResourceLocation, ModifyRoleDefinition> entry :
                RoleOverrideEngine.getInstance().getSnapshot().getActiveModifies().entrySet()) {
            ModifyRoleDefinition definition = entry.getValue();
            if (definition.winConditionHook().isEmpty()) continue;
            if (!hasAssignedRole(level, entry.getKey())) continue;

            SRERole role = TMMRoles.getRole(entry.getKey());
            if (role == null) continue;
            try {
                WinResult result = definition.winConditionHook().get().check(
                        new BlackoutWinCheckContext(level, role, true, false));
                if (result == null) continue;

                applyCustomWinner(level, role, result);
                return GameUtils.WinStatus.CUSTOM;
            } catch (Throwable throwable) {
                HabiTrainCore.LOGGER.warn(
                        "[RoleOverride] standard SRE win hook failed for {}",
                        entry.getKey(), throwable);
            }
        }
        return GameUtils.WinStatus.NOT_MODIFY;
    }

    private static void applyCustomWinner(ServerLevel level, SRERole role, WinResult result) {
        SREGameRoundEndComponent roundEnd = SREGameRoundEndComponent.KEY.get(level);
        if (roundEnd != null) {
            roundEnd.CustomWinnerPlayers = new ArrayList<>(result.getWinners());
            if (result.getReason() != null && !result.getReason().isBlank()) {
                roundEnd.CustomWinnerSubtitle = Component.literal(result.getReason());
            }
        }
        RoleUtils.customWinnerWin(level, role.identifier().getPath(), role.getColor());
        if (roundEnd != null) {
            // RoleUtils accepts only a free-form String and SRE normally stores a
            // path. Keep the full API identifier after the upstream helper runs.
            roundEnd.CustomWinnerID = role.identifier().toString();
        }
    }

    /** Win hooks are scoped to rounds where the target role is actually assigned. */
    public static boolean hasAssignedRole(
            ServerLevel level, net.minecraft.resources.ResourceLocation targetId) {
        if (level == null || targetId == null) return false;
        try {
            SRERoleWorldComponent roles = SRERoleWorldComponent.KEY.get(level);
            if (roles == null) return false;
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
