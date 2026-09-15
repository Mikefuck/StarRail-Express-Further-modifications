package com.habitrain.core.game.sre;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** Publishes role changes after the reversible role transaction commits. */
public final class SreRoleAssignmentEffects {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SreRoleAssignmentEffects");
    private SreRoleAssignmentEffects() {}
    public static void finishReassignRole(ServerLevel level, UUID playerId, SRERole oldRole,
                                          SRERole newRole, boolean record, boolean addStats) {
        if (level == null || playerId == null || newRole == null) {
            return;
        }
        ServerPlayer player = level.getServer() == null ? null
                : level.getServer().getPlayerList().getPlayer(playerId);
        if (player != null && oldRole != null) {
            try {
                org.agmas.harpymodloader.events.ModdedRoleRemoved.EVENT.invoker()
                        .removeModdedRole(player, oldRole);
            } catch (Throwable t) {
                LOGGER.warn("finishReassignRole: ModdedRoleRemoved failed for {}", playerId, t);
            }
            if (record) {
                try {
                    io.wifi.starrailexpress.SRE.REPLAY_MANAGER
                            .recordPlayerRoleChange(playerId, oldRole, newRole);
                } catch (Throwable t) {
                    LOGGER.warn("finishReassignRole: recordPlayerRoleChange failed for {}", playerId, t);
                }
            }
        }
        if (addStats && player != null) {
            addReassignStats(player, newRole);
        }
        SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(level);
        if (gameWorld != null) {
            gameWorld.syncRoles();
            try {
                io.wifi.starrailexpress.SRE.REPLAY_MANAGER.updateRolesFromComponent(gameWorld);
            } catch (Throwable ignored) {
            }
        }
        if (player != null) {
            try {
                org.agmas.harpymodloader.events.ModdedRoleAssigned.EVENT.invoker()
                        .assignModdedRole(player, newRole);
            } catch (Throwable t) {
                LOGGER.error("finishReassignRole: failed to fire ModdedRoleAssigned for {}", playerId, t);
            }
        }
    }

    private static void addReassignStats(ServerPlayer player, SRERole role) {
        try {
            var stats = io.wifi.starrailexpress.stats.PlayerStatsManager.get(player);
            stats.getOrCreateRoleStats(role.getIdentifier()).incrementTimesPlayed();
            if (role.isVigilanteTeam()) {
                stats.incrementTotalSheriffGames();
            } else if (role.canUseKiller()) {
                stats.incrementTotalKillerGames();
            } else if (role.isNeutrals()) {
                stats.incrementTotalNeutralGames();
            } else if (role.isInnocent() && !role.isVigilanteTeam()) {
                stats.incrementTotalCivilianGames();
            }
        } catch (Throwable t) {
            LOGGER.warn("finishReassignRole: stats update failed for {}", player.getUUID(), t);
        }
    }
}
