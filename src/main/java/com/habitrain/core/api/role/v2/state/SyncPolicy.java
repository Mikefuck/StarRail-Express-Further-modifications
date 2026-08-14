package com.habitrain.core.api.role.v2.state;

/**
 * Who should receive a synchronized copy of a state value (fix-doc §10.4).
 *
 * <p>The server decides recipients from this policy; a client can never
 * request a slot it is not entitled to. {@link #NONE} is the safe default
 * and never sends anything. {@link #SERVER_ONLY} marks a value that must
 * never leave the server (privacy wall).
 */
public enum SyncPolicy {
    /** Never sent to any client. */
    NONE,
    /** Sent only to the owning player (PLAYER scope). */
    OWNER,
    /** Sent to the owning player plus whoever is tracking them this round. */
    OWNER_AND_TRACKING,
    /** Sent to every online player. */
    ALL,
    /** Server-internal only; explicitly refused on the wire. */
    SERVER_ONLY
}
