package com.habitrain.core.api.role.v2.behavior;

/**
 * The set of players/entities a role hook applies to for a given event.
 *
 * <p>Declared when a hook is registered; the dispatcher uses it to decide which
 * players' current roles receive the event. {@link #HOLDER} is the common case
 * (the event player currently holds the role); the others cover killer/victim
 * sides, any live holder, round presence and global-while-enabled.
 */
public enum RoleScope {

    /** The event player currently holds the role. */
    HOLDER,
    /** The killer in a kill/death event holds the role. */
    KILLER,
    /** The victim in a kill/death event holds the role. */
    VICTIM,
    /** The explicit target of the event holds the role. */
    TARGET,
    /** Any live/online holder of the role receives the event. */
    ANY_ACTIVE_HOLDER,
    /** The role is present in this round's pool/history. */
    ROUND_PRESENT,
    /** Runs whenever the definition is enabled, regardless of holders. High risk. */
    GLOBAL_WHILE_ENABLED
}
