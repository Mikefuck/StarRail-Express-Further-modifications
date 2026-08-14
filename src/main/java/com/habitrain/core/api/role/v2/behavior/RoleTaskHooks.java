package com.habitrain.core.api.role.v2.behavior;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Task / quest hooks for a role.
 *
 * <p>Fires after the upstream finish-quest pipeline (gold, streak, mood)
 * has run. This increment is notification-only — it does not replace the
 * existing gold-reward mixin.
 */
public interface RoleTaskHooks {

    /**
     * Called when the holder finishes a quest. {@code quest} is the raw
     * quest id string passed to {@code RoleMethodDispatcher.callOnFinishQuest}.
     */
    default void onFinishQuest(ServerPlayer player, @Nullable String quest,
                               int taskStreak, boolean parallel, RoleHookContext ctx) {}
}
