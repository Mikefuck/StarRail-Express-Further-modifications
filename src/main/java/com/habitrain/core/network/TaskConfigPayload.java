package com.habitrain.core.network;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
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

    // decode 长度上限，防止恶意/损坏的 S2C 包触发 OOM
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_STR_LEN = 1024;
    private static final int MAX_MAPS_PER_ENTRY = 256;

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
            if (size < 0 || size > MAX_ENTRIES) {
                throw new DecoderException("Invalid task config entry size: " + size);
            }
            Map<String, TaskConfigEntry> configs = new HashMap<>();
            for (int i = 0; i < size; i++) {
                int keyLen = buf.readInt();
                if (keyLen < 0 || keyLen > MAX_STR_LEN) {
                    throw new DecoderException("Invalid config key length: " + keyLen);
                }
                byte[] keyBytes = new byte[keyLen];
                buf.readBytes(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);

                TaskConfigEntry entry = new TaskConfigEntry();
                entry.enabled = buf.readBoolean();
                int enabledMapCount = buf.readInt();
                if (enabledMapCount < 0 || enabledMapCount > MAX_MAPS_PER_ENTRY) {
                    throw new DecoderException("Invalid enabledMap count: " + enabledMapCount);
                }
                for (int j = 0; j < enabledMapCount; j++) {
                    int mapLen = buf.readInt();
                    if (mapLen < 0 || mapLen > MAX_STR_LEN) {
                        throw new DecoderException("Invalid map name length: " + mapLen);
                    }
                    byte[] mapBytes = new byte[mapLen];
                    buf.readBytes(mapBytes);
                    entry.enabledMaps.add(new String(mapBytes, StandardCharsets.UTF_8));
                }
                entry.instinctColor = buf.readInt();

                // 本协议为单模组内部协议（客户端与服务端同版本），无版本兼容需求。
                // 字段按 encode 顺序连续读取：mapFilterMode → hasGoldReward → goldReward → hasEmotionReward → emotionReward → hasRefreshWeight → refreshWeight → hasShopPrice → shopPrice
                entry.mapFilterMode = Math.max(0, Math.min(2, buf.readInt()));
                entry.hasGoldReward = buf.readBoolean();
                entry.goldReward = buf.readInt();
                entry.hasEmotionReward = buf.readBoolean();
                entry.emotionReward = buf.readFloat();
                entry.hasRefreshWeight = buf.readBoolean();
                entry.refreshWeight = buf.readFloat();
                entry.hasShopPrice = buf.readBoolean();
                entry.shopPrice = buf.readInt();

                configs.put(key, entry);
            }
            return new TaskConfigPayload(configs);
        }

        @Override
        public void encode(ByteBuf buf, TaskConfigPayload payload) {
            // ★ 防御性复制：防止单机模式下 save() 与 Netty IO 线程同时修改 map 导致 CME
            // 预先过滤 null entry，避免 Netty 编码线程抛 NPE；
            // 同时保证写入的 size 与实际条目数一致（decode 端按 size 读取）。
            var entries = new ArrayList<Map.Entry<String, TaskConfigEntry>>();
            for (var e : payload.configs.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    entries.add(e);
                }
            }
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
                    if (map == null) {
                        // 写入 0 长度占位，保持 count 一致
                        buf.writeInt(0);
                        continue;
                    }
                    byte[] mapBytes = map.getBytes(StandardCharsets.UTF_8);
                    buf.writeInt(mapBytes.length);
                    buf.writeBytes(mapBytes);
                }

                buf.writeInt(config.instinctColor);

                buf.writeInt(config.mapFilterMode);

                buf.writeBoolean(config.hasGoldReward);
                buf.writeInt(config.goldReward);
                buf.writeBoolean(config.hasEmotionReward);
                buf.writeFloat(config.emotionReward);
                buf.writeBoolean(config.hasRefreshWeight);
                buf.writeFloat(config.refreshWeight);
                buf.writeBoolean(config.hasShopPrice);
                buf.writeInt(config.shopPrice);
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
