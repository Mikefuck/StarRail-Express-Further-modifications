package com.habitrain.core.client.cache;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.client.gui.screen.maprotation.MapIntroDetail;
import io.wifi.starrailexpress.network.MapIntroRequestPayload;
import io.wifi.starrailexpress.network.MapIntroSyncPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.Util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户端地图介绍全量数据缓存与同步管理器。
 *
 * <p>集中维护上游 {@link MapIntroSyncPayload} 下发的数据（各地图 JSON 属性、投票配置、
 * 以及 6 大类特殊职业地图集合），解决从背包/按键/投票等不同入口进入时数据可能未请求或
 * 空白的问题，并为 API 投票界面（{@code OptionVoteScreen}）提供即时查询能力。</p>
 */
@Environment(EnvType.CLIENT)
public final class ClientMapIntroCache {
    private static final long REQUEST_COOLDOWN_MILLIS = 2000L;

    private static final Map<String, JsonObject> MAP_JSONS = new HashMap<>();
    private static final Map<String, MapIntroSyncPayload.VoteMap> VOTE_MAPS = new HashMap<>();
    private static final Map<String, com.habitrain.core.network.MapVoteProfilePayload.MapProfile> PROFILES = new HashMap<>();
    private static final Set<String> BAG_MAPS = new HashSet<>();
    private static final Set<String> POLICE_MAPS = new HashSet<>();
    private static final Set<String> UNDERWATER_MAPS = new HashSet<>();
    private static final Set<String> AIR_MAPS = new HashSet<>();
    private static final Set<String> TRAP_MAPS = new HashSet<>();
    private static final Set<String> HORSE_MAPS = new HashSet<>();

    private static MapIntroSyncPayload latestPayload;
    private static boolean hasData = false;
    private static long lastRequestMillis = 0L;

    private ClientMapIntroCache() {}

    /**
     * 接收并保存服务端下发的地图投票档案（含中文描述、标签、推荐人数及 PNG 预览字节）。
     */
    public static synchronized void applyProfiles(com.habitrain.core.network.MapVoteProfilePayload payload) {
        if (payload == null || payload.profiles() == null) {
            return;
        }
        for (var entry : payload.profiles().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                PROFILES.put(entry.getKey(), entry.getValue());
                com.habitrain.core.client.gui.MapVotePreviewCache.invalidate(entry.getKey());
            }
        }
        hasData = true;
    }

    /**
     * 针对单张地图更新或注入档案。
     */
    public static synchronized void putProfile(String mapId, com.habitrain.core.network.MapVoteProfilePayload.MapProfile profile) {
        if (mapId != null && !mapId.isBlank() && profile != null) {
            PROFILES.put(mapId, profile);
            com.habitrain.core.client.gui.MapVotePreviewCache.invalidate(mapId);
            hasData = true;
        }
    }

    /**
     * 更新并解析服务端下发的地图介绍载荷。
     */
    public static synchronized void update(MapIntroSyncPayload payload) {
        if (payload == null) {
            return;
        }
        latestPayload = payload;
        updateRaw(payload.maps(), payload.voteMaps(), payload.bagMaps(), payload.policeMaps(),
                payload.underwaterMaps(), payload.airMaps(), payload.trapMaps(), payload.horseMaps());
    }

    /**
     * 直接通过数据列表更新缓存（便于测试与低阶数据源注入）。
     */
    public static synchronized void updateRaw(List<MapIntroSyncPayload.MapJson> maps,
                                              List<MapIntroSyncPayload.VoteMap> voteMaps,
                                              List<String> bagMaps,
                                              List<String> policeMaps,
                                              List<String> underwaterMaps,
                                              List<String> airMaps,
                                              List<String> trapMaps,
                                              List<String> horseMaps) {
        MAP_JSONS.clear();
        VOTE_MAPS.clear();
        BAG_MAPS.clear();
        POLICE_MAPS.clear();
        UNDERWATER_MAPS.clear();
        AIR_MAPS.clear();
        TRAP_MAPS.clear();
        HORSE_MAPS.clear();

        if (bagMaps != null) BAG_MAPS.addAll(bagMaps);
        if (policeMaps != null) POLICE_MAPS.addAll(policeMaps);
        if (underwaterMaps != null) UNDERWATER_MAPS.addAll(underwaterMaps);
        if (airMaps != null) AIR_MAPS.addAll(airMaps);
        if (trapMaps != null) TRAP_MAPS.addAll(trapMaps);
        if (horseMaps != null) HORSE_MAPS.addAll(horseMaps);

        if (voteMaps != null) {
            for (MapIntroSyncPayload.VoteMap map : voteMaps) {
                if (map != null && map.id() != null && !map.id().isBlank()) {
                    VOTE_MAPS.put(map.id(), map);
                }
            }
        }

        if (maps != null) {
            for (MapIntroSyncPayload.MapJson map : maps) {
                if (map == null || map.id() == null || map.id().isBlank()) {
                    continue;
                }
                try {
                    JsonObject root = JsonParser.parseString(map.json()).getAsJsonObject();
                    MAP_JSONS.put(map.id(), root);
                } catch (Exception e) {
                    MAP_JSONS.put(map.id(), new JsonObject());
                }
            }
        }

        hasData = !MAP_JSONS.isEmpty() || !VOTE_MAPS.isEmpty();
        HabiTrainCore.LOGGER.debug("ClientMapIntroCache updated: {} maps, {} voteConfigs",
                MAP_JSONS.size(), VOTE_MAPS.size());
    }

    /**
     * 按需请求地图介绍同步（带冷却防抖）。
     */
    public static synchronized void requestSyncIfNeeded() {
        long now = Util.getMillis();
        if (now - lastRequestMillis < REQUEST_COOLDOWN_MILLIS) {
            return;
        }
        lastRequestMillis = now;
        try {
            ClientPlayNetworking.send(new MapIntroRequestPayload());
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("Failed to send MapIntroRequestPayload", e);
        }
    }

    /**
     * 强制重新请求地图介绍同步（忽略冷却）。
     */
    public static synchronized void forceRequestSync() {
        lastRequestMillis = Util.getMillis();
        try {
            ClientPlayNetworking.send(new MapIntroRequestPayload());
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("Failed to force send MapIntroRequestPayload", e);
        }
    }

    public static synchronized boolean hasData() {
        return hasData;
    }

    public static synchronized MapIntroSyncPayload getLatestPayload() {
        return latestPayload;
    }

    public static synchronized JsonObject getMapJson(String mapId) {
        return mapId == null ? null : MAP_JSONS.get(mapId);
    }

    public static synchronized MapIntroSyncPayload.VoteMap getVoteMap(String mapId) {
        return mapId == null ? null : VOTE_MAPS.get(mapId);
    }

    public static synchronized MapIntroDetail.SpecialSets getSpecialSets() {
        return new MapIntroDetail.SpecialSets(
                Collections.unmodifiableSet(new HashSet<>(BAG_MAPS)),
                Collections.unmodifiableSet(new HashSet<>(POLICE_MAPS)),
                Collections.unmodifiableSet(new HashSet<>(UNDERWATER_MAPS)),
                Collections.unmodifiableSet(new HashSet<>(AIR_MAPS)),
                Collections.unmodifiableSet(new HashSet<>(TRAP_MAPS)),
                Collections.unmodifiableSet(new HashSet<>(HORSE_MAPS))
        );
    }

    public static synchronized Set<String> getBagMaps() {
        return Collections.unmodifiableSet(new HashSet<>(BAG_MAPS));
    }

    public static synchronized Set<String> getPoliceMaps() {
        return Collections.unmodifiableSet(new HashSet<>(POLICE_MAPS));
    }

    public static synchronized Set<String> getUnderwaterMaps() {
        return Collections.unmodifiableSet(new HashSet<>(UNDERWATER_MAPS));
    }

    public static synchronized Set<String> getAirMaps() {
        return Collections.unmodifiableSet(new HashSet<>(AIR_MAPS));
    }

    public static synchronized Set<String> getTrapMaps() {
        return Collections.unmodifiableSet(new HashSet<>(TRAP_MAPS));
    }

    public static synchronized Set<String> getHorseMaps() {
        return Collections.unmodifiableSet(new HashSet<>(HORSE_MAPS));
    }

    public static synchronized com.habitrain.core.network.MapVoteProfilePayload.MapProfile getProfile(String mapId) {
        if (mapId == null) return null;
        com.habitrain.core.network.MapVoteProfilePayload.MapProfile p = PROFILES.get(mapId);
        if (p != null && p.previewBytes() != null && p.previewBytes().length > 0) {
            return p;
        }
        // 本地/单机/配置备用兜底
        try {
            com.habitrain.core.config.MapVoteEntry entry =
                    com.habitrain.core.config.ConfigManager.getInstance().getModeMapVoteSettings().maps.get(mapId);
            if (entry != null && entry.profile != null) {
                byte[] preview = (p != null && p.previewBytes() != null && p.previewBytes().length > 0)
                        ? p.previewBytes()
                        : loadLocalPreviewBytes(mapId, entry.profile.previewPath);
                var loaded = new com.habitrain.core.network.MapVoteProfilePayload.MapProfile(
                        entry.profile.description != null ? entry.profile.description : "",
                        entry.profile.tags != null ? entry.profile.tags : List.of(),
                        entry.minPlayers,
                        entry.maxPlayers,
                        preview
                );
                PROFILES.put(mapId, loaded);
                return loaded;
            }
        } catch (Throwable ignored) {}
        return p;
    }

    private static byte[] loadLocalPreviewBytes(String mapId, String previewPath) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.getSingleplayerServer() != null) {
                var overworld = mc.getSingleplayerServer().overworld();
                if (overworld != null) {
                    java.nio.file.Path base = com.habitrain.core.vote.MapVoteProfileStore.baseDir(overworld);
                    String pathStr = (previewPath != null && !previewPath.isBlank())
                            ? previewPath
                            : com.habitrain.core.vote.MapVoteProfileStore.PREVIEW_DIR + "/"
                            + com.habitrain.core.vote.MapVoteProfileStore.escapeId(mapId) + ".png";
                    java.nio.file.Path file = base.resolve(pathStr).normalize();
                    if (file.startsWith(base) && java.nio.file.Files.isRegularFile(file)) {
                        return java.nio.file.Files.readAllBytes(file);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return new byte[0];
    }

    public static synchronized String getDescription(String mapId) {
        var profile = getProfile(mapId);
        return profile != null && profile.description() != null ? profile.description() : "";
    }

    public static synchronized List<String> getTags(String mapId) {
        var profile = getProfile(mapId);
        return profile != null && profile.tags() != null ? profile.tags() : List.of();
    }

    public static synchronized byte[] getPreviewBytes(String mapId) {
        var profile = getProfile(mapId);
        return profile != null && profile.previewBytes() != null ? profile.previewBytes() : new byte[0];
    }

    public static synchronized String getCustomDisplayName(String mapId) {
        if (mapId == null) return "";
        try {
            com.habitrain.core.config.MapVoteEntry entry =
                    com.habitrain.core.config.ConfigManager.getInstance().getModeMapVoteSettings().maps.get(mapId);
            if (entry != null && entry.displayName != null && !entry.displayName.isBlank()) {
                return entry.displayName;
            }
        } catch (Throwable ignored) {}
        var vm = getVoteMap(mapId);
        if (vm != null && vm.displayName() != null && !vm.displayName().isBlank()) {
            return vm.displayName();
        }
        return "";
    }

    public static synchronized void clear() {
        MAP_JSONS.clear();
        VOTE_MAPS.clear();
        PROFILES.clear();
        BAG_MAPS.clear();
        POLICE_MAPS.clear();
        UNDERWATER_MAPS.clear();
        AIR_MAPS.clear();
        TRAP_MAPS.clear();
        HORSE_MAPS.clear();
        latestPayload = null;
        hasData = false;
        lastRequestMillis = 0L;
    }
}
