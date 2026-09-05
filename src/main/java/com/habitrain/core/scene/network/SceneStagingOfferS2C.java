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

/** Sends one private diagnostic staging asset to its initiating administrator. */
public record SceneStagingOfferS2C(String stagingId, String mapKey,
                                   SceneAssetDescriptor descriptor, long expiresAt)
        implements CustomPacketPayload {
    public static final Type<SceneStagingOfferS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_staging_offer"));

    public SceneStagingOfferS2C {
        stagingId = stagingId == null ? "" : stagingId;
        mapKey = mapKey == null ? "" : mapKey;
        descriptor = descriptor == null ? SceneAssetDescriptor.EMPTY : descriptor;
    }

    public static final StreamCodec<ByteBuf, SceneStagingOfferS2C> CODEC = new StreamCodec<>() {
        @Override public SceneStagingOfferS2C decode(ByteBuf buf) {
            String stagingId = readString(buf, 64);
            String mapKey = readString(buf, 32_767);
            String hash = readString(buf, 128);
            long uncompressed = buf.readLong();
            long compressed = buf.readLong();
            int sections = buf.readInt();
            int dataVersion = buf.readInt();
            String fingerprint = readString(buf, 32_767);
            long createdAt = buf.readLong();
            long expiresAt = buf.readLong();
            return new SceneStagingOfferS2C(stagingId, mapKey,
                    new SceneAssetDescriptor(hash, uncompressed, compressed, sections,
                            dataVersion, fingerprint, createdAt), expiresAt);
        }

        @Override public void encode(ByteBuf buf, SceneStagingOfferS2C payload) {
            writeString(buf, payload.stagingId, 64);
            writeString(buf, payload.mapKey, 32_767);
            SceneAssetDescriptor descriptor = payload.descriptor;
            writeString(buf, descriptor.sha256(), 128);
            buf.writeLong(descriptor.uncompressedSize());
            buf.writeLong(descriptor.compressedSize());
            buf.writeInt(descriptor.sectionCount());
            buf.writeInt(descriptor.dataVersion());
            writeString(buf, descriptor.fingerprint(), 32_767);
            buf.writeLong(descriptor.createdAt());
            buf.writeLong(payload.expiresAt);
        }
    };

    private static String readString(ByteBuf buf, int max) {
        int len = buf.readInt();
        if (len < 0 || len > max) throw new DecoderException("String too long: " + len);
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String value, int max) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > max) throw new EncoderException("String too long: " + bytes.length);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void register() { PayloadTypeRegistry.playS2C().register(TYPE, CODEC); }
}
