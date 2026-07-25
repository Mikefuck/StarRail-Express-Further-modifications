package com.habitrain.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Global map-pool rotation under {@link ModeMapVoteSettings} (per-round advance).
 * Pool count is variable ({@link #MIN_POOLS}–{@link #MAX_POOLS}); default seed is
 * {@link #DEFAULT_POOL_COUNT} empty pools.
 */
public final class MapPoolRotationSettings {
    public static final int MIN_POOLS = 1;
    public static final int MAX_POOLS = 20;
    public static final int DEFAULT_POOL_COUNT = 6;
    public static final String APPLY_LIMIT_VOTE = "LIMIT_VOTE";
    public static final String APPLY_DIRECT_PICK = "DIRECT_PICK";

    public boolean enabled = false;
    public boolean autoRepartition = true;
    /** {@link #APPLY_LIMIT_VOTE} or {@link #APPLY_DIRECT_PICK}. */
    public String applyMode = APPLY_LIMIT_VOTE;
    public int activePoolIndex = 0;
    /** yyyy-MM-dd server local, or null if never advanced by calendar. */
    public String lastRotationDate = null;
    /**
     * Successful advances since last repartition; when &gt;= {@link #poolCount()} and
     * autoRepartition, re-split.
     */
    public int poolsAdvancedSinceRepartition = 0;
    public final List<MapPoolEntry> pools = new ArrayList<>();

    public MapPoolRotationSettings() {
        ensurePools();
    }

    public static MapPoolRotationSettings createDefault() {
        return new MapPoolRotationSettings();
    }

    public int poolCount() {
        return Math.max(0, pools.size());
    }

    /**
     * Ensure at least {@link #MIN_POOLS} valid pools (seed {@link #DEFAULT_POOL_COUNT} when empty),
     * cap at {@link #MAX_POOLS}, normalize names/lists, clamp active index.
     */
    public void ensurePools() {
        if (pools.isEmpty()) {
            for (int i = 0; i < DEFAULT_POOL_COUNT; i++) {
                pools.add(MapPoolEntry.createDefault(i + 1));
            }
        }
        while (pools.size() < MIN_POOLS) {
            pools.add(MapPoolEntry.createDefault(pools.size() + 1));
        }
        while (pools.size() > MAX_POOLS) {
            pools.remove(pools.size() - 1);
        }
        for (int i = 0; i < pools.size(); i++) {
            MapPoolEntry p = pools.get(i);
            if (p == null) {
                pools.set(i, MapPoolEntry.createDefault(i + 1));
            } else if (p.displayName == null || p.displayName.isBlank()) {
                p.displayName = "池" + (i + 1);
            }
            if (p.mapIds == null) p.mapIds = new ArrayList<>();
        }
        activePoolIndex = clampIndex(activePoolIndex);
    }

    public int clampIndex(int index) {
        int n = poolCount();
        if (n <= 0) return 0;
        if (index < 0) return 0;
        if (index >= n) return n - 1;
        return index;
    }

    public boolean isDirectPick() {
        return APPLY_DIRECT_PICK.equalsIgnoreCase(applyMode);
    }

    public boolean isLimitVote() {
        return !isDirectPick();
    }

    public MapPoolEntry poolAt(int index) {
        ensurePools();
        return pools.get(clampIndex(index));
    }

    public boolean allPoolsEmpty() {
        ensurePools();
        for (MapPoolEntry p : pools) {
            if (p.mapIds != null && !p.mapIds.isEmpty()) return false;
        }
        return true;
    }

    /** Append an empty pool. @return true if added */
    public boolean addPool() {
        ensurePools();
        if (pools.size() >= MAX_POOLS) return false;
        pools.add(MapPoolEntry.createDefault(pools.size() + 1));
        return true;
    }

    /**
     * Remove pool at index. Keeps at least {@link #MIN_POOLS}.
     * @return true if removed
     */
    public boolean removePool(int index) {
        ensurePools();
        if (pools.size() <= MIN_POOLS) return false;
        if (index < 0 || index >= pools.size()) return false;
        pools.remove(index);
        activePoolIndex = clampIndex(activePoolIndex);
        return true;
    }

    public JsonObject toJson() {
        ensurePools();
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("autoRepartition", autoRepartition);
        o.addProperty("applyMode", applyMode != null ? applyMode : APPLY_LIMIT_VOTE);
        o.addProperty("activePoolIndex", clampIndex(activePoolIndex));
        if (lastRotationDate != null) {
            o.addProperty("lastRotationDate", lastRotationDate);
        } else {
            o.add("lastRotationDate", com.google.gson.JsonNull.INSTANCE);
        }
        o.addProperty("poolsAdvancedSinceRepartition", Math.max(0, poolsAdvancedSinceRepartition));
        JsonArray arr = new JsonArray();
        for (MapPoolEntry p : pools) {
            arr.add(p.toJson());
        }
        o.add("pools", arr);
        return o;
    }

    public static MapPoolRotationSettings fromJson(JsonObject o) {
        MapPoolRotationSettings s = new MapPoolRotationSettings();
        s.pools.clear();
        if (o == null) {
            s.ensurePools();
            return s;
        }
        if (o.has("enabled")) s.enabled = o.get("enabled").getAsBoolean();
        if (o.has("autoRepartition")) s.autoRepartition = o.get("autoRepartition").getAsBoolean();
        if (o.has("applyMode") && !o.get("applyMode").isJsonNull()) {
            String mode = o.get("applyMode").getAsString();
            if (APPLY_DIRECT_PICK.equalsIgnoreCase(mode)) {
                s.applyMode = APPLY_DIRECT_PICK;
            } else {
                s.applyMode = APPLY_LIMIT_VOTE;
            }
        }
        if (o.has("lastRotationDate") && !o.get("lastRotationDate").isJsonNull()) {
            s.lastRotationDate = o.get("lastRotationDate").getAsString();
            if (s.lastRotationDate != null && s.lastRotationDate.isBlank()) {
                s.lastRotationDate = null;
            }
        }
        if (o.has("poolsAdvancedSinceRepartition")) {
            s.poolsAdvancedSinceRepartition = Math.max(0, o.get("poolsAdvancedSinceRepartition").getAsInt());
        }
        if (o.has("pools") && o.get("pools").isJsonArray()) {
            for (var el : o.getAsJsonArray("pools")) {
                if (el != null && el.isJsonObject()) {
                    s.pools.add(MapPoolEntry.fromJson(el.getAsJsonObject()));
                }
            }
        }
        s.ensurePools();
        // clamp after pools known
        if (o.has("activePoolIndex")) {
            s.activePoolIndex = s.clampIndex(o.get("activePoolIndex").getAsInt());
        } else {
            s.activePoolIndex = s.clampIndex(s.activePoolIndex);
        }
        return s;
    }
}
