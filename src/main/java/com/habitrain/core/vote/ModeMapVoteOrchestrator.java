package com.habitrain.core.vote;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.ModeMapVoteConfig;
import com.habitrain.core.api.ModeMapVoteSnapshot;
import com.habitrain.core.api.VoteOption;
import com.habitrain.core.api.VoteResult;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.ModeMapVoteSettings;
import com.habitrain.core.config.ModeVoteEntry;
import com.habitrain.core.game.sre.SREModeStartAdapter;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Two-phase lobby vote: mode options → map options → loadMap → startMode.
 * Dimension-scoped; at most one session per level.
 */
public final class ModeMapVoteOrchestrator {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModeMapVoteOrchestrator");

    public enum Phase {
        IDLE, MODE_VOTING, MAP_VOTING, SWITCHING_MAP, STARTING_MODE
    }

    private static final ConcurrentMap<ResourceKey<Level>, Session> SESSIONS = new ConcurrentHashMap<>();

    private ModeMapVoteOrchestrator() {}

    private static final class Session {
        Phase phase = Phase.IDLE;
        ModeMapVoteConfig config;
        @Nullable String selectedModeId;
        @Nullable String selectedMapId;
        @Nullable String selectedModeDisplay;
        int phaseDurationSeconds;
        long phaseStartMs;
    }

    public static boolean start(ServerLevel level, ModeMapVoteConfig config) {
        if (level == null) return false;
        ModeMapVoteConfig cfg = config != null ? config : new ModeMapVoteConfig();

        ModeMapVoteSettings settings = ConfigManager.getInstance().getModeMapVoteSettings();
        if (settings == null || !settings.enabled) {
            return false;
        }

        Session existing = SESSIONS.get(level.dimension());
        if (existing != null && existing.phase != Phase.IDLE) {
            return false;
        }
        if (OptionVoteManager.isActive(level)) {
            return false;
        }
        if (SREModeStartAdapter.isSreGameBlocking(level)) {
            return false;
        }
        if (GameModeRegistry.isActiveInLevel(level)) {
            return false;
        }

        List<String> availableMaps = SREModeStartAdapter.getAvailableMaps(level);

        List<String> modeIds = new ArrayList<>();
        for (String fullId : GameModeRegistry.getAllIds()) {
            if (cfg.modeIds != null && !cfg.modeIds.contains(fullId)) {
                continue;
            }
            ModeVoteEntry entry = settings.modes.get(fullId);
            // missing entry = enabled
            if (entry != null && !entry.enabled) {
                continue;
            }
            modeIds.add(fullId);
        }

        ConfigManager.getInstance().ensureModeMapVoteDefaults(modeIds, availableMaps);
        // re-read after ensure (same instance, but entries may have been inserted)
        settings = ConfigManager.getInstance().getModeMapVoteSettings();

        List<VoteOption> options = new ArrayList<>();
        for (String fullId : modeIds) {
            options.add(new VoteOption(fullId, resolveModeDisplayName(settings, fullId)));
        }
        if (options.isEmpty()) {
            return false;
        }

        int duration = cfg.modeDurationSeconds > 0
                ? cfg.modeDurationSeconds
                : settings.modeDurationSeconds;
        duration = Math.max(1, duration);

        Session session = new Session();
        session.config = cfg;
        session.phase = Phase.MODE_VOTING;
        session.phaseDurationSeconds = duration;
        session.phaseStartMs = System.currentTimeMillis();
        SESSIONS.put(level.dimension(), session);

        boolean started = OptionVoteManager.start(
                level,
                "mode",
                "模式投票",
                "选择本局游戏模式",
                options,
                duration,
                result -> onModeResolved(level, result)
        );
        if (!started) {
            SESSIONS.remove(level.dimension());
            return false;
        }

        announce(level, Component.literal("§e模式投票已开始，按 V 打开投票界面"));
        LOGGER.info("[ModeMapVote] mode vote started options={} duration={}s dim={}",
                options.size(), duration, level.dimension().location());
        return true;
    }

    private static void onModeResolved(ServerLevel level, VoteResult result) {
        Session session = SESSIONS.get(level.dimension());
        if (session == null || session.phase != Phase.MODE_VOTING) {
            return;
        }

        String winnerId = result != null ? result.winnerId() : null;
        if (winnerId == null || winnerId.isBlank()) {
            announce(level, Component.literal("§c模式投票无有效结果，已取消"));
            clearSession(level);
            return;
        }

        session.selectedModeId = winnerId;
        ModeMapVoteSettings settings = ConfigManager.getInstance().getModeMapVoteSettings();
        session.selectedModeDisplay = resolveModeDisplayName(settings, winnerId);

        String pickHint = result.randomPick() ? "§7（随机）" : "";
        announce(level, Component.literal("§a模式已选定: §f" + session.selectedModeDisplay + pickHint));

        List<String> available = SREModeStartAdapter.getAvailableMaps(level);
        ConfigManager.getInstance().ensureModeMapVoteDefaults(List.of(winnerId), available);
        settings = ConfigManager.getInstance().getModeMapVoteSettings();

        ModeVoteEntry modeEntry = settings.modes.get(winnerId);
        Set<String> allowed = null;
        if (modeEntry != null && modeEntry.allowedMaps != null && !modeEntry.allowedMaps.isEmpty()) {
            allowed = new HashSet<>(modeEntry.allowedMaps);
        }

        Set<String> configMaps = null;
        if (session.config != null && session.config.mapIds != null) {
            configMaps = new HashSet<>(session.config.mapIds);
        }

        List<VoteOption> mapOptions = new ArrayList<>();
        for (String mapId : available) {
            if (mapId == null || mapId.isBlank()) continue;
            MapVoteEntry mapEntry = settings.maps.get(mapId);
            // missing entry = enabled
            if (mapEntry != null && !mapEntry.enabled) {
                continue;
            }
            if (allowed != null && !allowed.contains(mapId)) {
                continue;
            }
            if (configMaps != null && !configMaps.contains(mapId)) {
                continue;
            }
            mapOptions.add(new VoteOption(mapId, resolveMapDisplayName(settings, mapId)));
        }

        if (mapOptions.isEmpty()) {
            announce(level, Component.literal("§c无可用地图，投票结束"));
            clearSession(level);
            return;
        }

        int duration = session.config != null && session.config.mapDurationSeconds > 0
                ? session.config.mapDurationSeconds
                : settings.mapDurationSeconds;
        duration = Math.max(1, duration);

        session.phase = Phase.MAP_VOTING;
        session.phaseDurationSeconds = duration;
        session.phaseStartMs = System.currentTimeMillis();

        boolean started = OptionVoteManager.start(
                level,
                "map",
                "地图投票",
                "选择本局地图（模式: " + session.selectedModeDisplay + "）",
                mapOptions,
                duration,
                mapResult -> onMapResolved(level, mapResult)
        );
        if (!started) {
            announce(level, Component.literal("§c无法启动地图投票"));
            clearSession(level);
            return;
        }

        announce(level, Component.literal("§e地图投票已开始，按 V 打开投票界面"));
        LOGGER.info("[ModeMapVote] map vote started mode={} options={} duration={}s",
                winnerId, mapOptions.size(), duration);
    }

    private static void onMapResolved(ServerLevel level, VoteResult result) {
        Session session = SESSIONS.get(level.dimension());
        if (session == null || session.phase != Phase.MAP_VOTING) {
            return;
        }

        String winnerId = result != null ? result.winnerId() : null;
        if (winnerId == null || winnerId.isBlank()) {
            announce(level, Component.literal("§c地图投票无有效结果，已取消"));
            clearSession(level);
            return;
        }

        session.selectedMapId = winnerId;
        ModeMapVoteSettings settings = ConfigManager.getInstance().getModeMapVoteSettings();
        String mapDisplay = resolveMapDisplayName(settings, winnerId);
        String pickHint = result.randomPick() ? "§7（随机）" : "";
        announce(level, Component.literal("§a地图已选定: §f" + mapDisplay + pickHint));

        session.phase = Phase.SWITCHING_MAP;
        session.phaseDurationSeconds = 0;
        session.phaseStartMs = System.currentTimeMillis();
        announce(level, Component.literal("§e正在加载地图: §f" + mapDisplay));

        boolean loaded = SREModeStartAdapter.loadMap(level, winnerId);
        if (!loaded) {
            announce(level, Component.literal("§c地图加载失败: §f" + mapDisplay));
            clearSession(level);
            return;
        }

        session.phase = Phase.STARTING_MODE;
        String modeId = session.selectedModeId;
        String modeDisplay = session.selectedModeDisplay != null
                ? session.selectedModeDisplay
                : (modeId != null ? modeId : "?");
        announce(level, Component.literal("§e正在启动模式: §f" + modeDisplay));

        boolean started = SREModeStartAdapter.startMode(level, modeId);
        if (!started) {
            announce(level, Component.literal("§c模式启动失败: §f" + modeDisplay + " §7（地图已加载）"));
            LOGGER.warn("[ModeMapVote] startMode failed mode={} map={} (map kept)", modeId, winnerId);
        } else {
            announce(level, Component.literal("§a对局已启动: §f" + modeDisplay));
            LOGGER.info("[ModeMapVote] started mode={} map={}", modeId, winnerId);
        }

        clearSession(level);
    }

    public static void cancel(ServerLevel level) {
        if (level == null) return;
        OptionVoteManager.cancel(level);
        Session session = SESSIONS.remove(level.dimension());
        if (session != null && session.phase != Phase.IDLE) {
            announce(level, Component.literal("§e模式→地图投票已取消"));
            LOGGER.info("[ModeMapVote] cancelled phase={} dim={}",
                    session.phase, level.dimension().location());
        }
    }

    public static boolean isRunning(ServerLevel level) {
        if (level == null) return false;
        Session session = SESSIONS.get(level.dimension());
        return session != null && session.phase != Phase.IDLE;
    }

    public static @Nullable ModeMapVoteSnapshot snapshot(ServerLevel level) {
        if (level == null) return null;
        Session session = SESSIONS.get(level.dimension());
        if (session == null) {
            return new ModeMapVoteSnapshot(Phase.IDLE.name(), null, null, 0);
        }
        return new ModeMapVoteSnapshot(
                session.phase.name(),
                session.selectedModeId,
                session.selectedMapId,
                remainingSeconds(session)
        );
    }

    public static void reset(ServerLevel level) {
        if (level == null) return;
        SESSIONS.remove(level.dimension());
    }

    public static void onPlayerJoin(ServerPlayer player) {
        OptionVoteManager.syncTo(player);
    }

    private static int remainingSeconds(Session session) {
        if (session.phase != Phase.MODE_VOTING && session.phase != Phase.MAP_VOTING) {
            return 0;
        }
        if (session.phaseDurationSeconds <= 0) {
            return 0;
        }
        long elapsed = (System.currentTimeMillis() - session.phaseStartMs) / 1000L;
        long left = session.phaseDurationSeconds - elapsed;
        return (int) Math.max(0, left);
    }

    private static void clearSession(ServerLevel level) {
        SESSIONS.remove(level.dimension());
    }

    private static String resolveModeDisplayName(ModeMapVoteSettings settings, String fullId) {
        if (settings != null) {
            ModeVoteEntry entry = settings.modes.get(fullId);
            if (entry != null && entry.displayName != null && !entry.displayName.isBlank()) {
                return entry.displayName;
            }
        }
        GameMode mode = GameModeRegistry.get(fullId);
        if (mode != null) {
            String name = mode.getDisplayName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return fullId;
    }

    private static String resolveMapDisplayName(ModeMapVoteSettings settings, String mapId) {
        if (settings != null) {
            MapVoteEntry entry = settings.maps.get(mapId);
            if (entry != null && entry.displayName != null && !entry.displayName.isBlank()) {
                return entry.displayName;
            }
        }
        return mapId;
    }

    private static void announce(ServerLevel level, Component text) {
        if (level == null || text == null) return;
        for (ServerPlayer player : level.players()) {
            try {
                SubtitleNotifier.sendTop(player, text);
            } catch (Exception e) {
                HabiTrainCore.LOGGER.debug("SubtitleNotifier failed for {}", player.getGameProfile().getName(), e);
            }
        }
    }
}
