package com.habitrain.core.role.extension;

/**
 * Compiled entry status (fix-doc §4.3). Status is a compile result, never guessed
 * by the UI; invalid or conflicting entries do not enter the effective snapshot.
 * Only the subset needed for Phases B–C is defined here; the full set is closed
 * out in later phases.
 */
public enum EntryStatus {
    /** The entry is compiled into the current effective snapshot. */
    ACTIVE,
    /** The entry failed validation and is excluded from the snapshot. */
    INVALID,
    /** The entry conflicts with another declaration on the same target. */
    CONFLICT,
    /** The entry is disabled by global/provider/entry configuration. */
    DISABLED,
    /** A legacy (v1) declaration that core observes but does not manage yet. */
    LEGACY_UNMANAGED
}
