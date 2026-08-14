package com.habitrain.core.role.state;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * A storage bag of opaque state slots, shared by the fixed CCA containers
 * (player / world) and the process-level round store. Slot keys are the
 * {@link StateSlotKey#encode()} strings; values are {@link StoredState}.
 *
 * <p>The {@link CcaRoleStateStore} routes each slot to the bag that owns its
 * {@link com.habitrain.core.api.role.v2.state.StateScope}.
 */
public interface StateSlotBag {

    @Nullable StoredState read(String slotKey);

    void write(String slotKey, StoredState state);

    void remove(String slotKey);

    void clear();

    /** Live view of the slot map (read for sweeps / diagnostics). */
    Map<String, StoredState> slots();
}
