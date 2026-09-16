package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C 网络包 - 分片请求未被满足时的确定性回答。
 *
 * <p>此前服务端在校验失败或该玩家已有在途请求时直接 {@code return}，不发任何回包；
 * 客户端只会在收到分片后请求下一片，也没有超时与重试调度，于是该资产会永远留在
 * {@code activeDownloads} 里等待一个不会到来的回包。本包把这个静默失败变成一条
 * 明确结果：客户端据此决定「退避重试」还是「立即失败并诊断」。</p>
 */
public final class SceneAssetChunkStatusS2C implements CustomPacketPayload {
    public static final Type<SceneAssetChunkStatusS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_chunk_status"));

    /**
     * 请求结果。线上按 {@link #wireId()} 传输，不依赖枚举序号，避免日后插入常量改变协议。
     */
    public enum Status {
        /** 该玩家已有在途读取：稍后按 {@code retryAfterMillis} 重试，不消耗重试次数。 */
        BUSY(0),
        /** 请求本身不合法（哈希格式错误等）：立即失败。 */
        REJECTED(1),
        /** 未在清单中授权：立即失败。 */
        NOT_AUTHORIZED(2),
        /** 偏移量越界或未对齐、文件长度与授权不符：立即失败。 */
        OUT_OF_RANGE(3),
        /** 陈旧请求（属于已被取代的传输或旧请求序号）：客户端应忽略。 */
        STALE(4);

        private final int wireId;

        Status(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Status fromWireId(int id) {
            for (Status status : values()) {
                if (status.wireId == id) return status;
            }
            throw new DecoderException("Unknown chunk status: " + id);
        }

        /** 该状态是否应当立即判定传输失败（而非退避重试）。 */
        public boolean isTerminalFailure() {
            return this != BUSY && this != STALE;
        }
    }

    private final String sha256;
    private final long transferId;
    private final long requestId;
    private final long chunkOffset;
    private final Status status;
    private final int retryAfterMillis;

    public SceneAssetChunkStatusS2C(String sha256, long transferId, long requestId,
                                    long chunkOffset, Status status, int retryAfterMillis) {
        this.sha256 = sha256 != null ? sha256.trim().toLowerCase() : "";
        this.transferId = transferId;
        this.requestId = Math.max(0L, requestId);
        this.chunkOffset = chunkOffset;
        this.status = status != null ? status : Status.REJECTED;
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
    }

    public String sha256() { return sha256; }
    public long transferId() { return transferId; }
    public long requestId() { return requestId; }
    public long chunkOffset() { return chunkOffset; }
    public Status status() { return status; }
    public int retryAfterMillis() { return retryAfterMillis; }

    public static final StreamCodec<ByteBuf, SceneAssetChunkStatusS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetChunkStatusS2C decode(ByteBuf buf) {
            String hash = ScenePacketIo.readHash(buf);
            long transferId = buf.readLong();
            long requestId = buf.readLong();
            long offset = buf.readLong();
            Status status = Status.fromWireId(buf.readUnsignedByte());
            int retryAfter = buf.readInt();
            return new SceneAssetChunkStatusS2C(hash, transferId, requestId, offset, status, retryAfter);
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetChunkStatusS2C payload) {
            ScenePacketIo.writeHash(buf, payload.sha256);
            buf.writeLong(payload.transferId);
            buf.writeLong(payload.requestId);
            buf.writeLong(payload.chunkOffset);
            buf.writeByte(payload.status.wireId());
            buf.writeInt(payload.retryAfterMillis());
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
