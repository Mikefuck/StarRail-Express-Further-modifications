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
 * C2S 网络包 - 客户端本地缓存未命中时按分片偏移量向服务端请求数据。
 */
public final class SceneAssetChunkRequestC2S implements CustomPacketPayload {
    public static final Type<SceneAssetChunkRequestC2S> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_chunk_req"));

    public static final int CHUNK_SIZE = 65536; // 64 KiB

    private final String sha256;
    private final long chunkOffset;
    private final int chunkSize;

    public SceneAssetChunkRequestC2S(String sha256, long chunkOffset, int chunkSize) {
        this.sha256 = sha256 != null ? sha256.trim().toLowerCase() : "";
        this.chunkOffset = Math.max(0L, chunkOffset);
        this.chunkSize = Math.max(1, Math.min(CHUNK_SIZE, chunkSize));
    }

    public String sha256() { return sha256; }
    public long chunkOffset() { return chunkOffset; }
    public int chunkSize() { return chunkSize; }

    public static final StreamCodec<ByteBuf, SceneAssetChunkRequestC2S> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetChunkRequestC2S decode(ByteBuf buf) {
            String hash = readString(buf);
            long offset = buf.readLong();
            int size = buf.readInt();
            return new SceneAssetChunkRequestC2S(hash, offset, size);
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetChunkRequestC2S payload) {
            writeString(buf, payload.sha256);
            buf.writeLong(payload.chunkOffset);
            buf.writeInt(payload.chunkSize);
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
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
