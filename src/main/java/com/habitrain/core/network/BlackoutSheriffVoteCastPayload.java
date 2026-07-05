package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record BlackoutSheriffVoteCastPayload(UUID targetPlayerId, int slotIndex) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutSheriffVoteCastPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("blackout_sheriff_vote_cast"));

    public static final StreamCodec<FriendlyByteBuf, BlackoutSheriffVoteCastPayload> CODEC =
            StreamCodec.ofMember(BlackoutSheriffVoteCastPayload::write, BlackoutSheriffVoteCastPayload::new);

    private BlackoutSheriffVoteCastPayload(FriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(targetPlayerId);
        buf.writeVarInt(slotIndex);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}