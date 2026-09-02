package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.scene.model.SceneBounds;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/**
 * S2C 网络包 - 管理员 Shift+右键选点 A/B 或清除时同步选区状态。
 */
public final class SceneSelectionStateS2C implements CustomPacketPayload {
    public static final Type<SceneSelectionStateS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_selection_state"));

    private final String dimension;
    private final String mapKey;
    private final boolean hasSelection;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final int sectionCount;
    private final long blockCount;

    public SceneSelectionStateS2C(String dimension, String mapKey, boolean hasSelection,
                                  int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                  int sectionCount, long blockCount) {
        this.dimension = dimension != null ? dimension : "";
        this.mapKey = mapKey != null ? mapKey : "";
        this.hasSelection = hasSelection;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.sectionCount = sectionCount;
        this.blockCount = blockCount;
    }

    public static SceneSelectionStateS2C cleared(String dimension, String mapKey) {
        return new SceneSelectionStateS2C(dimension, mapKey, false, 0, 0, 0, 0, 0, 0, 0, 0L);
    }

    public static SceneSelectionStateS2C fromBounds(String dimension, String mapKey, SceneBounds bounds, long blockCount) {
        if (bounds == null || bounds.isEmpty()) {
            return cleared(dimension, mapKey);
        }
        return new SceneSelectionStateS2C(
                dimension, mapKey, true,
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                bounds.totalSections(), blockCount
        );
    }

    public String dimension() { return dimension; }
    public String mapKey() { return mapKey; }
    public boolean hasSelection() { return hasSelection; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }
    public int sectionCount() { return sectionCount; }
    public long blockCount() { return blockCount; }

    public SceneBounds toBounds() {
        return hasSelection ? new SceneBounds(minX, minY, minZ, maxX, maxY, maxZ) : SceneBounds.EMPTY;
    }

    public static final StreamCodec<ByteBuf, SceneSelectionStateS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneSelectionStateS2C decode(ByteBuf buf) {
            String dim = readString(buf);
            String map = readString(buf);
            boolean has = buf.readBoolean();
            int minX = buf.readInt();
            int minY = buf.readInt();
            int minZ = buf.readInt();
            int maxX = buf.readInt();
            int maxY = buf.readInt();
            int maxZ = buf.readInt();
            int sections = buf.readInt();
            long blocks = buf.readLong();
            return new SceneSelectionStateS2C(dim, map, has, minX, minY, minZ, maxX, maxY, maxZ, sections, blocks);
        }

        @Override
        public void encode(ByteBuf buf, SceneSelectionStateS2C payload) {
            writeString(buf, payload.dimension);
            writeString(buf, payload.mapKey);
            buf.writeBoolean(payload.hasSelection);
            buf.writeInt(payload.minX);
            buf.writeInt(payload.minY);
            buf.writeInt(payload.minZ);
            buf.writeInt(payload.maxX);
            buf.writeInt(payload.maxY);
            buf.writeInt(payload.maxZ);
            buf.writeInt(payload.sectionCount);
            buf.writeLong(payload.blockCount);
        }
    };

    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        if (len <= 0) return "";
        if (len > 32767) throw new DecoderException("String too long: " + len);
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32767) throw new EncoderException("String too long: " + bytes.length);
        buf.writeInt(bytes.length);
        if (bytes.length > 0) buf.writeBytes(bytes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
