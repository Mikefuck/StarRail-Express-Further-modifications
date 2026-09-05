package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/** Administrator-client bake result for a private staging asset. */
public record SceneStagingReportC2S(String stagingId, String mapKey, String assetHash,
                                    boolean success, String registryFingerprint,
                                    String clientEnvironment) implements CustomPacketPayload {
    public static final Type<SceneStagingReportC2S> TYPE =
            new Type<>(HabiTrainCore.id("scene_staging_report"));

    public SceneStagingReportC2S {
        stagingId = stagingId == null ? "" : stagingId;
        mapKey = mapKey == null ? "" : mapKey;
        assetHash = assetHash == null ? "" : assetHash;
        registryFingerprint = registryFingerprint == null ? "" : registryFingerprint;
        clientEnvironment = clientEnvironment == null ? "" : clientEnvironment;
    }

    public static final StreamCodec<ByteBuf, SceneStagingReportC2S> CODEC = new StreamCodec<>() {
        @Override public SceneStagingReportC2S decode(ByteBuf buf) {
            return new SceneStagingReportC2S(readString(buf, 64), readString(buf, 32_767),
                    readString(buf, 128), buf.readBoolean(), readString(buf, 32_767),
                    readString(buf, 1024));
        }

        @Override public void encode(ByteBuf buf, SceneStagingReportC2S payload) {
            writeString(buf, payload.stagingId, 64);
            writeString(buf, payload.mapKey, 32_767);
            writeString(buf, payload.assetHash, 128);
            buf.writeBoolean(payload.success);
            writeString(buf, payload.registryFingerprint, 32_767);
            writeString(buf, payload.clientEnvironment, 1024);
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
    public static void register() { PayloadTypeRegistry.playC2S().register(TYPE, CODEC); }
}
