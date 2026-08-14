package com.habitrain.core.api.role.v2;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The outcome of a role change transaction.
 *
 * <p>{@code success} is {@code true} only when the change completed; on failure
 * {@code message} carries a human-readable reason, {@code phase} the failing
 * transaction stage (fix-doc §11.2), and {@code role} the role that was
 * attempted (or {@code null} for a removal).
 */
public record RoleChangeResult(boolean success, @Nullable String message,
                               @Nullable RoleKey role, RoleChangeCause cause,
                               @Nullable String phase) {

    public RoleChangeResult {
        Objects.requireNonNull(cause, "cause");
    }

    /** Source-compatible constructor without a failure phase. */
    public RoleChangeResult(boolean success, @Nullable String message,
                            @Nullable RoleKey role, RoleChangeCause cause) {
        this(success, message, role, cause, null);
    }

    /** A successful change to the given role. */
    public static RoleChangeResult success(RoleKey role, RoleChangeCause cause) {
        return new RoleChangeResult(true, null, role, cause, null);
    }

    /** A failed change with a reason. */
    public static RoleChangeResult failure(String message, RoleChangeCause cause) {
        return new RoleChangeResult(false, message, null, cause, null);
    }

    /** A failed change with a reason and the transaction phase that failed. */
    public static RoleChangeResult failure(String message, RoleChangeCause cause,
                                           @Nullable String phase) {
        return new RoleChangeResult(false, message, null, cause, phase);
    }
}
