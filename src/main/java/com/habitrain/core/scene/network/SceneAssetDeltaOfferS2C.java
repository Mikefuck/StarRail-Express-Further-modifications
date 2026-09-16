package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C：对增量探测的确定回答——"当前这版资产有一枚从 base 到它的补丁"或"没有"。
 *
 * <p>没有"没有"这条确定回答，客户端就只能靠超时等——那会把一次正常请求变成一次等待。
 * 补丁本身不走这个包，它和资产一样是内容寻址文件，客户端拿到 patchHash 后用既有的分片链路下载。</p>
 */
public record SceneAssetDeltaOfferS2C(String targetSha256, Status status,
                                      String baseSha256, String patchSha256, long patchBytes)
        implements CustomPacketPayload {

    public enum Status {
        OK(0),
        NO_DELTA(1);

        private final int wireId;

        Status(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Status fromWire(int wireId) {
            for (Status status : values()) {
                if (status.wireId == wireId) return status;
            }
            throw new DecoderException("Unknown scene delta status: " + wireId);
        }
    }

    public static final Type<SceneAssetDeltaOfferS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_delta_offer"));

    public SceneAssetDeltaOfferS2C {
        targetSha256 = targetSha256 == null ? "" : targetSha256;
        status = status == null ? Status.NO_DELTA : status;
        baseSha256 = baseSha256 == null ? "" : baseSha256;
        patchSha256 = patchSha256 == null ? "" : patchSha256;
    }

    public static SceneAssetDeltaOfferS2C none(String targetSha256) {
        return new SceneAssetDeltaOfferS2C(targetSha256, Status.NO_DELTA, "", "", 0L);
    }

    public boolean hasDelta() {
        return status == Status.OK && baseSha256.matches("[0-9a-fA-F]{64}")
                && patchSha256.matches("[0-9a-fA-F]{64}") && patchBytes > 0
                && patchBytes <= com.habitrain.core.scene.asset.SceneAssetCodec.MAX_COMPRESSED_BYTES;
    }

    public static final StreamCodec<ByteBuf, SceneAssetDeltaOfferS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetDeltaOfferS2C decode(ByteBuf buf) {
            String target = ScenePacketIo.readHash(buf);
            Status status = Status.fromWire(buf.readByte() & 0xFF);
            String base = ScenePacketIo.readHash(buf);
            String patch = ScenePacketIo.readHash(buf);
            long bytes = buf.readLong();
            if (bytes < 0) throw new DecoderException("Negative scene delta size: " + bytes);
            return new SceneAssetDeltaOfferS2C(target, status, base, patch, bytes);
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetDeltaOfferS2C payload) {
            ScenePacketIo.writeHash(buf, payload.targetSha256);
            buf.writeByte(payload.status.wireId());
            ScenePacketIo.writeHash(buf, payload.baseSha256);
            ScenePacketIo.writeHash(buf, payload.patchSha256);
            buf.writeLong(payload.patchBytes);
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
