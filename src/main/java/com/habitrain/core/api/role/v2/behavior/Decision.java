package com.habitrain.core.api.role.v2.behavior;

/**
 * Explicit result of a role behavior hook, replacing bare booleans so merge
 * policy is deterministic regardless of listener order.
 *
 * <p>Merge policy (see {@link #merge}): for safety decisions (death, packet
 * validation, container open) {@code DENY} wins; for capability grants the
 * baseline is {@code PASS} and only an explicit {@code ALLOW} overrides.
 */
public enum Decision {

    /** No opinion; defer to the baseline or the next hook. */
    PASS,
    /** Explicitly allow / grant. */
    ALLOW,
    /** Explicitly deny / block. */
    DENY;

    /**
     * Merges two decisions. {@code DENY} dominates, then {@code ALLOW}, then
     * {@code PASS}. Used to fold the results of multiple hooks for one event.
     */
    public static Decision merge(Decision a, Decision b) {
        if (a == DENY || b == DENY) return DENY;
        if (a == ALLOW || b == ALLOW) return ALLOW;
        return PASS;
    }
}
