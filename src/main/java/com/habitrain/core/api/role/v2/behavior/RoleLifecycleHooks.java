package com.habitrain.core.api.role.v2.behavior;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Lifecycle hooks for a role: assignment, loss, and game start/end.
 *
 * <p>All methods are {@code default} no-ops so a provider implements only the
 * ones it needs. Hooks run on the server thread, isolated by the dispatcher
 * (a throwing hook is logged and circuit-broken, never crashing the tick).
 */
public interface RoleLifecycleHooks {

    /** Called after a player is assigned this role. */
    default void onAssigned(ServerPlayer player, RoleHookContext ctx) {}

    /** Called after a player loses this role (conversion, removal, round end). */
    default void onLost(ServerPlayer player, RoleHookContext ctx) {}

    /** Called when a game round starts. */
    default void onGameStart(ServerLevel level, RoleHookContext ctx) {}

    /** Called when a game round ends. */
    default void onGameEnd(ServerLevel level, RoleHookContext ctx) {}
}
