package com.habitrain.core.vote;

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
import com.habitrain.core.game.sre.MapVoteLoadCoordinator;
import com.habitrain.core.game.sre.RepairModeManager;
import com.habitrain.core.game.sre.SreOriginalModeProxy;
import com.habitrain.core.network.MapVoteProfilePayload;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;

/**
 * Two-phase lobby vote: mode options → map options → loadMap → startMode.
 * Dimension-scoped; at most one session per level.
 * <p>
 * No subtitle tips — clients auto-open the vote UI from {@code OptionVotePayload}.
 */
public final class ModeMapVoteOrchestrator {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModeMapVoteOrchestrator");

    public enum Phase {
        IDLE, MODE_VOTING, MAP_VOTING, SWITCHING_MAP, STARTING_MODE
    }

    private static final ConcurrentMap<ResourceKey<Level>, Session> SESSIONS = new ConcurrentHashMap<>();

    static {
        com.habitrain.core.task.ClearableHandlerRegistry.register(ModeMapVoteOrchestrator::resetAll);
    }

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

        // Seed defaults for registry + known config keys so settings.modes can hold order.
        List<String> registryModeIds = new ArrayList<>(GameModeRegistry.getAllIds());
        ConfigManager.getInstance().ensureModeMapVoteDefaults(registryModeIds, availableMaps);
        // re-read after ensure (same instance, but entries may have been inserted)
        settings = ConfigManager.getInstance().getModeMapVoteSettings();

        // Config LinkedHashMap order first, then any registry ids not yet present.
        LinkedHashSet<String> orderedModeIds = new LinkedHashSet<>();
        for (String fullId : settings.modes.keySet()) {
            if (isModeAllowed(cfg, settings, fullId)) {
                orderedModeIds.add(fullId);
            }
        }
        for (String fullId : registryModeIds) {
            if (isModeAllowed(cfg, settings, fullId)) {
                orderedModeIds.add(fullId);
            }
        }
        List<String> modeIds = new ArrayList<>(orderedModeIds);

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

        // 只有一个可投票模式：跳过模式投票，直接选定该模式进入地图投票。
        if (options.size() == 1) {
            String onlyId = options.get(0).id();
            session.selectedModeId = onlyId;
            session.selectedModeDisplay = options.get(0).displayName();
            LOGGER.info("[ModeMapVote] single mode option, skipping mode vote, mode={} dim={}",
                    onlyId, level.dimension().location());
            beginMapVote(level, session, onlyId, false);
            return true;
        }

        // title/description are client-localized via voteId; wire strings are placeholders only
        boolean started = OptionVoteManager.start(
                level,
                "mode",
                "mode",
                "",
                options,
                duration,
                result -> onModeResolved(level, result)
        );
        if (!started) {
            SESSIONS.remove(level.dimension());
            return false;
        }

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
            LOGGER.info("[ModeMapVote] mode vote had no valid winner; cancelled");
            clearSession(level);
            return;
        }

        session.selectedModeId = winnerId;
        ModeMapVoteSettings settings = ConfigManager.getInstance().getModeMapVoteSettings();
        session.selectedModeDisplay = resolveModeDisplayName(settings, winnerId);

        LOGGER.info("[ModeMapVote] mode selected={} randomPick={}",
                winnerId, result != null && result.randomPick());

        beginMapVote(level, session, winnerId, result != null && result.randomPick());
    }

    /** 模式已选定，进入地图投票阶段；单模式跳过模式投票时也走这里。 */
    private static void beginMapVote(ServerLevel level, Session session, String winnerId, boolean randomPick) {
        List<String> available = SREModeStartAdapter.getAvailableMaps(level);
        ConfigManager.getInstance().ensureModeMapVoteDefaults(List.of(winnerId), available);
        ModeMapVoteSettings settings = ConfigManager.getInstance().getModeMapVoteSettings();

        ModeVoteEntry modeEntry = settings.modes.get(winnerId);
        Set<String> allowed = null;
        if (modeEntry != null && modeEntry.allowedMaps != null && !modeEntry.allowedMaps.isEmpty()) {
            allowed = new HashSet<>(modeEntry.allowedMaps);
        }

        Set<String> configMaps = null;
        if (session.config != null && session.config.mapIds != null) {
            configMaps = new HashSet<>(session.config.mapIds);
        }

        List<String> candidateIds = new ArrayList<>();
        for (String mapId : available) {
            if (mapId == null || mapId.isBlank()) continue;
            MapVoteEntry mapEntry = settings.maps.get(mapId);
            // missing entry = enabled
            if (mapEntry != null && !mapEntry.enabled) {
                continue;
            }
            // 维修人员模式下被锁定的地图不进投票池（无人负责时自动回到池中）
            if (RepairModeManager.isMapLocked(mapId)) {
                continue;
            }
            if (allowed != null && !allowed.contains(mapId)) {
                continue;
            }
            if (configMaps != null && !configMaps.contains(mapId)) {
                continue;
            }
            candidateIds.add(mapId);
        }

        if (candidateIds.isEmpty()) {
            LOGGER.info("[ModeMapVote] no available maps after mode={}; ending", winnerId);
            clearSession(level);
            return;
        }

        Random rng = new Random(level.getRandom().nextLong());
        int playerCount = participatingPlayerCount(level);
        List<String> effectiveIds = candidateIds;
        Set<String> notRecommended = Set.of();
        if (MapPlayerCountService.shouldApply(settings)) {
            MapPlayerCountService.DrawResult draw = MapPlayerCountService.draw(
                    settings, candidateIds, playerCount, rng);
            effectiveIds = draw.ids();
            notRecommended = draw.notRecommended();
            if (effectiveIds.isEmpty()) {
                effectiveIds = candidateIds;
            }
            LOGGER.info("[ModeMapVote] player-count draw mode={} players={} candidates={} effective={} notRecommended={} drawCount={}",
                    winnerId, playerCount, candidateIds.size(), effectiveIds.size(), notRecommended.size(),
                    settings.playerCountOrDefault().drawCount);
        }

        // 剔除 SRE 会把 map_vote 目录自身枚举成候选地图的保留 id（如 "map_vote/maps"）
        List<String> filteredIds = new ArrayList<>();
        for (String mapId : effectiveIds) {
            if (!MapVoteProfileStore.isReservedMapId(mapId)) {
                filteredIds.add(mapId);
            }
        }
        effectiveIds = filteredIds;

        List<VoteOption> mapOptions = new ArrayList<>();
        for (String mapId : effectiveIds) {
            String name = resolveMapDisplayName(settings, mapId);
            if (notRecommended.contains(mapId)) {
                name = name + MapPlayerCountService.NOT_RECOMMENDED_MARK;
            }
            mapOptions.add(new VoteOption(mapId, name));
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
                "map",
                "",
                mapOptions,
                duration,
                mapResult -> onMapResolved(level, mapResult)
        );
        if (!started) {
            LOGGER.warn("[ModeMapVote] failed to start map vote after mode={}", winnerId);
            clearSession(level);
            return;
        }

        // 磁盘 I/O 与图片读取移出服务端 tick；完成后回到服务端线程确认投票仍有效并分片发送。
        List<String> profileIds = List.copyOf(effectiveIds);
        Map<String, MapVoteEntry> profileConfig = new java.util.LinkedHashMap<>();
        for (String id : profileIds) {
            MapVoteEntry source = settings.maps.get(id);
            if (source == null) continue;
            MapVoteEntry copy = MapVoteEntry.createDefault();
            copy.enabled = source.enabled;
            copy.displayName = source.displayName;
            copy.minPlayers = source.minPlayers;
            copy.maxPlayers = source.maxPlayers;
            copy.profile = source.profile == null ? null
                    : com.habitrain.core.config.MapVoteProfileSettings.fromJson(source.profile.toJson());
            profileConfig.put(id, copy);
        }
        CompletableFuture.supplyAsync(() -> {
            MapVoteProfileStore.ensureProfiles(level, profileIds, profileConfig);
            return MapVoteProfileStore.loadProfiles(level, profileIds, profileConfig);
        }).whenComplete((profiles, error) -> level.getServer().execute(() -> {
            if (error != null) {
                LOGGER.warn("[ModeMapVote] async profile load failed", error);
                return;
            }
            if (SESSIONS.get(level.dimension()) != session || session.phase != Phase.MAP_VOTING) {
                return;
            }
            OptionVoteManager.pushProfiles(level, profiles);
        }));

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
            LOGGER.info("[ModeMapVote] map vote had no valid winner; cancelled");
            clearSession(level);
            return;
        }

        LOGGER.info("[ModeMapVote] map selected={} randomPick={}",
                winnerId, result != null && result.randomPick());
        finishWithMap(level, session, winnerId, result != null && result.randomPick());
    }

    /** Load map then start mode; shared by map vote result and DIRECT_PICK. */
    private static void finishWithMap(ServerLevel level, Session session, String mapId, boolean randomPick) {
        if (session == null || mapId == null || mapId.isBlank()) {
            clearSession(level);
            return;
        }

        session.selectedMapId = mapId;
        session.phase = Phase.SWITCHING_MAP;
        session.phaseDurationSeconds = 0;
        session.phaseStartMs = System.currentTimeMillis();

        boolean loaded = SREModeStartAdapter.loadMap(level, mapId);
        if (!loaded) {
            LOGGER.warn("[ModeMapVote] map load failed map={} randomPick={}", mapId, randomPick);
            clearSession(level);
            return;
        }

        session.phase = Phase.STARTING_MODE;
        String modeId = session.selectedModeId;

        // 开局加载协调：地图重置开始即进入协调，客户端进入「加载」转场阶段；
        // 真正开局（trueStartGame）时由 SRETrueStartGameMixin 广播开局确认。
        MapVoteLoadCoordinator.beginLoad(level, mapId, modeId);

        boolean started = SREModeStartAdapter.startMode(level, modeId);
        if (!started) {
            LOGGER.warn("[ModeMapVote] startMode failed mode={} map={} (map kept)", modeId, mapId);
            MapVoteLoadCoordinator.reset(level);
        } else {
            LOGGER.info("[ModeMapVote] started mode={} map={} (loading)", modeId, mapId);
        }

        clearSession(level);
    }

    public static boolean cancel(ServerLevel level) {
        if (level == null) return false;
        Session session = SESSIONS.remove(level.dimension());
        if (session == null || session.phase == Phase.IDLE) {
            return false;
        }
        OptionVoteManager.cancel(level);
        LOGGER.info("[ModeMapVote] cancelled phase={} dim={}",
                session.phase, level.dimension().location());
        return true;
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
                remainingSeconds(level, session)
        );
    }

    public static void reset(ServerLevel level) {
        if (level == null) return;
        SESSIONS.remove(level.dimension());
    }

    public static void resetAll() {
        SESSIONS.clear();
    }

    public static void onPlayerJoin(ServerPlayer player) {
        OptionVoteManager.syncTo(player);
    }

    private static int remainingSeconds(ServerLevel level, Session session) {
        if (session.phase != Phase.MODE_VOTING && session.phase != Phase.MAP_VOTING) {
            return 0;
        }
        if (session.phaseDurationSeconds <= 0) {
            return 0;
        }
        return OptionVoteManager.remainingSeconds(level);
    }

    private static void clearSession(ServerLevel level) {
        SESSIONS.remove(level.dimension());
    }

    /**
     * 当前参加对局的人数：使用原版列车 SRE 的是否参加对局机制
     * （ParticipationComponent，默认参与、可退出），每位已确认参加对局的玩家计 1。
     */
    private static int participatingPlayerCount(ServerLevel level) {
        if (level == null) return 0;
        try {
            return Math.max(0, GameUtils.getParticipatingPlayerCount(level));
        } catch (Throwable t) {
            // SRE 参与组件不可用时的兜底：统计该维度非旁观在线玩家
            int count = 0;
            for (ServerPlayer p : level.players()) {
                if (p != null && !p.isSpectator()) count++;
            }
            return count;
        }
    }

    /**
     * Wire display name: operator override if set; for bridged original SRE proxies use
     * the raw SRE id (e.g. wifi:tnt_tag); otherwise the registry fullId so clients can
     * detect "no override" and translate by id (murder/repair/blackout lang keys).
     */
    private static String resolveModeDisplayName(ModeMapVoteSettings settings, String fullId) {
        if (settings != null) {
            ModeVoteEntry entry = settings.modes.get(fullId);
            if (entry != null && entry.displayName != null && !entry.displayName.isBlank()) {
                return entry.displayName;
            }
        }
        GameMode mode = GameModeRegistry.get(fullId);
        if (mode instanceof SreOriginalModeProxy proxy) {
            return proxy.getDisplayName();
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

    /** true if mode passes optional config.modeIds filter and is not disabled in settings. */
    private static boolean isModeAllowed(ModeMapVoteConfig cfg, ModeMapVoteSettings settings, String fullId) {
        if (fullId == null || fullId.isBlank()) {
            return false;
        }
        if (cfg != null && cfg.modeIds != null && !cfg.modeIds.contains(fullId)) {
            return false;
        }
        ModeVoteEntry entry = settings != null ? settings.modes.get(fullId) : null;
        // missing entry = enabled
        return entry == null || entry.enabled;
    }
}
