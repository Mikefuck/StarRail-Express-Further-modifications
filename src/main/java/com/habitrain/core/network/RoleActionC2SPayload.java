package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Multiplex C2S payload for every v2 {@link com.habitrain.core.api.role.v2.action.RoleActionSpec}.
 *
 * <p>Encoding: action id + sequence + raw payload bytes. Size is gated by the
 * spec after decode, not here.
 */
public record RoleActionC2SPayload(ResourceLocation actionId, int sequence, byte[] payload)
        implements CustomPacketPayload {

    public static final Type<RoleActionC2SPayload> TYPE =
            new Type<>(HabiTrainCore.id("role_action_c2s"));
    public static final StreamCodec<FriendlyByteBuf, RoleActionC2SPayload> CODEC =
            StreamCodec.ofMember(RoleActionC2SPayload::write, RoleActionC2SPayload::new);

    /** Hard decode cap; the spec's {@code maxBytes} is checked after lookup. */
    public static final int DECODE_MAX_BYTES = com.habitrain.core.api.role.v2.action.RoleActionSpec.MAX_PAYLOAD_BYTES;

    private RoleActionC2SPayload(FriendlyByteBuf buf) {
        this(buf.readResourceLocation(), buf.readVarInt(), readBytes(buf));
    }

    private static byte[] readBytes(FriendlyByteBuf buf) {
        int length = buf.readVarInt();
        if (length < 0 || length > DECODE_MAX_BYTES || length > buf.readableBytes())
            throw new io.netty.handler.codec.DecoderException("Role action is oversized or truncated");
        byte[] bytes = new byte[length]; buf.readBytes(bytes); return bytes;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(actionId);
        buf.writeVarInt(sequence);
        byte[] bytes = payload == null ? new byte[0] : payload;
        if (bytes.length > DECODE_MAX_BYTES) {
            throw new io.netty.handler.codec.EncoderException(
                    "RoleAction C2S payload too large: " + bytes.length);
        }
        buf.writeByteArray(bytes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
