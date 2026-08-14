package com.habitrain.core.api.role.v2.state;

/**
 * Reasons the platform drops a stored state value.
 *
 * <p>{@link #ROLE_LOST} and {@link #ROLE_ASSIGNED} only clear
 * {@link StateScope#PLAYER} slots for the affected player and role.
 * {@link #ROUND_END} / {@link #ROUND_START} / {@link #MANUAL} apply to
 * every scope whose spec listed the cause.
 */
public enum ResetCause {
    ROLE_LOST,
    ROLE_ASSIGNED,
    ROUND_END,
    ROUND_START,
    MANUAL
}
