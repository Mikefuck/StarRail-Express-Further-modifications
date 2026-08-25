package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.config.MenuGateService;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * S2C 网络包 - 服务端 → 客户端 Mod 菜单访问门控状态同步。
 *
 * <p>仅携带门控开关与「当前接收者是否被允许」两个布尔值，不下发完整允许名单。
 * 客户端据此决定是否用「当前为未授权的访问」覆盖层锁定受门控的 Mod 菜单页面。
 * 仅在专用服务器联机时生效。</p>
 *
 * <p>玩家加入时由 JOIN 事件按该玩家下发；命令变更（enable/disable/add/remove）后
 * 对每个在线玩家发送各自的 {@code youAreAllowed}，使被授权的玩家客户端立即解锁。</p>
 */
public class MenuGatePayload implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MenuGatePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("menu_gate"));

    private final boolean enabled;
    private final boolean youAreAllowed;

    public MenuGatePayload(boolean enabled, boolean youAreAllowed) {
        this.enabled = enabled;
        this.youAreAllowed = youAreAllowed;
    }

    public boolean isEnabled() { return enabled; }

    public boolean youAreAllowed() { return youAreAllowed; }

    public static final StreamCodec<ByteBuf, MenuGatePayload> CODEC = new StreamCodec<>() {
        @Override
        public MenuGatePayload decode(ByteBuf buf) {
            return new MenuGatePayload(buf.readBoolean(), buf.readBoolean());
        }

        @Override
        public void encode(ByteBuf buf, MenuGatePayload payload) {
            buf.writeBoolean(payload.enabled);
            buf.writeBoolean(payload.youAreAllowed);
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void sendToPlayer(ServerPlayer player) {
        ServerPlayNetworking.send(player, new MenuGatePayload(
                MenuGateService.isEnabled(), MenuGateService.isAllowed(player)));
    }

    public static void broadcastToAll(MinecraftServer server) {
        boolean enabled = MenuGateService.isEnabled();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, new MenuGatePayload(enabled, MenuGateService.isAllowed(player)));
        }
    }
}
