package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Identifies the last rendered quote; prices themselves are never client-authoritative. */
public record AppleShopBuyPayload(int index, long revision, int frame) implements CustomPacketPayload {
    public static final Type<AppleShopBuyPayload> TYPE = new Type<>(HabiTrainCore.id("apple_shop_buy"));
    public static final StreamCodec<FriendlyByteBuf, AppleShopBuyPayload> CODEC =
            StreamCodec.ofMember(AppleShopBuyPayload::write, AppleShopBuyPayload::new);

    private AppleShopBuyPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readLong(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(index);
        buf.writeLong(revision);
        buf.writeVarInt(frame);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void register() { PayloadTypeRegistry.playC2S().register(TYPE, CODEC); }
}
