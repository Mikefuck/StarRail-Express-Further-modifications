package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.scene.model.SceneBackgroundKey;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/** Authoritative map/background target currently owned by the administrator scene tool session. */
public record SceneToolTargetStateS2C(String mapKey, String backgroundId) implements CustomPacketPayload {
    public static final Type<SceneToolTargetStateS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_tool_target_state"));
    private static final int MAX_STRING_BYTES = 1024;

    public SceneToolTargetStateS2C {
        mapKey = mapKey == null ? "" : mapKey.trim();
        backgroundId = SceneBackgroundKey.normalizeBackgroundId(backgroundId);
    }

    public static final StreamCodec<ByteBuf, SceneToolTargetStateS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneToolTargetStateS2C decode(ByteBuf buf) {
            return new SceneToolTargetStateS2C(readString(buf), readString(buf));
        }

        @Override
        public void encode(ByteBuf buf, SceneToolTargetStateS2C payload) {
            writeString(buf, payload.mapKey);
            writeString(buf, payload.backgroundId);
        }
    };

    private static String readString(ByteBuf buf) {
        int length = buf.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || !buf.isReadable(length)) {
            throw new DecoderException("Invalid scene tool target string length: " + length);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = (value != null ? value : "").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new EncoderException("Scene tool target string too long: " + bytes.length);
        }
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
