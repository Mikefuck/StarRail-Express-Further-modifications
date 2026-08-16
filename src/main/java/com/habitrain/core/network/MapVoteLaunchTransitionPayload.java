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
 * 服务端确认地图已载入、游戏模式已成功启动，且 SRE 与 API 的对局环境均已应用后，
 * 通知客户端播放最终启程转场。
 * 独立于通用投票结束包，避免管理员取消投票时误播放“进入游戏”动画。
 */
public record MapVoteLaunchTransitionPayload(String winningMapId) implements CustomPacketPayload {
    public static final Type<MapVoteLaunchTransitionPayload> TYPE =
            new Type<>(HabiTrainCore.id("map_vote_launch_transition"));
    public static final StreamCodec<FriendlyByteBuf, MapVoteLaunchTransitionPayload> CODEC =
            StreamCodec.ofMember(MapVoteLaunchTransitionPayload::write, MapVoteLaunchTransitionPayload::new);

    public MapVoteLaunchTransitionPayload {
        winningMapId = winningMapId == null ? "" : winningMapId;
    }

    private MapVoteLaunchTransitionPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(64));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(winningMapId, 64);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void broadcastToLevel(ServerLevel level, String winningMapId) {
        if (level == null) return;
        MapVoteLaunchTransitionPayload payload =
                new MapVoteLaunchTransitionPayload(winningMapId);
        for (ServerPlayer player : level.players()) {
            if (com.habitrain.core.game.sre.RepairModeManager.isRepairer(player)) {
                continue; // 维修员不看开局转场
            }
            ServerPlayNetworking.send(player, payload);
        }
    }
}
