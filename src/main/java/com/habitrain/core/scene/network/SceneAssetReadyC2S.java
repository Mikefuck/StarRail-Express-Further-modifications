package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/** C2S：客户端完成资产校验、解码和 GPU 网格预编译后的会话回执。 */
public record SceneAssetReadyC2S(long sessionId, String sha256, boolean success)
        implements CustomPacketPayload {
    public static final Type<SceneAssetReadyC2S> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_ready"));

    public SceneAssetReadyC2S {
        sha256 = sha256 == null ? "" : sha256.trim().toLowerCase();
    }

    public static final StreamCodec<ByteBuf, SceneAssetReadyC2S> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetReadyC2S decode(ByteBuf buf) {
            long sessionId = buf.readLong();
            int length = buf.readInt();
            if (length < 0 || length > 128) throw new DecoderException("Invalid hash length: " + length);
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            return new SceneAssetReadyC2S(sessionId, new String(bytes, StandardCharsets.UTF_8), buf.readBoolean());
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetReadyC2S payload) {
            byte[] bytes = payload.sha256.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 128) throw new EncoderException("Hash too long: " + bytes.length);
            buf.writeLong(payload.sessionId);
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
            buf.writeBoolean(payload.success);
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
