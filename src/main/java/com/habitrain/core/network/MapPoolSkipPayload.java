package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: OP requests skip to the next daily map pool.
 * Empty payload; server validates permission level ≥ 4.
 */
public record MapPoolSkipPayload() implements CustomPacketPayload {

    public static final Type<MapPoolSkipPayload> TYPE =
            new Type<>(HabiTrainCore.id("map_pool_skip"));
    public static final StreamCodec<FriendlyByteBuf, MapPoolSkipPayload> CODEC =
            StreamCodec.ofMember(MapPoolSkipPayload::write, MapPoolSkipPayload::new);

    private MapPoolSkipPayload(FriendlyByteBuf buf) {
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
