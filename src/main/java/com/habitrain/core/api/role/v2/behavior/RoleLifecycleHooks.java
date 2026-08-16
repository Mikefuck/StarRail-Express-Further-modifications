package com.habitrain.core.api.role.v2.behavior;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Map;

/**
 * Lifecycle hooks for a role: assignment, loss, game start/end and the
 * pre-assignment role-confirm mutation point.
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

    /** Called when the safe-time/game-true-start phase begins (upstream {@code OnGameTrueStarted}). */
    default void onGameTrueStart(ServerLevel level, RoleHookContext ctx) {}

    /**
     * Called just before the final role map is confirmed. The map is mutable
     * and a hook may replace roles (the seven-sins mutex uses this to enforce
     * one-sin-per-round and natural spawn gates). Scope-gated per role.
     */
    default void onRolesConfirm(ServerLevel level, Map<Player, SRERole> roles, RoleHookContext ctx) {}

    /** Called when a game round ends. */
    default void onGameEnd(ServerLevel level, RoleHookContext ctx) {}
}
