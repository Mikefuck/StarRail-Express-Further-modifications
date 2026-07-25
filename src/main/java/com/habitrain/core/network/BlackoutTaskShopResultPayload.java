package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 停电任务商店购买结果 S2C payload。
 */
public record BlackoutTaskShopResultPayload(boolean success, String reason) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutTaskShopResultPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("task_shop_result"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutTaskShopResultPayload> CODEC =
            StreamCodec.ofMember(BlackoutTaskShopResultPayload::write, BlackoutTaskShopResultPayload::new);

    private BlackoutTaskShopResultPayload(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readUtf(256));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(success);
        buf.writeUtf(reason, 256);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}