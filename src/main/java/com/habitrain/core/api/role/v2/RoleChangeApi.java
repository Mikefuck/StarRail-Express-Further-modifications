package com.habitrain.core.api.role.v2;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Public role-change service (v2 Role Extension platform).
 *
 * <p>All role changes go through this service instead of directly mutating a
 * role map. A change is a transaction: it resolves the target to its canonical
 * role, validates it, cleans up the old role, updates the upstream SRE role map
 * and the mode faction state, writes the timeline/history, initializes the new
 * role and fires the assignment event. {@link #transform} is the common
 * mid-round conversion; {@link #assign} adds explicit options.
 */
public interface RoleChangeApi {

    /** The process-wide role-change service. */
    static RoleChangeApi instance() {
        return DefaultHolder.INSTANCE;
    }

    /** Lazily-bound default instance; avoids touching the game on class load. */
    final class DefaultHolder {
        private DefaultHolder() {}

        static final RoleChangeApi INSTANCE =
                new com.habitrain.core.role.change.RoleChangeServiceImpl();
    }

    /**
     * Assigns a role to a player with explicit options.
     *
     * @return the transaction result
     */
    RoleChangeResult assign(ServerPlayer player, RoleKey role, RoleChangeOptions options);

    /**
     * Transforms a player to a new role (a mid-round conversion) with default
     * options (record timeline + update stats).
     *
     * @return the transaction result
     */
    RoleChangeResult transform(ServerPlayer player, RoleKey role, RoleChangeCause cause);

    /**
     * Removes a player's role.
     *
     * @return the transaction result
     */
    RoleChangeResult remove(ServerPlayer player, RoleChangeCause cause);

    /** The player's current role view. */
    RoleView current(ServerPlayer player);

    /** The player's role history timeline. */
    List<RoleHistoryEntry> history(ServerPlayer player);
}
