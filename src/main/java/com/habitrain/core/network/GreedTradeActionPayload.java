package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S：贪婪交易界面的确认或取消操作。
 * <p>
 * 编码：action(utf) + sessionId(utf)
 */
public record GreedTradeActionPayload(String action, String sessionId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<GreedTradeActionPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("greed_trade_action"));
    public static final StreamCodec<FriendlyByteBuf, GreedTradeActionPayload> CODEC =
            StreamCodec.ofMember(GreedTradeActionPayload::write, GreedTradeActionPayload::new);

    private GreedTradeActionPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(16), buf.readUtf(64));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(action == null ? "" : action, 16);
        buf.writeUtf(sessionId == null ? "" : sessionId, 64);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
