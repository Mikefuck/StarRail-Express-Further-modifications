/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
 *  net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
 *  net.minecraft.network.FriendlyByteBuf
 *  net.minecraft.network.codec.StreamCodec
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload
 *  net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type
 *  net.minecraft.server.level.ServerPlayer
 */
package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public record GameEndTransitionPayload(String winStatusName, String modeId, String customWinnerId, int customWinnerColor, String customTitleJson, List<MvpPlayer> mvpPlayers, boolean environmentReady) implements CustomPacketPayload
{
    public static final int MAX_MVP_PLAYERS = 4;
    public static final CustomPacketPayload.Type<GameEndTransitionPayload> TYPE = new CustomPacketPayload.Type(HabiTrainCore.id("game_end_transition"));
    public static final StreamCodec<FriendlyByteBuf, GameEndTransitionPayload> CODEC = StreamCodec.ofMember(GameEndTransitionPayload::write, GameEndTransitionPayload::new);

    public GameEndTransitionPayload {
        winStatusName = GameEndTransitionPayload.bounded(winStatusName, 32);
        modeId = GameEndTransitionPayload.bounded(modeId, 64);
        customWinnerId = GameEndTransitionPayload.bounded(customWinnerId, 128);
        customTitleJson = customTitleJson == null || customTitleJson.length() > 4096 ? "" : customTitleJson;
        mvpPlayers = mvpPlayers == null || mvpPlayers.isEmpty() ? List.of() : List.copyOf(mvpPlayers.subList(0, Math.min(4, mvpPlayers.size())));
    }

    private GameEndTransitionPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(32), buf.readUtf(64), buf.readUtf(128), buf.readInt(), buf.readUtf(4096), GameEndTransitionPayload.readMvpPlayers(buf), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(this.winStatusName, 32);
        buf.writeUtf(this.modeId, 64);
        buf.writeUtf(this.customWinnerId, 128);
        buf.writeInt(this.customWinnerColor);
        buf.writeUtf(this.customTitleJson, 4096);
        buf.writeVarInt(this.mvpPlayers.size());
        for (MvpPlayer player : this.mvpPlayers) {
            player.write(buf);
        }
        buf.writeBoolean(this.environmentReady);
    }

    private static List<MvpPlayer> readMvpPlayers(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > 4) {
            throw new IllegalArgumentException("Invalid MVP player count: " + size);
        }
        ArrayList<MvpPlayer> result = new ArrayList<MvpPlayer>(size);
        for (int i = 0; i < size; ++i) {
            result.add(new MvpPlayer(buf));
        }
        return result;
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            --end;
        }
        return value.substring(0, end);
    }

    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void sendTo(ServerPlayer player, GameEndTransitionPayload payload) {
        if (player == null || payload == null) {
            return;
        }
        ServerPlayNetworking.send((ServerPlayer)player, (CustomPacketPayload)payload);
    }

    public record MvpPlayer(UUID playerId, String playerName, int score, int kills, int survivalSeconds, int itemUses) {
        public MvpPlayer {
            playerId = playerId == null ? new UUID(0L, 0L) : playerId;
            playerName = GameEndTransitionPayload.bounded(playerName, 64);
            score = Math.max(0, score);
            kills = Math.max(0, kills);
            survivalSeconds = Math.max(0, survivalSeconds);
            itemUses = Math.max(0, itemUses);
        }

        private MvpPlayer(FriendlyByteBuf buf) {
            this(buf.readUUID(), buf.readUtf(64), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeUUID(this.playerId);
            buf.writeUtf(this.playerName, 64);
            buf.writeVarInt(this.score);
            buf.writeVarInt(this.kills);
            buf.writeVarInt(this.survivalSeconds);
            buf.writeVarInt(this.itemUses);
        }
    }
}
