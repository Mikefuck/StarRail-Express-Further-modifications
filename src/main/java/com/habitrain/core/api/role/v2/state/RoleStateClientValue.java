package com.habitrain.core.api.role.v2.state;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** A decoded client mirror read; PRESENT includes an explicit null value. */
public record RoleStateClientValue<T>(Status status, @Nullable T value, int dataVersion, long revision) {
    public enum Status { PRESENT, MISSING, UNKNOWN_SCHEMA, SCHEMA_MISMATCH, DECODE_FAILED }

    public RoleStateClientValue {
        Objects.requireNonNull(status, "status");
        if (status != Status.PRESENT && value != null) {
            throw new IllegalArgumentException("Unavailable state cannot contain a value");
        }
    }

    public boolean present() { return status == Status.PRESENT; }

    /** Fallback only for unavailable reads; an explicit null remains null. */
    public @Nullable T orElse(@Nullable T fallback) { return present() ? value : fallback; }
}
