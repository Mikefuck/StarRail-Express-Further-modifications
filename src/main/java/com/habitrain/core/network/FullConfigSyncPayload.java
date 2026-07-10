package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.config.ConfigManager;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * S2C 网络包 - 服务端 → 客户端 完整配置同步。
 *
 * TaskConfigPayload 只同步任务条目（不含 gameModes/minigames/sheriffDivisor 等全局项）。
 * 本包同步整份配置 JSON（global + tasks + gameModes + minigames），让客户端配置界面显示
 * 服务端真实值，避免 OP 在联机保存时用本地过期的全局项覆盖服务端。
 *
 * 客户端收到后调用 ConfigManager.applySyncFromJson（抑制 save 回调，防止回环广播）。
 */
public class FullConfigSyncPayload implements CustomPacketPayload {
    private static final int MAX_JSON_LENGTH = 1048576;

    public static final CustomPacketPayload.Type<FullConfigSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("full_config_sync"));

    private final String configJson;

    public FullConfigSyncPayload(String configJson) {
        this.configJson = configJson != null ? configJson : "{}";
    }

    public String getConfigJson() {
        return configJson;
    }

    public static final StreamCodec<ByteBuf, FullConfigSyncPayload> CODEC = new StreamCodec<>() {
        @Override
        public FullConfigSyncPayload decode(ByteBuf buf) {
            int len = buf.readInt();
            if (len <= 0) return new FullConfigSyncPayload("{}");
            if (len > MAX_JSON_LENGTH) {
                throw new DecoderException("FullConfigSync payload 过大: " + len + " > " + MAX_JSON_LENGTH);
            }
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            return new FullConfigSyncPayload(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public void encode(ByteBuf buf, FullConfigSyncPayload payload) {
            byte[] bytes = payload.configJson.getBytes(StandardCharsets.UTF_8);
            buf.writeInt(bytes.length);
            if (bytes.length > 0) {
                buf.writeBytes(bytes);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void sendToPlayer(ServerPlayer player) {
        String json = ConfigManager.getInstance().toJsonString();
        ServerPlayNetworking.send(player, new FullConfigSyncPayload(json));
    }

    public static void broadcastToAll(MinecraftServer server) {
        String json = ConfigManager.getInstance().toJsonString();
        FullConfigSyncPayload payload = new FullConfigSyncPayload(json);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}