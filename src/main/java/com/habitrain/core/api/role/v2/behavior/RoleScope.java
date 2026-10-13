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

    /** Primary event player holds the role; corpse events have both participants, broadcasts require a holder. */
    HOLDER,
    /** The killer in a kill/death event holds the role. */
    KILLER,
    /** The victim in a kill/death event holds the role. */
    VICTIM,
    /** Explicit player target of an entity interaction or vote-out holds the role; non-player targets do not match. */
    TARGET,
    /** Fires once per declaration when an online holder exists; context names a representative holder. */
    ANY_ACTIVE_HOLDER,
    /** The role is present in this round's pool/history. */
    ROUND_PRESENT,
    /**
     * Runs whenever the definition is enabled, regardless of current holders.
     * High risk.
     *
     * <p>Registration does not reject this scope. At runtime the dispatcher
     * still requires {@code presentInRound && allowGlobalHooks} (the round
     * snapshot's {@code allowGlobalHooks} gate, or the live config when no
     * snapshot is bound). Declaring GLOBAL does not bypass those gates.
     */
    GLOBAL_WHILE_ENABLED
}
