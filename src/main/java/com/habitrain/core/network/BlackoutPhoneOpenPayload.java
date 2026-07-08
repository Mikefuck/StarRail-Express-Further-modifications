package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BlackoutPhoneOpenPayload(
        boolean unlocked,
        int remainingLockSeconds,
        int balance,
        boolean hasHiredThisGame,
        int sheriffCount,
        int killerCount
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutPhoneOpenPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("phone_open"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutPhoneOpenPayload> CODEC =
            StreamCodec.ofMember(BlackoutPhoneOpenPayload::write, BlackoutPhoneOpenPayload::new);

    private BlackoutPhoneOpenPayload(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(unlocked);
        buf.writeVarInt(remainingLockSeconds);
        buf.writeVarInt(balance);
        buf.writeBoolean(hasHiredThisGame);
        buf.writeVarInt(sheriffCount);
        buf.writeVarInt(killerCount);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
