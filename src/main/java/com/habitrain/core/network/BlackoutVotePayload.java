package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record BlackoutVotePayload(
        String purpose,         // "EXILE" | future: "SHERIFF"
        boolean active,
        int remainingSeconds,
        int totalSeconds,
        int maxSelections,      // 1 for exile
        String title,
        String description,
        List<Entry> candidates
) implements CustomPacketPayload {

    private static final int MAX_CANDIDATES = 64;

    public record Entry(UUID playerId, String playerName, int votes) {}

    public static final CustomPacketPayload.Type<BlackoutVotePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("vote"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutVotePayload> CODEC =
            StreamCodec.ofMember(BlackoutVotePayload::write, BlackoutVotePayload::new);

    public BlackoutVotePayload {
        candidates = List.copyOf(candidates);
    }

    private BlackoutVotePayload(FriendlyByteBuf buf) {
        this(buf.readUtf(32), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readUtf(128), buf.readUtf(256), readCandidates(buf));
    }

    private static List<Entry> readCandidates(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_CANDIDATES) {
            throw new DecoderException("Invalid vote candidate count: " + size);
        }
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new Entry(buf.readUUID(), buf.readUtf(64), buf.readVarInt()));
        }
        return list;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(purpose, 32);
        buf.writeBoolean(active);
        buf.writeVarInt(remainingSeconds);
        buf.writeVarInt(totalSeconds);
        buf.writeVarInt(maxSelections);
        buf.writeUtf(title, 128);
        buf.writeUtf(description, 256);
        buf.writeVarInt(candidates.size());
        for (Entry e : candidates) {
            buf.writeUUID(e.playerId());
            buf.writeUtf(e.playerName(), 64);
            buf.writeVarInt(e.votes());
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void broadcastToAll(net.minecraft.server.MinecraftServer server,
                                       String purpose, boolean active, int remainingSeconds,
                                       int totalSeconds, int maxSelections,
                                       String title, String description,
                                       List<Entry> candidates) {
        var payload = new BlackoutVotePayload(purpose, active, remainingSeconds,
                totalSeconds, maxSelections, title, description, candidates);
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        }
    }
}
