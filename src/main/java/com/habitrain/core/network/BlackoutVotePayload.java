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
        VotePurpose purpose,
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
        this(readPurpose(buf), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readUtf(128), buf.readUtf(256), readCandidates(buf));
    }

    private static VotePurpose readPurpose(FriendlyByteBuf buf) {
        String raw = buf.readUtf(32);
        VotePurpose p = VotePurpose.fromString(raw);
        if (p == null) {
            // 未来版本新增 purpose 时旧客户端不应误渲染为放逐投票，
            // 丢弃该包（接收端 catch DecoderException 后跳过）。
            throw new DecoderException("Unknown VotePurpose: " + raw);
        }
        return p;
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
        buf.writeUtf(purpose.name(), 32);
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

    /** @deprecated 优先 {@link #broadcastToLevel}；保留兼容全服广播。 */
    @Deprecated
    public static void broadcastToAll(net.minecraft.server.MinecraftServer server,
                                       VotePurpose purpose, boolean active, int remainingSeconds,
                                       int totalSeconds, int maxSelections,
                                       String title, String description,
                                       List<Entry> candidates) {
        var payload = new BlackoutVotePayload(purpose, active, remainingSeconds,
                totalSeconds, maxSelections, title, description, candidates);
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        }
    }

    public static void broadcastToLevel(net.minecraft.server.level.ServerLevel level,
                                        VotePurpose purpose, boolean active, int remainingSeconds,
                                        int totalSeconds, int maxSelections,
                                        String title, String description,
                                        List<Entry> candidates) {
        if (level == null) return;
        var payload = new BlackoutVotePayload(purpose, active, remainingSeconds,
                totalSeconds, maxSelections, title, description, candidates);
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        }
    }
}
