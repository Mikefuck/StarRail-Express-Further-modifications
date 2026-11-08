package com.habitrain.core.api.role.v2.state;

/**
 * Who owns a registered role-state value.
 *
 * <p>{@link #PLAYER} is keyed by player UUID and follows the player across
 * dimensions; {@link #WORLD} and {@link #ROUND} are isolated per world
 * ({@code worldKey} / dimension location) for the bound role.
 */
public enum StateScope {
    PLAYER,
    WORLD,
    ROUND
}
