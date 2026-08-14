package com.habitrain.core.api.role.v2.behavior;

import io.wifi.starrailexpress.content.entity.PlayerBodyEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Combat hooks for a role: death gating, death/kill notification, and
 * corpse handling.
 *
 * <p>{@link #allowDeath} / {@link #allowDeathByKiller} return a
 * {@link Decision}; the dispatcher merges them ({@code DENY} wins) and the
 * global listener honours the result. The other hooks are fire-and-forget.
 */
public interface RoleCombatHooks {

    /**
     * Whether the player may die. Return {@link Decision#DENY} to prevent the
     * death (e.g. a last-stand or conversion-on-death rule).
     */
    default Decision allowDeath(ServerPlayer player, ResourceLocation deathReason, RoleHookContext ctx) {
        return Decision.PASS;
    }

    /**
     * Whether the player may die when a killer is present. Distinct from
     * {@link #allowDeath} because upstream fires a separate event after the
     * generic gate. Return {@link Decision#DENY} to cancel the death.
     */
    default Decision allowDeathByKiller(ServerPlayer victim, @Nullable ServerPlayer killer,
                                        ResourceLocation deathReason, RoleHookContext ctx) {
        return Decision.PASS;
    }

    /** Called when the player dies. */
    default void onDeath(ServerPlayer player, ResourceLocation deathReason, RoleHookContext ctx) {}

    /**
     * Called when any player dies, for every registered role that subscribed
     * this hook. Used by nearby-holder rules (e.g. crime_scapegoat's 4-block
     * knife window). The dispatched {@code ctx.role()} is the subscribed
     * role, not the dead player's role.
     */
    default void onAnyDeath(ServerPlayer dead, ResourceLocation deathReason, RoleHookContext ctx) {}

    /** Called when the player kills another player (after the death is confirmed). */
    default void onKill(ServerPlayer victim, ServerPlayer killer,
                        ResourceLocation deathReason, RoleHookContext ctx) {}

    /**
     * Called after a corpse is spawned. Dispatched once for the victim's
     * role and once for the killer's role (when they differ).
     */
    default void onDeathWithBody(ServerPlayer victim, @Nullable ServerPlayer killer,
                                 ResourceLocation deathReason, @Nullable PlayerBodyEntity body,
                                 RoleHookContext ctx) {}
}
