package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * S2C 网络包 - 维修模式状态同步（仅发给对应玩家）。
 *
 * <p>进入/退出维修模式时下发，客户端据此：屏蔽 SRE 原版开局/结尾黑场（fade）、跳过投票与
 * 开局转场界面、忽略开局相机运镜与对局结束转场包，使维修员完全不被对局转场打扰。</p>
 */
public class RepairModeSyncPayload implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RepairModeSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("repair_mode_sync"));

    private final boolean repairing;

    public RepairModeSyncPayload(boolean repairing) {
        this.repairing = repairing;
    }

    public boolean isRepairing() {
        return repairing;
    }

    public static final StreamCodec<ByteBuf, RepairModeSyncPayload> CODEC = new StreamCodec<>() {
        @Override
        public RepairModeSyncPayload decode(ByteBuf buf) {
            return new RepairModeSyncPayload(buf.readBoolean());
        }

        @Override
        public void encode(ByteBuf buf, RepairModeSyncPayload payload) {
            buf.writeBoolean(payload.repairing);
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void sendToPlayer(ServerPlayer player, boolean repairing) {
        if (player != null) {
            ServerPlayNetworking.send(player, new RepairModeSyncPayload(repairing));
        }
    }
}
