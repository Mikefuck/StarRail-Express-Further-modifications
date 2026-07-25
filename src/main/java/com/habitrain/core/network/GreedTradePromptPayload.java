package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C：打开贪婪匿名交易双确认界面。
 * <p>
 * 编码：sessionId + side + itemId + price + partnerLabel
 */
public record GreedTradePromptPayload(
        String sessionId,
        String side,
        String itemId,
        int price,
        String partnerLabel
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<GreedTradePromptPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("greed_trade_prompt"));
    public static final StreamCodec<FriendlyByteBuf, GreedTradePromptPayload> CODEC =
            StreamCodec.ofMember(GreedTradePromptPayload::write, GreedTradePromptPayload::new);

    private GreedTradePromptPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(64), buf.readUtf(8), buf.readUtf(128), buf.readVarInt(), buf.readUtf(64));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId == null ? "" : sessionId, 64);
        buf.writeUtf(side == null ? "" : side, 8);
        buf.writeUtf(itemId == null ? "" : itemId, 128);
        buf.writeVarInt(price);
        buf.writeUtf(partnerLabel == null ? "" : partnerLabel, 64);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
