package com.habitrain.core.config;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ModeMapVoteSettings {
    public boolean enabled = true;
    public int modeDurationSeconds = 15;
    public int mapDurationSeconds = 15;
    public final Map<String, ModeVoteEntry> modes = new LinkedHashMap<>();
    public final Map<String, MapVoteEntry> maps = new LinkedHashMap<>();
    /**
     * Player-count based map draw (replaces the old fixed pool rotation); never null
     * after {@link #playerCountOrDefault()}.
     */
    public MapPlayerCountSettings mapPlayerCountDraw = MapPlayerCountSettings.createDefault();

    public static ModeMapVoteSettings createDefault() {
        ModeMapVoteSettings s = new ModeMapVoteSettings();
        s.mapPlayerCountDraw = MapPlayerCountSettings.createDefault();
        return s;
    }

    public MapPlayerCountSettings playerCountOrDefault() {
        if (mapPlayerCountDraw == null) {
            mapPlayerCountDraw = MapPlayerCountSettings.createDefault();
        }
        return mapPlayerCountDraw;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("modeDurationSeconds", modeDurationSeconds);
        o.addProperty("mapDurationSeconds", mapDurationSeconds);

        JsonObject modesObj = new JsonObject();
        for (Map.Entry<String, ModeVoteEntry> e : modes.entrySet()) {
            modesObj.add(e.getKey(), e.getValue().toJson());
        }
        o.add("modes", modesObj);

        JsonObject mapsObj = new JsonObject();
        for (Map.Entry<String, MapVoteEntry> e : maps.entrySet()) {
            mapsObj.add(e.getKey(), e.getValue().toJson());
        }
        o.add("maps", mapsObj);
        o.add("mapPlayerCountDraw", playerCountOrDefault().toJson());
        return o;
    }

    public static ModeMapVoteSettings fromJson(JsonObject o) {
        ModeMapVoteSettings s = createDefault();
        if (o.has("enabled")) s.enabled = o.get("enabled").getAsBoolean();
        if (o.has("modeDurationSeconds")) {
            s.modeDurationSeconds = clampDuration(o.get("modeDurationSeconds").getAsInt());
        }
        if (o.has("mapDurationSeconds")) {
            s.mapDurationSeconds = clampDuration(o.get("mapDurationSeconds").getAsInt());
        }
        if (o.has("modes") && o.get("modes").isJsonObject()) {
            JsonObject modesObj = o.getAsJsonObject("modes");
            for (var e : modesObj.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    s.modes.put(e.getKey(), ModeVoteEntry.fromJson(e.getValue().getAsJsonObject()));
                }
            }
        }
        if (o.has("maps") && o.get("maps").isJsonObject()) {
            JsonObject mapsObj = o.getAsJsonObject("maps");
            for (var e : mapsObj.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    s.maps.put(e.getKey(), MapVoteEntry.fromJson(e.getValue().getAsJsonObject()));
                }
            }
        }
        if (o.has("mapPlayerCountDraw") && o.get("mapPlayerCountDraw").isJsonObject()) {
            s.mapPlayerCountDraw = MapPlayerCountSettings.fromJson(o.getAsJsonObject("mapPlayerCountDraw"));
        } else {
            // Migration from the removed fixed-pool rotation: keep the "enabled" intent.
            if (o.has("mapPoolRotation") && o.get("mapPoolRotation").isJsonObject()) {
                JsonObject old = o.getAsJsonObject("mapPoolRotation");
                if (old.has("enabled") && old.get("enabled").getAsBoolean()) {
                    s.mapPlayerCountDraw.enabled = true;
                }
            }
        }
        return s;
    }

    /** Insert missing keys only; never overwrite existing entries. */
    public void ensureDefaults(Iterable<String> modeIds, Iterable<String> mapIds) {
        if (modeIds != null) {
            for (String id : modeIds) {
                if (id != null && !id.isBlank()) {
                    modes.computeIfAbsent(id, k -> ModeVoteEntry.createDefault());
                }
            }
        }
        if (mapIds != null) {
            for (String id : mapIds) {
                if (id != null && !id.isBlank()) {
                    maps.computeIfAbsent(id, k -> MapVoteEntry.createDefault());
                }
            }
        }
    }

    /**
     * Ensure map defaults using discovered upstream map metadata.
     * Missing entries are created with default metadata; existing entries preserve
     * user configuration but inherit empty display names.
     *
     * @return true if any entry was inserted or updated
     */
    public boolean ensureMapDefaultsWithInfo(Iterable<String> modeIds,
                                             Map<String, SREIntegration.DiscoveredMapInfo> mapInfoMap) {
        boolean changed = false;
        if (modeIds != null) {
            for (String id : modeIds) {
                if (id != null && !id.isBlank() && !modes.containsKey(id)) {
                    modes.put(id, ModeVoteEntry.createDefault());
                    changed = true;
                }
            }
        }
        if (mapInfoMap != null) {
            for (Map.Entry<String, SREIntegration.DiscoveredMapInfo> entry : mapInfoMap.entrySet()) {
                String mapId = entry.getKey();
                SREIntegration.DiscoveredMapInfo info = entry.getValue();
                if (mapId == null || mapId.isBlank() || info == null) continue;

                MapVoteEntry cur = maps.get(mapId);
                if (cur == null) {
                    MapVoteEntry created = MapVoteEntry.createDefault();
                    created.enabled = info.enabled();
                    created.displayName = info.displayName() != null ? info.displayName() : "";
                    created.minPlayers = info.minPlayers();
                    created.maxPlayers = info.maxPlayers();
                    maps.put(mapId, created);
                    changed = true;
                } else {
                    // Existing entry: preserve user customizations (enabled, min/maxPlayers, profile),
                    // but if displayName is blank, populate it from upstream metadata
                    if ((cur.displayName == null || cur.displayName.isBlank())
                            && info.displayName() != null && !info.displayName().isBlank()) {
                        cur.displayName = info.displayName();
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    /**
     * Synchronize with active world maps and prune orphan maps that no longer exist.
     * Also prunes invalid map IDs from each mode's allowedMaps list.
     *
     * @param modeIds known mode IDs
     * @param activeMaps currently active maps discovered in the world
     * @return true if maps were added, updated, or pruned
     */
    public boolean syncAndPruneMaps(Iterable<String> modeIds,
                                     Map<String, SREIntegration.DiscoveredMapInfo> activeMaps) {
        boolean changed = false;
        if (activeMaps != null) {
            // Prune maps that are no longer present in the active world
            java.util.Set<String> toRemove = new java.util.HashSet<>();
            for (String existingId : maps.keySet()) {
                if (!activeMaps.containsKey(existingId)) {
                    toRemove.add(existingId);
                }
            }
            if (!toRemove.isEmpty()) {
                maps.keySet().removeAll(toRemove);
                changed = true;
            }

            // Prune removed map IDs from all modes' allowedMaps
            for (ModeVoteEntry mode : modes.values()) {
                if (mode != null && mode.allowedMaps != null && !mode.allowedMaps.isEmpty()) {
                    if (mode.allowedMaps.removeIf(id -> !activeMaps.containsKey(id))) {
                        changed = true;
                    }
                }
            }
        }

        if (ensureMapDefaultsWithInfo(modeIds, activeMaps)) {
            changed = true;
        }

        return changed;
    }

    /**
     * Remove a map entry from settings and clear its reference from all modes' allowedMaps.
     *
     * @param mapId the map ID to remove
     * @return true if map was removed
     */
    public boolean removeMap(String mapId) {
        if (mapId == null || mapId.isBlank()) return false;
        boolean removed = maps.remove(mapId) != null;
        for (ModeVoteEntry mode : modes.values()) {
            if (mode != null && mode.allowedMaps != null) {
                if (mode.allowedMaps.remove(mapId)) {
                    removed = true;
                }
            }
        }
        return removed;
    }

    /**
     * Move a mode entry one step up or down in {@link #modes} LinkedHashMap order.
     *
     * @param modeId mode full id
     * @param delta  -1 = up, +1 = down
     * @return true if order changed
     */
    public boolean moveMode(String modeId, int delta) {
        if (modeId == null || modes.isEmpty() || delta == 0) {
            return false;
        }
        java.util.List<String> keys = new java.util.ArrayList<>(modes.keySet());
        int idx = keys.indexOf(modeId);
        if (idx < 0) {
            return false;
        }
        int target = idx + delta;
        if (target < 0 || target >= keys.size()) {
            return false;
        }
        java.util.Collections.swap(keys, idx, target);
        java.util.LinkedHashMap<String, ModeVoteEntry> rebuilt = new java.util.LinkedHashMap<>();
        for (String k : keys) {
            rebuilt.put(k, modes.get(k));
        }
        modes.clear();
        modes.putAll(rebuilt);
        return true;
    }

    private static int clampDuration(int seconds) {
        return Math.max(5, Math.min(120, seconds));
    }
}
