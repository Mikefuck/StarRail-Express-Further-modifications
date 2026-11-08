package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import io.netty.handler.codec.DecoderException;

/**
 * Multiplex S2C payload for every v2 {@link com.habitrain.core.api.role.v2.action.RoleActionSpec}.
 *
 * <p>Used both as the result/correction of a client-predicted C2S action
 * ({@code sequence} echoes the request, {@code push} is {@code false}) and as
 * a server-initiated push ({@code push} is {@code true}). The client routes
 * by {@code push}: a response resolves the pending request for that sequence;
 * a push is delivered to push listeners instead (fix-doc §12.5).
 */
public record RoleActionS2CPayload(
        ResourceLocation actionId,
        int sequence,
        boolean ok,
        String reasonKey,
        byte[] payload,
        boolean push) implements CustomPacketPayload {

    public static final Type<RoleActionS2CPayload> TYPE =
            new Type<>(HabiTrainCore.id("role_action_s2c"));

    private static final int MAX_PAYLOAD_BYTES = com.habitrain.core.api.role.v2.action.RoleActionSpec.MAX_PAYLOAD_BYTES;

    public static final StreamCodec<FriendlyByteBuf, RoleActionS2CPayload> CODEC =
            StreamCodec.ofMember(RoleActionS2CPayload::write, RoleActionS2CPayload::new);

    private RoleActionS2CPayload(FriendlyByteBuf buf) {
        this(buf.readResourceLocation(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readUtf(128),
                readBoundedByteArray(buf, MAX_PAYLOAD_BYTES),
                buf.readBoolean());
    }

    private static byte[] readBoundedByteArray(FriendlyByteBuf buf, int maxBytes) {
        int len = buf.readVarInt();
        if (len < 0 || len > maxBytes || len > buf.readableBytes()) {
            throw new DecoderException("RoleActionS2C payload too large or truncated: " + len + " > " + maxBytes);
        }
        byte[] data = new byte[len];
        buf.readBytes(data);
        return data;
    }

    private void write(FriendlyByteBuf buf) {
        byte[] bytes = payload == null ? new byte[0] : payload;
        if (bytes.length > MAX_PAYLOAD_BYTES) {
            throw new io.netty.handler.codec.EncoderException("RoleActionS2C payload too large: " + bytes.length + " > " + MAX_PAYLOAD_BYTES);
        }
        buf.writeResourceLocation(actionId);
        buf.writeVarInt(sequence);
        buf.writeBoolean(ok);
        buf.writeUtf(reasonKey == null ? "" : reasonKey, 128);
        buf.writeByteArray(bytes);
        buf.writeBoolean(push);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
