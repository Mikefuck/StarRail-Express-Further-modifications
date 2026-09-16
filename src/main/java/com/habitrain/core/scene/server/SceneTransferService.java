package com.habitrain.core.scene.server;

import com.habitrain.core.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.SceneLimits;
import com.habitrain.core.scene.network.SceneAssetChunkRequestC2S;
import com.habitrain.core.scene.network.SceneAssetChunkS2C;
import com.habitrain.core.scene.network.SceneAssetChunkStatusS2C;
import com.habitrain.core.scene.network.SceneAssetDeltaOfferS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 场景资产分片传输服务（限速、非阻塞读取、有界在途分片与 CRC32 校验）。
 *
 * <p><b>限速等待不占用 IO 线程</b>：此前 {@code reserveBandwidth} 之后直接
 * {@code Thread.sleep(delay)} 发生在唯一的 {@code HabiTrain-SceneTransfer-IO} 线程上，
 * 一个玩家等自己的配额会把其他玩家的文件读取一并阻塞。现在改为「先预约、再按 deadline
 * 延迟投递」：IO 线程只做真正的读取，等待发生在 pacer 上。</p>
 *
 * <p><b>每玩家允许有限个在途分片</b>：客户端的下载队列以有界窗口一次发出多片请求，
 * 因此这里不能再按「每玩家 1 片」拒绝。上限见 {@link #MAX_IN_FLIGHT_CHUNKS_PER_PLAYER}。</p>
 */
public final class SceneTransferService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneTransferService.class.getSimpleName());

    public static final int CHUNK_SIZE = 65536; // 64 KiB
    public static final long MAX_FILE_SIZE = SceneLimits.MAX_TRANSFER_BYTES;
    public static final long LOADING_BYTES_PER_SECOND = 5L * 1024L * 1024L;
    public static final long MATCH_BYTES_PER_SECOND = 1L * 1024L * 1024L;
    public static final long GLOBAL_BYTES_PER_SECOND = 16L * 1024L * 1024L;

    /** BUSY 回包携带的退避提示：客户端按此延迟重试，不消耗重试次数。 */
    public static final int BUSY_RETRY_AFTER_MS = 250;

    /**
     * 每玩家允许的在途分片数，取自公共容量边界。
     *
     * <p>取客户端窗口的上限而不是默认值：客户端超时回退时会丢弃整窗并在更高的 requestId
     * 上重发，而 pacer 里可能还停着上一窗的任务，服务端瞬时可见的在途数可达窗口的两倍。
     * 取上限使默认窗口 4 的正常与降级路径都不会撞上 BUSY；把窗口手动调到上限时降级路径
     * 可能收到 BUSY，客户端按提示退避且不消耗重试。</p>
     */
    public static final int MAX_IN_FLIGHT_CHUNKS_PER_PLAYER = SceneLimits.MAX_CHUNKS_IN_FLIGHT_PER_PLAYER;

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

    /**
     * 延迟投递线程：只负责等到 deadline 再把读取任务交给 IO 线程。
     * 等待发生在这里而不是 IO 线程上，是「单个玩家限速不再阻塞其他玩家」的关键。
     */
    private ScheduledExecutorService pacer = createPacer();
    private static ScheduledExecutorService createPacer() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HabiTrain-SceneTransfer-Pacer");
            t.setDaemon(true);
            return t;
        });
    }

    /** 记录每位玩家最近一次成功服务的分片，用于对重复请求幂等重发与识别陈旧请求。 */
    private record ServedRequest(long transferId, long requestId, String sha256, long offset) {}

    private final Map<UUID, ServedRequest> lastServed = new ConcurrentHashMap<>();
    private record TransferAuthorization(long compressedSize, File stagingFile, String stagingId, File deltaFile) {}

    private final ConcurrentMap<UUID, Map<String, TransferAuthorization>> manifestAllowlist = new ConcurrentHashMap<>();
    /** 每玩家当前在途分片数；从服务端线程、IO 线程与 pacer 线程访问，故所有改动都在 {@link #inFlightLock} 内。 */
    private final Map<UUID, Integer> inFlightCount = new HashMap<>();
    private final Object inFlightLock = new Object();
    /** 单玩家与全局的下行配额游标；只在服务端线程预约，因此这里不再额外加锁。 */
    private final SceneBandwidthGovernor governor =
            new SceneBandwidthGovernor(System::nanoTime, GLOBAL_BYTES_PER_SECOND);
    private final Map<UUID, BandwidthPhase> bandwidthPhases = new ConcurrentHashMap<>();

    private SceneTransferService() {}

    /**
     * 处理客户端发来的分片请求。
     *
     * <p>本方法在服务端线程执行（由 C2S 接收器调度）。每一个未满足的请求都会收到
     * 一条 {@link SceneAssetChunkStatusS2C}，不存在「静默丢弃」路径：此前被静默拒绝的
     * 请求会让客户端永远停留在等待下一片的 {@code activeDownloads} 里。</p>
     */
    public void handleChunkRequest(ServerPlayer player, SceneAssetChunkRequestC2S request) {
        if (player == null || request == null) return;
        UUID playerId = player.getUUID();
        String requestedHash = request.sha256();
        long transferId = request.transferId();
        long requestId = request.requestId();
        long offset = request.chunkOffset();
        int size = Math.min(CHUNK_SIZE, request.chunkSize());

        if (requestedHash == null || !requestedHash.matches("[0-9a-fA-F]{64}")) {
            LOGGER.warn("玩家 {} 请求了非法场景 hash", player.getName().getString());
            sendStatus(player, requestedHash, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.REJECTED, 0);
            return;
        }
        final String sha256 = requestedHash.toLowerCase();

        TransferAuthorization authorization = manifestAllowlist.getOrDefault(playerId, Map.of())
                .get(sha256);
        if (authorization == null) {
            LOGGER.warn("玩家 {} 请求了未由 Manifest 授权的场景资产: {}", player.getName().getString(), sha256);
            sendStatus(player, sha256, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.NOT_AUTHORIZED, 0);
            return;
        }

        // 陈旧请求：同一次传输内序号倒退回「已服务过的另一个分片」之前。
        // 对同一个 (hash, offset) 的重复请求不算陈旧——那是客户端超时重试，
        // 必须照常重发同一片，这正是幂等重发要解决的场景。
        ServedRequest previous = lastServed.get(playerId);
        if (previous != null
                && previous.transferId() == transferId
                && requestId < previous.requestId()
                && !(previous.offset() == offset && previous.sha256().equals(sha256))) {
            LOGGER.debug("玩家 {} 发来了陈旧的分片请求: requestId={} < {}",
                    player.getName().getString(), requestId, previous.requestId());
            // 携带退避提示：客户端的队列窗口一旦回退就整窗重置，给 0 会让它立刻以 1 ms
            // 的间隔热重试。客户端把 STALE 与 BUSY 同等对待，都不消耗重试次数。
            sendStatus(player, sha256, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.STALE, BUSY_RETRY_AFTER_MS);
            return;
        }

        if (!tryAcquirePlayerSlot(playerId)) {
            LOGGER.debug("玩家 {} 在途场景分片已达上限 {}，回复 BUSY 让其退避重试",
                    player.getName().getString(), MAX_IN_FLIGHT_CHUNKS_PER_PLAYER);
            sendStatus(player, sha256, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.BUSY, BUSY_RETRY_AFTER_MS);
            return;
        }

        // 校验 offset 对齐
        if (offset < 0 || (offset % CHUNK_SIZE != 0 && offset != 0)) {
            LOGGER.warn("玩家 {} 请求了未对齐的场景分片 offset: {}", player.getName().getString(), offset);
            releasePlayerSlot(playerId);
            sendStatus(player, sha256, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.OUT_OF_RANGE, 0);
            return;
        }

        // 授权条目可能指向三种东西：管理员暂存文件、增量补丁、正式资产。三者都是内容寻址的
        // 不透明字节，客户端只按 hash 请求，因此这里按同一条链路服务。
        File file = authorization.stagingFile() != null
                ? authorization.stagingFile()
                : (authorization.deltaFile() != null
                        ? authorization.deltaFile()
                        : SceneAssetStore.getInstance().getAssetFile(sha256));
        if (file == null || !file.exists()) {
            LOGGER.debug("玩家 {} 请求了不存在的场景资产: {}", player.getName().getString(), sha256);
            releasePlayerSlot(playerId);
            sendStatus(player, sha256, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.OUT_OF_RANGE, 0);
            return;
        }

        long fileLen = file.length();
        if (offset >= fileLen || fileLen > MAX_FILE_SIZE || fileLen != authorization.compressedSize()) {
            LOGGER.warn("玩家 {} 请求的 offset 超出文件大小: offset={}, fileLen={}", player.getName().getString(), offset, fileLen);
            releasePlayerSlot(playerId);
            sendStatus(player, sha256, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.OUT_OF_RANGE, 0);
            return;
        }

        int readLen = (int) Math.min((long) size, fileLen - offset);
        if (readLen <= 0) {
            releasePlayerSlot(playerId);
            sendStatus(player, sha256, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.OUT_OF_RANGE, 0);
            return;
        }

        lastServed.put(playerId, new ServedRequest(transferId, requestId, sha256, offset));
        ScenePreloadCoordinator.getInstance().recordConfirmedBytes(player, sha256, offset);

        dispatchRead(player, playerId, file, sha256, transferId, requestId, offset, fileLen, readLen);
    }

    /**
     * 预约带宽后把读取任务延迟投递到 IO 线程。
     *
     * <p>这里只做「算出最早可发送时刻 + 交给 pacer」，绝不 sleep——IO 线程是这个服务里唯一
     * 读磁盘的地方，在它上面等待会把所有其他玩家的分片一起堵住。</p>
     */
    private void dispatchRead(ServerPlayer player, UUID playerId, File file, String sha256,
                              long transferId, long requestId, long offset, long fileLen, int readLen) {
        Runnable readTask = () -> readAndSend(player, playerId, file, sha256,
                transferId, requestId, offset, fileLen, readLen);
        long readyAt = governor.reserve(playerId, readLen, bytesPerSecond(getBandwidthPhase(playerId)));
        // 必须与 governor 同一时基，否则注入假时钟时这里的等待时间不可验证。
        long delayNanos = readyAt - governor.nowNanos();
        if (delayNanos <= 0L) {
            ensureExecutor().execute(readTask);
            return;
        }
        try {
            // ScheduledThreadPoolExecutor 内部是 DelayQueue：等 deadline 的任务按
            // (deadline, 提交序号) 出队，因此同一玩家的分片顺序与请求顺序一致。
            ensurePacer().schedule(() -> ensureExecutor().execute(readTask), delayNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            // pacer 已关闭（服务器停止中）：退化为立即读取，而不是把这一片丢掉。
            ensureExecutor().execute(readTask);
        }
    }

    /** IO 线程：读盘 + 计算 CRC，再跳回服务端线程发送。文件句柄只在真正读取期间打开。 */
    private void readAndSend(ServerPlayer player, UUID playerId, File file, String sha256,
                             long transferId, long requestId, long offset, long fileLen, int readLen) {
        if (player.hasDisconnected()) {
            releasePlayerSlot(playerId);
            return;
        }
        try {
            byte[] buffer = new byte[readLen];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                raf.readFully(buffer);
            }

            long crc32 = SceneAssetCodec.calculateCrc32(buffer, 0, buffer.length);
            SceneAssetChunkS2C payload = new SceneAssetChunkS2C(
                    sha256, transferId, requestId, offset, fileLen, buffer, crc32);

            player.server.execute(() -> {
                try {
                    if (player.connection != null && !player.hasDisconnected()) {
                        ServerPlayNetworking.send(player, payload);
                    }
                } finally {
                    releasePlayerSlot(playerId);
                }
            });
        } catch (Throwable t) {
            releasePlayerSlot(playerId);
            LOGGER.error("读取场景资产分片异常: file=" + file.getName() + ", offset=" + offset, t);
        }
    }

    private void sendStatus(ServerPlayer player, String sha256, long transferId, long requestId,
                            long offset, SceneAssetChunkStatusS2C.Status status, int retryAfterMillis) {
        if (player.connection == null || player.hasDisconnected()) return;
        ServerPlayNetworking.send(player, new SceneAssetChunkStatusS2C(
                sha256, transferId, requestId, offset, status, retryAfterMillis));
    }

    public void authorize(UUID playerId, String sha256, long compressedSize) {
        if (playerId == null || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")
                || compressedSize <= 0 || compressedSize > MAX_FILE_SIZE) return;
        manifestAllowlist.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(sha256.toLowerCase(), new TransferAuthorization(compressedSize, null, "", null));
    }

    /**
     * 下发清单时的统一授权入口：把"这版资产"以及"它的增量补丁"一起授权给该玩家。
     *
     * <p>所有下发清单的地方都走这里，而不是各自调 {@code authorize(...)}——补丁 hash 漏授权
     * 的后果是客户端分片请求被拒成 {@code NOT_AUTHORIZED}，然后静默退化成全量下载：
     * 功能还在，只是永远不生效，而且没有任何报错。</p>
     */
    public void authorizeAsset(ServerPlayer player, String mapKey, SceneAssetDescriptor descriptor) {
        if (player == null || descriptor == null || !descriptor.isValid()) return;
        authorize(player.getUUID(), descriptor.sha256(), descriptor.compressedSize());
        SceneDeltaStore.DeltaInfo info = SceneDeltaStore.getInstance().get(mapKey);
        if (info != null && info.isValid()) {
            authorizeDelta(player.getUUID(), info.patchSha256(), info.patchBytes());
        }
    }

    /**
     * 授权一位玩家下载某枚增量补丁。
     *
     * <p>补丁与资产共用同一条分片链路（hash → offset → 分片），因此这里只需要把
     * "这个 hash 对应 deltas/ 下的哪个文件"记进授权表即可。</p>
     */
    public void authorizeDelta(UUID playerId, String patchSha256, long patchBytes) {
        if (playerId == null || patchSha256 == null || !patchSha256.matches("[0-9a-fA-F]{64}")
                || patchBytes <= 0 || patchBytes > MAX_FILE_SIZE) return;
        File patchFile = SceneDeltaStore.getInstance().patchFile(patchSha256);
        if (patchFile == null || !patchFile.isFile() || patchFile.length() != patchBytes) {
            LOGGER.warn("拒绝授权不存在的增量补丁: {}", patchSha256);
            return;
        }
        manifestAllowlist.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(patchSha256.toLowerCase(),
                        new TransferAuthorization(patchBytes, null, "", patchFile));
    }

    /**
     * 处理增量探测：只查内存索引与文件存在性，不做磁盘读，因此可以安全地在服务端线程执行。
     */
    public void handleDeltaProbe(ServerPlayer player, String mapKey, String targetSha256) {
        if (player == null) return;
        String target = targetSha256 == null ? "" : targetSha256.trim().toLowerCase();
        if (player.connection == null || player.hasDisconnected()) return;
        if (!target.matches("[0-9a-fA-F]{64}")) {
            sendDeltaOffer(player, SceneAssetDeltaOfferS2C.none(target));
            return;
        }
        SceneAssetDescriptor descriptor = SceneAssetStore.getInstance().getDescriptor(mapKey);
        SceneDeltaStore.DeltaInfo info = SceneDeltaStore.getInstance().get(mapKey);
        // 只对"当前这一版"提供补丁：补丁是相对上一版生成的，目标对不上就没有意义。
        if (descriptor == null || !descriptor.isValid()
                || !descriptor.sha256().equalsIgnoreCase(target) || info == null || !info.isValid()) {
            sendDeltaOffer(player, SceneAssetDeltaOfferS2C.none(target));
            return;
        }
        authorizeDelta(player.getUUID(), info.patchSha256(), info.patchBytes());
        sendDeltaOffer(player, new SceneAssetDeltaOfferS2C(
                target, SceneAssetDeltaOfferS2C.Status.OK,
                info.baseSha256(), info.patchSha256(), info.patchBytes()));
    }

    private void sendDeltaOffer(ServerPlayer player, SceneAssetDeltaOfferS2C payload) {
        if (player.connection == null || player.hasDisconnected()) return;
        ServerPlayNetworking.send(player, payload);
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
                        new TransferAuthorization(compressedSize, stagingFile, stagingId, null));
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
                            new TransferAuthorization(authorization.compressedSize(), null, "", null));
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

    /**
     * 占用一个在途分片名额。
     *
     * <p>名额从服务端线程取、由 IO 线程与 pacer 线程释放，因此必须串行化；这里只做 O(1)
     * 的 map 操作，不构成新的热点。</p>
     */
    private boolean tryAcquirePlayerSlot(UUID playerId) {
        synchronized (inFlightLock) {
            int current = inFlightCount.getOrDefault(playerId, 0);
            if (current >= MAX_IN_FLIGHT_CHUNKS_PER_PLAYER) return false;
            inFlightCount.put(playerId, current + 1);
            return true;
        }
    }

    /** 释放一个名额；归零时移除键，避免长会话里 UUID 表无界增长。 */
    private void releasePlayerSlot(UUID playerId) {
        if (playerId == null) return;
        synchronized (inFlightLock) {
            int current = inFlightCount.getOrDefault(playerId, 0);
            if (current <= 1) {
                inFlightCount.remove(playerId);
            } else {
                inFlightCount.put(playerId, current - 1);
            }
        }
    }

    /** 供诊断：该玩家当前在途分片数。 */
    public int inFlightChunks(UUID playerId) {
        if (playerId == null) return 0;
        synchronized (inFlightLock) {
            return inFlightCount.getOrDefault(playerId, 0);
        }
    }

    private synchronized ExecutorService ensureExecutor() {
        if (ioExecutor == null || ioExecutor.isShutdown()) ioExecutor = createExecutor();
        return ioExecutor;
    }

    private synchronized ScheduledExecutorService ensurePacer() {
        if (pacer == null || pacer.isShutdown()) pacer = createPacer();
        return pacer;
    }

    public void onPlayerDisconnect(UUID playerId) {
        if (playerId != null) {
            lastServed.remove(playerId);
            manifestAllowlist.remove(playerId);
            synchronized (inFlightLock) {
                inFlightCount.remove(playerId);
            }
            governor.forget(playerId);
            bandwidthPhases.remove(playerId);
        }
    }

    public synchronized void shutdown() {
        lastServed.clear();
        manifestAllowlist.clear();
        synchronized (inFlightLock) {
            inFlightCount.clear();
        }
        governor.reset();
        bandwidthPhases.clear();
        if (ioExecutor != null) ioExecutor.shutdownNow();
        if (pacer != null) pacer.shutdownNow();
    }
}
