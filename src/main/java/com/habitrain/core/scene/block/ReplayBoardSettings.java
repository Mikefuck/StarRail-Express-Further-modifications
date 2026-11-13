package com.habitrain.core.scene.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Per-table display placement. Distances are world blocks, offsets follow the table's facing. */
public record ReplayBoardSettings(float mapSize, float height, float offsetX, float offsetZ, float rotation) {
    public static final float DEFAULT_MAP_SIZE = 6.25f;
    public static final float MIN_SIZE = 1, MAX_SIZE = 32, MAX_HEIGHT = 16, MAX_OFFSET = 16;
    public static final ReplayBoardSettings DEFAULT = new ReplayBoardSettings(DEFAULT_MAP_SIZE, 0.25f, 0, 0, 0);

    public ReplayBoardSettings {
        if (!inRange(mapSize, MIN_SIZE, MAX_SIZE) || !inRange(height, 0, MAX_HEIGHT)
                || !inRange(offsetX, -MAX_OFFSET, MAX_OFFSET) || !inRange(offsetZ, -MAX_OFFSET, MAX_OFFSET)
                || !inRange(rotation, -180, 180)) throw new IllegalArgumentException("Invalid replay table settings");
    }

    public float scale() { return mapSize / DEFAULT_MAP_SIZE; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("MapSize", mapSize);
        tag.putFloat("Height", height);
        tag.putFloat("OffsetX", offsetX);
        tag.putFloat("OffsetZ", offsetZ);
        tag.putFloat("Rotation", rotation);
        return tag;
    }

    /** Old markers have no settings; damaged individual values fall back without hiding the table. */
    public static ReplayBoardSettings load(CompoundTag tag) {
        return new ReplayBoardSettings(read(tag, "MapSize", DEFAULT.mapSize, MIN_SIZE, MAX_SIZE),
                read(tag, "Height", DEFAULT.height, 0, MAX_HEIGHT),
                read(tag, "OffsetX", 0, -MAX_OFFSET, MAX_OFFSET),
                read(tag, "OffsetZ", 0, -MAX_OFFSET, MAX_OFFSET), read(tag, "Rotation", 0, -180, 180));
    }

    private static float read(CompoundTag tag, String key, float fallback, float min, float max) {
        if (!tag.contains(key, Tag.TAG_ANY_NUMERIC)) return fallback;
        float value = tag.getFloat(key);
        return inRange(value, min, max) ? value : fallback;
    }

    private static boolean inRange(float value, float min, float max) {
        return Float.isFinite(value) && value >= min && value <= max;
    }
}
