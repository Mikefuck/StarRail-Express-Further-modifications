package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用选项投票 S2C 状态同步。
 * 候选人使用字符串 optionId（模式/地图 id），而非玩家 UUID。
 * {@code resolvedOptionId} 仅在正常结算的 inactive 包中有值；管理员取消时为空。
 */
public record OptionVotePayload(
        String voteId,
        boolean active,
        int remainingSeconds,
        int totalSeconds,
        int maxSelections,
        String title,
        String description,
        String resolvedOptionId,
        List<Entry> candidates
) implements CustomPacketPayload {

    private static final int MAX_CANDIDATES = 64;

    public record Entry(String optionId, String displayName, int votes) {}

    public static final Type<OptionVotePayload> TYPE =
            new Type<>(HabiTrainCore.id("option_vote"));
    public static final StreamCodec<FriendlyByteBuf, OptionVotePayload> CODEC =
            StreamCodec.ofMember(OptionVotePayload::write, OptionVotePayload::new);

    public OptionVotePayload {
        resolvedOptionId = resolvedOptionId == null ? "" : resolvedOptionId;
        candidates = List.copyOf(candidates);
    }

    private OptionVotePayload(FriendlyByteBuf buf) {
        this(buf.readUtf(64), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readUtf(128), buf.readUtf(256), buf.readUtf(64),
                readCandidates(buf));
    }

    private static List<Entry> readCandidates(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_CANDIDATES) {
            throw new DecoderException("Invalid option vote candidate count: " + size);
        }
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new Entry(buf.readUtf(64), buf.readUtf(64), buf.readVarInt()));
        }
        return list;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(voteId, 64);
        buf.writeBoolean(active);
        buf.writeVarInt(remainingSeconds);
        buf.writeVarInt(totalSeconds);
        buf.writeVarInt(maxSelections);
        buf.writeUtf(title, 128);
        buf.writeUtf(description, 256);
        buf.writeUtf(resolvedOptionId, 64);
        buf.writeVarInt(candidates.size());
        for (Entry e : candidates) {
            buf.writeUtf(e.optionId(), 64);
            buf.writeUtf(e.displayName(), 64);
            buf.writeVarInt(e.votes());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    /** @deprecated 优先 {@link #broadcastToLevel}。 */
    @Deprecated
    public static void broadcastToAll(MinecraftServer server,
                                      String voteId, boolean active, int remainingSeconds,
                                      int totalSeconds, int maxSelections,
                                      String title, String description,
                                      List<Entry> candidates) {
        OptionVotePayload payload = new OptionVotePayload(
                voteId, active, remainingSeconds, totalSeconds, maxSelections,
                title, description, "", candidates);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public static void broadcastToLevel(net.minecraft.server.level.ServerLevel level,
                                        String voteId, boolean active, int remainingSeconds,
                                        int totalSeconds, int maxSelections,
                                        String title, String description,
                                        List<Entry> candidates) {
        broadcastToLevel(level, voteId, active, remainingSeconds, totalSeconds,
                maxSelections, title, description, "", candidates);
    }

    public static void broadcastToLevel(net.minecraft.server.level.ServerLevel level,
                                        String voteId, boolean active, int remainingSeconds,
                                        int totalSeconds, int maxSelections,
                                        String title, String description,
                                        String resolvedOptionId,
                                        List<Entry> candidates) {
        if (level == null) return;
        OptionVotePayload payload = new OptionVotePayload(
                voteId, active, remainingSeconds, totalSeconds, maxSelections,
                title, description, resolvedOptionId, candidates);
        for (ServerPlayer player : level.players()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public static void sendTo(ServerPlayer player,
                              String voteId, boolean active, int remainingSeconds,
                              int totalSeconds, int maxSelections,
                              String title, String description,
                              List<Entry> candidates) {
        sendTo(player, voteId, active, remainingSeconds, totalSeconds,
                maxSelections, title, description, "", candidates);
    }

    public static void sendTo(ServerPlayer player,
                              String voteId, boolean active, int remainingSeconds,
                              int totalSeconds, int maxSelections,
                              String title, String description,
                              String resolvedOptionId,
                              List<Entry> candidates) {
        ServerPlayNetworking.send(player, new OptionVotePayload(
                voteId, active, remainingSeconds, totalSeconds, maxSelections,
                title, description, resolvedOptionId, candidates));
    }
}
