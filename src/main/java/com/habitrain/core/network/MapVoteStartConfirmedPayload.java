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
 * 判定点 A：地图重置完成、{@code trueStartGame} 已成功进入 STARTING。
 *
 * <p>客户端据此收起顶部「重置地图中」进度牌，打开全屏开局转场，赶在随后的传送前盖住画面。</p>
 *
 * <p>与 {@link MapVoteLaunchTransitionPayload}（环境就绪 / 判定点 B）分离，
 * 因为真正的玩家传送发生在 STARTING 末的 {@code initializeGame}，必须在此前盖住画面。</p>
 */
public record MapVoteStartConfirmedPayload(String mapId) implements CustomPacketPayload {
    public static final Type<MapVoteStartConfirmedPayload> TYPE =
            new Type<>(HabiTrainCore.id("map_vote_start_confirmed"));
    public static final StreamCodec<FriendlyByteBuf, MapVoteStartConfirmedPayload> CODEC =
            StreamCodec.ofMember(MapVoteStartConfirmedPayload::write, MapVoteStartConfirmedPayload::new);

    public MapVoteStartConfirmedPayload {
        mapId = mapId == null ? "" : mapId;
    }

    private MapVoteStartConfirmedPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(64));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(mapId, 64);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void broadcastToLevel(ServerLevel level, String mapId) {
        if (level == null) return;
        MapVoteStartConfirmedPayload payload = new MapVoteStartConfirmedPayload(mapId);
        for (ServerPlayer player : level.players()) {
            if (com.habitrain.core.game.sre.RepairModeManager.isRepairer(player)) {
                continue;
            }
            ServerPlayNetworking.send(player, payload);
        }
    }
}
