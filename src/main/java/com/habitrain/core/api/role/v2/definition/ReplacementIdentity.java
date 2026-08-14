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
     */
    PRESERVE_TARGET_ID,

    /**
     * The replacement takes a new canonical id (in the provider's namespace) and
     * the old target id is registered as an alias resolving to it.
     */
    NEW_ID_WITH_ALIAS
}
