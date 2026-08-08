package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * 对局结束结算画面（GameEndTransitionScreen）的 S2C 载荷。
 * <p>
 * 服务端在 {@code GameStatus.STOPPING} 时由 {@code GameEndTransitionCoordinator}
 * 构建并广播；客户端收到后打开/更新 2D 结算画面。
 */
public record GameEndTransitionPayload(
        String winStatusName,
        String modeId,
        String customWinnerId,
        int customWinnerColor,
        String customTitleJson,
        boolean environmentReady
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<GameEndTransitionPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("game_end_transition"));

    public static final StreamCodec<FriendlyByteBuf, GameEndTransitionPayload> CODEC =
            StreamCodec.ofMember(GameEndTransitionPayload::write, GameEndTransitionPayload::new);

    public GameEndTransitionPayload {
        winStatusName = winStatusName == null ? "" : winStatusName;
        modeId = modeId == null ? "" : modeId;
        customWinnerId = customWinnerId == null ? "" : customWinnerId;
        customTitleJson = customTitleJson == null ? "" : customTitleJson;
    }

    private GameEndTransitionPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(32), buf.readUtf(64), buf.readUtf(128), buf.readInt(), buf.readUtf(4096), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(winStatusName, 32);
        buf.writeUtf(modeId, 64);
        buf.writeUtf(customWinnerId, 128);
        buf.writeInt(customWinnerColor);
        buf.writeUtf(customTitleJson, 4096);
        buf.writeBoolean(environmentReady);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void sendTo(ServerPlayer player, GameEndTransitionPayload payload) {
        if (player == null || payload == null) return;
        ServerPlayNetworking.send(player, payload);
    }
}
