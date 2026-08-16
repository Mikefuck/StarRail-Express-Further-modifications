package com.habitrain.core.api.role.v2.behavior;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Emergency-meeting hooks for a role.
 *
 * <p>{@link #onMeetingStart} / {@link #onMeetingEnd} are broadcast to every
 * subscribed role. {@link #allowVoteOut} runs for the voted player's role;
 * {@link Decision#DENY} blocks the ejection.
 */
public interface RoleMeetingHooks {

    /** Called when an emergency meeting starts. Broadcast to every subscriber. */
    default void onMeetingStart(@Nullable ServerLevel level, @Nullable ServerPlayer reporter,
                                RoleHookContext ctx) {}

    /** Called when an emergency meeting ends. Broadcast to every subscriber. */
    default void onMeetingEnd(@Nullable ServerLevel level, RoleHookContext ctx) {}

    /**
     * Whether the voted player may be ejected. Return {@link Decision#DENY}
     * to keep them in the game (politician-style immunity).
     */
    default Decision allowVoteOut(ServerPlayer voted, RoleHookContext ctx) {
        return Decision.PASS;
    }
}
