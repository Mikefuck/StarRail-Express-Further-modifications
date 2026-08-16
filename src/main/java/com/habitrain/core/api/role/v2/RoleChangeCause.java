package com.habitrain.core.api.role.v2;

/**
 * Why a player's role changed. Recorded in the role history timeline and used to
 * drive cleanup/initialization semantics per change type.
 */
public enum RoleChangeCause {

    /** A fresh role assignment at round start. */
    ASSIGN,
    /** A mid-round conversion to another role (e.g. scapegoat -> killer). */
    CONVERSION,
    /** A sheriff elected by vote. */
    SHERIFF_ELECTION,
    /** A player revived into their previous role. */
    REVIVE,
    /** The player's role was removed. */
    REMOVE,
    /** Any other change. */
    OTHER
}
