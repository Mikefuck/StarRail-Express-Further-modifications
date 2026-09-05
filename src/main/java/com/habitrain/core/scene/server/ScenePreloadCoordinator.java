package com.habitrain.core.scene.server;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.network.SceneAssetPrefetchS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 地图投票加载期场景预取会话。
 *
 * <p>服务端只把通过 Manifest 授权的获胜地图资产加入会话。客户端完成文件校验、解码与
 * GPU 网格预编译后回执；加载遮罩可以据此提前放行。超时后的未完成玩家继续以对局速率下载。</p>
 */
public final class ScenePreloadCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|ScenePreloadCoordinator");
    private static final ScenePreloadCoordinator INSTANCE = new ScenePreloadCoordinator();
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong(System.currentTimeMillis());

    public static ScenePreloadCoordinator getInstance() {
        return INSTANCE;
    }

    private static final class PlayerProgress {
        final long totalBytes;
        final ConcurrentMap<String, Long> confirmedByHash = new ConcurrentHashMap<>();
        final Set<String> readyHashes = ConcurrentHashMap.newKeySet();
        volatile long confirmedBytes;
        volatile boolean ready;
        volatile boolean failed;
        volatile int lastLoggedQuarter = -1;

        PlayerProgress(long totalBytes) {
            this.totalBytes = Math.max(0L, totalBytes);
        }
    }

    private static final class Session {
        final long id;
        final ResourceKey<Level> dimension;
        final String mapKey;
        final Map<String, SceneAssetDescriptor> descriptorsByAssetKey;
        final Set<String> expectedHashes;
        final long totalBytes;
        final ConcurrentMap<UUID, PlayerProgress> players = new ConcurrentHashMap<>();
        volatile boolean loadingPhase = true;

        Session(long id, ResourceKey<Level> dimension, String mapKey,
                Map<String, SceneAssetDescriptor> descriptorsByAssetKey) {
            this.id = id;
            this.dimension = dimension;
            this.mapKey = mapKey;
            this.descriptorsByAssetKey = Map.copyOf(descriptorsByAssetKey);
            this.expectedHashes = descriptorsByAssetKey.values().stream()
                    .map(SceneAssetDescriptor::sha256).collect(java.util.stream.Collectors.toUnmodifiableSet());
            this.totalBytes = descriptorsByAssetKey.values().stream()
                    .mapToLong(SceneAssetDescriptor::compressedSize).sum();
        }
    }

    private final ConcurrentMap<ResourceKey<Level>, Session> sessions = new ConcurrentHashMap<>();

    private ScenePreloadCoordinator() {}

    /** Starts prefetch for the voted map. Missing/disabled scenes are treated as immediately ready. */
    public boolean begin(ServerLevel level, String mapKey) {
        if (level == null) return false;
        reset(level);

        String normalizedMapKey = mapKey == null || mapKey.isBlank() ? "__default__" : mapKey.trim();
        var settings = ConfigManager.getInstance().getSceneMotionSettings();
        Map<String, SceneAssetDescriptor> descriptors = new LinkedHashMap<>();
        if (settings.enabled) {
            for (var background : settings.getResolvedBackgrounds(normalizedMapKey)) {
                if (!background.profile().isEnabled()) continue;
                String assetKey = background.assetKey(normalizedMapKey);
                SceneAssetDescriptor descriptor = SceneAssetStore.getInstance().getDescriptor(assetKey);
                if (descriptor != null && descriptor.isValid()) descriptors.put(assetKey, descriptor);
            }
        }
        if (descriptors.isEmpty()) {
            LOGGER.info("[ScenePreload] skipped dim={} map={} enabled={} assets=0",
                    level.dimension().location(), normalizedMapKey, settings.enabled);
            return false;
        }

        Session session = new Session(NEXT_SESSION_ID.incrementAndGet(), level.dimension(),
                normalizedMapKey, descriptors);
        sessions.put(level.dimension(), session);
        for (ServerPlayer player : level.players()) {
            enroll(session, player);
        }
        LOGGER.info("[ScenePreload] begin session={} dim={} map={} players={} assets={} bytes={}",
                session.id, level.dimension().location(), normalizedMapKey,
                session.players.size(), descriptors.size(), session.totalBytes);
        return true;
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (player == null || player.serverLevel() == null) return;
        Session session = sessions.get(player.serverLevel().dimension());
        if (session != null) enroll(session, player);
    }

    private void enroll(Session session, ServerPlayer player) {
        if (session == null || player == null || player.serverLevel() == null
                || !session.dimension.equals(player.serverLevel().dimension())) return;
        session.players.computeIfAbsent(player.getUUID(), ignored ->
                new PlayerProgress(session.totalBytes));
        SceneTransferService transfer = SceneTransferService.getInstance();
        transfer.setBandwidthPhase(player.getUUID(), session.loadingPhase
                ? SceneTransferService.BandwidthPhase.LOADING
                : SceneTransferService.BandwidthPhase.MATCH);
        for (Map.Entry<String, SceneAssetDescriptor> entry : session.descriptorsByAssetKey.entrySet()) {
            SceneAssetDescriptor descriptor = entry.getValue();
            transfer.authorize(player.getUUID(), descriptor.sha256(), descriptor.compressedSize());
            ServerPlayNetworking.send(player,
                    new SceneAssetPrefetchS2C(session.id, entry.getKey(), descriptor));
        }
    }

    /** The next requested offset proves that the previous bytes passed client CRC validation. */
    public void recordConfirmedBytes(ServerPlayer player, String sha256, long confirmedBytes) {
        Session session = sessionFor(player, sha256);
        if (session == null) return;
        PlayerProgress progress = session.players.get(player.getUUID());
        if (progress == null || progress.ready) return;
        SceneAssetDescriptor descriptor = session.descriptorsByAssetKey.values().stream()
                .filter(value -> value.sha256().equalsIgnoreCase(sha256)).findFirst().orElse(null);
        if (descriptor == null) return;
        progress.confirmedByHash.merge(descriptor.sha256(),
                Math.min(descriptor.compressedSize(), Math.max(0L, confirmedBytes)), Math::max);
        progress.confirmedBytes = progress.confirmedByHash.values().stream().mapToLong(Long::longValue).sum();
        int quarter = progress.totalBytes <= 0L ? 0
                : (int) Math.min(3L, progress.confirmedBytes * 4L / progress.totalBytes);
        if (quarter > progress.lastLoggedQuarter) {
            progress.lastLoggedQuarter = quarter;
            LOGGER.debug("[ScenePreload] progress session={} player={} bytes={}/{}",
                    session.id, player.getGameProfile().getName(),
                    progress.confirmedBytes, progress.totalBytes);
        }
    }

    public void handleReady(ServerPlayer player, long sessionId, String sha256, boolean success) {
        Session session = sessionFor(player, sha256);
        if (session == null || session.id != sessionId) return;
        PlayerProgress progress = session.players.get(player.getUUID());
        if (progress == null) return;
        progress.failed = progress.failed || !success;
        if (success) {
            progress.readyHashes.add(sha256.toLowerCase(java.util.Locale.ROOT));
            progress.ready = progress.readyHashes.containsAll(session.expectedHashes.stream()
                    .map(hash -> hash.toLowerCase(java.util.Locale.ROOT)).toList());
            if (progress.ready) progress.confirmedBytes = progress.totalBytes;
        }
        LOGGER.info("[ScenePreload] client {} session={} player={} ready={} ({}/{})",
                success ? "ready" : "failed", session.id, player.getGameProfile().getName(), success,
                readyCount(session), session.players.size());
    }

    public boolean allReady(ServerLevel level) {
        if (level == null) return true;
        Session session = sessions.get(level.dimension());
        if (session == null || session.players.isEmpty()) return true;
        for (PlayerProgress progress : session.players.values()) {
            if (!progress.ready) return false;
        }
        return true;
    }

    /** Switches every unfinished player from loading-page 5 MiB/s to in-match 1 MiB/s. */
    public void enterMatch(ServerLevel level) {
        if (level == null) return;
        Session session = sessions.get(level.dimension());
        if (session == null) return;
        session.loadingPhase = false;
        for (UUID playerId : session.players.keySet()) {
            SceneTransferService.getInstance().setBandwidthPhase(
                    playerId, SceneTransferService.BandwidthPhase.MATCH);
        }
        LOGGER.info("[ScenePreload] enter match session={} ready={}/{}",
                session.id, readyCount(session), session.players.size());
    }

    public void onPlayerDisconnect(UUID playerId) {
        if (playerId == null) return;
        for (Session session : sessions.values()) session.players.remove(playerId);
    }

    public void reset(ServerLevel level) {
        if (level == null) return;
        Session removed = sessions.remove(level.dimension());
        if (removed != null) {
            for (UUID playerId : removed.players.keySet()) {
                SceneTransferService.getInstance().setBandwidthPhase(
                        playerId, SceneTransferService.BandwidthPhase.MATCH);
            }
        }
    }

    public void resetAll() {
        for (Map.Entry<ResourceKey<Level>, Session> entry : sessions.entrySet()) {
            for (UUID playerId : entry.getValue().players.keySet()) {
                SceneTransferService.getInstance().setBandwidthPhase(
                        playerId, SceneTransferService.BandwidthPhase.MATCH);
            }
        }
        sessions.clear();
    }

    private Session sessionFor(ServerPlayer player, String sha256) {
        if (player == null || player.serverLevel() == null || sha256 == null) return null;
        Session session = sessions.get(player.serverLevel().dimension());
        if (session == null) return null;
        return session.expectedHashes.stream().anyMatch(hash -> hash.equalsIgnoreCase(sha256)) ? session : null;
    }

    private static int readyCount(Session session) {
        int count = 0;
        for (PlayerProgress progress : session.players.values()) if (progress.ready) count++;
        return count;
    }
}
