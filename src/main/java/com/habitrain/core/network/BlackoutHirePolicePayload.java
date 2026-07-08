package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BlackoutHirePolicePayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutHirePolicePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("hire_police"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutHirePolicePayload> CODEC =
            StreamCodec.ofMember((p, buf) -> {}, buf -> new BlackoutHirePolicePayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
