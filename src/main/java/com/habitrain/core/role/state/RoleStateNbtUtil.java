package com.habitrain.core.role.state;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NBT round-trip for a {@code Map<String, StoredState>} slot bag. Each slot is
 * a nested {@code {v:int, [n:bool|d:byte[]]}} compound. Unknown keys survive
 * untouched because every key is opaque to the container (fix-doc §10.3).
 */
final class RoleStateNbtUtil {

    private RoleStateNbtUtil() {}

    static CompoundTag toTag(Map<String, StoredState> slots) {
        CompoundTag root = new CompoundTag();
        for (Map.Entry<String, StoredState> e : slots.entrySet()) {
            CompoundTag entry = new CompoundTag();
            StoredState state = e.getValue();
            entry.putInt("v", state.dataVersion());
            if (state.isNull()) {
                entry.putBoolean("n", true);
            } else {
                entry.putByteArray("d", state.encoded());
            }
            root.put(e.getKey(), entry);
        }
        return root;
    }

    /** Merges {@code root} into {@code target}, preserving keys whose payload is malformed. */
    static void fromTag(CompoundTag root, Map<String, StoredState> target) {
        if (root == null) {
            return;
        }
        for (String key : root.getAllKeys()) {
            StoredState parsed = parseEntry(root.getCompound(key));
            if (parsed != null) {
                target.put(key, parsed);
            }
            // A malformed entry is kept out of the live map but its key is not
            // dropped — callers that sweep the map may want to keep the raw NBT.
        }
    }

    private static @Nullable StoredState parseEntry(CompoundTag entry) {
        if (!entry.contains("v")) {
            return null;
        }
        int version = entry.getInt("v");
        if (entry.contains("n") && entry.getBoolean("n")) {
            return StoredState.ofNull(Math.max(1, version));
        }
        if (entry.contains("d")) {
            return StoredState.of(Math.max(1, version), entry.getByteArray("d"));
        }
        return null;
    }

    /** Produces a defensive copy of {@code slots} as a new map. */
    static Map<String, StoredState> copyOf(Map<String, StoredState> slots) {
        return new LinkedHashMap<>(slots);
    }
}
