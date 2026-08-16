package com.habitrain.core.api.role.v2;

/**
 * The consumer a {@link RoleQuery} serves. The purpose selects sensible default
 * filtering and ordering for a given consumer, while individual filters can
 * still be overridden explicitly.
 */
public enum QueryPurpose {

    /**
     * Exploratory / unspecified. Includes the full visible effective set in
     * registration order; no other-mode roles are excluded even if a mode is set.
     */
    GENERIC,

    /** Random / assignment pools: honours {@code canBeRandomed()}. */
    RANDOM,

    /** Role book / role introduction rendering. */
    ROLE_BOOK,

    /** Command tab-completion and inspection. */
    COMMAND,

    /** Single-select / lightning draft rotation pools. Keeps other-mode roles. */
    ROTATION,

    /** Lottery card candidates. */
    LOTTERY_CARD,

    /** Player self-select screens. */
    SELF_SELECT,

    /** History / statistics. */
    STATISTICS;

    /**
     * Whether roles marked {@code isOtherModeRole()} should be excluded when a
     * concrete game mode is present on the query. Rotation keeps every role;
     * generic exploratory queries never exclude.
     */
    public boolean excludesOtherModeRoles() {
        return this != GENERIC && this != ROTATION;
    }
}
