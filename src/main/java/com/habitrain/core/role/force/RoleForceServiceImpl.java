package com.habitrain.core.role.force;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleFaction;
import com.habitrain.core.api.role.v2.RoleForceApi;
import com.habitrain.core.api.role.v2.RoleKey;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.Harpymodloader;
import org.agmas.harpymodloader.modded_murder.PlayerRoleWeightManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Next-round force queue. Writes Harpy / {@code ForcePlayerTeam} internally.
 */
public final class RoleForceServiceImpl implements RoleForceApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|RoleForceApi");
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

    @Override
    public boolean queueExact(ServerPlayer player, RoleKey role) {
        if (!isOnline(player) || role == null) {
            return false;
        }
        pruneOffline(player.getServer());
        SRERole sre = resolve(role);
        if (sre == null) {
            LOGGER.error("RoleForceApi.queueExact: catalog has no live role for {}", role);
            return false;
        }
        if (sre.isOtherModeRole() || !hasOnlineCapacity(sre, player)) return false;
        try {
            clear(player.getUUID());
            Harpymodloader.addToForcedRoles(sre, player);
            int type = sre.getRoleType();
            if (type > 0) {
                PlayerRoleWeightManager.ForcePlayerTeam.put(player.getUUID(), type);
            }
            queued.add(player.getUUID());
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("RoleForceApi.queueExact failed for {}", player.getUUID(), e);
            return false;
        }
    }

    @Override
    public boolean queueFaction(ServerPlayer player, RoleFaction faction) {
        if (player == null || faction == null) {
            return false;
        }
        int type = switch (faction) {
            case INNOCENT -> 1;
            case NEUTRAL -> 2;
            case KILLER, MAFIA -> 4;
            case VIGILANTE -> 5;
        };
        return queueRoleType(player, type);
    }

    @Override
    public boolean queueRoleType(ServerPlayer player, int roleTypeId) {
        if (!isOnline(player) || roleTypeId < 1 || roleTypeId > 5) {
            return false;
        }
        pruneOffline(player.getServer());
        try {
            clear(player.getUUID());
            PlayerRoleWeightManager.ForcePlayerTeam.put(player.getUUID(), roleTypeId);
            queued.add(player.getUUID());
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("RoleForceApi.queueRoleType failed for {}", player.getUUID(), e);
            return false;
        }
    }

    @Override
    public boolean isQueued(@Nullable UUID player) {
        if (player == null) {
            return false;
        }
        boolean upstreamQueued = false;
        try {
            if (PlayerRoleWeightManager.ForcePlayerTeam.containsKey(player)) {
                upstreamQueued = true;
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (Harpymodloader.FORCED_MODDED_ROLE_FLIP.containsKey(player)) {
                upstreamQueued = true;
            }
        } catch (RuntimeException ignored) {
        }
        return reconcileQueueState(queued, player, upstreamQueued);
    }

    /**
     * Reconciles the API-owned marker with the authoritative upstream queues.
     * SRE consumes and clears those queues after role assignment, so retaining
     * only the local marker would incorrectly block the player's later cards.
     */
    static boolean reconcileQueueState(Set<UUID> queued, UUID player, boolean upstreamQueued) {
        if (upstreamQueued) {
            return true;
        }
        queued.remove(player);
        return false;
    }

    @Override
    public void clear(@Nullable UUID player) {
        if (player == null) {
            return;
        }
        queued.remove(player);
        try {
            PlayerRoleWeightManager.ForcePlayerTeam.remove(player);
        } catch (RuntimeException ignored) {
        }
        try {
            Harpymodloader.FORCED_MODDED_ROLE_FLIP.remove(player);
            if (Harpymodloader.FORCED_MODDED_ROLE != null) {
                var it = Harpymodloader.FORCED_MODDED_ROLE.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    var holders = entry.getValue();
                    if (holders != null) {
                        holders.remove(player);
                        if (holders.isEmpty()) {
                            it.remove();
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug("clear exact force failed: {}", e.toString());
        }
    }

    private static SRERole resolve(RoleKey role) {
        try {
            return RoleCatalogApi.instance().find(role)
                    .map(EffectiveRole::role)
                    .orElse(null);
        } catch (RuntimeException e) {
            LOGGER.error("catalog find failed for {}", role, e);
            return null;
        }
    }

    private static boolean isOnline(@Nullable ServerPlayer player) {
        return player != null && player.getServer() != null
                && player.getServer().getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private void pruneOffline(net.minecraft.server.MinecraftServer server) {
        for (UUID id : Set.copyOf(queued)) {
            if (server.getPlayerList().getPlayer(id) == null) clear(id);
        }
    }

    private static boolean hasOnlineCapacity(SRERole role, ServerPlayer player) {
        int maximum = role.spawnInfo == null ? role.defaultMaxCount : role.spawnInfo.maxSpawn;
        if (maximum < 0) return true;
        Set<UUID> holders = new java.util.HashSet<>();
        var existing = Harpymodloader.FORCED_MODDED_ROLE.get(role);
        if (existing != null) for (UUID id : existing) {
            if (!id.equals(player.getUUID()) && player.getServer().getPlayerList().getPlayer(id) != null) holders.add(id);
        }
        return holders.size() < maximum;
    }
}
