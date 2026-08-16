package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.config.MenuGateService;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * S2C 网络包 - 服务端 → 客户端 Mod 菜单访问门控状态同步。
 *
 * <p>携带门控开关与允许访问的玩家列表；客户端据此决定是否用「当前为未授权的访问」
 * 覆盖层锁定受门控的 Mod 菜单页面。仅在专用服务器联机时生效。</p>
 *
 * <p>玩家加入时由 JOIN 事件下发，命令变更（enable/disable/add/remove）后广播给所有在线玩家，
 * 使被授权的玩家客户端立即解锁。</p>
 */
public class MenuGatePayload implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MenuGatePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("menu_gate"));

    private final boolean enabled;
    private final List<Entry> allowed;

    public MenuGatePayload(boolean enabled, List<Entry> allowed) {
        this.enabled = enabled;
        this.allowed = allowed != null ? allowed : List.of();
    }

    public boolean isEnabled() { return enabled; }

    public List<Entry> getAllowed() { return allowed; }

    public record Entry(String name, String uuid) {}

    private static final int MAX_ENTRY_UTF_BYTES = 4096;

    public static final StreamCodec<ByteBuf, MenuGatePayload> CODEC = new StreamCodec<>() {
        @Override
        public MenuGatePayload decode(ByteBuf buf) {
            boolean enabled = buf.readBoolean();
            int size = buf.readInt();
            if (size < 0 || size > 1024) {
                throw new DecoderException("MenuGate payload 条目数非法: " + size);
            }
            List<Entry> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                String name = readUtf(buf);
                String uuid = readUtf(buf);
                list.add(new Entry(name, uuid));
            }
            return new MenuGatePayload(enabled, list);
        }

        @Override
        public void encode(ByteBuf buf, MenuGatePayload payload) {
            buf.writeBoolean(payload.enabled);
            buf.writeInt(payload.allowed.size());
            for (Entry e : payload.allowed) {
                writeUtf(buf, e.name() == null ? "" : e.name());
                writeUtf(buf, e.uuid() == null ? "" : e.uuid());
            }
        }

        private String readUtf(ByteBuf buf) {
            int len = buf.readInt();
            if (len < 0 || len > MAX_ENTRY_UTF_BYTES) {
                throw new DecoderException("MenuGate payload 字符串过长: " + len);
            }
            if (len == 0) return "";
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private void writeUtf(ByteBuf buf, String s) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            buf.writeInt(bytes.length);
            if (bytes.length > 0) {
                buf.writeBytes(bytes);
            }
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
        ServerPlayNetworking.send(player, new MenuGatePayload(MenuGateService.isEnabled(), snapshot()));
    }

    public static void broadcastToAll(MinecraftServer server) {
        MenuGatePayload payload = new MenuGatePayload(MenuGateService.isEnabled(), snapshot());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static List<Entry> snapshot() {
        List<Entry> entries = new ArrayList<>();
        for (MenuGateService.AllowedPlayer ap : MenuGateService.getAllowed()) {
            entries.add(new Entry(ap.getName(), ap.getUuid()));
        }
        return entries;
    }
}
