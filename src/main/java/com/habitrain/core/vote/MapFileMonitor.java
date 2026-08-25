package com.habitrain.core.vote;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.SREIntegration;
import com.habitrain.core.network.MapVoteProfilePayload;
import io.wifi.starrailexpress.game.MapManager;
import io.wifi.starrailexpress.game.data.MapConfig;
import io.wifi.starrailexpress.game.data.ServerMapConfig;
import io.wifi.starrailexpress.network.MapIntroSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.agmas.noellesroles.config.NoellesRolesConfig;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 服务端地图文件监控器：
 * 每 5 秒（100 ticks）检测 <world>/train_maps/ 及 map_vote 相关文件是否变动，
 * 发现变动时自动重新加载并把脏地图 JSON 同步给所有在线客户端。
 */
public final class MapFileMonitor {
    /**
     * Present in a hot-reload {@link MapIntroSyncPayload} so the client merges
     * map JSON instead of wiping the JOIN cache. Stripped before DLC screens
     * see the packet. Not sent on JOIN/full sync.
     */
    public static final String INTRO_MERGE_MARKER = "__habitrain_map_merge__";

    private static final Map<String, FileStamp> PREVIOUS_STAMPS = new HashMap<>();
    private static boolean initialized = false;
    private static volatile boolean scanInFlight = false;
    private static int epoch = 0;

    private record FileStamp(long lastModified, long size) {}

    private MapFileMonitor() {}

    /**
     * 服务端每 100 ticks（5秒）调用一次。目录扫描在后台线程，结果回到主线程应用。
     */
    public static void checkAndSync(MinecraftServer server) {
        if (server == null || scanInFlight) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;

        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path trainMapsDir = worldRoot.resolve("train_maps").toAbsolutePath().normalize();
        Path voteMapsJson = worldRoot.resolve("train_vote_maps.json").toAbsolutePath().normalize();
        int scanEpoch = epoch;
        scanInFlight = true;
        Util.backgroundExecutor().execute(() -> {
            Map<String, FileStamp> currentStamps = null;
            try {
                currentStamps = scanDirectory(trainMapsDir, voteMapsJson);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[MapFileMonitor] background scan failed", t);
            }
            Map<String, FileStamp> stamps = currentStamps;
            server.execute(() -> {
                try {
                    if (scanEpoch != epoch) {
                        return;
                    }
                    if (stamps != null) {
                        applyScanResult(server, stamps);
                    }
                } finally {
                    if (scanEpoch == epoch) {
                        scanInFlight = false;
                    }
                }
            });
        });
    }

    private static void applyScanResult(MinecraftServer server, Map<String, FileStamp> currentStamps) {
        if (!initialized) {
            PREVIOUS_STAMPS.clear();
            PREVIOUS_STAMPS.putAll(currentStamps);
            initialized = true;
            return;
        }

        if (currentStamps.equals(PREVIOUS_STAMPS)) {
            return;
        }

        Set<String> dirtyMapIds = dirtyMapIds(PREVIOUS_STAMPS, currentStamps);
        PREVIOUS_STAMPS.clear();
        PREVIOUS_STAMPS.putAll(currentStamps);
        HabiTrainCore.LOGGER.info("[MapFileMonitor] 检测到地图相关文件已被修改，正在自动同步至客户端...");
        syncMapDataToAll(server, dirtyMapIds);
    }

    /**
     * 重置状态（服务器关闭/重启时）。
     */
    public static void reset() {
        epoch++;
        PREVIOUS_STAMPS.clear();
        initialized = false;
        scanInFlight = false;
    }

    /**
     * 构建并向所有在线客户端广播完整地图介绍载荷与档案载荷。
     */
    public static void syncMapDataToAll(MinecraftServer server) {
        syncMapDataToAll(server, null);
    }

    /**
     * @param dirtyMapIds {@code null} 表示 JOIN/全量（含全部地图 JSON）；非 null 只带脏地图 JSON，
     *                    客户端走 merge。voteMaps / 特殊集合始终全量（体积小）。
     */
    private static void syncMapDataToAll(MinecraftServer server, @Nullable Set<String> dirtyMapIds) {
        if (server == null) return;
        try {
            ConfigManager.getInstance().refreshFromUpstreamMaps(server, false);

            ServerLevel overworld = server.overworld();
            if (overworld != null) {
                var configMaps = ConfigManager.getInstance().getModeMapVoteSettings().maps;
                MapVoteProfileStore.ensureProfiles(overworld, configMaps.keySet(), configMaps);

                SREIntegration.syncToSREServerMapConfig(server, ConfigManager.getInstance().getModeMapVoteSettings());

                MapIntroSyncPayload introPayload = buildMapIntroPayload(server, dirtyMapIds);

                var profiles = MapVoteProfileStore.loadProfiles(overworld, configMaps.keySet(), configMaps);
                List<MapVoteProfilePayload> profilePayloads = MapVoteProfilePayload.fragment(profiles);

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

                HabiTrainCore.LOGGER.info("[MapFileMonitor] 地图数据自动同步完成：{} 张地图，{} 份档案",
                        introPayload.maps().size(), profiles.size());
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[MapFileMonitor] syncMapDataToAll failed", t);
        }
    }

    public static MapIntroSyncPayload buildMapIntroPayload(MinecraftServer server) {
        return buildMapIntroPayload(server, null);
    }

    public static MapIntroSyncPayload buildMapIntroPayload(MinecraftServer server,
                                                           @Nullable Set<String> dirtyMapIds) {
        ArrayList<MapIntroSyncPayload.MapJson> maps = new ArrayList<>();
        ArrayList<MapIntroSyncPayload.VoteMap> voteMaps = new ArrayList<>();
        Path mapsDir = server.getWorldPath(LevelResource.ROOT)
                .resolve("train_maps")
                .toAbsolutePath()
                .normalize();

        boolean partial = dirtyMapIds != null;
        if (partial) {
            maps.add(new MapIntroSyncPayload.MapJson(INTRO_MERGE_MARKER, "{}"));
        }

        ServerLevel overworld = server.overworld();
        if (overworld != null && Files.isDirectory(mapsDir)) {
            for (String mapId : MapManager.getAvailableMaps(overworld, true)) {
                if (SREIntegration.isReservedMapId(mapId)) continue;
                if (partial && !dirtyMapIds.contains(mapId)) continue;
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
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".json") && !name.endsWith(".JSON")) {
                        return;
                    }
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

    private static Set<String> dirtyMapIds(Map<String, FileStamp> previous, Map<String, FileStamp> current) {
        Set<String> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        keys.addAll(previous.keySet());
        keys.addAll(current.keySet());
        for (String key : keys) {
            if ("train_vote_maps.json".equals(key) || !isJsonStampKey(key)) {
                continue;
            }
            if (Objects.equals(previous.get(key), current.get(key))) {
                continue;
            }
            String mapId = mapIdFromRelPath(key);
            if (mapId != null && !mapId.isBlank()) {
                ids.add(mapId);
            }
        }
        return ids;
    }

    private static boolean isJsonStampKey(String key) {
        return key != null && (key.endsWith(".json") || key.endsWith(".JSON"));
    }

    private static @Nullable String mapIdFromRelPath(String rel) {
        if (rel == null || rel.isBlank()) {
            return null;
        }
        String name = rel;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.length() <= 5) {
            return null;
        }
        return name.substring(0, name.length() - 5);
    }
}
