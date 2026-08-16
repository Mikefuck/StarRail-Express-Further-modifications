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
 * 服务端在开局确认前发现游戏未能真正启动（例如 SRE {@code trueStartGame} 因参与人数
 * 不足 {@code minPlayerCount} 而中止）时广播的「开局中止」信号。
 *
 * <p>客户端 {@code VoteLaunchTransitionScreen} 收到后立即交还画面（不再等待 ACTIVE），
 * 避免因游戏从未进入 STARTING/ACTIVE 而在加载/扫场画面无限停留（旧实现的 120s 兜底太慢）。</p>
 */
public record MapVoteLaunchAbortPayload() implements CustomPacketPayload {
    public static final Type<MapVoteLaunchAbortPayload> TYPE =
            new Type<>(HabiTrainCore.id("map_vote_launch_abort"));
    public static final StreamCodec<FriendlyByteBuf, MapVoteLaunchAbortPayload> CODEC =
            StreamCodec.ofMember(MapVoteLaunchAbortPayload::write, MapVoteLaunchAbortPayload::new);

    private MapVoteLaunchAbortPayload(FriendlyByteBuf buf) {
        this();
    }

    private void write(FriendlyByteBuf buf) {
        // 无字段
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void broadcastToLevel(ServerLevel level) {
        if (level == null) return;
        MapVoteLaunchAbortPayload payload = new MapVoteLaunchAbortPayload();
        for (ServerPlayer player : level.players()) {
            if (com.habitrain.core.game.sre.RepairModeManager.isRepairer(player)) {
                continue; // 维修员无转场屏可交还
            }
            ServerPlayNetworking.send(player, payload);
        }
    }
}
