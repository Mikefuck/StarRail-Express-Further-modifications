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
     * Merges two decisions where {@code DENY} dominates, then {@code ALLOW}, then
     * {@code PASS}. Used for safety/veto decisions (e.g. death, packet validation,
     * container open, game end).
     */
    public static Decision merge(Decision a, Decision b) {
        if (a == DENY || b == DENY) return DENY;
        if (a == ALLOW || b == ALLOW) return ALLOW;
        return PASS;
    }

    /**
     * Merges two decisions where {@code ALLOW} dominates over {@code DENY}, then
     * {@code PASS}. Used when evaluating additive capability grants.
     */
    public static Decision mergeAllowWins(Decision a, Decision b) {
        if (a == ALLOW || b == ALLOW) return ALLOW;
        if (a == DENY || b == DENY) return DENY;
        return PASS;
    }
}
