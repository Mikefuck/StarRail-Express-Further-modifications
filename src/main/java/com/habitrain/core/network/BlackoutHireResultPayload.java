package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 电话聘请结果 S2C payload。
 * 服务端处理 HirePolice C2S 后，向发起者发送本包，客户端据此更新 statusText。
 */
public record BlackoutHireResultPayload(boolean success, String reason) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutHireResultPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("hire_result"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutHireResultPayload> CODEC =
            StreamCodec.ofMember(BlackoutHireResultPayload::write, BlackoutHireResultPayload::new);

    private BlackoutHireResultPayload(FriendlyByteBuf buf) {
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
