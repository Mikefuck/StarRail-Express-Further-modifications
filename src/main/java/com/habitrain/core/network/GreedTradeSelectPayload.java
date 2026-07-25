package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** C2S request created from the Greed backpack player selector. */
public record GreedTradeSelectPayload(UUID partnerId) implements CustomPacketPayload {
    public static final Type<GreedTradeSelectPayload> TYPE =
            new Type<>(HabiTrainCore.id("greed_trade_select"));
    public static final StreamCodec<FriendlyByteBuf, GreedTradeSelectPayload> CODEC =
            StreamCodec.ofMember(GreedTradeSelectPayload::write, GreedTradeSelectPayload::new);

    private GreedTradeSelectPayload(FriendlyByteBuf buf) {
        this(buf.readUUID());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(partnerId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
