package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** Visual events contain no role/target identities. State is sent to its owner only. */
public record IdiotEffectPayload(int event, Vec3 position, int boostTicks, int pityRemaining) implements CustomPacketPayload {
    public static final int STATE = 0, SPLASH = 1, HATCH = 2, FACE = 3, JACKPOT = 4;
    public static final Type<IdiotEffectPayload> TYPE = new Type<>(HabiTrainCore.id("idiot_effect"));
    public static final StreamCodec<FriendlyByteBuf, IdiotEffectPayload> CODEC = StreamCodec.ofMember(
            IdiotEffectPayload::write, IdiotEffectPayload::read);
    private void write(FriendlyByteBuf b) {
        b.writeByte(event); b.writeDouble(position.x); b.writeDouble(position.y); b.writeDouble(position.z);
        b.writeVarInt(boostTicks); b.writeVarInt(pityRemaining);
    }
    private static IdiotEffectPayload read(FriendlyByteBuf b) {
        int event = b.readUnsignedByte();
        Vec3 pos = new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
        int boost = b.readVarInt(), pity = b.readVarInt();
        if (event > JACKPOT || !Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)
                || boost < 0 || boost > 200 || pity < 0 || pity > 60) throw new DecoderException("Invalid idiot effect");
        return new IdiotEffectPayload(event, pos, boost, pity);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void register() { PayloadTypeRegistry.playS2C().register(TYPE, CODEC); }
}
