package com.habitrain.core.api.role.v2.behavior;

/**
 * Operations a {@link WinPatch} can apply to the proposed winner set.
 *
 * <p>Merge policy: {@link #REPLACE_WINNERS}, {@link #DECLARE_FACTION_WIN}
 * and {@link #DECLARE_CUSTOM} replace the accumulator; {@link #ADD_WINNER}
 * / {@link #REMOVE_WINNER} mutate its winner list; {@link #NO_CHANGE} is
 * a no-op.
 */
public enum WinPatchOp {
    NO_CHANGE,
    ADD_WINNER,
    REMOVE_WINNER,
    REPLACE_WINNERS,
    DECLARE_FACTION_WIN,
    DECLARE_CUSTOM
}
