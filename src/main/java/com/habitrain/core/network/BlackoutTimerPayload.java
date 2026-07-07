package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public record BlackoutTimerPayload(
    int totalTimeRemaining,
    long endTimeTick,
    boolean blackoutActive,
    int phase
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutTimerPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("blackout_timer"));

    public static final StreamCodec<FriendlyByteBuf, BlackoutTimerPayload> CODEC =
            StreamCodec.ofMember(BlackoutTimerPayload::write, BlackoutTimerPayload::new);

    private BlackoutTimerPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarLong(), buf.readBoolean(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(totalTimeRemaining);
        buf.writeVarLong(endTimeTick);
        buf.writeBoolean(blackoutActive);
        buf.writeVarInt(phase);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void broadcastToAll(MinecraftServer server, int totalTime, long endTimeTick, boolean active, int phase) {
        var payload = new BlackoutTimerPayload(totalTime, endTimeTick, active, phase);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
