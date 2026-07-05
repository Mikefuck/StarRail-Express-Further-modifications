package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

public record BlackoutSheriffVotePayload(
        boolean active,
        int remainingSeconds,
        int totalSeconds,
        int sheriffCount,
        List<Entry> players
) implements CustomPacketPayload {

    public record Entry(UUID playerId, String playerName, int votes) {}

    public static final CustomPacketPayload.Type<BlackoutSheriffVotePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("blackout_sheriff_vote"));

    public static final StreamCodec<FriendlyByteBuf, BlackoutSheriffVotePayload> CODEC =
            StreamCodec.ofMember(BlackoutSheriffVotePayload::write, BlackoutSheriffVotePayload::new);

    public BlackoutSheriffVotePayload {
        players = List.copyOf(players);
    }

    private BlackoutSheriffVotePayload(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), readPlayers(buf));
    }

    private static List<Entry> readPlayers(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> players = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            players.add(new Entry(buf.readUUID(), buf.readUtf(64), buf.readVarInt()));
        }
        return players;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeVarInt(remainingSeconds);
        buf.writeVarInt(totalSeconds);
        buf.writeVarInt(sheriffCount);
        buf.writeVarInt(players.size());
        for (Entry entry : players) {
            buf.writeUUID(entry.playerId());
            buf.writeUtf(entry.playerName(), 64);
            buf.writeVarInt(entry.votes());
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void broadcastToAll(MinecraftServer server, boolean active, int remainingSeconds,
                                      int totalSeconds, int sheriffCount, List<Entry> players) {
        var payload = new BlackoutSheriffVotePayload(active, remainingSeconds, totalSeconds, sheriffCount, players);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}