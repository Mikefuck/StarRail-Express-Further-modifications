package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BlackoutAnnouncePayload(
    String roleName,        // 显示名, e.g. "黑化杀手"
    String subtitle,        // 副标题, e.g. "§7坏人阵营 — 破坏列车，消灭好人"
    String goal,            // 目标, e.g. "消灭所有好人"
    int killerCount,
    int targetCount
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutAnnouncePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("blackout_announce"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutAnnouncePayload> CODEC =
            StreamCodec.ofMember(BlackoutAnnouncePayload::write, BlackoutAnnouncePayload::new);

    private BlackoutAnnouncePayload(FriendlyByteBuf buf) {
        this(buf.readUtf(256), buf.readUtf(256), buf.readUtf(256), buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(roleName, 256);
        buf.writeUtf(subtitle, 256);
        buf.writeUtf(goal, 256);
        buf.writeVarInt(killerCount);
        buf.writeVarInt(targetCount);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
