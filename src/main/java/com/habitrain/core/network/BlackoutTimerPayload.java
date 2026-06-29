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
    int blackoutCountdown,
    boolean blackoutActive
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutTimerPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("blackout_timer"));

    public static final StreamCodec<FriendlyByteBuf, BlackoutTimerPayload> CODEC =
            StreamCodec.ofMember(BlackoutTimerPayload::write, BlackoutTimerPayload::new);

    private BlackoutTimerPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(totalTimeRemaining);
        buf.writeVarInt(blackoutCountdown);
        buf.writeBoolean(blackoutActive);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void broadcastToAll(MinecraftServer server, int totalTime, int blackoutCD, boolean active) {
        var payload = new BlackoutTimerPayload(totalTime, blackoutCD, active);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
