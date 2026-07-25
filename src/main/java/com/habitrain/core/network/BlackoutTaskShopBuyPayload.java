package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 停电任务商店购买 C2S payload。
 * 客户端点击商店条目时发送，服务端执行 tryPurchase。
 */
public record BlackoutTaskShopBuyPayload(String entryKey) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutTaskShopBuyPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("task_shop_buy"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutTaskShopBuyPayload> CODEC =
            StreamCodec.ofMember(BlackoutTaskShopBuyPayload::write, BlackoutTaskShopBuyPayload::new);

    private BlackoutTaskShopBuyPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(128));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(entryKey, 128);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}