package com.habitrain.core.network;

import com.habitrain.core.config.ConfigManager;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * S2C 网络包 - 服务端 → 客户端 同步光影白名单配置
 *
 * 当玩家加入服务器时（在 JOIN 事件中），服务端将此配置发送给客户端，
 * 以便 OP 玩家在 ModMenu 中查看当前白名单设置。
 *
 * 包体格式：
 * - enabled (boolean): 是否启用白名单
 * - whitelist (String[]): 允许的光影包名称列表
 */
public class ShaderConfigPayload implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("habitrain_core", "shader_config_sync");
    public static final CustomPacketPayload.Type<ShaderConfigPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    private final boolean enabled;
    private final List<String> whitelist;

    public ShaderConfigPayload(boolean enabled, List<String> whitelist) {
        this.enabled = enabled;
        this.whitelist = whitelist != null ? whitelist : List.of();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> getWhitelist() {
        return whitelist;
    }

    public static final StreamCodec<ByteBuf, ShaderConfigPayload> CODEC = new StreamCodec<>() {
        @Override
        public ShaderConfigPayload decode(ByteBuf buf) {
            boolean enabled = buf.readBoolean();
            int count = buf.readInt();
            List<String> whitelist = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int len = buf.readInt();
                byte[] bytes = new byte[len];
                buf.readBytes(bytes);
                whitelist.add(new String(bytes, StandardCharsets.UTF_8));
            }
            return new ShaderConfigPayload(enabled, whitelist);
        }

        @Override
        public void encode(ByteBuf buf, ShaderConfigPayload payload) {
            buf.writeBoolean(payload.enabled);
            buf.writeInt(payload.whitelist.size());
            for (String name : payload.whitelist) {
                byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 注册 S2C 包类型
     */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    /**
     * 发送白名单配置到指定客户端
     */
    public static void sendToPlayer(ServerPlayer player) {
        if (player == null) return;
        ConfigManager cfg = ConfigManager.getInstance();
        ServerPlayNetworking.send(player, new ShaderConfigPayload(
                cfg.isShaderWhitelistEnabled(),
                cfg.getShaderWhitelist()
        ));
    }

    /**
     * 广播白名单配置到所有在线玩家
     */
    public static void broadcastToAll(MinecraftServer server) {
        if (server == null) return;
        ConfigManager cfg = ConfigManager.getInstance();
        var packet = new ShaderConfigPayload(
                cfg.isShaderWhitelistEnabled(),
                cfg.getShaderWhitelist()
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, packet);
        }
    }
}
