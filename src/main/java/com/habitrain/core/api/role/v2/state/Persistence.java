package com.habitrain.core.api.role.v2.state;

/**
 * How long a state value is intended to live. Persistence is a declaration
 * for future NBT/CCA backends; this increment stores everything in memory
 * and honours lifetime through {@link ResetCause} instead.
 */
public enum Persistence {
    /** Never written; dropped as soon as a matching reset fires. */
    NONE,
    /** Lives for the current round only. */
    ROUND,
    /** Lives for the current world session. */
    WORLD,
    /** Intended to survive world reloads once a backend exists. */
    PERMANENT
}
