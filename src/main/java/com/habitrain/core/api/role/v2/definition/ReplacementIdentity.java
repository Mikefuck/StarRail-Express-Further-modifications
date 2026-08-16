package com.habitrain.core.api.role.v2.definition;

/**
 * Identity strategy for a v2 {@code REPLACE} operation.
 *
 * <p>Decides whether the replacement keeps the target's canonical id (best for
 * seamless rewrites that must stay compatible with saves, commands and object
 * comparisons) or takes a brand-new id while the old target id becomes an alias
 * (best for an explicit migration to a new role identity).
 */
public enum ReplacementIdentity {

    /**
     * The replacement continues to use the target's canonical id. The compiled
     * replacement definition's key must equal the target key.
     *
     * <p>This preserves the canonical id, not the original Java object identity.
     * Upstream code that compares roles with {@code ==} may still behave
     * differently; use MODIFY on the original object when object identity must
     * survive.
     */
    KEEP_CANONICAL_ID,

    /**
     * Deprecated alias for {@link #KEEP_CANONICAL_ID}. Kept for source
     * compatibility with early v2 callers.
     */
    @Deprecated
    PRESERVE_TARGET_ID,

    /**
     * The replacement takes a new canonical id (in the provider's namespace) and
     * the old target id is registered as an alias resolving to it.
     */
    NEW_ID_WITH_ALIAS
}
