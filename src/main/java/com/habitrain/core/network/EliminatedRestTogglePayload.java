package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S request issued by the DLC's ability key while an eliminated player is
 * switching between the rest area and spectator view.
 */
public record EliminatedRestTogglePayload() implements CustomPacketPayload {

    public static final Type<EliminatedRestTogglePayload> TYPE =
            new Type<>(HabiTrainCore.id("eliminated_rest_toggle"));
    public static final StreamCodec<FriendlyByteBuf, EliminatedRestTogglePayload> CODEC =
            StreamCodec.ofMember(EliminatedRestTogglePayload::write, EliminatedRestTogglePayload::new);

    private EliminatedRestTogglePayload(FriendlyByteBuf buf) {
        this();
    }

    private void write(FriendlyByteBuf buf) {
        // no fields
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
