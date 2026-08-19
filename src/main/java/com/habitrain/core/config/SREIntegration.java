package com.habitrain.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.content.minigame.QuestMinigame;
import io.wifi.starrailexpress.content.minigame.QuestMinigames;
import io.wifi.starrailexpress.game.MapManager;
import io.wifi.starrailexpress.game.data.MapConfig;
import io.wifi.starrailexpress.game.data.ServerMapConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter isolating SRE DLC dependency behind try/catch wrappers.
 * This is the only class in the config package that directly references SRE types.
 */
public final class SREIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger("SREIntegration");

    public record DiscoveredMapInfo(
            String id,
            String displayName,
            int minPlayers,
            int maxPlayers,
            boolean enabled,
            String description,
            String color,
            List<String> gameModes
    ) {}

    private SREIntegration() {}

    /**
     * @return all registered QuestMinigame IDs, or empty list if SRE is not installed
     */
    public static List<String> getAllMinigameIds() {
        try {
            return QuestMinigames.getAll().stream()
                    .map(QuestMinigame::id)
                    .toList();
        } catch (Throwable t) {
            LOGGER.warn("SRE not available, returning empty minigame list", t);
            return List.of();
        }
    }

    /**
     * Discover all maps from both ServerMapConfig (train_vote_maps.json) and MapManager
     * (train_maps/ instance files), filtering reserved IDs.
     */
    public static Map<String, DiscoveredMapInfo> discoverServerMaps(ServerLevel level) {
        if (level == null) {
            return discoverClientFallbackMaps();
        }
        return discoverServerMaps(level.getServer(), level);
    }

    public static Map<String, DiscoveredMapInfo> discoverServerMaps(MinecraftServer server) {
        if (server == null) {
            return discoverClientFallbackMaps();
        }
        ServerLevel overworld = server.overworld();
        return discoverServerMaps(server, overworld);
    }

    private static Map<String, DiscoveredMapInfo> discoverServerMaps(MinecraftServer server, ServerLevel level) {
        Map<String, DiscoveredMapInfo> result = new LinkedHashMap<>();
        try {
            // Layer 1: Actual available maps on disk from MapManager (<world>/train_maps/ directory)
            List<String> available = level != null ? MapManager.getAvailableMaps(level, true) : null;
            ServerMapConfig serverMapConfig = server != null ? ServerMapConfig.getInstance(server) : null;

            if (available != null) {
                // When level is available, train_maps/ on disk is the authoritative source of truth.
                for (String mapId : available) {
                    if (mapId == null || mapId.isBlank() || isReservedMapId(mapId)) continue;

                    MapConfig.MapEntry entry = serverMapConfig != null ? serverMapConfig.getMapById(mapId) : null;
                    if (entry != null) {
                        int minCount = entry.minCount > 0 ? entry.minCount : 0;
                        int maxCount = entry.maxCount > 0 ? entry.maxCount : 0;
                        String dn = entry.displayName != null ? entry.displayName : "";
                        String desc = entry.description != null ? entry.description : "";
                        String col = entry.color != null ? entry.color : "";
                        List<String> gms = entry.gameModes != null ? List.copyOf(entry.gameModes) : List.of();

                        result.put(mapId, new DiscoveredMapInfo(
                                mapId, dn, minCount, maxCount, entry.canSelect, desc, col, gms
                        ));
                    } else {
                        String dn = MapManager.getVoteMapName(level, mapId);
                        if (dn == null || dn.equals(mapId)) dn = "";
                        result.put(mapId, new DiscoveredMapInfo(
                                mapId, dn, 0, 0, true, "", "", List.of()
                        ));
                    }
                }
            } else if (serverMapConfig != null && serverMapConfig.getMaps() != null) {
                // Fallback for headless / test environments where level is null but server exists
                for (MapConfig.MapEntry entry : serverMapConfig.getMaps()) {
                    if (entry == null || entry.id == null || entry.id.isBlank()) continue;
                    if (isReservedMapId(entry.id)) continue;

                    int minCount = entry.minCount > 0 ? entry.minCount : 0;
                    int maxCount = entry.maxCount > 0 ? entry.maxCount : 0;
                    String dn = entry.displayName != null ? entry.displayName : "";
                    String desc = entry.description != null ? entry.description : "";
                    String col = entry.color != null ? entry.color : "";
                    List<String> gms = entry.gameModes != null ? List.copyOf(entry.gameModes) : List.of();

                    result.put(entry.id, new DiscoveredMapInfo(
                            entry.id, dn, minCount, maxCount, entry.canSelect, desc, col, gms
                    ));
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("discoverServerMaps encounter error, falling back to client discovery", t);
        }

        if (result.isEmpty() && server == null && level == null) {
            return discoverClientFallbackMaps();
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Discovers maps on client side (e.g. title screen / main menu before world start).
     * Tries client MapConfig instance first, then upstream default maps.json in assets.
     */
    public static Map<String, DiscoveredMapInfo> discoverClientFallbackMaps() {
        Map<String, DiscoveredMapInfo> result = new LinkedHashMap<>();
        try {
            // 1. Check client MapConfig instance
            MapConfig clientConfig = MapConfig.getInstance();
            if (clientConfig != null && clientConfig.getMaps() != null) {
                for (MapConfig.MapEntry entry : clientConfig.getMaps()) {
                    if (entry == null || entry.id == null || entry.id.isBlank()) continue;
                    if (isReservedMapId(entry.id)) continue;
                    int minCount = entry.minCount > 0 ? entry.minCount : 0;
                    int maxCount = entry.maxCount > 0 ? entry.maxCount : 0;
                    String dn = entry.displayName != null ? entry.displayName : "";
                    String desc = entry.description != null ? entry.description : "";
                    String col = entry.color != null ? entry.color : "";
                    List<String> gms = entry.gameModes != null ? List.copyOf(entry.gameModes) : List.of();
                    result.put(entry.id, new DiscoveredMapInfo(
                            entry.id, dn, minCount, maxCount, entry.canSelect, desc, col, gms
                    ));
                }
            }

            // 2. If empty or only contains "random", try loading default maps.json from mod assets
            if (result.isEmpty() || (result.size() == 1 && result.containsKey("random"))) {
                loadDefaultMapsFromAssets(result);
            }
        } catch (Throwable t) {
            LOGGER.debug("discoverClientFallbackMaps failed", t);
            loadDefaultMapsFromAssets(result);
        }

        return Collections.unmodifiableMap(result);
    }

    private static void loadDefaultMapsFromAssets(Map<String, DiscoveredMapInfo> target) {
        try (InputStream stream = SREIntegration.class.getResourceAsStream("/assets/starrailexpress/config/maps.json")) {
            if (stream != null) {
                try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("maps") && root.get("maps").isJsonArray()) {
                        JsonArray mapsArr = root.getAsJsonArray("maps");
                        for (JsonElement el : mapsArr) {
                            if (!el.isJsonObject()) continue;
                            JsonObject obj = el.getAsJsonObject();
                            String id = obj.has("id") ? obj.get("id").getAsString() : null;
                            if (id == null || id.isBlank() || isReservedMapId(id)) continue;
                            String dn = obj.has("displayName") ? obj.get("displayName").getAsString() : "";
                            String desc = obj.has("description") ? obj.get("description").getAsString() : "";
                            String col = obj.has("color") ? obj.get("color").getAsString() : "";
                            int minCount = obj.has("mincount") ? obj.get("mincount").getAsInt() : 0;
                            if (minCount < 0) minCount = 0;
                            int maxCount = obj.has("maxcount") ? obj.get("maxcount").getAsInt() : 0;
                            if (maxCount < 0) maxCount = 0;
                            boolean canSel = !obj.has("canSelect") || obj.get("canSelect").getAsBoolean();

                            List<String> gms = new ArrayList<>();
                            if (obj.has("gameModes") && obj.get("gameModes").isJsonArray()) {
                                for (JsonElement gmEl : obj.getAsJsonArray("gameModes")) {
                                    gms.add(gmEl.getAsString());
                                }
                            }

                            if (!target.containsKey(id)) {
                                target.put(id, new DiscoveredMapInfo(
                                        id, dn, minCount, maxCount, canSel, desc, col, gms
                                ));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Check if the map ID is a reserved internal path (like map_vote).
     */
    public static boolean isReservedMapId(String mapId) {
        if (mapId == null || mapId.isBlank()) return true;
        String s = mapId.replace('\\', '/');
        return s.equals("map_vote") || s.startsWith("map_vote/") || s.equalsIgnoreCase("random");
    }

    /**
     * Resolve upstream map display name by id, or return the id if unavailable.
     */
    public static String getMapDisplayName(String mapId) {
        if (mapId == null || mapId.isBlank()) return "";
        try {
            MapConfig config = MapConfig.getInstance();
            if (config != null) {
                MapConfig.MapEntry entry = config.getMapById(mapId);
                if (entry != null && entry.displayName != null && !entry.displayName.isBlank()) {
                    return entry.displayName;
                }
            }
        } catch (Throwable ignored) {}
        return mapId;
    }

    /**
     * 将 ModeMapVoteSettings 中的地图显示名、推荐人数及启用状态同步写入 SRE 的 ServerMapConfig。
     */
    public static void syncToSREServerMapConfig(MinecraftServer server, ModeMapVoteSettings settings) {
        if (server == null || settings == null) return;
        try {
            ServerMapConfig mapConfig = ServerMapConfig.getInstance(server);
            if (mapConfig != null && mapConfig.getMaps() != null) {
                boolean changed = false;
                for (MapConfig.MapEntry entry : mapConfig.getMaps()) {
                    if (entry == null || entry.id == null) continue;
                    MapVoteEntry custom = settings.maps.get(entry.id);
                    if (custom != null) {
                        if (custom.displayName != null && !custom.displayName.isBlank()
                                && !custom.displayName.equals(entry.displayName)) {
                            entry.displayName = custom.displayName;
                            changed = true;
                        }
                        if (custom.minPlayers > 0 && entry.minCount != custom.minPlayers) {
                            entry.minCount = custom.minPlayers;
                            changed = true;
                        }
                        if (custom.maxPlayers > 0 && entry.maxCount != custom.maxPlayers) {
                            entry.maxCount = custom.maxPlayers;
                            changed = true;
                        }
                        if (entry.canSelect != custom.enabled) {
                            entry.canSelect = custom.enabled;
                            changed = true;
                        }
                    }
                }
                if (changed) {
                    mapConfig.saveConfig(server);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("syncToSREServerMapConfig failed", t);
        }
    }

    /**
     * Apply minigame enforcement to all world levels via AreasWorldComponent.
     * Filters minigames per-level based on each level's map name.
     */
    public static void applyMinigameSettings(MinecraftServer server,
                                              boolean globalEnabled,
                                              List<String> allMinigameIds,
                                              ConfigRepository repo) {
        if (server == null) return;
        try {
            for (var level : server.getAllLevels()) {
                var areas = AreasWorldComponent.KEY.get(level);
                if (areas == null) continue;

                areas.areasSettings.minigameQuestEnabled = globalEnabled;
                String mapName = areas.mapName != null ? areas.mapName : "";
                areas.availableMinigameIds.clear();
                for (String mgId : allMinigameIds) {
                    MinigameConfigEntry entry = repo.getMinigameConfig(mgId);
                    if (entry == null || (entry.enabled && entry.isAllowedOnMap(mapName))) {
                        areas.availableMinigameIds.add(mgId);
                    }
                }
                areas.sync();
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to apply minigame settings, SRE may not be installed", t);
        }
    }
}
