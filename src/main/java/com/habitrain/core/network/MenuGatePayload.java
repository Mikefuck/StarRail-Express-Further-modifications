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
import org.jetbrains.annotations.Nullable;

/**
 * S2C 网络包 - 服务端 → 客户端 Mod 菜单访问门控状态同步。
 *
 * <p>携带门控开关、「当前接收者是否被允许」以及<b>服务端是否自报为专用服务器</b>三个布尔值，
 * 不下发完整允许名单。客户端据此决定是否用「当前为未授权的访问」覆盖层锁定受门控的
 * Mod 菜单页面。</p>
 *
 * <p>审核 B19/M-07：旧版客户端用 {@code Minecraft.getSingleplayerServer() == null}
 * 自行推断「专用服务器」，在局域网房主的整合服上会把客人误判为专用服务器并锁屏，
 * 而服务端用的是 {@code MinecraftServer#isDedicatedServer()}——两边不对称。
 * 现在该判定由服务端下发，客户端不再自行推断。</p>
 *
 * <p>玩家加入时由 JOIN 事件按该玩家下发；命令变更（enable/disable/add/remove）后
 * 对每个在线玩家发送各自的 {@code youAreAllowed}，使被授权的玩家客户端立即解锁。</p>
 */
public class MenuGatePayload implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MenuGatePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("menu_gate"));

    private final boolean enabled;
    private final boolean youAreAllowed;
    private final boolean dedicatedServer;

    public MenuGatePayload(boolean enabled, boolean youAreAllowed, boolean dedicatedServer) {
        this.enabled = enabled;
        this.youAreAllowed = youAreAllowed;
        this.dedicatedServer = dedicatedServer;
    }

    /**
     * @deprecated 改用 {@link #MenuGatePayload(boolean, boolean, boolean)}；
     *     本重载把 {@code dedicatedServer} 记为 {@code false}（即「不拦截」），
     *     不会误锁局域网客人的菜单页面。
     */
    @Deprecated(forRemoval = true, since = "2.0.11")
    public MenuGatePayload(boolean enabled, boolean youAreAllowed) {
        this(enabled, youAreAllowed, false);
    }

    public boolean isEnabled() { return enabled; }

    public boolean youAreAllowed() { return youAreAllowed; }

    /** 服务端自报的 {@code MinecraftServer#isDedicatedServer()}。 */
    public boolean isDedicatedServer() { return dedicatedServer; }

    public static final StreamCodec<ByteBuf, MenuGatePayload> CODEC = new StreamCodec<>() {
        @Override
        public MenuGatePayload decode(ByteBuf buf) {
            return new MenuGatePayload(buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
        }

        @Override
        public void encode(ByteBuf buf, MenuGatePayload payload) {
            buf.writeBoolean(payload.enabled);
            buf.writeBoolean(payload.youAreAllowed);
            buf.writeBoolean(payload.dedicatedServer);
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
        ServerPlayNetworking.send(player, forPlayer(player.getServer(), player));
    }

    public static void broadcastToAll(MinecraftServer server) {
        boolean enabled = MenuGateService.isEnabled();
        boolean dedicated = server != null && server.isDedicatedServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player,
                    new MenuGatePayload(enabled, MenuGateService.isAllowed(player), dedicated));
        }
    }

    private static MenuGatePayload forPlayer(@Nullable MinecraftServer server, ServerPlayer player) {
        return new MenuGatePayload(
                MenuGateService.isEnabled(),
                MenuGateService.isAllowed(player),
                server != null && server.isDedicatedServer());
    }
}
