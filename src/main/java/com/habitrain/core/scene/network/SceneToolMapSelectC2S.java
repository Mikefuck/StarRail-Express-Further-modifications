package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/** Immediately synchronizes the map selected in the administrator scene editor. */
public record SceneToolMapSelectC2S(String mapKey) implements CustomPacketPayload {
    public static final Type<SceneToolMapSelectC2S> TYPE =
            new Type<>(HabiTrainCore.id("scene_tool_map_select"));
    private static final int MAX_MAP_KEY_BYTES = 1024;

    public SceneToolMapSelectC2S {
        mapKey = mapKey == null ? "" : mapKey.trim();
    }

    public static final StreamCodec<ByteBuf, SceneToolMapSelectC2S> CODEC = new StreamCodec<>() {
        @Override
        public SceneToolMapSelectC2S decode(ByteBuf buf) {
            int length = buf.readInt();
            if (length < 0 || length > MAX_MAP_KEY_BYTES) {
                throw new DecoderException("Invalid scene map key length: " + length);
            }
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            return new SceneToolMapSelectC2S(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public void encode(ByteBuf buf, SceneToolMapSelectC2S payload) {
            byte[] bytes = payload.mapKey.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_MAP_KEY_BYTES) {
                throw new EncoderException("Scene map key too long: " + bytes.length);
            }
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);
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
