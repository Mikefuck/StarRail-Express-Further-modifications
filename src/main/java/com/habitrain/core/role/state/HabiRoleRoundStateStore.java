package com.habitrain.core.role.state;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Fixed, non-persistent store for {@link com.habitrain.core.api.role.v2.state.StateScope#ROUND}
 * role state (fix-doc §10.2). A round lives in a world; slots are keyed by the
 * world-qualified {@link StateSlotKey#encode()} string. Cleared at round end.
 */
public final class HabiRoleRoundStateStore implements StateSlotBag {

    public static final HabiRoleRoundStateStore INSTANCE = new HabiRoleRoundStateStore();

    private final Map<String, StoredState> slots = new HashMap<>();

    private HabiRoleRoundStateStore() {}

    @Override
    public @Nullable StoredState read(String slotKey) {
        return slotKey == null ? null : slots.get(slotKey);
    }

    @Override
    public void write(String slotKey, StoredState state) {
        if (slotKey != null && state != null) {
            slots.put(slotKey, state);
        }
    }

    @Override
    public void remove(String slotKey) {
        if (slotKey != null) {
            slots.remove(slotKey);
        }
    }

    @Override
    public void clear() {
        slots.clear();
    }

    @Override
    public Map<String, StoredState> slots() {
        return slots;
    }
}
