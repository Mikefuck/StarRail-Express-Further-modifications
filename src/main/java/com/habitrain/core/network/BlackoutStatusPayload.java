package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * S2C: 停电/模式状态变更事件广播
 */
public record BlackoutStatusPayload(
    StatusType statusType,
    String data
) implements CustomPacketPayload {
    public enum StatusType {
        BLACKOUT_START,
        BLACKOUT_END,
        TIME_WARNING
    }

    public static final CustomPacketPayload.Type<BlackoutStatusPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("blackout_status"));

    public static final StreamCodec<FriendlyByteBuf, BlackoutStatusPayload> CODEC =
            StreamCodec.ofMember(BlackoutStatusPayload::write, BlackoutStatusPayload::new);

    private BlackoutStatusPayload(FriendlyByteBuf buf) {
        this(buf.readEnum(StatusType.class), buf.readUtf(64));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeEnum(statusType);
        buf.writeUtf(data, 64);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void broadcast(MinecraftServer server, StatusType type, String data) {
        var payload = new BlackoutStatusPayload(type, data);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
