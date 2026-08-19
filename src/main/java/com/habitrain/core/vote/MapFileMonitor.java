package com.habitrain.core.vote;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.SREIntegration;
import com.habitrain.core.network.FullConfigSyncPayload;
import com.habitrain.core.network.MapVoteProfilePayload;
import io.wifi.starrailexpress.game.MapManager;
import io.wifi.starrailexpress.game.data.MapConfig;
import io.wifi.starrailexpress.game.data.ServerMapConfig;
import io.wifi.starrailexpress.network.MapIntroSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.agmas.noellesroles.config.NoellesRolesConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 服务端地图文件监控器：
 * 每 5 秒（100 ticks）检测 <world>/train_maps/ 及 map_vote 相关文件是否变动，
 * 发现变动时自动重新加载并全量同步给所有在线客户端。
 */
public final class MapFileMonitor {
    private static final Map<String, FileStamp> PREVIOUS_STAMPS = new HashMap<>();
    private static boolean initialized = false;

    private record FileStamp(long lastModified, long size) {}

    private MapFileMonitor() {}

    /**
     * 服务端每 100 ticks（5秒）调用一次。
     */
    public static void checkAndSync(MinecraftServer server) {
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;

        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path trainMapsDir = worldRoot.resolve("train_maps").toAbsolutePath().normalize();
        Path voteMapsJson = worldRoot.resolve("train_vote_maps.json").toAbsolutePath().normalize();

        Map<String, FileStamp> currentStamps = scanDirectory(trainMapsDir, voteMapsJson);
        if (!initialized) {
            PREVIOUS_STAMPS.clear();
            PREVIOUS_STAMPS.putAll(currentStamps);
            initialized = true;
            return;
        }

        boolean changed = !currentStamps.equals(PREVIOUS_STAMPS);
        if (changed) {
            PREVIOUS_STAMPS.clear();
            PREVIOUS_STAMPS.putAll(currentStamps);
            HabiTrainCore.LOGGER.info("[MapFileMonitor] 检测到地图相关文件已被修改，正在自动同步至客户端...");
            syncMapDataToAll(server);
        }
    }

    /**
     * 重置状态（服务器关闭/重启时）。
     */
    public static void reset() {
        PREVIOUS_STAMPS.clear();
        initialized = false;
    }

    /**
     * 构建并向所有在线客户端广播地图介绍载荷与档案载荷。
     */
    public static void syncMapDataToAll(MinecraftServer server) {
        if (server == null) return;
        try {
            // 1. 刷新 ConfigManager 与上游地图关联
            ConfigManager.getInstance().refreshFromUpstreamMaps(server, false);

            ServerLevel overworld = server.overworld();
            if (overworld != null) {
                // 2. 补齐档案与预览占位
                var configMaps = ConfigManager.getInstance().getModeMapVoteSettings().maps;
                MapVoteProfileStore.ensureProfiles(overworld, configMaps.keySet(), configMaps);

                // 3. 同步 SRE ServerMapConfig
                SREIntegration.syncToSREServerMapConfig(server, ConfigManager.getInstance().getModeMapVoteSettings());

                // 4. 构建 MapIntroSyncPayload
                MapIntroSyncPayload introPayload = buildMapIntroPayload(server);

                // 5. 构建 MapVoteProfilePayload
                var profiles = MapVoteProfileStore.loadProfiles(overworld, configMaps.keySet(), configMaps);
                List<MapVoteProfilePayload> profilePayloads = MapVoteProfilePayload.fragment(profiles);

                // 6. 发送给所有在线玩家
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player != null) {
                        try {
                            ServerPlayNetworking.send(player, introPayload);
                            for (MapVoteProfilePayload profilePayload : profilePayloads) {
                                ServerPlayNetworking.send(player, profilePayload);
                            }
                        } catch (Exception pe) {
                            HabiTrainCore.LOGGER.debug("[MapFileMonitor] send to player {} failed", player.getName().getString(), pe);
                        }
                    }
                }

                // 7. 若不是单机，同步完整全局配置
                if (!server.isSingleplayer()) {
                    FullConfigSyncPayload.broadcastToAll(server);
                }

                HabiTrainCore.LOGGER.info("[MapFileMonitor] 地图数据自动同步完成：{} 张地图，{} 份档案",
                        introPayload.maps().size(), profiles.size());
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[MapFileMonitor] syncMapDataToAll failed", t);
        }
    }

    public static MapIntroSyncPayload buildMapIntroPayload(MinecraftServer server) {
        ArrayList<MapIntroSyncPayload.MapJson> maps = new ArrayList<>();
        ArrayList<MapIntroSyncPayload.VoteMap> voteMaps = new ArrayList<>();
        Path mapsDir = server.getWorldPath(LevelResource.ROOT)
                .resolve("train_maps")
                .toAbsolutePath()
                .normalize();

        ServerLevel overworld = server.overworld();
        if (overworld != null && Files.isDirectory(mapsDir)) {
            for (String mapId : MapManager.getAvailableMaps(overworld, true)) {
                if (SREIntegration.isReservedMapId(mapId)) continue;
                try {
                    Path path = mapsDir.resolve(mapId + ".json").normalize();
                    if (!path.startsWith(mapsDir) || !Files.isRegularFile(path)) continue;
                    maps.add(new MapIntroSyncPayload.MapJson(mapId, Files.readString(path, StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    HabiTrainCore.LOGGER.debug("[MapFileMonitor] Failed to read map json for {}", mapId, e);
                }
            }
        }

        ServerMapConfig mapConfig = ServerMapConfig.getInstance(server);
        if (mapConfig != null && mapConfig.getMaps() != null) {
            for (MapConfig.MapEntry entry : mapConfig.getMaps()) {
                if (entry == null || entry.id == null || entry.id.isBlank() || SREIntegration.isReservedMapId(entry.id)) {
                    continue;
                }
                voteMaps.add(new MapIntroSyncPayload.VoteMap(
                        entry.id,
                        entry.displayName,
                        entry.minCount,
                        entry.maxCount,
                        entry.canSelect,
                        entry.gameModes == null ? List.of() : entry.gameModes));
            }
        }

        NoellesRolesConfig config = NoellesRolesConfig.HANDLER.instance();
        return new MapIntroSyncPayload(
                maps,
                voteMaps,
                config.maChenXuMaps == null ? List.of() : config.maChenXuMaps,
                config.swastMaps == null ? List.of() : config.swastMaps,
                config.underwaterRolesMaps == null ? List.of() : config.underwaterRolesMaps,
                config.airRolesMaps == null ? List.of() : config.airRolesMaps,
                config.trapRolesMaps == null ? List.of() : config.trapRolesMaps,
                config.horseRolesMaps == null ? List.of() : config.horseRolesMaps
        );
    }

    private static Map<String, FileStamp> scanDirectory(Path trainMapsDir, Path voteMapsJson) {
        Map<String, FileStamp> stamps = new HashMap<>();
        if (Files.isRegularFile(voteMapsJson)) {
            try {
                stamps.put("train_vote_maps.json", new FileStamp(
                        Files.getLastModifiedTime(voteMapsJson).toMillis(),
                        Files.size(voteMapsJson)));
            } catch (IOException ignored) {}
        }
        if (Files.isDirectory(trainMapsDir)) {
            try (Stream<Path> stream = Files.walk(trainMapsDir, 5)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        String rel = trainMapsDir.relativize(p).toString().replace('\\', '/');
                        stamps.put(rel, new FileStamp(
                                Files.getLastModifiedTime(p).toMillis(),
                                Files.size(p)));
                    } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }
        return stamps;
    }
}
