package com.habitrain.core.role.state;

import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Persistence backend for role state (fix-doc §10.2). Stores the opaque
 * {@link StoredState} of every {@code WORLD}/{@code PERMANENT} slot; transient
 * {@code ROUND}/{@code NONE} slots never reach the store.
 *
 * <p>The default implementation is {@link MemoryRoleStateStore} (tests and
 * fallback). Production binds {@link CcaRoleStateStore}, which routes each
 * slot by {@link com.habitrain.core.api.role.v2.state.StateScope} to the fixed
 * CCA containers. The store is deliberately codec-agnostic so unknown provider
 * slots round-trip byte-for-byte.
 */
public interface RoleStateStore {

    @Nullable StoredState read(StateSlotKey key);

    void write(StateSlotKey key, StoredState state);

    void remove(StateSlotKey key);

    /** Removes every slot whose key matches {@code filter} (reset / lifecycle sweeps). */
    void removeWhere(Predicate<StateSlotKey> filter);

    void clearAll();

    /** Snapshot of every persistent slot, for restart simulation / diagnostics. */
    java.util.Map<StateSlotKey, StoredState> exportAll();

    /** Restores a previously exported snapshot, replacing current persistent slots. */
    void importAll(java.util.Map<StateSlotKey, StoredState> data);
}
