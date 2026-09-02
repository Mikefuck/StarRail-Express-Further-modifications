package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/**
 * S2C 网络包 - 场景资产分片数据下发（携带分片偏移、总大小、数据与 CRC32）。
 */
public final class SceneAssetChunkS2C implements CustomPacketPayload {
    public static final Type<SceneAssetChunkS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_chunk"));

    private final String sha256;
    private final long chunkOffset;
    private final long totalSize;
    private final byte[] data;
    private final long crc32;

    public SceneAssetChunkS2C(String sha256, long chunkOffset, long totalSize, byte[] data, long crc32) {
        this.sha256 = sha256 != null ? sha256.trim().toLowerCase() : "";
        this.chunkOffset = chunkOffset;
        this.totalSize = totalSize;
        this.data = data != null ? data : new byte[0];
        this.crc32 = crc32;
    }

    public String sha256() { return sha256; }
    public long chunkOffset() { return chunkOffset; }
    public long totalSize() { return totalSize; }
    public byte[] data() { return data; }
    public long crc32() { return crc32; }

    public static final StreamCodec<ByteBuf, SceneAssetChunkS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetChunkS2C decode(ByteBuf buf) {
            String hash = readString(buf);
            long offset = buf.readLong();
            long total = buf.readLong();
            int len = buf.readInt();
            if (len < 0 || len > 131072) {
                throw new DecoderException("Invalid chunk length: " + len);
            }
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            long crc = buf.readLong();
            return new SceneAssetChunkS2C(hash, offset, total, bytes, crc);
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetChunkS2C payload) {
            writeString(buf, payload.sha256);
            buf.writeLong(payload.chunkOffset);
            buf.writeLong(payload.totalSize);
            buf.writeInt(payload.data.length);
            if (payload.data.length > 0) {
                buf.writeBytes(payload.data);
            }
            buf.writeLong(payload.crc32);
        }
    };

    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        if (len <= 0) return "";
        if (len > 128) throw new DecoderException("Hash too long: " + len);
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 128) throw new EncoderException("Hash too long: " + bytes.length);
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
