package com.habitrain.core.scene.server;

import com.habitrain.core.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.scene.network.SceneAssetChunkRequestC2S;
import com.habitrain.core.scene.network.SceneAssetChunkS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;

/**
 * 场景资产分片传输服务（限速、非阻塞读取、单在途分片与 CRC32 校验）。
 */
public final class SceneTransferService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneTransferService.class.getSimpleName());

    public static final int CHUNK_SIZE = 65536; // 64 KiB
    public static final long MAX_FILE_SIZE = SceneLimits.MAX_TRANSFER_BYTES;
    public static final long LOADING_BYTES_PER_SECOND = 5L * 1024L * 1024L;
    public static final long MATCH_BYTES_PER_SECOND = 1L * 1024L * 1024L;
    public static final long GLOBAL_BYTES_PER_SECOND = 16L * 1024L * 1024L;

    public enum BandwidthPhase {
        LOADING,
        MATCH
    }

    private static final SceneTransferService INSTANCE = new SceneTransferService();

    public static SceneTransferService getInstance() {
        return INSTANCE;
    }

    private ExecutorService ioExecutor = createExecutor();
    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HabiTrain-SceneTransfer-IO");
            t.setDaemon(true);
            return t;
        });
    }

    /** 记录每位玩家当前是否有未完成的在途分片请求 */
    private final Map<UUID, Long> lastRequestTime = new ConcurrentHashMap<>();
    private record TransferAuthorization(long compressedSize, File stagingFile, String stagingId) {}

    private final ConcurrentMap<UUID, Map<String, TransferAuthorization>> manifestAllowlist = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> nextPlayerSendNanos = new ConcurrentHashMap<>();
    private final Map<UUID, BandwidthPhase> bandwidthPhases = new ConcurrentHashMap<>();
    private long nextGlobalSendNanos;

    private SceneTransferService() {}

    /**
     * 处理客户端发来的分片请求。
     */
    public void handleChunkRequest(ServerPlayer player, SceneAssetChunkRequestC2S request) {
        if (player == null || request == null) return;
        UUID playerId = player.getUUID();
        String sha256 = request.sha256();
        long offset = request.chunkOffset();
        int size = Math.min(CHUNK_SIZE, request.chunkSize());

        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            LOGGER.warn("玩家 {} 请求了非法场景 hash", player.getName().getString());
            return;
        }

        TransferAuthorization authorization = manifestAllowlist.getOrDefault(playerId, Map.of())
                .get(sha256.toLowerCase());
        if (authorization == null) {
            LOGGER.warn("玩家 {} 请求了未由 Manifest 授权的场景资产: {}", player.getName().getString(), sha256);
            return;
        }

        if (!inFlight.add(playerId)) {
            LOGGER.debug("玩家 {} 同时请求多个场景分片，已拒绝", player.getName().getString());
            return;
        }

        // 校验 offset 对齐
        if (offset < 0 || (offset % CHUNK_SIZE != 0 && offset != 0)) {
            LOGGER.warn("玩家 {} 请求了未对齐的场景分片 offset: {}", player.getName().getString(), offset);
            inFlight.remove(playerId);
            return;
        }

        File file = authorization.stagingFile() != null
                ? authorization.stagingFile()
                : SceneAssetStore.getInstance().getAssetFile(sha256);
        if (file == null || !file.exists()) {
            LOGGER.debug("玩家 {} 请求了不存在的场景资产: {}", player.getName().getString(), sha256);
            inFlight.remove(playerId);
            return;
        }

        long fileLen = file.length();
        if (offset >= fileLen || fileLen > MAX_FILE_SIZE || fileLen != authorization.compressedSize()) {
            LOGGER.warn("玩家 {} 请求的 offset 超出文件大小: offset={}, fileLen={}", player.getName().getString(), offset, fileLen);
            inFlight.remove(playerId);
            return;
        }

        int readLen = (int) Math.min((long) size, fileLen - offset);
        if (readLen <= 0) {
            inFlight.remove(playerId);
            return;
        }

        lastRequestTime.put(playerId, System.currentTimeMillis());
        ScenePreloadCoordinator.getInstance().recordConfirmedBytes(player, sha256, offset);

        // 在后台 IO 线程读取文件分片，避免在 server tick 线程打开 RandomAccessFile
        ensureExecutor().execute(() -> {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long delayNanos = reserveBandwidth(playerId, readLen);
                if (delayNanos > 0L) {
                    Thread.sleep(Math.max(1L, delayNanos / 1_000_000L));
                }
                raf.seek(offset);
                byte[] buffer = new byte[readLen];
                raf.readFully(buffer);

                long crc32 = SceneAssetCodec.calculateCrc32(buffer, 0, buffer.length);
                SceneAssetChunkS2C payload = new SceneAssetChunkS2C(sha256, offset, fileLen, buffer, crc32);

                player.server.execute(() -> {
                    try {
                        if (player.connection != null && !player.hasDisconnected()) {
                            ServerPlayNetworking.send(player, payload);
                        }
                    } finally {
                        inFlight.remove(playerId);
                    }
                });
            } catch (Throwable t) {
                inFlight.remove(playerId);
                LOGGER.error("读取场景资产分片异常: file=" + file.getName() + ", offset=" + offset, t);
            }
        });
    }

    public void authorize(UUID playerId, String sha256, long compressedSize) {
        if (playerId == null || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")
                || compressedSize <= 0 || compressedSize > MAX_FILE_SIZE) return;
        manifestAllowlist.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(sha256.toLowerCase(), new TransferAuthorization(compressedSize, null, ""));
    }

    /** Grants one administrator access to one staging file; no other player is authorized. */
    public void authorizeStaging(UUID playerId, String stagingId, String sha256, long compressedSize) {
        if (playerId == null || stagingId == null || sha256 == null
                || !sha256.matches("[0-9a-fA-F]{64}")
                || compressedSize <= 0 || compressedSize > MAX_FILE_SIZE) return;
        File stagingFile = SceneAssetStore.getInstance().getStagingAssetFile(stagingId);
        if (stagingFile == null || stagingFile.length() != compressedSize) return;
        manifestAllowlist.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(sha256.toLowerCase(),
                        new TransferAuthorization(compressedSize, stagingFile, stagingId));
    }

    public void revokeStaging(String stagingId) {
        if (stagingId == null || stagingId.isBlank()) return;
        for (Map<String, TransferAuthorization> authorizations : manifestAllowlist.values()) {
            for (Map.Entry<String, TransferAuthorization> entry : Map.copyOf(authorizations).entrySet()) {
                TransferAuthorization authorization = entry.getValue();
                if (!stagingId.equals(authorization.stagingId())) continue;
                File liveFile = SceneAssetStore.getInstance().getAssetFile(entry.getKey());
                if (liveFile != null && liveFile.length() == authorization.compressedSize()) {
                    authorizations.replace(entry.getKey(), authorization,
                            new TransferAuthorization(authorization.compressedSize(), null, ""));
                } else {
                    authorizations.remove(entry.getKey(), authorization);
                }
            }
        }
    }

    boolean hasStagingAuthorization(UUID playerId, String stagingId, String sha256) {
        TransferAuthorization authorization = manifestAllowlist.getOrDefault(playerId, Map.of())
                .get(sha256 == null ? "" : sha256.toLowerCase());
        return authorization != null && stagingId != null && stagingId.equals(authorization.stagingId());
    }

    public void setBandwidthPhase(UUID playerId, BandwidthPhase phase) {
        if (playerId == null) return;
        bandwidthPhases.put(playerId, phase == null ? BandwidthPhase.MATCH : phase);
    }

    public BandwidthPhase getBandwidthPhase(UUID playerId) {
        return playerId == null ? BandwidthPhase.MATCH
                : bandwidthPhases.getOrDefault(playerId, BandwidthPhase.MATCH);
    }

    public static long bytesPerSecond(BandwidthPhase phase) {
        return phase == BandwidthPhase.LOADING ? LOADING_BYTES_PER_SECOND : MATCH_BYTES_PER_SECOND;
    }

    private synchronized long reserveBandwidth(UUID playerId, int bytes) {
        long now = System.nanoTime();
        long start = Math.max(now, Math.max(nextPlayerSendNanos.getOrDefault(playerId, now), nextGlobalSendNanos));
        long playerRate = bytesPerSecond(getBandwidthPhase(playerId));
        nextPlayerSendNanos.put(playerId, start + (long) (bytes * (1_000_000_000.0 / playerRate)));
        nextGlobalSendNanos = start + (long) (bytes * (1_000_000_000.0 / GLOBAL_BYTES_PER_SECOND));
        return Math.max(0L, start - now);
    }

    private synchronized ExecutorService ensureExecutor() {
        if (ioExecutor == null || ioExecutor.isShutdown()) ioExecutor = createExecutor();
        return ioExecutor;
    }

    public void onPlayerDisconnect(UUID playerId) {
        if (playerId != null) {
            lastRequestTime.remove(playerId);
            manifestAllowlist.remove(playerId);
            inFlight.remove(playerId);
            nextPlayerSendNanos.remove(playerId);
            bandwidthPhases.remove(playerId);
        }
    }

    public synchronized void shutdown() {
        lastRequestTime.clear();
        manifestAllowlist.clear();
        inFlight.clear();
        nextPlayerSendNanos.clear();
        bandwidthPhases.clear();
        nextGlobalSendNanos = 0L;
        if (ioExecutor != null) ioExecutor.shutdownNow();
    }
}
