package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

/** Select a marked target, or answer a server-issued revival token. */
public record LustActionPayload(int action, UUID id) implements CustomPacketPayload {
    public static final int SELECT = 0, REVIVE = 1, DECLINE = 2;
    public static final Type<LustActionPayload> TYPE = new Type<>(HabiTrainCore.id("lust_action"));
    public static final StreamCodec<FriendlyByteBuf, LustActionPayload> CODEC =
            StreamCodec.ofMember(LustActionPayload::write, LustActionPayload::new);
    private LustActionPayload(FriendlyByteBuf buf) { this(readAction(buf), buf.readUUID()); }
    private static int readAction(FriendlyByteBuf buf) {
        int value = buf.readUnsignedByte();
        if (value > DECLINE) throw new DecoderException("Invalid Lust action: " + value);
        return value;
    }
    private void write(FriendlyByteBuf buf) { buf.writeByte(action); buf.writeUUID(id); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void register() { PayloadTypeRegistry.playC2S().register(TYPE, CODEC); }
}
