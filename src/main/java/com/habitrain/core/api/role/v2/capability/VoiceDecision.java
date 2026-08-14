package com.habitrain.core.api.role.v2.capability;

/**
 * Result of evaluating voice policies for one speaker → listener pair.
 *
 * <p>{@link #PASS} means no registered policy cares; adapters leave the
 * packet alone. {@link #BLOCK} cancels the packet.
 */
public enum VoiceDecision {
    PASS,
    BLOCK
}
