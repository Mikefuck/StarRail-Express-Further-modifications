package com.habitrain.core.scene.server;

import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.api.scene.SceneLimits;
import com.habitrain.core.scene.network.SceneAssetChunkRequestC2S;
import com.habitrain.core.scene.network.SceneAssetChunkS2C;
import com.habitrain.core.scene.network.SceneAssetChunkStatusS2C;
import com.habitrain.core.scene.network.SceneAssetDeltaOfferS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
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
    private static final long WARN_LOG_INTERVAL_NANOS = 5_000_000_000L;

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
    private final ThreadLocal<SceneFileReader> fileReader = ThreadLocal.withInitial(() -> new SceneFileReader(32));
    private ExecutorService createExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(256), r -> {
            Thread t = new Thread(() -> {
                try (SceneFileReader reader = fileReader.get()) { r.run(); }
                catch (java.io.IOException failure) { LOGGER.warn("关闭场景文件句柄失败", failure); }
                finally { fileReader.remove(); }
            }, "HabiTrain-SceneTransfer-IO");
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

    /** 记录每位玩家针对每个资产最近一次成功服务的分片，用于对重复请求幂等重发与识别陈旧请求。 */
    private record ServedRequest(long transferId, long requestId, String sha256, long offset) {}

    private final Map<UUID, Map<String, ServedRequest>> lastServed = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWarnNanos = new ConcurrentHashMap<>();
    private record InstanceGrant(String instanceId, String targetHash) {}
    private record TransferAuthorization(long compressedSize, File stagingFile, String stagingId, File deltaFile,
                                         java.util.Set<InstanceGrant> instanceGrants) {
        TransferAuthorization(long size, File staged, String stagingId, File delta) {
            this(size, staged, stagingId, delta, null); // public or owner-only staging grant
        }
    }

    private final ConcurrentMap<UUID, Map<String, TransferAuthorization>> manifestAllowlist = new ConcurrentHashMap<>();
    /** 每玩家当前在途分片数与会话代数；从服务端线程、IO 线程与 pacer 线程访问，故所有改动都在 {@link #inFlightLock} 内。 */
    private final Map<UUID, Integer> inFlightCount = new HashMap<>();
    private final Map<UUID, Long> playerEpochs = new HashMap<>();
    private final Object inFlightLock = new Object();
    private long nextPlayerEpoch;
    private volatile long serviceEpoch = 1L;
    private volatile boolean accepting = true;
    /** 单玩家与全局的下行配额游标；只在服务端线程预约，因此这里不再额外加锁。 */
    private final SceneBandwidthGovernor governor =
            new SceneBandwidthGovernor(System::nanoTime, GLOBAL_BYTES_PER_SECOND);
    private final Map<UUID, BandwidthPhase> bandwidthPhases = new ConcurrentHashMap<>();

    private SceneTransferService() {}

    /**
     * 处理客户端发来的分片请求。
     *
     * <p>本方法在服务端线程执行（由 C2S 接收器调度）。每一个未满足的请求都会收到
     * 一条 {@link SceneAssetChunkStatusS2C}；读取异常也会回传 REJECTED 终态。
     * 连接断开或服务器停止后无法保证送达，客户端仍以超时机制兜底。</p>
     */
    public void handleChunkRequest(ServerPlayer player, SceneAssetChunkRequestC2S request) {
        if (player == null || request == null || !accepting) return;
        UUID playerId = player.getUUID();
        String requestedHash = request.sha256();
        long transferId = request.transferId();
        long requestId = request.requestId();
        long offset = request.chunkOffset();
        int size = Math.min(CHUNK_SIZE, request.chunkSize());

        if (requestedHash == null || !SceneAssetDescriptor.isValidSha256(requestedHash)) {
            if (shouldLogWarn(playerId)) {
                LOGGER.warn("玩家 {} 请求了非法场景 hash", player.getName().getString());
            }
            sendStatus(player, requestedHash, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.REJECTED, 0);
            return;
        }
        final String sha256 = requestedHash.toLowerCase(Locale.ROOT);

        TransferAuthorization authorization = manifestAllowlist.getOrDefault(playerId, Map.of())
                .get(sha256);
        if (authorization == null || !isCurrentlyVisible(player, authorization)) {
            if (shouldLogWarn(playerId)) {
                LOGGER.warn("玩家 {} 请求了未由 Manifest 授权的场景资产: {}", player.getName().getString(), sha256);
            }
            sendStatus(player, sha256, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.NOT_AUTHORIZED, 0);
            return;
        }

        // 陈旧请求：同一资产、同一次传输内序号倒退回「已服务过的另一个分片」之前。
        // 对同一个 (hash, offset) 的重复请求不算陈旧——那是客户端超时重试，
        // 必须照常重发同一片，这正是幂等重发要解决的场景。
        Map<String, ServedRequest> playerServed = lastServed.get(playerId);
        ServedRequest previous = playerServed != null ? playerServed.get(sha256) : null;
        if (previous != null
                && previous.transferId() == transferId
                && requestId < previous.requestId()
                && !(previous.offset() == offset && previous.sha256().equals(sha256))) {
            LOGGER.debug("玩家 {} 发来了陈旧的分片请求: requestId={} < {}",
                    player.getName().getString(), requestId, previous.requestId());
            sendStatus(player, sha256, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.STALE, BUSY_RETRY_AFTER_MS);
            return;
        }

        // 校验 offset 对齐与授权声明的文件大小（不在此处触碰主线程磁盘 stat）
        long expectedLen = authorization.compressedSize();
        if (offset < 0 || (offset % CHUNK_SIZE != 0 && offset != 0)
                || offset >= expectedLen || expectedLen <= 0 || expectedLen > MAX_FILE_SIZE) {
            if (shouldLogWarn(playerId)) {
                LOGGER.warn("玩家 {} 请求了越界或未对齐的场景分片: offset={}, expectedLen={}",
                        player.getName().getString(), offset, expectedLen);
            }
            sendStatus(player, sha256, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.OUT_OF_RANGE, 0);
            return;
        }

        int readLen = (int) Math.min((long) size, expectedLen - offset);
        if (readLen <= 0) {
            sendStatus(player, sha256, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.OUT_OF_RANGE, 0);
            return;
        }

        long slotEpoch = tryAcquirePlayerSlot(playerId);
        if (slotEpoch < 0L) {
            LOGGER.debug("玩家 {} 在途场景分片已达上限 {}，回复 BUSY 让其退避重试",
                    player.getName().getString(), MAX_IN_FLIGHT_CHUNKS_PER_PLAYER);
            sendStatus(player, sha256, transferId, requestId, offset,
                    SceneAssetChunkStatusS2C.Status.BUSY, BUSY_RETRY_AFTER_MS);
            return;
        }

        lastServed.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(sha256, new ServedRequest(transferId, requestId, sha256, offset));
        ScenePreloadCoordinator.getInstance().recordConfirmedBytes(player, sha256, offset);

        dispatchRead(player, playerId, slotEpoch, authorization, sha256,
                transferId, requestId, offset, expectedLen, readLen);
    }

    private boolean shouldLogWarn(UUID playerId) {
        long now = System.nanoTime();
        Long prev = lastWarnNanos.get(playerId);
        if (prev == null || now - prev >= WARN_LOG_INTERVAL_NANOS) {
            lastWarnNanos.put(playerId, now);
            return true;
        }
        return false;
    }

    /**
     * 预约带宽后把读取任务延迟投递到 IO 线程。
     *
     * <p>这里只做「算出最早可发送时刻 + 交给 pacer」，绝不 sleep——IO 线程是这个服务里唯一
     * 读磁盘的地方，在它上面等待会把所有其他玩家的分片一起堵住。</p>
     */
    private void dispatchRead(ServerPlayer player, UUID playerId, long slotEpoch,
                              TransferAuthorization authorization, String sha256,
                              long transferId, long requestId, long offset, long expectedLen, int readLen) {
        final long dispatchServiceEpoch = this.serviceEpoch;
        final File sourceFile = authorization.stagingFile() != null ? authorization.stagingFile()
                : authorization.deltaFile() != null ? authorization.deltaFile()
                : SceneAssetStore.getInstance().assetPath(sha256);
        Runnable readTask = () -> readAndSend(player, playerId, slotEpoch, sourceFile, sha256,
                transferId, requestId, offset, expectedLen, readLen);
        long readyAt = governor.reserve(playerId, readLen, bytesPerSecond(getBandwidthPhase(playerId)));
        // 必须与 governor 同一时基，否则注入假时钟时这里的等待时间不可验证。
        long delayNanos = readyAt - governor.nowNanos();
        if (delayNanos <= 0L) {
            if (!submitToIo(dispatchServiceEpoch, readTask)) {
                releasePlayerSlot(playerId, slotEpoch);
                retryDispatch(player, playerId, slotEpoch, sha256, transferId, requestId, offset);
            }
            return;
        }
        try {
            ScheduledExecutorService currentPacer = ensurePacer(dispatchServiceEpoch);
            if (currentPacer == null) {
                releasePlayerSlot(playerId, slotEpoch);
                return;
            }
            currentPacer.schedule(() -> {
                if (!submitToIo(dispatchServiceEpoch, readTask)) {
                    releasePlayerSlot(playerId, slotEpoch);
                    retryDispatch(player, playerId, slotEpoch, sha256, transferId, requestId, offset);
                }
            }, delayNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            if (!submitToIo(dispatchServiceEpoch, readTask)) {
                releasePlayerSlot(playerId, slotEpoch);
                retryDispatch(player, playerId, slotEpoch, sha256, transferId, requestId, offset);
            }
        }
    }

    private void retryDispatch(ServerPlayer player, UUID playerId, long epoch, String hash,
                               long transfer, long request, long offset) {
        if (!isCurrentSlot(playerId, epoch)) return;
        player.server.execute(() -> {
            if (isCurrentSlot(playerId, epoch)) sendStatus(player, hash, transfer, request, offset,
                    SceneAssetChunkStatusS2C.Status.BUSY, BUSY_RETRY_AFTER_MS);
        });
    }

    private synchronized boolean submitToIo(long expectedServiceEpoch, Runnable task) {
        if (!accepting || this.serviceEpoch != expectedServiceEpoch) {
            return false;
        }
        try {
            ensureExecutor().execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** IO 线程：解析文件并校验长度、读盘 + 计算 CRC，再跳回服务端线程发送。 */
    private void readAndSend(ServerPlayer player, UUID playerId, long slotEpoch,
                             File file, String sha256,
                             long transferId, long requestId, long offset, long expectedLen, int readLen) {
        if (!isCurrentSlot(playerId, slotEpoch)) {
            releasePlayerSlot(playerId, slotEpoch);
            return;
        }
        if (file == null) {
            releasePlayerSlot(playerId, slotEpoch);
            try {
                player.server.execute(() -> { if (isCurrentSlot(playerId, slotEpoch)) sendStatus(player, sha256, transferId, requestId, offset,
                        SceneAssetChunkStatusS2C.Status.OUT_OF_RANGE, 0); });
            } catch (RuntimeException ignored) {
            }
            return;
        }
        try {
            byte[] buffer = fileReader.get().read(file, expectedLen, offset, readLen);

            long crc32 = SceneAssetCodec.calculateCrc32(buffer, 0, buffer.length);
            SceneAssetChunkS2C payload = new SceneAssetChunkS2C(
                    sha256, transferId, requestId, offset, expectedLen, buffer, crc32);

            player.server.execute(() -> {
                try {
                    if (!isCurrentSlot(playerId, slotEpoch)) return;
                    TransferAuthorization current = manifestAllowlist.getOrDefault(playerId, Map.of()).get(sha256);
                    if (isCurrentSlot(playerId, slotEpoch) && player.connection != null && !player.hasDisconnected()
                            && current != null && isCurrentlyVisible(player, current)) {
                        ServerPlayNetworking.send(player, payload);
                    } else {
                        sendStatus(player, sha256, transferId, requestId, offset,
                                SceneAssetChunkStatusS2C.Status.NOT_AUTHORIZED, 0);
                    }
                } finally {
                    releasePlayerSlot(playerId, slotEpoch);
                }
            });
        } catch (Throwable t) {
            releasePlayerSlot(playerId, slotEpoch);
            LOGGER.error("读取场景资产分片异常: file=" + file.getName() + ", offset=" + offset, t);
            try {
                player.server.execute(() -> { if (isCurrentSlot(playerId, slotEpoch)) sendStatus(player, sha256, transferId, requestId, offset,
                        SceneAssetChunkStatusS2C.Status.REJECTED, 0); });
            } catch (RuntimeException deliveryFailure) {
                LOGGER.warn("服务器停止或调度失败，分片读取错误无法送达；客户端将超时: player={}, request={}",
                        playerId, requestId, deliveryFailure);
            }
        }
    }

    private void sendStatus(ServerPlayer player, String sha256, long transferId, long requestId,
                            long offset, SceneAssetChunkStatusS2C.Status status, int retryAfterMillis) {
        if (player.connection == null || player.hasDisconnected()) return;
        ServerPlayNetworking.send(player, new SceneAssetChunkStatusS2C(
                sha256, transferId, requestId, offset, status, retryAfterMillis));
    }

    public void authorize(UUID playerId, String sha256, long compressedSize) {
        if (playerId == null || !SceneAssetDescriptor.isValidSha256(sha256)
                || compressedSize <= 0 || compressedSize > MAX_FILE_SIZE) return;
        manifestAllowlist.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(sha256.toLowerCase(Locale.ROOT), new TransferAuthorization(compressedSize, null, "", null));
    }

    /**
     * 下发清单时的统一授权入口：把"这版资产"以及"它的增量补丁"一起授权给该玩家。
     *
     * <p>所有下发清单的地方都走这里，而不是各自调 {@code authorize(...)}——补丁 hash 漏授权
     * 的后果是客户端分片请求被拒成 {@code NOT_AUTHORIZED}，然后静默退化成全量下载：
     * 功能还在，只是永远不生效，而且没有任何报错。</p>
     */
    static boolean instanceAllows(com.habitrain.core.api.scene.model.SceneInstance instance,
                                  UUID playerId, String dimension, String targetHash) {
        return instance != null && instance.isVisibleTo(playerId) && instance.belongsTo(dimension)
                && instance.assetHash().equalsIgnoreCase(targetHash);
    }

    private boolean isCurrentlyVisible(ServerPlayer player, TransferAuthorization authorization) {
        if (authorization.instanceGrants() == null) return true;
        SceneInstanceService instances = SceneInstanceService.getInstance();
        if (!instances.isGlobalEnabled()) return false;
        String dimension = player.serverLevel().dimension().location().toString();
        for (InstanceGrant grant : authorization.instanceGrants()) {
            if (instanceAllows(instances.get(grant.instanceId()).orElse(null), player.getUUID(), dimension, grant.targetHash())) return true;
        }
        return false;
    }

    public void authorizeInstanceAsset(ServerPlayer player, com.habitrain.core.api.scene.model.SceneInstance instance,
                                       SceneAssetDescriptor descriptor) {
        if (player == null || descriptor == null || !descriptor.isValid()
                || !instanceAllows(instance, player.getUUID(), player.serverLevel().dimension().location().toString(), descriptor.sha256())) return;
        InstanceGrant grant = new InstanceGrant(instance.id(), descriptor.sha256());
        grantInstanceHash(player.getUUID(), descriptor.sha256(), descriptor.compressedSize(), null, grant);
        var delta = SceneDeltaStore.getInstance().get(instance.assetKey());
        if (delta != null && delta.isValid() && delta.matchesTarget(descriptor.sha256())) {
            File file = SceneDeltaStore.getInstance().patchFile(delta.patchSha256());
            if (file != null)
                grantInstanceHash(player.getUUID(), delta.patchSha256(), delta.patchBytes(), file, grant);
        }
    }

    private void grantInstanceHash(UUID playerId, String hash, long bytes, File deltaFile, InstanceGrant grant) {
        manifestAllowlist.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>()).compute(hash.toLowerCase(Locale.ROOT), (key, prior) -> {
            if (prior != null && prior.instanceGrants() == null) return prior;
            java.util.Set<InstanceGrant> grants = new java.util.HashSet<>();
            if (prior != null) grants.addAll(prior.instanceGrants());
            grants.removeIf(old -> SceneInstanceService.getInstance().get(old.instanceId()).isEmpty());
            grants.add(grant);
            return new TransferAuthorization(bytes, null, "", deltaFile, java.util.Set.copyOf(grants));
        });
    }

    public void authorizeAsset(ServerPlayer player, String mapKey, SceneAssetDescriptor descriptor) {
        if (player == null || descriptor == null || !descriptor.isValid()) return;
        authorize(player.getUUID(), descriptor.sha256(), descriptor.compressedSize());
        SceneDeltaStore.DeltaInfo info = SceneDeltaStore.getInstance().get(mapKey);
        if (info != null && info.isValid() && info.matchesTarget(descriptor.sha256())) {
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
        if (playerId == null || !SceneAssetDescriptor.isValidSha256(patchSha256)
                || patchBytes <= 0 || patchBytes > MAX_FILE_SIZE) return;
        File patchFile = SceneDeltaStore.getInstance().patchFile(patchSha256);
        if (patchFile == null) {
            LOGGER.warn("拒绝授权不存在的增量补丁: {}", patchSha256);
            return;
        }
        manifestAllowlist.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(patchSha256.toLowerCase(Locale.ROOT),
                         new TransferAuthorization(patchBytes, null, "", patchFile));
    }

    /**
     * 处理增量探测：只查内存索引与授权表，不做磁盘读，因此可以安全地在服务端线程执行。
     */
    public void handleDeltaProbe(ServerPlayer player, String mapKey, String targetSha256) {
        if (player == null) return;
        String target = targetSha256 == null ? "" : targetSha256.trim().toLowerCase(Locale.ROOT);
        if (player.connection == null || player.hasDisconnected()) return;
        if (!SceneAssetDescriptor.isValidSha256(target)) {
            sendDeltaOffer(player, SceneAssetDeltaOfferS2C.none(target));
            return;
        }
        // 仅允许已被授权下载目标资产的玩家探测增量补丁
        Map<String, TransferAuthorization> playerAllowlist = manifestAllowlist.get(player.getUUID());
        if (playerAllowlist == null || !playerAllowlist.containsKey(target)
                || !isCurrentlyVisible(player, playerAllowlist.get(target))) {
            sendDeltaOffer(player, SceneAssetDeltaOfferS2C.none(target));
            return;
        }
        SceneAssetDescriptor descriptor = SceneAssetStore.getInstance().getDescriptor(mapKey);
        SceneDeltaStore.DeltaInfo info = SceneDeltaStore.getInstance().get(mapKey);
        // 只对"当前这一版"且 targetSha256 吻合的资产提供补丁
        if (descriptor == null || !descriptor.isValid()
                || !descriptor.sha256().equalsIgnoreCase(target)
                || info == null || !info.isValid()
                || !info.matchesTarget(descriptor.sha256())) {
            sendDeltaOffer(player, SceneAssetDeltaOfferS2C.none(target));
            return;
        }
        TransferAuthorization targetGrant = playerAllowlist.get(target);
        if (targetGrant.instanceGrants() == null) {
            authorizeDelta(player.getUUID(), info.patchSha256(), info.patchBytes());
        } else {
            for (InstanceGrant grant : targetGrant.instanceGrants()) {
                File file = SceneDeltaStore.getInstance().patchFile(info.patchSha256());
                if (file != null)
                    grantInstanceHash(player.getUUID(), info.patchSha256(), info.patchBytes(), file, grant);
            }
        }
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
        if (playerId == null || stagingId == null || !SceneAssetDescriptor.isValidSha256(sha256)
                || compressedSize <= 0 || compressedSize > MAX_FILE_SIZE) return;
        File stagingFile = SceneAssetStore.getInstance().getStagingAssetFile(stagingId);
        if (stagingFile == null || stagingFile.length() != compressedSize) return;
        manifestAllowlist.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(sha256.toLowerCase(Locale.ROOT),
                        new TransferAuthorization(compressedSize, stagingFile, stagingId, null));
    }

    public void revokeStaging(String stagingId) {
        if (stagingId == null || stagingId.isBlank()) return;
        for (Map<String, TransferAuthorization> authorizations : manifestAllowlist.values()) {
            for (Map.Entry<String, TransferAuthorization> entry : Map.copyOf(authorizations).entrySet()) {
                TransferAuthorization authorization = entry.getValue();
                if (!stagingId.equals(authorization.stagingId())) continue;
                boolean published = SceneAssetStore.getInstance().getAllDescriptors().values().stream()
                        .anyMatch(value -> value.sha256().equalsIgnoreCase(entry.getKey())
                                && value.compressedSize() == authorization.compressedSize());
                if (published) {
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
                .get(sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT));
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
     * 占用一个在途分片名额，返回当前玩家的会话代数（{@code < 0} 表示已满）。
     */
    private long tryAcquirePlayerSlot(UUID playerId) {
        synchronized (inFlightLock) {
            if (!accepting) return -1L;
            int current = inFlightCount.getOrDefault(playerId, 0);
            if (current >= MAX_IN_FLIGHT_CHUNKS_PER_PLAYER) return -1L;
            inFlightCount.put(playerId, current + 1);
            return playerEpochs.computeIfAbsent(playerId, ignored -> ++nextPlayerEpoch);
        }
    }

    /** 释放一个名额；仅当代数与申请时一致时才扣减，防止闪断重连后旧在途任务误扣新会话名额。 */
    private void releasePlayerSlot(UUID playerId, long expectedEpoch) {
        if (playerId == null) return;
        synchronized (inFlightLock) {
            long currentEpoch = playerEpochs.getOrDefault(playerId, -1L);
            if (currentEpoch != expectedEpoch) {
                return;
            }
            int current = inFlightCount.getOrDefault(playerId, 0);
            if (current <= 1) {
                inFlightCount.remove(playerId);
            } else {
                inFlightCount.put(playerId, current - 1);
            }
        }
    }

    private boolean isCurrentSlot(UUID playerId, long epoch) {
        synchronized (inFlightLock) {
            return accepting && java.util.Objects.equals(playerEpochs.get(playerId), epoch);
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
        if (!accepting) throw new RejectedExecutionException("Transfer service stopped");
        if (ioExecutor == null || ioExecutor.isShutdown()) ioExecutor = createExecutor();
        return ioExecutor;
    }

    private synchronized ScheduledExecutorService ensurePacer(long expectedServiceEpoch) {
        if (!accepting || this.serviceEpoch != expectedServiceEpoch) return null;
        if (pacer == null || pacer.isShutdown()) pacer = createPacer();
        return pacer;
    }

    public void onPlayerDisconnect(UUID playerId) {
        if (playerId != null) {
            lastServed.remove(playerId);
            lastWarnNanos.remove(playerId);
            manifestAllowlist.remove(playerId);
            synchronized (inFlightLock) {
                inFlightCount.remove(playerId);
                playerEpochs.remove(playerId);
            }
            governor.forget(playerId);
            bandwidthPhases.remove(playerId);
            SceneInstanceService.getInstance().onPlayerDisconnect(playerId);
        }
    }

    public synchronized void start() {
        if (!accepting) { serviceEpoch++; accepting = true; }
    }

    public synchronized void shutdown() {
        accepting = false;
        serviceEpoch++;
        lastServed.clear();
        lastWarnNanos.clear();
        manifestAllowlist.clear();
        synchronized (inFlightLock) {
            inFlightCount.clear();
            playerEpochs.clear();
        }
        governor.reset();
        bandwidthPhases.clear();
        if (pacer != null) pacer.shutdownNow();
        if (ioExecutor != null) ioExecutor.shutdownNow();
    }

    /** Wait outside service locks so cancelled workers can finish their finally blocks. */
    public void awaitStopped() {
        awaitExecutor(pacer);
        awaitExecutor(ioExecutor);
    }
    private static void awaitExecutor(java.util.concurrent.ExecutorService executor) {
        if (executor == null || !executor.isShutdown()) return;
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                LOGGER.warn("Scene worker is still cancelling; lifecycle generations reject its results");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
