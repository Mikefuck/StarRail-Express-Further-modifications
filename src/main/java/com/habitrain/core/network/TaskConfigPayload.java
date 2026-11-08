package com.habitrain.core.network;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MinigameConfigEntry;
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
import java.util.List;
import java.util.Map;

/**
 * 任务与小游戏配置同步网络包 - 用于服务端向客户端同步任务与小游戏配置
 * 客户端需要知道哪些任务/小游戏启用以正确渲染透视颜色与轮廓宽度等信息
 */
public class TaskConfigPayload implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("habitrain_core", "task_config_sync");
    public static final CustomPacketPayload.Type<TaskConfigPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    // decode 长度上限，防止恶意/损坏的 S2C 包触发 OOM
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_STR_LEN = 1024;
    private static final int MAX_MAPS_PER_ENTRY = 256;

    private static volatile Map<String, MinigameConfigEntry> latestDecodedMinigames;

    private final Map<String, TaskConfigEntry> configs;
    private final Map<String, MinigameConfigEntry> minigames;

    public TaskConfigPayload(Map<String, TaskConfigEntry> configs) {
        this(configs, Map.of());
    }

    public TaskConfigPayload(Map<String, TaskConfigEntry> configs, Map<String, MinigameConfigEntry> minigames) {
        this.configs = configs != null ? snapshotConfigs(configs) : Map.of();
        this.minigames = minigames != null ? snapshotMinigames(minigames) : Map.of();
    }

    public Map<String, TaskConfigEntry> getConfigs() {
        latestDecodedMinigames = this.minigames;
        return configs;
    }

    public Map<String, MinigameConfigEntry> getMinigames() {
        return minigames;
    }

    public static Map<String, MinigameConfigEntry> pollLatestMinigames() {
        Map<String, MinigameConfigEntry> mg = latestDecodedMinigames;
        latestDecodedMinigames = null;
        return mg;
    }

    public void applyToClient() {
        ConfigManager cfg = ConfigManager.getInstance();
        cfg.applySyncData(this.configs, this.minigames, cfg.getDlcProbabilityTarget());
        cfg.setMinigames(this.minigames);
    }

    /**
     * 流编解码器 - 用于网络传输
     */
    public static final StreamCodec<ByteBuf, TaskConfigPayload> CODEC = new StreamCodec<>() {
        @Override
        public TaskConfigPayload decode(ByteBuf buf) {
            int size = buf.readInt();
            if (size < 0 || size > MAX_ENTRIES || size > buf.readableBytes() / 4) {
                throw new DecoderException("Invalid task config entry size: " + size);
            }
            Map<String, TaskConfigEntry> configs = new HashMap<>(Math.min(size, 64));
            for (int i = 0; i < size; i++) {
                int keyLen = buf.readInt();
                if (keyLen < 0 || keyLen > MAX_STR_LEN || keyLen > buf.readableBytes()) {
                    throw new DecoderException("Invalid config key length: " + keyLen);
                }
                byte[] keyBytes = new byte[keyLen];
                buf.readBytes(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);

                TaskConfigEntry entry = new TaskConfigEntry();
                entry.enabled = buf.readBoolean();
                int enabledMapCount = buf.readInt();
                if (enabledMapCount < 0 || enabledMapCount > MAX_MAPS_PER_ENTRY || enabledMapCount > buf.readableBytes() / 4) {
                    throw new DecoderException("Invalid enabledMap count: " + enabledMapCount);
                }
                for (int j = 0; j < enabledMapCount; j++) {
                    int mapLen = buf.readInt();
                    if (mapLen < 0 || mapLen > MAX_STR_LEN || mapLen > buf.readableBytes()) {
                        throw new DecoderException("Invalid map name length: " + mapLen);
                    }
                    byte[] mapBytes = new byte[mapLen];
                    buf.readBytes(mapBytes);
                    entry.enabledMaps.add(new String(mapBytes, StandardCharsets.UTF_8));
                }
                entry.instinctColor = buf.readInt();
                entry.hasInstinctColor = buf.readBoolean();
                entry.outlineWidth = buf.readFloat();

                entry.mapFilterMode = Math.max(0, Math.min(2, buf.readInt()));
                entry.hasGoldReward = buf.readBoolean();
                entry.goldReward = buf.readInt();
                entry.hasEmotionReward = buf.readBoolean();
                entry.emotionReward = buf.readFloat();
                entry.hasRefreshWeight = buf.readBoolean();
                entry.refreshWeight = buf.readFloat();

                configs.put(key, entry);
            }

            Map<String, MinigameConfigEntry> minigames = new HashMap<>();
            if (buf.isReadable(4)) {
                int mgSize = buf.readInt();
                if (mgSize < 0 || mgSize > MAX_ENTRIES || mgSize > buf.readableBytes() / 4) {
                    throw new DecoderException("Invalid minigame config entry size: " + mgSize);
                }
                for (int i = 0; i < mgSize; i++) {
                    int keyLen = buf.readInt();
                    if (keyLen < 0 || keyLen > MAX_STR_LEN || keyLen > buf.readableBytes()) {
                        throw new DecoderException("Invalid minigame config key length: " + keyLen);
                    }
                    byte[] keyBytes = new byte[keyLen];
                    buf.readBytes(keyBytes);
                    String key = new String(keyBytes, StandardCharsets.UTF_8);

                    MinigameConfigEntry mg = new MinigameConfigEntry();
                    mg.enabled = buf.readBoolean();
                    int enabledMapCount = buf.readInt();
                    if (enabledMapCount < 0 || enabledMapCount > MAX_MAPS_PER_ENTRY || enabledMapCount > buf.readableBytes() / 4) {
                        throw new DecoderException("Invalid minigame enabledMap count: " + enabledMapCount);
                    }
                    for (int j = 0; j < enabledMapCount; j++) {
                        int mapLen = buf.readInt();
                        if (mapLen < 0 || mapLen > MAX_STR_LEN || mapLen > buf.readableBytes()) {
                            throw new DecoderException("Invalid minigame map name length: " + mapLen);
                        }
                        byte[] mapBytes = new byte[mapLen];
                        buf.readBytes(mapBytes);
                        mg.enabledMaps.add(new String(mapBytes, StandardCharsets.UTF_8));
                    }
                    mg.instinctColor = buf.readInt();
                    mg.hasInstinctColor = buf.readBoolean();
                    mg.outlineWidth = buf.readFloat();

                    mg.mapFilterMode = Math.max(0, Math.min(2, buf.readInt()));
                    mg.hasGoldReward = buf.readBoolean();
                    mg.goldReward = buf.readInt();
                    mg.hasEmotionReward = buf.readBoolean();
                    mg.emotionReward = buf.readFloat();
                    mg.hasRefreshWeight = buf.readBoolean();
                    mg.refreshWeight = buf.readFloat();

                    minigames.put(key, mg);
                }
            }
            latestDecodedMinigames = minigames;
            return new TaskConfigPayload(configs, minigames);
        }

        @Override
        public void encode(ByteBuf buf, TaskConfigPayload payload) {
            var entries = new ArrayList<Map.Entry<String, TaskConfigEntry>>();
            for (var e : payload.configs.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    entries.add(e);
                    if (entries.size() >= MAX_ENTRIES) break;
                }
            }
            buf.writeInt(entries.size());
            for (var entry : entries) {
                String key = entry.getKey();
                TaskConfigEntry config = entry.getValue();

                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                if (keyBytes.length > MAX_STR_LEN) {
                    throw new io.netty.handler.codec.EncoderException("Task config key too long: " + keyBytes.length);
                }
                buf.writeInt(keyBytes.length);
                buf.writeBytes(keyBytes);

                buf.writeBoolean(config.enabled);

                List<String> enabledMaps = new ArrayList<>(config.enabledMaps);
                int mapLimit = Math.min(enabledMaps.size(), MAX_MAPS_PER_ENTRY);
                buf.writeInt(mapLimit);
                for (int i = 0; i < mapLimit; i++) {
                    String map = enabledMaps.get(i);
                    if (map == null) {
                        buf.writeInt(0);
                        continue;
                    }
                    byte[] mapBytes = map.getBytes(StandardCharsets.UTF_8);
                    if (mapBytes.length > MAX_STR_LEN) {
                        throw new io.netty.handler.codec.EncoderException("Map name too long: " + mapBytes.length);
                    }
                    buf.writeInt(mapBytes.length);
                    buf.writeBytes(mapBytes);
                }

                buf.writeInt(config.instinctColor);
                buf.writeBoolean(config.hasInstinctColor);
                buf.writeFloat(config.outlineWidth);

                buf.writeInt(Math.max(0, Math.min(2, config.mapFilterMode)));

                buf.writeBoolean(config.hasGoldReward);
                buf.writeInt(config.goldReward);
                buf.writeBoolean(config.hasEmotionReward);
                buf.writeFloat(config.emotionReward);
                buf.writeBoolean(config.hasRefreshWeight);
                buf.writeFloat(config.refreshWeight);
            }

            var mgEntries = new ArrayList<Map.Entry<String, MinigameConfigEntry>>();
            for (var e : payload.minigames.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    mgEntries.add(e);
                    if (mgEntries.size() >= MAX_ENTRIES) break;
                }
            }
            buf.writeInt(mgEntries.size());
            for (var entry : mgEntries) {
                String key = entry.getKey();
                MinigameConfigEntry mg = entry.getValue();

                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                if (keyBytes.length > MAX_STR_LEN) {
                    throw new io.netty.handler.codec.EncoderException("Minigame config key too long: " + keyBytes.length);
                }
                buf.writeInt(keyBytes.length);
                buf.writeBytes(keyBytes);

                buf.writeBoolean(mg.enabled);

                List<String> enabledMaps = new ArrayList<>(mg.enabledMaps);
                int mapLimit = Math.min(enabledMaps.size(), MAX_MAPS_PER_ENTRY);
                buf.writeInt(mapLimit);
                for (int i = 0; i < mapLimit; i++) {
                    String map = enabledMaps.get(i);
                    if (map == null) {
                        buf.writeInt(0);
                        continue;
                    }
                    byte[] mapBytes = map.getBytes(StandardCharsets.UTF_8);
                    if (mapBytes.length > MAX_STR_LEN) {
                        throw new io.netty.handler.codec.EncoderException("Minigame map name too long: " + mapBytes.length);
                    }
                    buf.writeInt(mapBytes.length);
                    buf.writeBytes(mapBytes);
                }

                buf.writeInt(mg.instinctColor);
                buf.writeBoolean(mg.hasInstinctColor);
                buf.writeFloat(mg.outlineWidth);

                buf.writeInt(Math.max(0, Math.min(2, mg.mapFilterMode)));

                buf.writeBoolean(mg.hasGoldReward);
                buf.writeInt(mg.goldReward);
                buf.writeBoolean(mg.hasEmotionReward);
                buf.writeFloat(mg.emotionReward);
                buf.writeBoolean(mg.hasRefreshWeight);
                buf.writeFloat(mg.refreshWeight);
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

    private static Map<String, TaskConfigEntry> snapshotConfigs(Map<String, TaskConfigEntry> source) {
        Map<String, TaskConfigEntry> copy = new HashMap<>(source.size());
        source.forEach((k, v) -> {
            if (k != null && v != null) copy.put(k, v.copy());
        });
        return copy;
    }

    private static Map<String, MinigameConfigEntry> snapshotMinigames(Map<String, MinigameConfigEntry> source) {
        Map<String, MinigameConfigEntry> copy = new HashMap<>(source.size());
        source.forEach((k, v) -> {
            if (k != null && v != null) copy.put(k, v.copy());
        });
        return copy;
    }

    /**
     * 发送配置到客户端
     */
    public static void sendToPlayer(ServerPlayer player) {
        if (player == null) return;
        ConfigManager cfg = ConfigManager.getInstance();
        ServerPlayNetworking.send(player, new TaskConfigPayload(
                snapshotConfigs(cfg.getAllConfigs()),
                snapshotMinigames(cfg.getAllMinigameConfigs())
        ));
    }

    /**
     * 广播配置到所有在线玩家
     */
    public static void broadcastToAll(MinecraftServer server) {
        if (server == null) return;
        ConfigManager cfg = ConfigManager.getInstance();
        var packet = new TaskConfigPayload(
                snapshotConfigs(cfg.getAllConfigs()),
                snapshotMinigames(cfg.getAllMinigameConfigs())
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, packet);
        }
    }
}
