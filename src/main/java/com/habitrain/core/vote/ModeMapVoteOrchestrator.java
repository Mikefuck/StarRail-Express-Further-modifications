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
import com.habitrain.core.game.sre.config.SREIntegration;
import com.habitrain.core.game.sre.mode.SREModeStartAdapter;
import com.habitrain.core.game.sre.map.MapVoteLoadCoordinator;
import com.habitrain.core.game.sre.mode.RepairModeManager;
import com.habitrain.core.game.sre.mode.SreOriginalModeProxy;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /**
     * Launch UI id for repair-escape: upstream {@code startGame} skips Areas and uses
     * the auto-generated manor, so a voted train map must never be broadcast as the match map.
     */
    static final String REPAIR_LAUNCH_MAP_ID = "repair_manor";

    private static final ConcurrentMap<ResourceKey<Level>, Session> SESSIONS = new ConcurrentHashMap<>();
    /** 最近一次 start 失败的可读原因（审核 C7/M-13）。 */
    private static final ConcurrentMap<ResourceKey<Level>, String> LAST_FAILURE = new ConcurrentHashMap<>();

    private ModeMapVoteOrchestrator() {}

    private static final class Session {
        Phase phase = Phase.IDLE;
        ModeMapVoteConfig config;
        com.habitrain.core.game.sre.match.SingleMatchGate.Snapshot reservation;
        @Nullable String selectedModeId;
        @Nullable String selectedMapId;
        @Nullable String selectedModeDisplay;
        int phaseDurationSeconds;
    }

    /**
     * API-TV-19：将显示名（及可选后缀，如人数未推荐标记）裁剪至网络契约上限（64 字符）以内，
     * 防止地图/模式名拼接后缀后超出 {@link OptionVoteManager#MAX_WIRE_ID_LENGTH} 导致二阶段投票启动失败。
     */
    static String fitWireDisplayName(@Nullable String baseName, @Nullable String suffix) {
        String safeBase = baseName == null ? "" : baseName;
        String safeSuffix = suffix == null ? "" : suffix;
        int maxLen = OptionVoteManager.MAX_WIRE_ID_LENGTH;
        if (safeSuffix.length() >= maxLen) {
            return safeSuffix.substring(0, maxLen);
        }
        int maxBase = maxLen - safeSuffix.length();
        if (safeBase.length() > maxBase) {
            safeBase = maxBase > 1 ? safeBase.substring(0, maxBase - 1) + "…" : safeBase.substring(0, maxBase);
        }
        return safeBase + safeSuffix;
    }

    public static boolean start(ServerLevel level, ModeMapVoteConfig config) {
        if (level == null) return false;
        // 审核 B2：调用方传入的对象可能被其后续修改，这里做一次不可变快照再会话持有。
        ModeMapVoteConfig cfg = config != null ? config.snapshot() : new ModeMapVoteConfig();

        ModeMapVoteSettings settings = ConfigManager.getInstance().getModeMapVoteSettings();
        if (settings == null || !settings.enabled) {
            return fail(level, "配置未启用模式→地图投票 (modeMapVote.enabled=false)");
        }

        Session existing = SESSIONS.get(level.dimension());
        if (existing != null && existing.phase != Phase.IDLE) {
            return fail(level, "该维度已有进行中的投票 (phase=" + existing.phase + ")");
        }
        if (OptionVoteManager.isActive(level)) {
            return fail(level, "该维度已有 active 选项投票 (voteId="
                    + OptionVoteManager.currentVoteId(level) + ")");
        }
        if (SREModeStartAdapter.isSreGameBlocking(level)) {
            return fail(level, "SRE 对局已占用该维度");
        }
        if (GameModeRegistry.isActiveInLevel(level)) {
            return fail(level, "该维度已有活跃 GameMode");
        }

        var discoveredMaps = SREIntegration.discoverServerMaps(level);

        // Seed defaults for registry + known config keys so settings.modes can hold order.
        List<String> registryModeIds = new ArrayList<>(GameModeRegistry.getAllIds());
        ConfigManager.getInstance().ensureModeMapVoteDefaultsWithInfo(registryModeIds, discoveredMaps);
        // re-read after ensure (same instance, but entries may have been inserted)
        settings = ConfigManager.getInstance().getModeMapVoteSettings();

        // Config LinkedHashMap order first, then any registry ids not yet present.
        LinkedHashSet<String> orderedModeIds = new LinkedHashSet<>();
        for (String fullId : settings.modes.keySet()) {
            if (isModeAllowed(cfg, settings, fullId) && OptionVoteManager.isValidWireId(fullId)) {
                orderedModeIds.add(fullId);
            }
        }
        for (String fullId : registryModeIds) {
            if (isModeAllowed(cfg, settings, fullId) && OptionVoteManager.isValidWireId(fullId)) {
                orderedModeIds.add(fullId);
            }
        }
        List<String> modeIds = new ArrayList<>(orderedModeIds);

        List<VoteOption> options = new ArrayList<>();
        for (String fullId : modeIds) {
            String display = fitWireDisplayName(resolveModeDisplayName(settings, fullId), null);
            options.add(new VoteOption(fullId, display));
        }
        if (options.isEmpty()) {
            return fail(level, "没有可通过配置过滤的候选模式");
        }

        int duration = cfg.modeDurationSeconds > 0
                ? cfg.modeDurationSeconds
                : settings.modeDurationSeconds;
        duration = Math.max(1, duration);

        Session session = new Session();
        session.config = cfg;
        session.phase = Phase.MODE_VOTING;
        session.phaseDurationSeconds = duration;
        if (!com.habitrain.core.game.sre.match.ServerMatchContext.GATE.vote(com.habitrain.core.game.sre.match.ServerMatchContext.dimension(level))) return fail(level, "服务器已有另一局投票、加载或对局");
        session.reservation = com.habitrain.core.game.sre.match.ServerMatchContext.GATE.snapshot();
        SESSIONS.put(level.dimension(), session);
        clearFailure(level);

        // API-TV-19：只有一个可投票模式时跳过模式投票，并真实返回地图阶段/直开结果而非盲目返回 true。
        if (options.size() == 1) {
            String onlyId = options.get(0).id();
            session.selectedModeId = onlyId;
            session.selectedModeDisplay = options.get(0).displayName();
            LOGGER.info("[ModeMapVote] single mode option, skipping mode vote, mode={} dim={}",
                    onlyId, level.dimension().location());
            return beginMapVote(level, session, onlyId, false);
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
            com.habitrain.core.game.sre.match.ServerMatchContext.GATE.releaseVote(com.habitrain.core.game.sre.match.ServerMatchContext.dimension(level));
            SESSIONS.remove(level.dimension(), session);
            return fail(level, "选项投票层拒绝发起模式投票（可能已存在 active 投票）");
        }

        LOGGER.info("[ModeMapVote] mode vote started options={} duration={}s dim={}",
                options.size(), duration, level.dimension().location());
        return true;
    }

    /** 记录失败原因并返回 {@code false}，供 {@link #lastFailure(ServerLevel)} 读取。 */
    private static boolean fail(ServerLevel level, String reason) {
        LAST_FAILURE.put(level.dimension(), reason);
        LOGGER.info("[ModeMapVote] start refused dim={} reason={}", level.dimension().location(), reason);
        return false;
    }

    private static void clearFailure(ServerLevel level) {
        LAST_FAILURE.remove(level.dimension());
    }

    /** 最近一次 {@link #start} 失败的可读原因；没有失败记录时返回空串。 */
    public static String lastFailure(ServerLevel level) {
        if (level == null) return "";
        String reason = LAST_FAILURE.get(level.dimension());
        return reason != null ? reason : "";
    }

    private static void onModeResolved(ServerLevel level, VoteResult result) {
        Session session = SESSIONS.get(level.dimension());
        if (session == null || session.phase != Phase.MODE_VOTING) {
            return;
        }

        String winnerId = result != null ? result.winnerId() : null;
        if (winnerId == null || winnerId.isBlank()) {
            LOGGER.info("[ModeMapVote] mode vote had no valid winner; cancelled");
            clearSession(level, session);
            return;
        }

        session.selectedModeId = winnerId;
        ModeMapVoteSettings settings = ConfigManager.getInstance().getModeMapVoteSettings();
        session.selectedModeDisplay = fitWireDisplayName(resolveModeDisplayName(settings, winnerId), null);

        LOGGER.info("[ModeMapVote] mode selected={} randomPick={}",
                winnerId, result != null && result.randomPick());

        beginMapVote(level, session, winnerId, result != null && result.randomPick());
    }

    /** 模式已选定，进入地图投票阶段；单模式跳过模式投票时也走这里。 */
    private static boolean beginMapVote(ServerLevel level, Session session, String winnerId, boolean randomPick) {
        if (!validateSelectedMode(level, session, winnerId)) return false;
        // 修机：上游 startGame 丢弃 Areas / 投票图，用地图投票会把将被丢弃的图告诉玩家。
        if (isRepairModeId(winnerId)) {
            LOGGER.info("[ModeMapVote] repair mode skips map vote (upstream auto manor) mode={}", winnerId);
            return finishRepairWithoutVotedMap(level, session);
        }

        var discovered = SREIntegration.discoverServerMaps(level);
        ConfigManager.getInstance().ensureModeMapVoteDefaultsWithInfo(List.of(winnerId), discovered);
        List<String> available = new ArrayList<>(discovered.keySet());
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
            // API-TV-19：过滤空 ID、保留 ID 以及超出选项投票网络层 64 字符上限的 mapId
            if (!OptionVoteManager.isValidWireId(mapId) || MapVoteProfileStore.isReservedMapId(mapId)) {
                continue;
            }
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
            clearSession(level, session);
            return fail(level, "所选模式没有可用的候选地图: " + winnerId);
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

        // 剔除 SRE 会把 map_vote 目录自身枚举成候选地图的保留 id（如 "map_vote/maps"）及超长 id
        List<String> filteredIds = new ArrayList<>();
        for (String mapId : effectiveIds) {
            if (OptionVoteManager.isValidWireId(mapId) && !MapVoteProfileStore.isReservedMapId(mapId)) {
                filteredIds.add(mapId);
            }
        }
        effectiveIds = filteredIds;
        if (effectiveIds.isEmpty()) {
            clearSession(level, session);
            return fail(level, "过滤后没有有效的候选地图: " + winnerId);
        }

        List<VoteOption> mapOptions = new ArrayList<>();
        for (String mapId : effectiveIds) {
            String baseName = resolveMapDisplayName(settings, mapId);
            String suffix = notRecommended.contains(mapId) ? MapPlayerCountService.NOT_RECOMMENDED_MARK : null;
            String name = fitWireDisplayName(baseName, suffix);
            mapOptions.add(new VoteOption(mapId, name));
        }

        int duration = session.config != null && session.config.mapDurationSeconds > 0
                ? session.config.mapDurationSeconds
                : settings.mapDurationSeconds;
        duration = Math.max(1, duration);

        session.phase = Phase.MAP_VOTING;
        session.phaseDurationSeconds = duration;

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
            clearSession(level, session);
            return fail(level, "无法启动地图投票阶段: " + winnerId);
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
        Path baseDir = MapVoteProfileStore.baseDir(level);
        MinecraftServer server = level.getServer();
        CompletableFuture.supplyAsync(() -> {
            MapVoteProfileStore.ensureProfiles(baseDir, profileIds, profileConfig);
            return MapVoteProfileStore.loadProfiles(baseDir, profileIds, profileConfig);
        }).whenComplete((profiles, error) -> {
            if (server == null || server.isStopped()) return;
            server.execute(() -> {
                if (server.isStopped()) return;
                if (SESSIONS.get(level.dimension()) != session || session.phase != Phase.MAP_VOTING) {
                    return;
                }
                if (error != null) {
                    LOGGER.warn("[ModeMapVote] async profile load failed", error);
                    return;
                }
                OptionVoteManager.pushProfiles(level, profiles);
            });
        });

        LOGGER.info("[ModeMapVote] map vote started mode={} options={} duration={}s",
                winnerId, mapOptions.size(), duration);
        return true;
    }

    private static void onMapResolved(ServerLevel level, VoteResult result) {
        Session session = SESSIONS.get(level.dimension());
        if (session == null || session.phase != Phase.MAP_VOTING) {
            return;
        }

        String winnerId = result != null ? result.winnerId() : null;
        if (winnerId == null || winnerId.isBlank()) {
            LOGGER.info("[ModeMapVote] map vote had no valid winner; cancelled");
            clearSession(level, session);
            return;
        }

        LOGGER.info("[ModeMapVote] map selected={} randomPick={}",
                winnerId, result != null && result.randomPick());
        finishWithMap(level, session, winnerId, result != null && result.randomPick());
    }

    /**
     * API-TV-18：仅对走上游 SRE {@code GameUtils.startGame -> trueStartGame} 管线的模式启用
     * {@link MapVoteLoadCoordinator} 加载遮罩；纯自定义 {@link GameModeRegistry} 模式直接开局，
     * 不会触发 {@code trueStartGame}，若开启加载遮罩会导致全体客户端永久卡在 0% 加载黑屏。
     */
    static boolean usesSreLoadCoordinator(@Nullable String modeId) {
        if (modeId == null || modeId.isBlank()) {
            return false;
        }
        if (com.habitrain.core.api.GameModeIds.MURDER.equals(modeId)
                || com.habitrain.core.api.GameModeIds.REGISTRY_MURDER.equals(modeId)
                || isRepairModeId(modeId)) {
            return true;
        }
        return GameModeRegistry.get(modeId) instanceof SreOriginalModeProxy;
    }

    /** Load map then start mode; shared by map vote result and DIRECT_PICK. */
    private static void finishWithMap(ServerLevel level, Session session, String mapId, boolean randomPick) {
        if (session == null || mapId == null || mapId.isBlank()) {
            clearSession(level, session);
            return;
        }
        if (!validateSelectedMode(level, session, session.selectedModeId)) return;

        if (isRepairModeId(session.selectedModeId)) {
            LOGGER.info("[ModeMapVote] repair DIRECT_PICK skipped loadMap map={} (upstream auto manor)", mapId);
            finishRepairWithoutVotedMap(level, session);
            return;
        }

        if (RepairModeManager.isMapLocked(mapId)) {
            LOGGER.warn("[ModeMapVote] abort start: map is repair-locked map={} mode={}",
                    mapId, session.selectedModeId);
            clearSession(level, session);
            return;
        }

        session.selectedMapId = mapId;
        session.phase = Phase.SWITCHING_MAP;
        session.phaseDurationSeconds = 0;

        boolean loaded = SREModeStartAdapter.loadMap(level, mapId);
        if (!loaded) {
            LOGGER.warn("[ModeMapVote] map load failed map={} randomPick={}", mapId, randomPick);
            clearSession(level, session);
            return;
        }

        session.phase = Phase.STARTING_MODE;
        String modeId = session.selectedModeId;
        if (!validateSelectedMode(level, session, modeId)) return;

        // API-TV-18：仅 SRE 模式开启 MapVoteLoadCoordinator 加载遮罩
        boolean useLoadCoordinator = usesSreLoadCoordinator(modeId);
        if (useLoadCoordinator) {
            MapVoteLoadCoordinator.beginLoad(level, mapId, modeId);
        }

        boolean started = SREModeStartAdapter.startMode(level, modeId);
        if (!started) {
            LOGGER.warn("[ModeMapVote] startMode failed mode={} map={} (map kept)", modeId, mapId);
            if (useLoadCoordinator) {
                MapVoteLoadCoordinator.reset(level);
            }
        } else {
            LOGGER.info("[ModeMapVote] started mode={} map={} (loading={})", modeId, mapId, useLoadCoordinator);
        }

        clearSession(level, session);
    }

    /** Repair escape: no map vote / no loadMap; coordinator UI uses auto-manor id. */
    private static boolean finishRepairWithoutVotedMap(ServerLevel level, Session session) {
        if (session == null) {
            clearSession(level, null);
            return false;
        }
        String modeId = session.selectedModeId;
        if (!validateSelectedMode(level, session, modeId)) return false;
        session.selectedMapId = REPAIR_LAUNCH_MAP_ID;
        session.phase = Phase.STARTING_MODE;
        session.phaseDurationSeconds = 0;

        boolean useLoadCoordinator = usesSreLoadCoordinator(modeId);
        if (useLoadCoordinator) {
            MapVoteLoadCoordinator.beginLoad(level, REPAIR_LAUNCH_MAP_ID, modeId);
        }
        boolean started = SREModeStartAdapter.startMode(level, modeId);
        if (!started) {
            LOGGER.warn("[ModeMapVote] startMode failed mode={} map={} (repair auto manor)",
                    modeId, REPAIR_LAUNCH_MAP_ID);
            if (useLoadCoordinator) {
                MapVoteLoadCoordinator.reset(level);
            }
            clearSession(level, session);
            return fail(level, "启动修机模式失败: " + modeId);
        } else {
            LOGGER.info("[ModeMapVote] started mode={} map={} (repair auto manor, loading={})",
                    modeId, REPAIR_LAUNCH_MAP_ID, useLoadCoordinator);
        }
        clearSession(level, session);
        return true;
    }

    static boolean isRepairModeId(@Nullable String modeId) {
        if (modeId == null || modeId.isBlank()) {
            return false;
        }
        String id = modeId.toLowerCase(Locale.ROOT);
        net.minecraft.resources.ResourceLocation parsed = net.minecraft.resources.ResourceLocation.tryParse(id);
        return com.habitrain.core.api.GameModeIds.REPAIR.equals(id)
                || com.habitrain.core.api.GameModeIds.REGISTRY_REPAIR.equals(id)
                || parsed != null && "repair_escape".equals(parsed.getPath());
    }

    public static boolean cancel(ServerLevel level) {
        if (level == null) return false;
        Session session = SESSIONS.remove(level.dimension());
        if (session == null || session.phase == Phase.IDLE) {
            return false;
        }
        // Release before the cancellation callback: it may immediately open a replacement vote.
        // This also guarantees release if broadcasting the cancellation throws.
        com.habitrain.core.game.sre.match.ServerMatchContext.GATE.releaseVote(session.reservation);
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
            return new ModeMapVoteSnapshot(com.habitrain.core.api.ModeMapVotePhase.IDLE, null, null, 0);
        }
        return new ModeMapVoteSnapshot(
                com.habitrain.core.api.ModeMapVotePhase.fromName(session.phase.name()),
                session.selectedModeId,
                session.selectedMapId,
                remainingSeconds(level, session)
        );
    }

    public static void reset(ServerLevel level) {
        if (level == null) return;
        com.habitrain.core.game.sre.match.ServerMatchContext.GATE.releaseVote(com.habitrain.core.game.sre.match.ServerMatchContext.dimension(level));
        SESSIONS.remove(level.dimension());
        LAST_FAILURE.remove(level.dimension());
    }

    public static void resetAll() {
        SESSIONS.clear();
        LAST_FAILURE.clear();
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

    private static void clearSession(ServerLevel level, @Nullable Session expected) {
        if (level == null) return;
        if (expected != null) {
            if (SESSIONS.remove(level.dimension(), expected)) {
                com.habitrain.core.game.sre.match.ServerMatchContext.GATE.releaseVote(
                        expected.reservation);
            }
        } else {
            com.habitrain.core.game.sre.match.ServerMatchContext.GATE.releaseVote(com.habitrain.core.game.sre.match.ServerMatchContext.dimension(level));
            SESSIONS.remove(level.dimension());
        }
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
        String sreName = SREIntegration.getMapDisplayName(mapId);
        if (sreName != null && !sreName.isBlank()) {
            return sreName;
        }
        return mapId;
    }

    /** true if mode passes optional config.modeIds filter and is not disabled in settings. */
    static boolean isModeAllowed(ModeMapVoteConfig cfg, ModeMapVoteSettings settings, String fullId) {
        if (fullId == null || fullId.isBlank()) {
            return false;
        }
        if (!SREModeStartAdapter.isModeAvailable(fullId)) return false;
        if (cfg != null && cfg.modeIds != null && !cfg.modeIds.contains(fullId)) {
            return false;
        }
        ModeVoteEntry entry = settings != null ? settings.modes.get(fullId) : null;
        // missing entry = enabled
        return entry == null || entry.enabled;
    }

    private static boolean validateSelectedMode(ServerLevel level, Session session, String modeId) {
        if (isModeAllowed(session.config, ConfigManager.getInstance().getModeMapVoteSettings(), modeId)) return true;
        String reason = "所选模式已不可用，投票已取消: " + modeId;
        fail(level, reason);
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(reason));
        }
        clearSession(level, session);
        return false;
    }
}
