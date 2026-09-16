package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C 网络包 - 场景资产分片数据下发（携带分片偏移、总大小、数据与 CRC32）。
 *
 * <p>{@code transferId}/{@code requestId} 原样回显客户端请求中的值，使客户端能区分
 * 「本次请求的正常回包」、「上一次请求的迟到重复包」与「陈旧传输的残包」。缺少
 * 这两个字段时，迟到的重复包会落到偏移量相等检查上并触发整次下载中止。</p>
 */
public final class SceneAssetChunkS2C implements CustomPacketPayload {
    public static final Type<SceneAssetChunkS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_chunk"));

    /** 单片数据上限：64 KiB 分片留一倍余量。 */
    public static final int MAX_CHUNK_BYTES = 131072;

    private final String sha256;
    private final long transferId;
    private final long requestId;
    private final long chunkOffset;
    private final long totalSize;
    private final byte[] data;
    private final long crc32;

    public SceneAssetChunkS2C(String sha256, long transferId, long requestId, long chunkOffset,
                              long totalSize, byte[] data, long crc32) {
        this.sha256 = sha256 != null ? sha256.trim().toLowerCase() : "";
        this.transferId = transferId;
        this.requestId = Math.max(0L, requestId);
        this.chunkOffset = chunkOffset;
        this.totalSize = totalSize;
        this.data = data != null ? data : new byte[0];
        this.crc32 = crc32;
    }

    public String sha256() { return sha256; }
    public long transferId() { return transferId; }
    public long requestId() { return requestId; }
    public long chunkOffset() { return chunkOffset; }
    public long totalSize() { return totalSize; }
    public byte[] data() { return data; }
    public long crc32() { return crc32; }

    public static final StreamCodec<ByteBuf, SceneAssetChunkS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetChunkS2C decode(ByteBuf buf) {
            String hash = ScenePacketIo.readHash(buf);
            long transferId = buf.readLong();
            long requestId = buf.readLong();
            long offset = buf.readLong();
            long total = buf.readLong();
            int len = buf.readInt();
            if (len < 0 || len > MAX_CHUNK_BYTES) {
                throw new DecoderException("Invalid chunk length: " + len);
            }
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            long crc = buf.readLong();
            return new SceneAssetChunkS2C(hash, transferId, requestId, offset, total, bytes, crc);
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetChunkS2C payload) {
            ScenePacketIo.writeHash(buf, payload.sha256);
            buf.writeLong(payload.transferId);
            buf.writeLong(payload.requestId);
            buf.writeLong(payload.chunkOffset);
            buf.writeLong(payload.totalSize);
            buf.writeInt(payload.data.length);
            if (payload.data.length > 0) {
                buf.writeBytes(payload.data);
            }
            buf.writeLong(payload.crc32);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
