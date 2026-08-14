package com.habitrain.core.role.config;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of a manifest handshake: status plus a human-readable message and
 * the concrete list of missing providers/modules (fix-doc §14.2).
 */
public record RoleHandshakeResult(
        RoleHandshakeStatus status,
        @Nullable String message,
        List<String> missingModules) {

    public RoleHandshakeResult {
        Objects.requireNonNull(status, "status");
        missingModules = missingModules == null ? List.of() : List.copyOf(missingModules);
    }

    public static RoleHandshakeResult ok() {
        return new RoleHandshakeResult(RoleHandshakeStatus.OK, null, List.of());
    }

    public static RoleHandshakeResult degraded(String message) {
        return new RoleHandshakeResult(RoleHandshakeStatus.DEGRADED_CLIENT_EXTENSION, message, List.of());
    }

    public static RoleHandshakeResult rejected(List<String> missing, String message) {
        return new RoleHandshakeResult(RoleHandshakeStatus.REJECTED_MISSING_PROVIDER, message, missing);
    }

    public static RoleHandshakeResult hashMismatch(String message) {
        return new RoleHandshakeResult(RoleHandshakeStatus.HASH_MISMATCH, message, List.of());
    }
}
