package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * C2S: 玩家投票 (targetUUID = 投票给谁, isResult=false)
 * S2C: 投票结果同步 (sheriffUUID = 当选警长, isResult=true)
 */
public record BlackoutVotePayload(UUID targetUUID, boolean isResult) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutVotePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("blackout_vote"));

    public static final StreamCodec<FriendlyByteBuf, BlackoutVotePayload> CODEC =
            StreamCodec.ofMember(BlackoutVotePayload::write, BlackoutVotePayload::new);

    private BlackoutVotePayload(FriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(targetUUID);
        buf.writeBoolean(isResult);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
