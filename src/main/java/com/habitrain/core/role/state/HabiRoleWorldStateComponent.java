package com.habitrain.core.role.state;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.Component;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * Fixed CCA container for {@link com.habitrain.core.api.role.v2.state.StateScope#WORLD}
 * persistent role state (fix-doc §10.2). Attached to the world, so each
 * dimension owns its own slot bag — no two worlds share a static map. Slots are
 * opaque {@code key -> StoredState} entries preserved through NBT.
 */
public final class HabiRoleWorldStateComponent implements Component, StateSlotBag {

    public static final ComponentKey<HabiRoleWorldStateComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("role_state_world"),
                    HabiRoleWorldStateComponent.class);

    private final Level level;
    private final Map<String, StoredState> slots = new HashMap<>();

    public HabiRoleWorldStateComponent(Level level) {
        this.level = level;
    }

    public Level getLevel() {
        return level;
    }

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

    @Override
    public void writeToNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.put("Slots", RoleStateNbtUtil.toTag(slots));
    }

    @Override
    public void readFromNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        slots.clear();
        if (tag.contains("Slots")) {
            RoleStateNbtUtil.fromTag(tag.getCompound("Slots"), slots);
        }
    }
}
