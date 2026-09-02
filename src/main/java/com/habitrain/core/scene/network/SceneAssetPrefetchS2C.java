package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/** S2C：地图投票加载页开始后，要求客户端预取并编译获胜地图的场景资产。 */
public record SceneAssetPrefetchS2C(long sessionId, String mapKey,
                                    SceneAssetDescriptor descriptor) implements CustomPacketPayload {
    public static final Type<SceneAssetPrefetchS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_prefetch"));

    public SceneAssetPrefetchS2C {
        mapKey = mapKey == null ? "" : mapKey;
        descriptor = descriptor == null ? SceneAssetDescriptor.EMPTY : descriptor;
    }

    public static final StreamCodec<ByteBuf, SceneAssetPrefetchS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetPrefetchS2C decode(ByteBuf buf) {
            long sessionId = buf.readLong();
            String map = readString(buf, 32_767);
            String hash = readString(buf, 128);
            long uncomp = buf.readLong();
            long comp = buf.readLong();
            int sections = buf.readInt();
            int version = buf.readInt();
            String fingerprint = readString(buf, 32_767);
            long created = buf.readLong();
            return new SceneAssetPrefetchS2C(sessionId, map,
                    new SceneAssetDescriptor(hash, uncomp, comp, sections, version, fingerprint, created));
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetPrefetchS2C payload) {
            buf.writeLong(payload.sessionId);
            writeString(buf, payload.mapKey, 32_767);
            SceneAssetDescriptor descriptor = payload.descriptor;
            writeString(buf, descriptor.sha256(), 128);
            buf.writeLong(descriptor.uncompressedSize());
            buf.writeLong(descriptor.compressedSize());
            buf.writeInt(descriptor.sectionCount());
            buf.writeInt(descriptor.dataVersion());
            writeString(buf, descriptor.fingerprint(), 32_767);
            buf.writeLong(descriptor.createdAt());
        }
    };

    private static String readString(ByteBuf buf, int maxLength) {
        int length = buf.readInt();
        if (length < 0 || length > maxLength) throw new DecoderException("Invalid string length: " + length);
        if (length == 0) return "";
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String value, int maxLength) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxLength) throw new EncoderException("String too long: " + bytes.length);
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
