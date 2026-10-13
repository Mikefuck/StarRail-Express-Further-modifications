package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A rule update requests a fresh report even when the shader name is unchanged. */
public record ShaderReportRequestPayload(long ruleRevision) implements CustomPacketPayload {
    public static final Type<ShaderReportRequestPayload> TYPE = new Type<>(HabiTrainCore.id("shader_report_request"));
    public static final StreamCodec<ByteBuf, ShaderReportRequestPayload> CODEC = new StreamCodec<>() {
        public ShaderReportRequestPayload decode(ByteBuf buf) { return new ShaderReportRequestPayload(buf.readLong()); }
        public void encode(ByteBuf buf, ShaderReportRequestPayload value) { buf.writeLong(value.ruleRevision); }
    };
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void register() { PayloadTypeRegistry.playS2C().register(TYPE, CODEC); }
}
