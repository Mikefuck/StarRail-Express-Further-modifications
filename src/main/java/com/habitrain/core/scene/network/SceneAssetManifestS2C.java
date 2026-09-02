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

/**
 * S2C 网络包 - 场景资产清单下发（加入服务器/开局/资产发布时下发给客户端以决定是否需要下载）。
 */
public final class SceneAssetManifestS2C implements CustomPacketPayload {
    public static final Type<SceneAssetManifestS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_manifest"));

    private final String mapKey;
    private final SceneAssetDescriptor descriptor;

    public SceneAssetManifestS2C(String mapKey, SceneAssetDescriptor descriptor) {
        this.mapKey = mapKey != null ? mapKey : "";
        this.descriptor = descriptor != null ? descriptor : SceneAssetDescriptor.EMPTY;
    }

    public String mapKey() { return mapKey; }
    public SceneAssetDescriptor descriptor() { return descriptor; }

    public static final StreamCodec<ByteBuf, SceneAssetManifestS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetManifestS2C decode(ByteBuf buf) {
            String map = readString(buf);
            String hash = readString(buf);
            long uncomp = buf.readLong();
            long comp = buf.readLong();
            int sections = buf.readInt();
            int version = buf.readInt();
            String fp = readString(buf);
            long created = buf.readLong();
            SceneAssetDescriptor desc = new SceneAssetDescriptor(hash, uncomp, comp, sections, version, fp, created);
            return new SceneAssetManifestS2C(map, desc);
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetManifestS2C payload) {
            writeString(buf, payload.mapKey);
            writeString(buf, payload.descriptor.sha256());
            buf.writeLong(payload.descriptor.uncompressedSize());
            buf.writeLong(payload.descriptor.compressedSize());
            buf.writeInt(payload.descriptor.sectionCount());
            buf.writeInt(payload.descriptor.dataVersion());
            writeString(buf, payload.descriptor.fingerprint());
            buf.writeLong(payload.descriptor.createdAt());
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
