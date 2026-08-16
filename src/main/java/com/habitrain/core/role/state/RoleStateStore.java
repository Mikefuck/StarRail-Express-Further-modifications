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

    /**
     * Every slot currently materialized in this store. Used by the late-join /
     * reconnect full sync (audit P0-2) and by removal sweeps that must report
     * exactly which slots were deleted (audit P0-1). Memory stores list all
     * keys; CCA stores list the slots of online players / loaded worlds plus
     * the round bucket.
     */
    java.util.Collection<StateSlotKey> keys();

    /** Snapshot of every persistent slot, for restart simulation / diagnostics. */
    java.util.Map<StateSlotKey, StoredState> exportAll();

    /** Restores a previously exported snapshot, replacing current persistent slots. */
    void importAll(java.util.Map<StateSlotKey, StoredState> data);
}
