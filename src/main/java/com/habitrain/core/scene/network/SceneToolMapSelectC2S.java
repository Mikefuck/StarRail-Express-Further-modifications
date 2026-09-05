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
public record SceneToolMapSelectC2S(String mapKey, String backgroundId) implements CustomPacketPayload {
    public static final Type<SceneToolMapSelectC2S> TYPE =
            new Type<>(HabiTrainCore.id("scene_tool_map_select"));
    private static final int MAX_MAP_KEY_BYTES = 1024;

    public SceneToolMapSelectC2S(String mapKey) {
        this(mapKey, com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID);
    }

    public SceneToolMapSelectC2S {
        mapKey = mapKey == null ? "" : mapKey.trim();
        backgroundId = backgroundId == null || backgroundId.isBlank()
                ? com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID
                : com.habitrain.core.scene.model.SceneBackgroundKey.normalizeBackgroundId(backgroundId);
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
            String map = new String(bytes, StandardCharsets.UTF_8);

            String bgId = com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID;
            if (buf.isReadable(4)) {
                int bgLen = buf.readInt();
                if (bgLen >= 0 && bgLen <= MAX_MAP_KEY_BYTES && buf.isReadable(bgLen)) {
                    byte[] bgBytes = new byte[bgLen];
                    buf.readBytes(bgBytes);
                    bgId = new String(bgBytes, StandardCharsets.UTF_8);
                }
            }
            return new SceneToolMapSelectC2S(map, bgId);
        }

        @Override
        public void encode(ByteBuf buf, SceneToolMapSelectC2S payload) {
            byte[] bytes = payload.mapKey.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_MAP_KEY_BYTES) {
                throw new EncoderException("Scene map key too long: " + bytes.length);
            }
            buf.writeInt(bytes.length);
            buf.writeBytes(bytes);

            byte[] bgBytes = payload.backgroundId.getBytes(StandardCharsets.UTF_8);
            if (bgBytes.length > MAX_MAP_KEY_BYTES) {
                throw new EncoderException("Scene background id too long: " + bgBytes.length);
            }
            buf.writeInt(bgBytes.length);
            buf.writeBytes(bgBytes);
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
