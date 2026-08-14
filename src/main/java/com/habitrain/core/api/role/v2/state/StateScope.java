package com.habitrain.core.api.role.v2.state;

/**
 * Who owns a registered role-state value.
 *
 * <p>{@link #PLAYER} is keyed by player UUID; {@link #WORLD} and {@link #ROUND}
 * are process-wide for the bound role (one slot, not per player).
 */
public enum StateScope {
    PLAYER,
    WORLD,
    ROUND
}
