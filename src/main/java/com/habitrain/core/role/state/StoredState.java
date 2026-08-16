package com.habitrain.core.role.state;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Opaque persisted payload for one state slot: the value's {@code dataVersion}
 * plus its codec-encoded bytes. The store never decodes the bytes — an unknown
 * (unloaded provider) slot survives a read-modify-write untouched
 * (fix-doc §10.3 opaque preservation).
 *
 * <p>A {@code null} encoded array means "present-but-null", which is distinct
 * from "never written" (no entry at all).
 */
public record StoredState(int dataVersion, @Nullable byte[] encoded) {

    public StoredState {
        if (dataVersion < 1) {
            throw new IllegalArgumentException("dataVersion must be >= 1");
        }
        encoded = encoded == null ? null : encoded.clone();
    }

    @Override
    public @Nullable byte[] encoded() {
        return encoded == null ? null : encoded.clone();
    }

    /** Whether the slot holds an explicit {@code null} value. */
    public boolean isNull() {
        return encoded == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StoredState other)) {
            return false;
        }
        return dataVersion == other.dataVersion && Arrays.equals(encoded, other.encoded);
    }

    @Override
    public int hashCode() {
        return 31 * dataVersion + Arrays.hashCode(encoded);
    }

    @Override
    public String toString() {
        return "StoredState{v" + dataVersion + (isNull() ? ", null" : ", " + encoded.length + "b") + '}';
    }

    /** Helper: a "present but null" payload at {@code dataVersion}. */
    public static StoredState ofNull(int dataVersion) {
        return new StoredState(dataVersion, null);
    }

    /** Helper: a value payload at {@code dataVersion} (defensive copy taken). */
    public static StoredState of(int dataVersion, byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        return new StoredState(dataVersion, encoded);
    }
}
