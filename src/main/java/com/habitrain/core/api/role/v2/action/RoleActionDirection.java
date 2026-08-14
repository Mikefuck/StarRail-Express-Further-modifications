package com.habitrain.core.api.role.v2.action;

/**
 * Direction of a managed role action (design §16.2).
 *
 * <p>{@link #BIDIRECTIONAL} accepts both the multiplex C2S request and an S2C
 * push; handlers still run only on the logical server.
 */
public enum RoleActionDirection {
    C2S,
    S2C,
    BIDIRECTIONAL
}
