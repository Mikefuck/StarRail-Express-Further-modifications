package com.habitrain.core.api.role.v2.capability;

/**
 * Result of evaluating chat policies for one speaker → listener pair.
 *
 * <p>{@link #PASS} leaves the message alone; {@link #BLOCK} cancels it.
 */
public enum ChatDecision {
    PASS,
    BLOCK
}
