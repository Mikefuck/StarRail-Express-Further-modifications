package com.habitrain.core.network;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
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
import java.util.HashMap;
import java.util.Map;

/**
 * 任务配置同步网络包 - 用于服务端向客户端同步任务配置
 * 客户端需要知道哪些任务启用以正确渲染透视颜色等信息
 */
public class TaskConfigPayload implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("habitrain_core", "task_config_sync");
    public static final CustomPacketPayload.Type<TaskConfigPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    private final Map<String, TaskConfigEntry> configs;

    public TaskConfigPayload(Map<String, TaskConfigEntry> configs) {
        this.configs = configs;
    }

    public Map<String, TaskConfigEntry> getConfigs() {
        return configs;
    }

    /**
     * 流编解码器 - 用于网络传输
     */
    public static final StreamCodec<ByteBuf, TaskConfigPayload> CODEC = new StreamCodec<>() {
        @Override
        public TaskConfigPayload decode(ByteBuf buf) {
            int size = buf.readInt();
            Map<String, TaskConfigEntry> configs = new HashMap<>();
            for (int i = 0; i < size; i++) {
                int keyLen = buf.readInt();
                byte[] keyBytes = new byte[keyLen];
                buf.readBytes(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);

                TaskConfigEntry entry = new TaskConfigEntry();
                entry.enabled = buf.readBoolean();
                int enabledMapCount = buf.readInt();
                for (int j = 0; j < enabledMapCount; j++) {
                    int mapLen = buf.readInt();
                    byte[] mapBytes = new byte[mapLen];
                    buf.readBytes(mapBytes);
                    entry.enabledMaps.add(new String(mapBytes, StandardCharsets.UTF_8));
                }
                entry.instinctColor = buf.readInt();

                // ★ v3: mapFilterMode (0=不启用, 1=白名单, 2=黑名单)
                if (buf.readableBytes() >= 4) {
                    entry.mapFilterMode = Math.max(0, Math.min(2, buf.readInt()));
                }

                // ====== v2: 新增奖励和权重字段 (向后兼容) ======
                entry.goldReward = buf.readInt();
                entry.emotionReward = buf.readFloat();
                entry.refreshWeight = buf.readFloat();

                configs.put(key, entry);
            }
            return new TaskConfigPayload(configs);
        }

        @Override
        public void encode(ByteBuf buf, TaskConfigPayload payload) {
            // ★ 防御性复制：防止单机模式下 save() 与 Netty IO 线程同时修改 map 导致 CME
            var entries = new ArrayList<>(payload.configs.entrySet());
            buf.writeInt(entries.size());
            for (var entry : entries) {
                String key = entry.getKey();
                TaskConfigEntry config = entry.getValue();

                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                buf.writeInt(keyBytes.length);
                buf.writeBytes(keyBytes);

                buf.writeBoolean(config.enabled);

                buf.writeInt(config.enabledMaps.size());
                for (String map : config.enabledMaps) {
                    byte[] mapBytes = map.getBytes(StandardCharsets.UTF_8);
                    buf.writeInt(mapBytes.length);
                    buf.writeBytes(mapBytes);
                }

                buf.writeInt(config.instinctColor);

                // ★ v3: mapFilterMode
                buf.writeInt(config.mapFilterMode);

                // ====== v2: 新增奖励和权重字段 ======
                buf.writeInt(config.goldReward);
                buf.writeFloat(config.emotionReward);
                buf.writeFloat(config.refreshWeight);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 注册网络包类型
     */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    /**
     * 发送配置到客户端
     */
    public static void sendToPlayer(ServerPlayer player) {
        if (player == null) return;
        Map<String, TaskConfigEntry> allConfigs = ConfigManager.getInstance().getAllConfigs();
        // ★ 防御性复制：防止多线程并发修改导致 CME
        ServerPlayNetworking.send(player, new TaskConfigPayload(new HashMap<>(allConfigs)));
    }

    /**
     * 广播配置到所有在线玩家
     */
    public static void broadcastToAll(MinecraftServer server) {
        if (server == null) return;
        Map<String, TaskConfigEntry> allConfigs = ConfigManager.getInstance().getAllConfigs();
        // ★ 防御性复制：防止多线程并发修改导致 CME
        var packet = new TaskConfigPayload(new HashMap<>(allConfigs));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, packet);
        }
    }
}
