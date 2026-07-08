package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record BlackoutVoteCastPayload(String purpose, UUID targetPlayerId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutVoteCastPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("vote_cast"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutVoteCastPayload> CODEC =
            StreamCodec.ofMember(BlackoutVoteCastPayload::write, BlackoutVoteCastPayload::new);

    private BlackoutVoteCastPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(32), buf.readUUID());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(purpose, 32);
        buf.writeUUID(targetPlayerId);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
