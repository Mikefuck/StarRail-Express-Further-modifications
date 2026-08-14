package com.habitrain.core.role.state;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Default {@link RoleStateStore}: a process-local map keyed by
 * {@link StateSlotKey}. Used by unit tests and as the fallback when no CCA
 * container is available. {@link #exportAll()}/{@link #importAll} let a test
 * (or an integrated-server restart) simulate the NBT round-trip a world
 * component would otherwise provide.
 */
public final class MemoryRoleStateStore implements RoleStateStore {

    private final Map<StateSlotKey, StoredState> slots = new ConcurrentHashMap<>();

    @Override
    public @Nullable StoredState read(StateSlotKey key) {
        return key == null ? null : slots.get(key);
    }

    @Override
    public void write(StateSlotKey key, StoredState state) {
        if (key == null || state == null) {
            return;
        }
        slots.put(key, state);
    }

    @Override
    public void remove(StateSlotKey key) {
        if (key != null) {
            slots.remove(key);
        }
    }

    @Override
    public void removeWhere(Predicate<StateSlotKey> filter) {
        if (filter == null) {
            return;
        }
        slots.keySet().removeIf(filter);
    }

    @Override
    public void clearAll() {
        slots.clear();
    }

    @Override
    public Map<StateSlotKey, StoredState> exportAll() {
        // Defensive: copy values so callers cannot mutate stored payloads.
        Map<StateSlotKey, StoredState> copy = new LinkedHashMap<>();
        for (Map.Entry<StateSlotKey, StoredState> e : slots.entrySet()) {
            copy.put(e.getKey(), e.getValue());
        }
        return copy;
    }

    @Override
    public void importAll(Map<StateSlotKey, StoredState> data) {
        slots.clear();
        if (data != null) {
            slots.putAll(data);
        }
    }
}
