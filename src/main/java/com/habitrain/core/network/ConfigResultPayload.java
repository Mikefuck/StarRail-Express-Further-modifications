package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.config.ConfigSaveResult;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.nio.charset.StandardCharsets;

/** Matched request result; a broadcast alone is never a save acknowledgement. */
public record ConfigResultPayload(String requestId, long revision, ConfigSaveResult.Status status,
                                  String message, String authoritativeJson) implements CustomPacketPayload {
    public ConfigResultPayload {
        if (message == null) message = "配置提交失败";
        if (message.codePointCount(0, message.length()) > 800) message = message.substring(0, message.offsetByCodePoints(0, 800)) + "…";
    }
    public static final Type<ConfigResultPayload> TYPE = new Type<>(HabiTrainCore.id("config_result_v1"));
    public static final StreamCodec<ByteBuf, ConfigResultPayload> CODEC = new StreamCodec<>() {
        public ConfigResultPayload decode(ByteBuf buf) {
            return new ConfigResultPayload(read(buf, 80), buf.readLong(), ConfigSaveResult.Status.valueOf(read(buf, 32)), read(buf, 4096), read(buf, com.habitrain.core.config.ConfigPatch.MAX_SYNC_JSON_BYTES));
        }
        public void encode(ByteBuf buf, ConfigResultPayload value) {
            write(buf, value.requestId, 80); buf.writeLong(value.revision); write(buf, value.status.name(), 32);
            write(buf, value.message, 4096); write(buf, value.authoritativeJson, com.habitrain.core.config.ConfigPatch.MAX_SYNC_JSON_BYTES);
        }
    };
    private static String read(ByteBuf buf, int max) {
        int size = buf.readInt(); if (size < 0 || size > max || size > buf.readableBytes()) throw new io.netty.handler.codec.DecoderException("配置确认字段过大或截断");
        byte[] bytes = new byte[size]; buf.readBytes(bytes); return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void write(ByteBuf buf, String value, int max) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); if (bytes.length > max) throw new io.netty.handler.codec.EncoderException("配置确认字段过大");
        buf.writeInt(bytes.length); buf.writeBytes(bytes);
    }
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void register() { PayloadTypeRegistry.playS2C().register(TYPE, CODEC); }
}
