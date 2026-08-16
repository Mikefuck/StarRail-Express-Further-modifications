package com.habitrain.core.api.role.v2.behavior;

import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Three-phase victory hooks, matching design §14.
 *
 * <ol>
 *   <li>{@link #allowGameEnd} — gate. {@link Decision#DENY} blocks the end
 *       (pride-style “not yet”).</li>
 *   <li>{@link #evaluateWin} — winner-set patch. Folded across roles;
 *       declare/replace overwrite, add/remove mutate.</li>
 *   <li>{@link #afterWinnersFinalized} — read-only settlement. Cannot change
 *       the winner set.</li>
 * </ol>
 *
 * <p>{@code proposed} is the upstream {@code WinStatus} name, or
 * {@code "BLACKOUT"} on the blackout checker path.
 */
public interface RoleWinHooks {

    /**
     * Whether the game may end given the proposed status. Return
     * {@link Decision#DENY} to keep the round running.
     */
    default Decision allowGameEnd(@Nullable ServerLevel level, @Nullable String proposed,
                                  boolean loose, RoleHookContext ctx) {
        return Decision.PASS;
    }

    /**
     * Contribute a winner-set patch. Return {@link WinPatch#noChange()} to
     * leave the accumulator alone.
     */
    default WinPatch evaluateWin(@Nullable ServerLevel level, @Nullable String proposed,
                                 boolean loose, RoleHookContext ctx) {
        return WinPatch.noChange();
    }

    /**
     * Called after winners are locked. Stats, grants and announcements go
     * here — do not write winners from this hook.
     */
    default void afterWinnersFinalized(@Nullable ServerLevel level, WinOutcome outcome,
                                       RoleHookContext ctx) {}
}
