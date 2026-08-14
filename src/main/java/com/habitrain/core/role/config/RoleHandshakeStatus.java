package com.habitrain.core.role.config;

/**
 * Handshake outcome after comparing the server manifest with the local manifest
 * (fix-doc §14.2). The client never silently runs on a mismatch: it either
 * proceeds, degrades to a read-only presentation, or reports a clear error.
 */
public enum RoleHandshakeStatus {
    /** Server and client manifests match; normal play. */
    OK,
    /** Optional presentation metadata differs and the client lacks the resources. */
    DEGRADED_CLIENT_EXTENSION,
    /** A required provider/version is missing on the client. */
    REJECTED_MISSING_PROVIDER,
    /** The gameplay definition hash differs; cannot silently run. */
    HASH_MISMATCH
}
