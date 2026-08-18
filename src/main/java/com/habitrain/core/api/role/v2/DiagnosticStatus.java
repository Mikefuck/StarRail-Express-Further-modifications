package com.habitrain.core.api.role.v2;

/**
 * Diagnostic status of a v2 role-extension entry.
 *
 * <p>Extends the legacy {@code ACTIVE/DISABLED/INVALID/CONFLICT} set with the
 * v2-specific states the design guide calls for.
 */
public enum DiagnosticStatus {

    /** The entry is live and effective. */
    ACTIVE,
    /** The entry is registered but disabled by config. */
    DISABLED,
    /** The entry failed validation and is not effective. */
    INVALID,
    /** The entry conflicts with another entry and is not effective. */
    CONFLICT,
    /**
     * @deprecated Pending activation is reported at snapshot level via
     * {@link DiagnosticSnapshot#pendingId()}; an entry is not marked pending
     * unless its current and prepared effective forms are compared.
     */
    @Deprecated
    PENDING_NEXT_ROUND,
    /** A legacy/unmanaged registration that v2 does not control. */
    LEGACY_UNMANAGED,
    /** A managed hook was circuit-broken after repeated failures. */
    HOOK_CIRCUIT_BROKEN
}
