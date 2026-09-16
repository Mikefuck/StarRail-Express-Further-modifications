package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C 网络包 - 场景资产清单下发（加入服务器/开局/资产发布时下发给客户端以决定是否需要下载）。
 */
public final class SceneAssetManifestS2C implements CustomPacketPayload {
    public static final Type<SceneAssetManifestS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_manifest"));

    private final String mapKey;
    private final SceneAssetDescriptor descriptor;
    private final int protocolVersion;

    public SceneAssetManifestS2C(String mapKey, SceneAssetDescriptor descriptor) {
        this(mapKey, descriptor, SceneProtocol.VERSION);
    }

    public SceneAssetManifestS2C(String mapKey, SceneAssetDescriptor descriptor, int protocolVersion) {
        this.mapKey = mapKey != null ? mapKey : "";
        this.descriptor = descriptor != null ? descriptor : SceneAssetDescriptor.EMPTY;
        this.protocolVersion = protocolVersion;
    }

    public String mapKey() { return mapKey; }
    public SceneAssetDescriptor descriptor() { return descriptor; }
    public int protocolVersion() { return protocolVersion; }

    public static final StreamCodec<ByteBuf, SceneAssetManifestS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetManifestS2C decode(ByteBuf buf) {
            String map = ScenePacketIo.readId(buf);
            int protocol = buf.readInt();
            String hash = ScenePacketIo.readHash(buf);
            long uncomp = buf.readLong();
            long comp = buf.readLong();
            int sections = buf.readInt();
            int version = buf.readInt();
            String fp = ScenePacketIo.readId(buf);
            long created = buf.readLong();
            SceneAssetDescriptor desc = new SceneAssetDescriptor(hash, uncomp, comp, sections, version, fp, created);
            return new SceneAssetManifestS2C(map, desc, protocol);
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetManifestS2C payload) {
            ScenePacketIo.writeId(buf, payload.mapKey);
            buf.writeInt(payload.protocolVersion);
            ScenePacketIo.writeHash(buf, payload.descriptor.sha256());
            buf.writeLong(payload.descriptor.uncompressedSize());
            buf.writeLong(payload.descriptor.compressedSize());
            buf.writeInt(payload.descriptor.sectionCount());
            buf.writeInt(payload.descriptor.dataVersion());
            ScenePacketIo.writeId(buf, payload.descriptor.fingerprint());
            buf.writeLong(payload.descriptor.createdAt());
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
