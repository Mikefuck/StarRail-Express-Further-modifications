package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 地图投票完成后「开局加载」的服务端权威进度同步。
 *
 * <p>服务端在 SRE 地图重置任务推进时每秒广播一次：进度 0-100、当前参与游玩人数、
 * 按模式配置估算的杀手人数、选中地图 id 与模式 id。客户端转场屏据此绘制加载面板与
 * 进度条；当 SRE 真正开始对局（{@code OnGameStarted}）时，服务端改发
 * {@link MapVoteLaunchTransitionPayload} 作为「开局确认」，客户端再播放扫场亮标题动画。</p>
 */
public record MapVoteProgressPayload(
        int progress,
        int playerCount,
        int killerCount,
        String mapId,
        String modeId
) implements CustomPacketPayload {

    public static final Type<MapVoteProgressPayload> TYPE =
            new Type<>(HabiTrainCore.id("map_vote_progress"));
    public static final StreamCodec<FriendlyByteBuf, MapVoteProgressPayload> CODEC =
            StreamCodec.ofMember(MapVoteProgressPayload::write, MapVoteProgressPayload::new);

    public MapVoteProgressPayload {
        progress = Math.max(0, Math.min(progress, 100));
        playerCount = Math.max(0, playerCount);
        killerCount = Math.max(0, killerCount);
        mapId = mapId == null ? "" : mapId;
        modeId = modeId == null ? "" : modeId;
    }

    private MapVoteProgressPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readUtf(64), buf.readUtf(64));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(progress);
        buf.writeVarInt(playerCount);
        buf.writeVarInt(killerCount);
        buf.writeUtf(mapId, 64);
        buf.writeUtf(modeId, 64);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void broadcastToLevel(ServerLevel level,
                                        int progress, int playerCount, int killerCount,
                                        String mapId, String modeId) {
        if (level == null) return;
        MapVoteProgressPayload payload = new MapVoteProgressPayload(
                progress, playerCount, killerCount, mapId, modeId);
        for (ServerPlayer player : level.players()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public static void sendTo(ServerPlayer player,
                              int progress, int playerCount, int killerCount,
                              String mapId, String modeId) {
        ServerPlayNetworking.send(player, new MapVoteProgressPayload(
                progress, playerCount, killerCount, mapId, modeId));
    }
}
