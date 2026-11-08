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
        return toTag(slots, null);
    }

    static CompoundTag toTag(Map<String, StoredState> slots, @Nullable Map<String, net.minecraft.nbt.Tag> preservedMalformed) {
        CompoundTag root = new CompoundTag();
        if (preservedMalformed != null) {
            for (Map.Entry<String, net.minecraft.nbt.Tag> e : preservedMalformed.entrySet()) {
                // A parsed slot removed by scope cleanup must not reappear from its raw envelope.
                if (e.getValue() instanceof CompoundTag compound && parseEntry(compound) != null) continue;
                if (!slots.containsKey(e.getKey()) && e.getValue() != null) {
                    root.put(e.getKey(), e.getValue().copy());
                }
            }
        }
        for (Map.Entry<String, StoredState> e : slots.entrySet()) {
            net.minecraft.nbt.Tag raw = preservedMalformed == null ? null : preservedMalformed.get(e.getKey());
            if (raw instanceof CompoundTag original && e.getValue().equals(parseEntry(original))) {
                root.put(e.getKey(), original.copy());
                continue;
            }
            CompoundTag entry = raw instanceof CompoundTag compound ? compound.copy() : new CompoundTag();
            entry.remove("n");
            entry.remove("d");
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
        fromTag(root, target, null);
    }

    static void fromTag(CompoundTag root, Map<String, StoredState> target,
                        @Nullable Map<String, net.minecraft.nbt.Tag> preservedMalformed) {
        if (root == null) {
            return;
        }
        for (String key : root.getAllKeys()) {
            net.minecraft.nbt.Tag rawEntry = root.get(key);
            StoredState parsed = rawEntry instanceof CompoundTag compound ? parseEntry(compound) : null;
            if (preservedMalformed != null && rawEntry != null) preservedMalformed.put(key, rawEntry.copy());
            if (parsed != null) {
                target.put(key, parsed);
            }
        }
    }

    private static @Nullable StoredState parseEntry(CompoundTag entry) {
        if (!entry.contains("v", net.minecraft.nbt.Tag.TAG_INT) || entry.getInt("v") <= 0) {
            return null;
        }
        int version = entry.getInt("v");
        if (entry.contains("n", net.minecraft.nbt.Tag.TAG_BYTE) && entry.getBoolean("n")) {
            return StoredState.ofNull(Math.max(1, version));
        }
        if (entry.contains("d", net.minecraft.nbt.Tag.TAG_BYTE_ARRAY)) {
            return StoredState.of(Math.max(1, version), entry.getByteArray("d"));
        }
        return null;
    }

    /** Produces a defensive copy of {@code slots} as a new map. */
    static Map<String, StoredState> copyOf(Map<String, StoredState> slots) {
        return new LinkedHashMap<>(slots);
    }
}
