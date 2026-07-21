package com.habitrain.core.vote;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapPoolEntry;
import com.habitrain.core.config.MapPoolRotationSettings;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.ModeMapVoteSettings;
import com.habitrain.core.network.FullConfigSyncPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Variable-count map pool rotation: per-round advance; balanced multi-membership repartition;
 * skip, resolve effective maps.
 */
public final class MapPoolRotationService {
    private static final Logger LOGGER = LoggerFactory.getLogger("MapPoolRotationService");
    public static final int MIN_CANDIDATES = 4;
    public static final int PAD_TARGET = 4;
    public static final int MAPS_PER_POOL = 4;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private MapPoolRotationService() {}

    public static boolean shouldApply(@Nullable ModeMapVoteSettings settings, int candidateCount) {
        if (settings == null) return false;
        MapPoolRotationSettings rot = settings.rotationOrDefault();
        return rot.enabled && candidateCount >= MIN_CANDIDATES && rot.poolCount() >= 1;
    }

    /** Globally enabled map ids from settings.maps (missing entry = enabled). */
    public static List<String> globalEnabledMapIds(ModeMapVoteSettings settings) {
        List<String> out = new ArrayList<>();
        if (settings == null || settings.maps == null) return out;
        for (var e : settings.maps.entrySet()) {
            String id = e.getKey();
            if (id == null || id.isBlank()) continue;
            MapVoteEntry me = e.getValue();
            if (me != null && !me.enabled) continue;
            out.add(id);
        }
        return out;
    }

    /**
     * Balanced multi-membership repartition: each pool gets up to {@link #MAPS_PER_POOL}
     * maps; maps may appear in multiple pools, preferring lowest global occurrence.
     */
    public static void repartition(ModeMapVoteSettings settings, Random random) {
        if (settings == null) return;
        MapPoolRotationSettings rot = settings.rotationOrDefault();
        int poolN = rot.poolCount();
        if (poolN <= 0) return;
        List<String> all = new ArrayList<>(globalEnabledMapIds(settings));
        Random rng = random != null ? random : new Random();

        if (all.isEmpty()) {
            for (int i = 0; i < poolN; i++) {
                MapPoolEntry pool = rot.poolAt(i);
                pool.mapIds = new ArrayList<>();
            }
            rot.poolsAdvancedSinceRepartition = 0;
            LOGGER.info("[MapPool] repartitioned empty maps into {} pools", poolN);
            return;
        }

        // Global occurrence counts for balance across pools.
        HashMap<String, Integer> count = new HashMap<>();
        for (String id : all) count.put(id, 0);

        int k = Math.min(MAPS_PER_POOL, all.size());
        for (int i = 0; i < poolN; i++) {
            MapPoolEntry pool = rot.poolAt(i);
            pool.mapIds = new ArrayList<>();
            Set<String> chosen = new LinkedHashSet<>();
            while (chosen.size() < k) {
                int best = Integer.MAX_VALUE;
                List<String> candidates = new ArrayList<>();
                for (String id : all) {
                    if (chosen.contains(id)) continue;
                    int c = count.getOrDefault(id, 0);
                    if (c < best) {
                        best = c;
                        candidates.clear();
                        candidates.add(id);
                    } else if (c == best) {
                        candidates.add(id);
                    }
                }
                if (candidates.isEmpty()) break;
                String pick = candidates.get(rng.nextInt(candidates.size()));
                chosen.add(pick);
                pool.mapIds.add(pick);
                count.put(pick, count.getOrDefault(pick, 0) + 1);
            }
        }
        rot.poolsAdvancedSinceRepartition = 0;
        LOGGER.info("[MapPool] balanced repartition n={} pools={} mapsPerPool={}", all.size(), poolN, k);
    }

    /**
     * If rotation is enabled and pools are empty while enough maps exist, fill once.
     */
    public static void ensureSeededIfNeeded(ModeMapVoteSettings settings, Random random) {
        if (settings == null) return;
        MapPoolRotationSettings rot = settings.rotationOrDefault();
        if (!rot.enabled) return;
        if (!rot.allPoolsEmpty()) return;
        List<String> all = globalEnabledMapIds(settings);
        if (all.size() < MIN_CANDIDATES) return;
        repartition(settings, random != null ? random : new Random());
    }

    /**
     * Resolve effective map ids for a mode round.
     */
    public static List<String> resolveEffectiveMaps(
            ModeMapVoteSettings settings,
            List<String> candidates,
            Random random
    ) {
        List<String> cand = candidates != null ? new ArrayList<>(candidates) : List.of();
        if (cand.isEmpty()) return List.of();
        MapPoolRotationSettings rot = settings.rotationOrDefault();
        ensureSeededIfNeeded(settings, random);

        int poolN = rot.poolCount();
        if (poolN <= 0) return new ArrayList<>(cand);

        int start = rot.clampIndex(rot.activePoolIndex);
        int chosen = -1;
        for (int step = 0; step < poolN; step++) {
            int idx = (start + step) % poolN;
            MapPoolEntry pool = rot.poolAt(idx);
            if (pool.enabled) {
                chosen = idx;
                break;
            }
        }
        if (chosen < 0) {
            return new ArrayList<>(cand);
        }
        rot.activePoolIndex = chosen;

        Set<String> candSet = new LinkedHashSet<>(cand);
        List<String> effective = new ArrayList<>();
        MapPoolEntry pool = rot.poolAt(chosen);
        if (pool.mapIds != null) {
            for (String id : pool.mapIds) {
                if (candSet.contains(id) && !effective.contains(id)) {
                    effective.add(id);
                }
            }
        }

        int target = Math.min(PAD_TARGET, cand.size());
        if (effective.size() < target) {
            List<String> rest = new ArrayList<>();
            for (String id : cand) {
                if (!effective.contains(id)) rest.add(id);
            }
            if (random != null) {
                Collections.shuffle(rest, random);
            } else {
                Collections.shuffle(rest);
            }
            for (String id : rest) {
                if (effective.size() >= target) break;
                effective.add(id);
            }
        }
        return effective;
    }

    /**
     * Advance to next enabled pool; may auto-repartition after a full cycle of poolCount advances.
     * @return true if settings changed
     */
    public static boolean advance(ModeMapVoteSettings settings, @Nullable Random random) {
        if (settings == null) return false;
        MapPoolRotationSettings rot = settings.rotationOrDefault();
        int poolN = rot.poolCount();
        if (poolN <= 0) return false;
        int start = rot.clampIndex(rot.activePoolIndex);
        int next = -1;
        for (int step = 1; step <= poolN; step++) {
            int idx = (start + step) % poolN;
            if (rot.poolAt(idx).enabled) {
                next = idx;
                break;
            }
        }
        if (next < 0) {
            return false;
        }
        rot.activePoolIndex = next;
        rot.poolsAdvancedSinceRepartition = Math.max(0, rot.poolsAdvancedSinceRepartition) + 1;
        if (rot.autoRepartition && rot.poolsAdvancedSinceRepartition >= poolN) {
            List<String> all = globalEnabledMapIds(settings);
            if (all.size() >= MIN_CANDIDATES) {
                repartition(settings, random != null ? random : new Random());
            } else {
                rot.poolsAdvancedSinceRepartition = 0;
            }
        }
        return true;
    }

    public static String todayString() {
        return LocalDate.now().format(DAY);
    }

    public static void onCalendarTick(MinecraftServer server) {
        // Per-round advance only (ModeMapVoteOrchestrator). Calendar daily rotation removed 2026-07-21.
        // Intentionally empty — kept as hook for ModTickHandler compatibility.
    }

    public static boolean skip(ServerPlayer player) {
        if (player == null) return false;
        if (!player.hasPermissions(4)) {
            player.sendSystemMessage(Component.literal("§c需要 OP 权限（等级 ≥ 4）才能跳过地图池"));
            return false;
        }
        ModeMapVoteSettings settings = ConfigManager.getInstance().getModeMapVoteSettings();
        MapPoolRotationSettings rot = settings.rotationOrDefault();
        if (!rot.enabled) {
            player.sendSystemMessage(Component.literal("§c地图池轮换未启用"));
            return false;
        }
        boolean ok = advance(settings, new Random());
        if (!ok) {
            player.sendSystemMessage(Component.literal("§c无法跳过（可能全部池已禁用）"));
            return false;
        }
        ConfigManager.getInstance().setModeMapVoteSettings(settings);
        ConfigManager.getInstance().save();
        MinecraftServer server = player.getServer();
        if (server != null && !server.isSingleplayer()) {
            FullConfigSyncPayload.broadcastToAll(server);
        }
        MapPoolEntry cur = rot.poolAt(rot.activePoolIndex);
        player.sendSystemMessage(Component.literal(
                "§a已跳到池 " + (rot.activePoolIndex + 1) + " §7" + cur.displayName
                        + " §8(" + (cur.mapIds != null ? cur.mapIds.size() : 0) + " 图)"
                        + " §7共" + rot.poolCount() + "池"));
        LOGGER.info("[MapPool] skip by {} -> index={}", player.getName().getString(), rot.activePoolIndex);
        return true;
    }

    public static String statusLine(ModeMapVoteSettings settings) {
        MapPoolRotationSettings rot = settings.rotationOrDefault();
        MapPoolEntry p = rot.poolAt(rot.activePoolIndex);
        return "enabled=" + rot.enabled
                + " pools=" + rot.poolCount()
                + " index=" + rot.activePoolIndex
                + " name=" + p.displayName
                + " maps=" + (p.mapIds != null ? p.mapIds.size() : 0)
                + " mode=" + rot.applyMode
                + " autoRepartition=" + rot.autoRepartition
                + " lastDay=" + rot.lastRotationDate
                + " advanced=" + rot.poolsAdvancedSinceRepartition;
    }
}
