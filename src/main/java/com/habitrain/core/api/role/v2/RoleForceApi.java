package com.habitrain.core.api.role.v2;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Next-round role force queue (lobby cards / self-select). Distinct from
 * {@link RoleChangeApi}, which mutates the live round.
 *
 * <p>Core writes Harpy {@code addToForcedRoles} and {@code ForcePlayerTeam}
 * internally. Addons must not call those types directly.
 */
public interface RoleForceApi {

    static RoleForceApi instance() {
        return com.habitrain.core.api.spi.RoleSpi.force();
    }

    /**
     * Queue an exact role for the player's next assignment, and a matching
     * faction bucket so rotation drafts honour the card.
     */
    boolean queueExact(ServerPlayer player, RoleKey role);

    /**
     * Queue a faction bucket (innocent/killer/neutral/vigilante/mafia) without
     * pinning a specific role id.
     */
    boolean queueFaction(ServerPlayer player, RoleFaction faction);

    /**
     * Queue by SRE assigner role-type id (1 civilian, 2 neutral, 3 killer-neutral,
     * 4 killer, 5 vigilante).
     */
    boolean queueRoleType(ServerPlayer player, int roleTypeId);

    /** Typed alternative to the upstream numeric assignment buckets. */
    default boolean queueRoleType(ServerPlayer player, RoleAssignmentType roleType) {
        return queueRoleType(player, java.util.Objects.requireNonNull(roleType, "roleType").id());
    }

    boolean isQueued(@Nullable UUID player);

    void clear(@Nullable UUID player);
}
