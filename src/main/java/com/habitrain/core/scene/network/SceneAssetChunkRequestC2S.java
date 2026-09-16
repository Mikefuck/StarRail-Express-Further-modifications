package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S 网络包 - 客户端本地缓存未命中时按分片偏移量向服务端请求数据。
 *
 * <p>{@code transferId} 标识客户端的一次资产传输（重连或重置后改变），
 * {@code requestId} 是同一次传输内单调递增的请求序号。服务端据此识别重复请求
 * 与陈旧请求，从而给出确定性回答而不是静默丢弃。</p>
 */
public final class SceneAssetChunkRequestC2S implements CustomPacketPayload {
    public static final Type<SceneAssetChunkRequestC2S> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_chunk_req"));

    public static final int CHUNK_SIZE = 65536; // 64 KiB

    private final String sha256;
    private final long transferId;
    private final long requestId;
    private final long chunkOffset;
    private final int chunkSize;

    public SceneAssetChunkRequestC2S(String sha256, long transferId, long requestId,
                                     long chunkOffset, int chunkSize) {
        this.sha256 = sha256 != null ? sha256.trim().toLowerCase() : "";
        this.transferId = transferId;
        this.requestId = Math.max(0L, requestId);
        this.chunkOffset = Math.max(0L, chunkOffset);
        this.chunkSize = Math.max(1, Math.min(CHUNK_SIZE, chunkSize));
    }

    public String sha256() { return sha256; }
    public long transferId() { return transferId; }
    public long requestId() { return requestId; }
    public long chunkOffset() { return chunkOffset; }
    public int chunkSize() { return chunkSize; }

    public static final StreamCodec<ByteBuf, SceneAssetChunkRequestC2S> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetChunkRequestC2S decode(ByteBuf buf) {
            String hash = ScenePacketIo.readHash(buf);
            long transferId = buf.readLong();
            long requestId = buf.readLong();
            long offset = buf.readLong();
            int size = buf.readInt();
            return new SceneAssetChunkRequestC2S(hash, transferId, requestId, offset, size);
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetChunkRequestC2S payload) {
            ScenePacketIo.writeHash(buf, payload.sha256);
            buf.writeLong(payload.transferId);
            buf.writeLong(payload.requestId);
            buf.writeLong(payload.chunkOffset);
            buf.writeInt(payload.chunkSize);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
