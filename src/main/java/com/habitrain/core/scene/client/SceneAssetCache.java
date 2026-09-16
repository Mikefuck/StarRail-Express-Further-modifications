package com.habitrain.core.scene.client;

import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.config.SceneClientPerformanceRules;
import com.habitrain.core.scene.asset.SceneAssetCodec;
import com.habitrain.core.scene.asset.SceneAssetDelta;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.asset.SceneRegistryFingerprint;
import com.habitrain.core.scene.network.SceneAssetChunkRequestC2S;
import com.habitrain.core.scene.network.SceneAssetChunkS2C;
import com.habitrain.core.scene.network.SceneAssetChunkStatusS2C;
import com.habitrain.core.scene.network.SceneAssetDeltaOfferS2C;
import com.habitrain.core.scene.network.SceneAssetDeltaProbeC2S;
import com.habitrain.core.scene.network.SceneProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 客户端场景资产缓存与下载管理器（管理 .minecraft/habitrain_scene_cache/ 与内存 AssetData）。
 *
 * <p>状态机、调度与落盘分别在 {@link AssetTransferRegistry}（每个 hash 一条生命周期链）、
 * {@link SceneAssetDownloadQueue}（全连接单在途、超时、有界重试）与
 * {@link SceneAssetWritePipeline}（分片校验与写盘在独立写线程上）里；本类负责文件布局、
 * 续传决策、淘汰策略与 Minecraft 侧接线，因此这些逻辑都能在单测里绕开。</p>
 *
 * <p><b>加锁规则</b>：本类不使用实例级 {@code synchronized}。文件 IO 一律在 {@link #IO_EXECUTOR}
 * 或写线程上执行；跨线程回调必须经 {@code Minecraft.getInstance().execute} 跳回客户端线程；
 * 任何可能重入 registry 的调用都不在持锁状态下发生。</p>
 */
public final class SceneAssetCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneAssetCache.class.getSimpleName());
    private static final String DIR_NAME = "habitrain_scene_cache";
    private static final String CACHED_SUFFIX = ".hscene";
    /** 增量补丁的落盘后缀。与资产同目录、同配额、同 LRU，但认不出它就等于没人清理。 */
    private static final String PATCH_SUFFIX = ".hpatch";
    public static final long MAX_SINGLE_ASSET_BYTES = 64L * 1024L * 1024L;

    /** 整文件校验/解码：会被移动、读取大文件，因此与写盘分开，避免互相堵住。 */
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HabiTrain-SceneCache-IO");
        t.setDaemon(true);
        return t;
    });

    /**
     * 分片写盘线程。必须与 {@link #IO_EXECUTOR} 分开：一次 32 MiB 的整文件校验会把
     * 小写入堵上几秒，那期间下载会因为请求超时而反复重发。
     */
    private static final ExecutorService WRITE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HabiTrain-SceneCache-Writer");
        t.setDaemon(true);
        return t;
    });

    private static final SceneAssetCache INSTANCE = new SceneAssetCache();

    public static SceneAssetCache getInstance() {
        return INSTANCE;
    }

    private final File cacheDir;
    /** 整合包预置目录：命中即零网络入库。见 {@link SceneSeedIndex}。 */
    private final SceneSeedIndex seedIndex;
    private final Map<String, SceneAssetCodec.AssetData> memoryCache = new ConcurrentHashMap<>();
    /**
     * 服务端对增量探测的回答，键是**目标资产**的 hash（值里的 NO_DELTA 也要记：
     * 记下来才不会再问第二遍）。
     */
    private final Map<String, SceneAssetDeltaOfferS2C> deltaOffers = new ConcurrentHashMap<>();
    /**
     * 本会话内已放弃增量的目标 hash。
     *
     * <p>补丁失败后回退到整文件下载时写进来：没有它，回退会重新走到同一个探测结果上，
     * 变成"补丁失败 → 回退 → 又试补丁"的死循环。</p>
     */
    private final Set<String> deltaDisabled = ConcurrentHashMap.newKeySet();
    /** 正在被应用补丁的 base 资产：淘汰必须绕开它，否则补丁立刻变成废纸。 */
    private final Set<String> pinnedHashes = ConcurrentHashMap.newKeySet();
    private final AssetTransferRegistry registry = new AssetTransferRegistry();
    private final SceneDeltaProbeGate deltaProbes =
            new SceneDeltaProbeGate(System::nanoTime, 1_000_000_000L);
    private final ScenePartialStore partialStore;
    private final SceneCacheIndex cacheIndex;
    private final SceneAssetWritePipeline writePipeline;
    private final SceneAssetDownloadQueue queue;

    /**
     * 超时与重试次数在本地缓存成 volatile 字段而不是每次回读配置：
     * 配置读取是 synchronized 且有首次文件 IO，而队列会在持锁状态下索取这两个值。
     */
    private volatile int requestTimeoutMs = SceneClientPerformanceRules.DEFAULT_REQUEST_TIMEOUT_MS;
    private volatile int maxChunkRetries = SceneClientPerformanceRules.DEFAULT_MAX_CHUNK_RETRIES;
    private volatile int transferWindowChunks = SceneClientPerformanceRules.DEFAULT_TRANSFER_WINDOW_CHUNKS;
    /** 最近一次落盘确认的字节数，仅用于诊断（请求推进已不再依赖它）。 */
    private volatile long lastCommittedBytes;
    /** 已解码资产的堆占用记账与 LRU 选取；实际移除由本类执行。 */
    private final SceneMemoryBudget memoryBudget = new SceneMemoryBudget(
            SceneClientPerformanceRules.quotaBytes(SceneClientPerformanceRules.DEFAULT_MEMORY_CACHE_QUOTA_MIB));
    private volatile long diskCacheQuotaBytes =
            SceneClientPerformanceRules.quotaBytes(SceneClientPerformanceRules.DEFAULT_DISK_CACHE_QUOTA_MIB);
    private volatile boolean partialResumeEnabled = SceneClientPerformanceRules.DEFAULT_PARTIAL_RESUME_ENABLED;
    /** 增量协商开关。关掉之后行为与推出该功能之前完全一致。 */
    private volatile boolean deltaNegotiationEnabled = true;
    /** "缓存目录里有没有东西"的会话级记忆；null = 还没看过。 */
    private volatile Boolean cacheDirHasFiles;
    private volatile long partialCacheQuotaBytes =
            SceneClientPerformanceRules.quotaBytes(SceneClientPerformanceRules.DEFAULT_PARTIAL_CACHE_QUOTA_MIB);
    private volatile long partialExpiryMillis =
            SceneClientPerformanceRules.expiryMillis(SceneClientPerformanceRules.DEFAULT_PARTIAL_EXPIRY_HOURS);

    private SceneAssetCache() {
        File gameDir = Minecraft.getInstance().gameDirectory;
        if (gameDir == null) {
            gameDir = new File(".");
        }
        this.cacheDir = new File(gameDir, DIR_NAME);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            LOGGER.error("无法创建场景缓存目录 {}：磁盘缓存与断点续传将无法工作", cacheDir);
        }
        this.seedIndex = new SceneSeedIndex(new File(gameDir, "habitrain_scene_seed"));
        applyClientConfig();
        this.partialStore = new ScenePartialStore(cacheDir.toPath());
        this.cacheIndex = new SceneCacheIndex(cacheDir.toPath().resolve(SceneCacheIndex.FILE_NAME),
                System::currentTimeMillis);
        this.writePipeline = new SceneAssetWritePipeline(
                WRITE_EXECUTOR,
                RandomAccessPartFile::new,
                partialStore,
                registry,
                new PipelineEvents(),
                System::currentTimeMillis);
        this.queue = new SceneAssetDownloadQueue(
                System::nanoTime,
                new SceneAssetDownloadQueue.Sender() {
                    @Override
                    public boolean canSend() {
                        return ClientPlayNetworking.canSend(SceneAssetChunkRequestC2S.TYPE);
                    }

                    @Override
                    public void send(SceneAssetChunkRequestC2S request) {
                        ClientPlayNetworking.send(request);
                    }
                },
                this::onTransferExhausted,
                () -> requestTimeoutMs,
                () -> maxChunkRetries,
                () -> transferWindowChunks,
                SceneClientPerformanceRules.RETRY_BACKOFF_BASE_MS,
                SceneClientPerformanceRules.RETRY_BACKOFF_MAX_MS);
        // 构造期只做 mkdirs：扫描与清理必须离开客户端线程。
        IO_EXECUTOR.execute(this::runStartupMaintenance);
    }

    /** 重新读取客户端本地配置；配置中心修改这些值后调用。 */
    public void applyClientConfig() {
        requestTimeoutMs = ClientVisualPreferences.getRequestTimeoutMs();
        maxChunkRetries = ClientVisualPreferences.getMaxChunkRetries();
        transferWindowChunks = ClientVisualPreferences.getTransferWindowChunks();
        memoryBudget.setQuotaBytes(SceneClientPerformanceRules.quotaBytes(
                ClientVisualPreferences.getMemoryCacheQuotaMiB()));
        SceneBuildScheduler.getInstance().applyBudgetMsPerFrame(
                ClientVisualPreferences.getMeshBuildBudgetMsPerFrame());
        diskCacheQuotaBytes = SceneClientPerformanceRules.quotaBytes(
                ClientVisualPreferences.getDiskCacheQuotaMiB());
        partialResumeEnabled = ClientVisualPreferences.isPartialResumeEnabled();
        partialCacheQuotaBytes = SceneClientPerformanceRules.quotaBytes(
                ClientVisualPreferences.getPartialCacheQuotaMiB());
        partialExpiryMillis = SceneClientPerformanceRules.expiryMillis(
                ClientVisualPreferences.getPartialExpiryHours());
    }

    private void runStartupMaintenance() {
        cacheIndex.loadIfNeeded();
        // 清理旧版 .download.* 崩溃残留、无元数据的孤儿、过期项与超配额项。
        partialStore.sweep(System.currentTimeMillis(), partialCacheQuotaBytes, partialExpiryMillis);
    }

    /**
     * 获取或异步请求下载资产。若命中缓存则直接回调；若未命中则发起分片请求并在下载完成后回调。
     */
    public void getOrFetchAsset(SceneAssetDescriptor descriptor, Consumer<SceneAssetCodec.AssetData> onComplete) {
        getOrFetchAsset(descriptor, SceneAssetDownloadQueue.Priority.PREFETCH, onComplete);
    }

    /** 带优先级提示的版本：运行中的主背景应高于预取，避免被预取挤在后面排队。 */
    public void getOrFetchAsset(SceneAssetDescriptor descriptor,
                                 SceneAssetDownloadQueue.Priority priority,
                                 Consumer<SceneAssetCodec.AssetData> onComplete) {
        if (descriptor == null || !descriptor.isValid()) {
            invokeQuietly(onComplete, null);
            return;
        }

        String sha256 = descriptor.sha256();
        String localFingerprint = SceneRegistryFingerprint.calculate();
        if (descriptor.fingerprint() != null && !descriptor.fingerprint().isBlank()
                && !descriptor.fingerprint().equals(localFingerprint)) {
            LOGGER.error("场景资产方块注册表指纹不匹配，禁止加载: hash={}", descriptor.shortHash());
            invokeQuietly(onComplete, null);
            return;
        }
        if (descriptor.compressedSize() <= 0 || descriptor.compressedSize() > MAX_SINGLE_ASSET_BYTES) {
            LOGGER.warn("拒绝超出客户端单资产上限的场景资产: hash={}, bytes={}",
                    descriptor.shortHash(), descriptor.compressedSize());
            invokeQuietly(onComplete, null);
            return;
        }

        // 1. 内存缓存命中
        SceneAssetCodec.AssetData memoryData = memoryCache.get(sha256);
        if (memoryData != null) {
            // 内存命中也要记热度：否则反复使用的资产会因为"很久没读盘"被当成旧文件淘汰。
            cacheIndex.touch(sha256, System.currentTimeMillis());
            memoryBudget.touch(sha256);
            invokeQuietly(onComplete, memoryData);
            return;
        }

        // 2. 增量：服务端确认过"当前这版有一枚从 base 到它的补丁"，且本地确实持有那个 base。
        //    这条判定要碰文件系统，所以整体挪到 IO 线程——拿不准就原样走下面的全量链路。
        boolean diskCacheExists = assetFile(sha256).isFile();
        long generation = registry.generation();
        if (!diskCacheExists && deltaProbes.await(sha256, () -> {
            if (!registry.isCurrent(generation)) invokeQuietly(onComplete, null);
            else getOrFetchAsset(descriptor, priority, onComplete);
        })) return;
        SceneAssetDeltaOfferS2C offer = deltaOffers.get(sha256);
        if (!diskCacheExists && registry.find(sha256) == null && deltaNegotiationEnabled
                && offer != null && offer.hasDelta() && !deltaDisabled.contains(sha256)) {
            IO_EXECUTOR.execute(() -> {
                boolean baseExists = assetFile(offer.baseSha256()).isFile();
                onClientThread(() -> {
                    if (!registry.isCurrent(generation)) {
                        invokeQuietly(onComplete, null);
                        return;
                    }
                    if (baseExists) beginDeltaOrFallback(descriptor, offer, priority, onComplete);
                    else {
                        deltaDisabled.add(sha256);
                        fullDownload(descriptor, priority, onComplete);
                    }
                });
            });
            return;
        }

        // 3. 交给状态机决定：加入既有链路、从磁盘加载、或新建下载
        AssetTransferRegistry.Acquire acquire = registry.acquire(
                sha256, descriptor.compressedSize(), diskCacheExists, onComplete);

        switch (acquire.action()) {
            case REJECTED -> {
                // acquire 已经用 null 结束了等待者。
            }
            case JOINED -> {
                // 已有链路在推进。仍要提升优先级：一个正在预取的资产可能刚刚变成当前主背景，
                // 不提升就会被排在后面的预取挡住。
                queue.hintPriority(sha256, priority);
            }
            case START_DISK_LOAD -> IO_EXECUTOR.execute(() -> loadDiskCache(acquire.entry()));
            case START_DOWNLOAD -> startDownload(acquire.entry(), priority);
        }
    }

    // ---- 增量补丁：协商、下载、应用与回退 ----

    /**
     * 收到清单/预取时调用：服务端支持增量协商、本地又还没有这一版时，问一句有没有补丁。
     *
     * <p>探测是纯附加的：失败、没答、答"没有"都只是照常走整文件下载。旧服务端根本认不出这个包，
     * 所以先用 {@link SceneProtocol#DELTA_NEGOTIATION_VERSION} 把版本闸住。</p>
     */
    public void considerDeltaProbe(String mapKey, SceneAssetDescriptor descriptor, int serverProtocolVersion) {
        if (!deltaNegotiationEnabled) return;
        if (descriptor == null || !descriptor.isValid() || mapKey == null || mapKey.isBlank()) return;
        if (serverProtocolVersion < SceneProtocol.DELTA_NEGOTIATION_VERSION) return;
        String target = descriptor.sha256();
        if (memoryCache.containsKey(target) || assetFile(target).isFile()) return;
        if (deltaOffers.containsKey(target) || deltaDisabled.contains(target)) return;
        // 全新安装手里不可能有 base，问了也只是一次白跑往返。
        if (!hasAnyCachedAsset()) return;
        if (!deltaProbes.begin(target)) return;
        try {
            ClientPlayNetworking.send(new SceneAssetDeltaProbeC2S(mapKey, target));
        } catch (Exception e) {
            deltaProbes.complete(target);
            LOGGER.debug("增量探测发送失败: target={}", shortHash(target), e);
        }
    }

    /** 服务端对探测的回答。{@code NO_DELTA} 也要记下来，否则每次清单都要重问一遍。 */
    public void acceptDeltaOffer(SceneAssetDeltaOfferS2C offer) {
        if (offer == null || offer.targetSha256().isBlank()) return;
        deltaOffers.put(offer.targetSha256(), offer);
        deltaProbes.complete(offer.targetSha256());
        if (offer.hasDelta()) {
            LOGGER.info("服务端提供场景增量补丁: target={}, base={}, 补丁 {} 字节",
                    shortHash(offer.targetSha256()), shortHash(offer.baseSha256()), offer.patchBytes());
        }
    }

    /** 测试与配置用：关掉增量协商后，行为与本功能上线前完全一致。 */
    public void setDeltaNegotiationEnabled(boolean enabled) {
        this.deltaNegotiationEnabled = enabled;
    }

    private boolean hasAnyCachedAsset() {
        // 每次清单都去列目录没必要，也不是零成本；一次会话里这个答案不会从"空"变成"有"
        // 到需要重新判断的程度（真有下载成功后也有 memoryCache 兜着）。
        Boolean cached = cacheDirHasFiles;
        if (cached != null) return cached;
        File[] files = listCacheFiles();
        boolean any = files != null && files.length > 0;
        cacheDirHasFiles = any;
        return any;
    }

    /** 缓存目录里的资产**与补丁**：补丁不参与枚举就等于永远不清理、也不计配额。 */
    private File[] listCacheFiles() {
        return cacheDir.listFiles((dir, name) -> name.endsWith(CACHED_SUFFIX) || name.endsWith(PATCH_SUFFIX));
    }

    private File assetFile(String sha256) {
        return new File(cacheDir, sha256 + CACHED_SUFFIX);
    }

    private File patchFile(String sha256) {
        return new File(cacheDir, sha256 + PATCH_SUFFIX);
    }

    /** 回到客户端线程后登记补丁链路；底稿存在性已在 IO 线程检查。 */
    private void beginDeltaOrFallback(SceneAssetDescriptor descriptor, SceneAssetDeltaOfferS2C offer,
                                      SceneAssetDownloadQueue.Priority priority,
                                      Consumer<SceneAssetCodec.AssetData> onComplete) {
        String target = descriptor.sha256();
        SceneAssetCodec.AssetData memory = memoryCache.get(target);
        if (memory != null) {
            invokeQuietly(onComplete, memory);
            return;
        }
        if (deltaDisabled.contains(target) || registry.find(target) != null
                || offer.patchBytes() > MAX_SINGLE_ASSET_BYTES) {
            // 另一条回调可能已经回退全量，不能重新启动补丁。
            deltaDisabled.add(target);
            fullDownload(descriptor, priority, onComplete);
            return;
        }
        AssetTransferRegistry.PatchTarget patchTarget =
                new AssetTransferRegistry.PatchTarget(offer.baseSha256(), descriptor);
        File patch = patchFile(offer.patchSha256());
        AssetTransferRegistry.Acquire acquire = registry.acquire(
                offer.patchSha256(), offer.patchBytes(), patch.isFile(), patchTarget, onComplete);
        switch (acquire.action()) {
            case REJECTED -> { }
            case JOINED -> queue.hintPriority(offer.patchSha256(), priority);
            case START_DISK_LOAD -> IO_EXECUTOR.execute(() -> loadPatchFromDisk(acquire.entry()));
            case START_DOWNLOAD -> startDownload(acquire.entry(), priority);
        }
    }

    /** 常规整文件链路的入口，被增量路径复用（探测无果、补丁失败回退时）。 */
    private void fullDownload(SceneAssetDescriptor descriptor, SceneAssetDownloadQueue.Priority priority,
                              Consumer<SceneAssetCodec.AssetData> onComplete) {
        String sha256 = descriptor.sha256();
        AssetTransferRegistry.Acquire acquire = registry.acquire(sha256, descriptor.compressedSize(),
                assetFile(sha256).isFile(), onComplete);
        switch (acquire.action()) {
            case REJECTED -> { }
            case JOINED -> queue.hintPriority(sha256, priority);
            case START_DISK_LOAD -> IO_EXECUTOR.execute(() -> loadDiskCache(acquire.entry()));
            case START_DOWNLOAD -> startDownload(acquire.entry(), priority);
        }
    }

    private void loadPatchFromDisk(AssetTransferRegistry.AssetEntry entry) {
        if (entry == null || entry.patchTarget() == null) return;
        File patch = patchFile(entry.sha256());
        if (!registry.transition(entry, AssetTransferRegistry.State.DECODING)) return;
        if (!applyPatch(entry, patch.toPath(), false)) {
            fallbackToFullAsset(entry, "磁盘上的补丁不可用");
        }
    }

    /**
     * 解码补丁、解码 base、合并、按目标 hash 交付。
     *
     * @param moveIntoCache 下载路径为 true：只有应用成功才把补丁搬进缓存（失败留在盘上会变成
     *                      "每次进图都重试同一枚坏补丁"）；磁盘复用路径为 false。
     */
    private boolean applyPatch(AssetTransferRegistry.AssetEntry entry, Path patchPath, boolean moveIntoCache) {
        AssetTransferRegistry.PatchTarget patchTarget = entry.patchTarget();
        if (patchTarget == null) return false;
        try {
            long length = Files.size(patchPath);
            if (length != entry.totalSize() || length > MAX_SINGLE_ASSET_BYTES) {
                throw new IOException("Scene patch size mismatch: " + length + " != " + entry.totalSize());
            }
            byte[] bytes = Files.readAllBytes(patchPath);
            if (!SceneAssetCodec.calculateSha256(bytes).equalsIgnoreCase(entry.sha256())) {
                throw new IOException("Scene patch SHA-256 mismatch");
            }
            SceneAssetDelta.DeltaData delta = SceneAssetDelta.decode(bytes);
            if (!delta.targetSha256().equalsIgnoreCase(patchTarget.target().sha256())) {
                throw new IOException("Scene patch targets another asset");
            }
            if (!delta.baseSha256().equalsIgnoreCase(patchTarget.baseSha256())) {
                throw new IOException("Scene patch is based on another asset");
            }

            pinnedHashes.add(patchTarget.baseSha256());
            SceneAssetCodec.AssetData base;
            try {
                base = loadBaseAsset(patchTarget.baseSha256());
            } finally {
                pinnedHashes.remove(patchTarget.baseSha256());
            }
            if (base == null) {
                throw new IOException("Scene patch base is missing or corrupted");
            }

            SceneAssetCodec.AssetData merged = SceneAssetDelta.apply(base, delta);
            if (merged.sections.size() != patchTarget.target().sectionCount()) {
                throw new IOException("Scene patch result section count mismatch: "
                        + merged.sections.size() + " != " + patchTarget.target().sectionCount());
            }

            // Persist and verify the full target, both for reconnects and the next delta base.
            byte[] reconstructed = SceneAssetDelta.encodeVerifiedTarget(merged, patchTarget.target());
            Path targetFile = assetFile(patchTarget.target().sha256()).toPath();
            Path temporary = targetFile.resolveSibling(targetFile.getFileName() + ".rebuild");
            try {
                Files.write(temporary, reconstructed);
                Files.move(temporary, targetFile, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            if (moveIntoCache) {
                Files.move(patchPath, patchFile(entry.sha256()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            long now = System.currentTimeMillis();
            cacheIndex.touch(entry.sha256(), now);
            // 底是本地的整份资产：给它记一次热度，别让它在下一次淘汰里先走。
            cacheIndex.touch(patchTarget.baseSha256(), now);
            enforceCacheLimit(patchTarget.target().sha256());

            SceneAssetCodec.AssetData loaded = merged;
            onClientThread(() -> {
                if (!isLive(entry)) return;
                queue.onDownloadComplete(entry);
                putMemory(patchTarget.target().sha256(), loaded);
                List<Consumer<SceneAssetCodec.AssetData>> waiters = registry.completeSuccess(entry);
                AssetTransferRegistry.fireWaiters(waiters, loaded);
            });
            LOGGER.info("场景资产走增量下载: target={}, base={}, 补丁 {} 字节",
                    shortHash(patchTarget.target().sha256()), shortHash(patchTarget.baseSha256()),
                    entry.totalSize());
            return true;
        } catch (Exception e) {
            LOGGER.warn("应用场景增量补丁失败: patch={}, 原因={}", shortHash(entry.sha256()), e.toString());
            return false;
        }
    }

    private SceneAssetCodec.AssetData loadBaseAsset(String baseSha256) {
        SceneAssetCodec.AssetData memory = memoryCache.get(baseSha256);
        if (memory != null) return memory;
        File file = assetFile(baseSha256);
        if (!file.isFile()) return null;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (!SceneAssetCodec.calculateSha256(bytes).equalsIgnoreCase(baseSha256)) {
                LOGGER.warn("增量补丁的底校验失败，已删除: {}", shortHash(baseSha256));
                file.delete();
                return null;
            }
            return SceneAssetCodec.decode(bytes);
        } catch (Exception e) {
            LOGGER.warn("读取增量补丁的底失败: " + shortHash(baseSha256), e);
            return null;
        }
    }

    /**
     * 补丁不可用时把等待者接到目标的整文件下载上。
     *
     * <p>关键点是**不能**给它们发 {@code null}：调用方等着资产去编译网格，收到 null 就是
     * 这张图没有背景。同时把该目标记进 {@code deltaDisabled}，否则回退后的整文件链路会再次
     * 命中同一个补丁、再失败一次，转成死循环。</p>
     */
    private void fallbackToFullAsset(AssetTransferRegistry.AssetEntry entry, String reason) {
        onClientThread(() -> fallbackToFullAssetOnClient(entry, reason));
    }

    private void fallbackToFullAssetOnClient(AssetTransferRegistry.AssetEntry entry, String reason) {
        if (!isLive(entry)) return;
        AssetTransferRegistry.PatchTarget patchTarget = entry.patchTarget();
        if (patchTarget == null) return;
        SceneAssetDescriptor target = patchTarget.target();
        deltaDisabled.add(target.sha256());
        LOGGER.warn("增量补丁回退整文件下载: target={}, 原因={}", shortHash(target.sha256()), reason);

        queue.cancel(entry.sha256());
        writePipeline.abandon(entry);
        List<Consumer<SceneAssetCodec.AssetData>> waiters = registry.detachWaiters(entry);
        registry.completeFailure(entry);
        for (Consumer<SceneAssetCodec.AssetData> waiter : waiters) {
            fullDownload(target, SceneAssetDownloadQueue.Priority.PREFETCH, waiter);
        }
    }

    // ---- 下载启动与续传决策 ----

    private void startDownload(AssetTransferRegistry.AssetEntry entry,
                               SceneAssetDownloadQueue.Priority priority) {
        if (entry == null) return;
        // 续传要读元数据、重读并校验前缀（最大 64 MiB），绝不能放在客户端线程上。
        IO_EXECUTOR.execute(() -> prepareDownload(entry, priority));
    }

    /**
     * 尝试从整合包预置目录里补齐这份资产。
     *
     * <p><b>复制而不是移动</b>：预置目录是随整合包分发的种子文件，第一个用它的客户端无权把它
     * 搬走。文件名只是给人看的，能不能用一律以内容 SHA-256 为准。</p>
     *
     * @return true 表示已经接管（成功入库或已失败并回退），调用方不必再走下载
     */
    private boolean trySeedImport(AssetTransferRegistry.AssetEntry entry) {
        File seed = seedIndex.find(entry.sha256());
        if (seed == null) {
            seedIndex.ensureScannedAsync();
            return false;
        }
        try {
            if (seed.length() != entry.totalSize() || seed.length() > MAX_SINGLE_ASSET_BYTES) {
                seedIndex.reject(entry.sha256());
                return false;
            }
            byte[] bytes = Files.readAllBytes(seed.toPath());
            if (!SceneAssetCodec.calculateSha256(bytes).equalsIgnoreCase(entry.sha256())) {
                LOGGER.warn("预置场景资产内容与哈希不符，已跳过: {}", seed.getName());
                seedIndex.reject(entry.sha256());
                return false;
            }
            Path target = assetFile(entry.sha256()).toPath();
            Files.createDirectories(target.getParent());
            Files.copy(seed.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            long now = System.currentTimeMillis();
            target.toFile().setLastModified(now);
            cacheIndex.touch(entry.sha256(), now);
            LOGGER.info("场景资产命中整合包预置目录，无需联网: hash={}", shortHash(entry.sha256()));
            onClientThread(() -> {
                if (!isLive(entry)) return;
                if (!registry.transition(entry, AssetTransferRegistry.State.VERIFYING)) return;
                IO_EXECUTOR.execute(() -> loadDiskCache(entry));
            });
            return true;
        } catch (Exception e) {
            LOGGER.warn("从预置目录导入场景资产失败，改为联网下载: " + shortHash(entry.sha256()), e);
            return false;
        }
    }

    /** 可否续传的判定结果。{@code seed} 为 null 表示"校验通过但无法继续写检查点"。 */
    private record PrefixCheck(boolean ok, MessageDigest seed) {
        static PrefixCheck fail() { return new PrefixCheck(false, null); }
        static PrefixCheck pass(MessageDigest seed) { return new PrefixCheck(true, seed); }
    }

    private void prepareDownload(AssetTransferRegistry.AssetEntry entry,
                                 SceneAssetDownloadQueue.Priority priority) {
        if (!isLive(entry)) return;
        if (!partialResumeEnabled) partialStore.discard(entry.sha256());
        // 预置目录优先于网络：整合包已经把资产随包发下去了，再从游戏服务器下一遍是纯浪费。
        if (trySeedImport(entry)) {
            return;
        }
        Path dataFile = partialStore.dataFile(entry.sha256());
        long startOffset = 0L;
        MessageDigest seed = null;
        boolean resumed = false;

        if (partialResumeEnabled) {
            Optional<ScenePartialStore.PendingPart> loaded = partialStore.load(entry.sha256());
            if (loaded.isPresent() && !matchesEntry(entry, loaded.get())) {
                // 清单变了（总大小/指纹不符）：这份前缀对不上当前资产，不能用来续传。
                partialStore.discard(entry.sha256());
            } else if (loaded.isPresent()) {
                ScenePartialStore.PendingPart part = loaded.get();
                if (part.resumableBytes() >= part.totalSize()) {
                    // 上次收尾时链路被 reset，文件其实已经收全：整文件 SHA-256 比前缀校验更强，
                    // 直接进入校验阶段，一个字节都不用重传。
                    LOGGER.info("场景资产已完整落盘，直接进入校验: hash={}", shortHash(entry.sha256()));
                    onClientThread(() -> {
                        if (!isLive(entry)) return;
                        if (!registry.transition(entry, AssetTransferRegistry.State.VERIFYING)) return;
                        IO_EXECUTOR.execute(() -> finishDownload(entry, dataFile));
                    });
                    return;
                }
                PrefixCheck check = verifyPrefix(part);
                if (check.ok()) {
                    resumed = true;
                    startOffset = part.resumableBytes();
                    seed = check.seed();
                    LOGGER.info("断点续传场景资产: hash={}, 从 {} / {} bytes 继续",
                            shortHash(entry.sha256()), startOffset, part.totalSize());
                } else {
                    partialStore.discard(entry.sha256());
                }
            }
        }
        if (!resumed) {
            seed = ScenePrefixHash.newSha256();
        }

        long beginOffset = startOffset;
        MessageDigest beginSeed = seed;
        String fingerprint = SceneRegistryFingerprint.calculate();
        onClientThread(() -> {
            if (!isLive(entry)) return;
            if (!registry.transition(entry, AssetTransferRegistry.State.DOWNLOADING)) return;
            writePipeline.begin(entry, dataFile, beginOffset, fingerprint, beginSeed,
                    () -> onSlotReady(entry, beginOffset, priority),
                    reason -> onClientThread(() -> failEntry(entry, reason)));
        });
    }

    /** 元数据里的总大小/指纹是否仍然对得上当前这条链路。 */
    private boolean matchesEntry(AssetTransferRegistry.AssetEntry entry, ScenePartialStore.PendingPart part) {
        if (!part.sha256().equalsIgnoreCase(entry.sha256())) return false;
        if (part.totalSize() != entry.totalSize()) return false;
        if (part.totalSize() > MAX_SINGLE_ASSET_BYTES) return false;
        String recordedFingerprint = part.fingerprint();
        if (recordedFingerprint != null && !recordedFingerprint.isBlank()
                && !recordedFingerprint.equals(SceneRegistryFingerprint.calculate())) {
            return false;
        }
        return true;
    }

    /**
     * 重读盘上前缀并比对元数据记录的 SHA-256。
     *
     * <p>这是"元数据只能少续、不能多续"的落点：崩溃可能让元数据声称了没落盘的字节，
     * 校验不过就整份作废，绝不把没验证过的字节当成数据。</p>
     */
    private PrefixCheck verifyPrefix(ScenePartialStore.PendingPart part) {
        MessageDigest digest = ScenePrefixHash.newSha256();
        if (digest == null) return PrefixCheck.fail();
        Path data = partialStore.dataFile(part.sha256());
        try (InputStream in = Files.newInputStream(data)) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = part.resumableBytes();
            while (remaining > 0L) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    LOGGER.warn("续传前缀短于记录长度: hash={}", shortHash(part.sha256()));
                    return PrefixCheck.fail();
                }
                digest.update(buffer, 0, read);
                remaining -= read;
            }
        } catch (IOException e) {
            LOGGER.warn("读取续传前缀失败: hash={}", shortHash(part.sha256()), e);
            return PrefixCheck.fail();
        }

        String actual = ScenePrefixHash.snapshotHex(digest);
        if (actual == null) {
            // 当前 JVM 不支持克隆摘要：仍可校验一次，但无法把状态交给写线程继续写检查点。
            if (!ScenePrefixHash.hex(digest.digest()).equalsIgnoreCase(part.prefixSha256())) {
                LOGGER.warn("续传前缀校验失败，重新下载: hash={}", shortHash(part.sha256()));
                return PrefixCheck.fail();
            }
            return PrefixCheck.pass(null);
        }
        if (!actual.equalsIgnoreCase(part.prefixSha256())) {
            LOGGER.warn("续传前缀校验失败，重新下载: hash={}", shortHash(part.sha256()));
            return PrefixCheck.fail();
        }
        return PrefixCheck.pass(digest);
    }

    /** 写线程回调：句柄已就绪。确认续传点必须先于队列发首片，否则首片会按 offset=0 请求。 */
    private void onSlotReady(AssetTransferRegistry.AssetEntry entry, long startOffset,
                             SceneAssetDownloadQueue.Priority priority) {
        if (startOffset > 0L) {
            registry.confirmBytes(entry, startOffset);
        }
        // 请求游标与落盘游标在续传点重合；不写这一笔的话首片会从 0 开始。
        registry.confirmAccepted(entry, startOffset);
        onClientThread(() -> {
            if (!isLive(entry)) return;
            queue.enqueue(entry, priority);
        });
    }

    // ---- 分片接收 ----

    /**
     * 接收服务端下发的分片数据。
     *
     * <p>本方法在客户端线程执行，只做 O(1) 的归属与去重判定，随后把 CRC 校验与落盘整段
     * 交给写线程——磁盘繁忙不再体现为帧时间抖动。</p>
     */
    public void acceptChunk(SceneAssetChunkS2C chunk) {
        if (chunk == null) return;
        AssetTransferRegistry.AssetEntry entry = findEntry(chunk.sha256());
        if (entry == null) {
            LOGGER.debug("收到未知资产的分片，已忽略: hash={}", shortHash(chunk.sha256()));
            return;
        }
        if (chunk.transferId() != entry.transferId()) {
            // 属于上一次传输的残包（重连/重试后），忽略而不是中止当前链路。
            LOGGER.debug("忽略陈旧传输的分片: hash={}, transferId={} != {}",
                    shortHash(chunk.sha256()), chunk.transferId(), entry.transferId());
            return;
        }
        if (chunk.totalSize() != entry.totalSize()) {
            failEntry(entry, "总大小与清单不符");
            return;
        }
        if (chunk.data().length <= 0) {
            requestRetryOrFail(entry, "空分片");
            return;
        }

        SceneAssetWritePipeline.Offer offer = writePipeline.offer(
                entry, chunk.chunkOffset(), chunk.data(), chunk.crc32());
        switch (offer) {
            case ACCEPTED -> // 接收即推进请求游标：下一片不再等写盘确认，也不需要客户端线程跳转。
                    queue.onBytesReceived(entry, chunk.chunkOffset(), chunk.data().length);
            case DUPLICATE -> {
                // 整窗回退后重发的偏移往往已经被接收过。这里同样要归还窗口槽位，
                // 否则在途记录永远挂着，整条链路只能靠超时脱困。
                LOGGER.debug("忽略重复分片: hash={}, offset={}",
                        shortHash(chunk.sha256()), chunk.chunkOffset());
                queue.onBytesReceived(entry, chunk.chunkOffset(), chunk.data().length);
            }
            case GAP -> {
                LOGGER.warn("场景分片出现缺口: hash={}, offset={}",
                        shortHash(chunk.sha256()), chunk.chunkOffset());
                requestRetryOrFail(entry, "分片缺口");
            }
            case CONFLICT -> {
                LOGGER.warn("场景分片状态不一致，重试该片: hash={}, offset={}",
                        shortHash(chunk.sha256()), chunk.chunkOffset());
                requestRetryOrFail(entry, "分片状态不一致");
            }
            case INACTIVE -> LOGGER.debug("分片到达时落盘槽位已失效: hash={}", shortHash(chunk.sha256()));
        }
    }

    /** 接收服务端对未满足请求的确定性回答。 */
    public void acceptStatus(SceneAssetChunkStatusS2C status) {
        if (status == null) return;
        AssetTransferRegistry.AssetEntry entry = findEntry(status.sha256());
        if (entry == null) return;
        if (status.transferId() != entry.transferId()) return;

        // BUSY 与 STALE 都按「整窗回退 + 按提示退避」处理，且都不消耗重试次数。
        // STALE 此前被静默忽略：单在途时它只意味着一个作废的重发，但有了窗口之后
        // 那条在途记录会一直挂着，只能等超时才脱困。
        if (status.status() == SceneAssetChunkStatusS2C.Status.BUSY
                || status.status() == SceneAssetChunkStatusS2C.Status.STALE) {
            LOGGER.debug("服务端未接受分片请求（{}），稍后重试: hash={}, 建议 {} ms",
                    status.status(), shortHash(status.sha256()), status.retryAfterMillis());
            queue.onBusy(entry, status.retryAfterMillis());
            return;
        }
        failEntry(entry, "服务端拒绝: " + status.status());
    }

    /** 由客户端 tick 驱动：检查分片请求超时并推进队列。 */
    public void tick() {
        deltaProbes.tick();
        queue.tick();
    }

    // ---- 写线程事件 ----

    private final class PipelineEvents implements SceneAssetWritePipeline.Events {
        @Override
        public void onCommitted(AssetTransferRegistry.AssetEntry entry, long committedBytes) {
            // 下一片请求现在由「接收」而不是「落盘」驱动（见 acceptChunk），因此这里
            // 不再需要每 64 KiB 一次客户端线程跳转——那正是加载期吞吐被钉在约 3.8 MiB/s
            // 的原因。落盘进度只留给诊断。
            lastCommittedBytes = committedBytes;
            queue.onBytesCommitted(entry, committedBytes);
        }

        @Override
        public void onComplete(AssetTransferRegistry.AssetEntry entry, Path dataFile) {
            // 写线程已经 close 过句柄（Windows 上打开的文件无法移动），这里直接转整文件校验。
            IO_EXECUTOR.execute(() -> finishDownload(entry, dataFile));
        }

        @Override
        public void onChunkRejected(AssetTransferRegistry.AssetEntry entry, String reason) {
            onClientThread(() -> {
                if (!isLive(entry)) return;
                // 先把"已接收前缀"退回真正落盘的偏移，再让队列整窗从那里重发。
                // 顺序不能反：队列的整窗回退读的就是这个游标。
                writePipeline.rollbackAccepted(entry);
                requestRetryOrFail(entry, reason);
            });
        }
    }

    private AssetTransferRegistry.AssetEntry findEntry(String sha256) {
        return registry.find(sha256);
    }

    /** 链路是否仍是当前世代里登记的那一个（reset 之后一律为 false）。 */
    private boolean isLive(AssetTransferRegistry.AssetEntry entry) {
        return entry != null
                && registry.find(entry.sha256()) == entry
                && registry.isCurrent(entry.generation());
    }

    private void requestRetryOrFail(AssetTransferRegistry.AssetEntry entry, String reason) {
        if (!queue.onRetryableFailure(entry, reason)) {
            // 重试次数耗尽：onTransferExhausted 已经处理了终态。
            return;
        }
    }

    /**
     * 重试耗尽回调（可能来自客户端线程）。
     *
     * <p><b>挂起而不是删除</b>：已落在盘上的前缀是可信的，保留它下次就能只补缺失的部分。</p>
     */
    private void onTransferExhausted(AssetTransferRegistry.AssetEntry entry, String reason) {
        if (entry.patchTarget() != null) {
            // 补丁重试耗尽：等待者要的是资产，不是补丁——直接接到整文件链路上。
            writePipeline.abandon(entry);
            fallbackToFullAsset(entry, "补丁下载重试耗尽: " + reason);
            return;
        }
        LOGGER.warn("场景资产下载失败: hash={}, 原因={}", shortHash(entry.sha256()), reason);
        writePipeline.suspend(entry);
        List<Consumer<SceneAssetCodec.AssetData>> waiters = registry.completeFailure(entry);
        AssetTransferRegistry.fireWaiters(waiters, null);
    }

    /**
     * 终态失败：协议/状态不一致说明前缀已不可信，直接丢弃部分文件，
     * 否则下次会拿着一个坏前缀反复"续传"下去。
     */
    private void failEntry(AssetTransferRegistry.AssetEntry entry, String reason) {
        if (!isLive(entry)) return;
        if (entry.patchTarget() != null) {
            failEntryPatch(entry, reason);
            return;
        }
        LOGGER.error("场景资产传输失败: hash={}, 原因={}", shortHash(entry.sha256()), reason);
        queue.cancel(entry.sha256());
        writePipeline.abandon(entry);
        List<Consumer<SceneAssetCodec.AssetData>> waiters = registry.completeFailure(entry);
        AssetTransferRegistry.fireWaiters(waiters, null);
    }

    /**
     * 补丁链路的终态失败：协议/状态不一致说明这份补丁不可信，丢弃它的部分文件，
     * 把等待者接到整文件下载上。
     */
    private void failEntryPatch(AssetTransferRegistry.AssetEntry entry, String reason) {
        LOGGER.warn("场景增量补丁传输失败: patch={}, 原因={}", shortHash(entry.sha256()), reason);
        queue.cancel(entry.sha256());
        writePipeline.abandon(entry);
        fallbackToFullAsset(entry, reason);
    }

    // ---- 整文件校验与落库 ----

    private void loadDiskCache(AssetTransferRegistry.AssetEntry entry) {
        if (!isLive(entry)) return;
        String sha256 = entry.sha256();
        File cachedFile = new File(cacheDir, sha256 + CACHED_SUFFIX);
        SceneAssetCodec.AssetData data = null;
        try (InputStream fis = Files.newInputStream(cachedFile.toPath())) {
            long length = cachedFile.length();
            if (length <= 0 || length > MAX_SINGLE_ASSET_BYTES) {
                throw new IOException("Cached asset size is invalid: " + length);
            }
            byte[] bytes = fis.readAllBytes();
            if (!SceneAssetCodec.calculateSha256(bytes).equalsIgnoreCase(sha256)) {
                throw new IOException("Cached asset hash mismatch");
            }
            data = SceneAssetCodec.decode(bytes);
        } catch (Exception e) {
            LOGGER.warn("读取本地场景缓存失败，将重新下载: " + sha256, e);
            cachedFile.delete();
        }

        SceneAssetCodec.AssetData loaded = data;
        if (loaded != null) {
            if (!registry.transition(entry, AssetTransferRegistry.State.AVAILABLE)) {
                return; // 链路已作废
            }
            // 已经有成品了，同 hash 的续传记录不再需要。
            partialStore.discard(sha256);
            long now = System.currentTimeMillis();
            cacheIndex.touch(sha256, now);
            cachedFile.setLastModified(now);
            onClientThread(() -> {
                if (!isLive(entry)) return;
                putMemory(sha256, loaded);
                List<Consumer<SceneAssetCodec.AssetData>> waiters = registry.completeSuccess(entry);
                AssetTransferRegistry.fireWaiters(waiters, loaded);
            });
            return;
        }

        // 磁盘缓存不可用：退回下载。此前的实现会在磁盘加载失败时对每个 waiter
        // 重入 getOrFetchAsset，容易在 reset 之后又启动新下载；这里只重置条目一次。
        if (registry.transition(entry, AssetTransferRegistry.State.QUEUED)) {
            // 磁盘缓存不可用时的兜底下载：此时没有调用方的优先级上下文，按预取处理。
            onClientThread(() -> startDownload(entry, SceneAssetDownloadQueue.Priority.PREFETCH));
        }
    }

    private void finishDownload(AssetTransferRegistry.AssetEntry entry, Path dataFile) {
        if (!isLive(entry)) return;
        if (entry.patchTarget() != null) {
            // 补丁链路：校验、应用、按目标 hash 交付；失败一律回退整文件。
            try {
                if (registry.transition(entry, AssetTransferRegistry.State.DECODING)
                        && applyPatch(entry, dataFile, true)) {
                    return;
                }
            } finally {
                partialStore.discard(entry.sha256());
            }
            fallbackToFullAsset(entry, "补丁下载完成但不可用");
            return;
        }
        String sha256 = entry.sha256();
        SceneAssetCodec.AssetData data = null;
        boolean moved = false;
        try {
            long length = Files.size(dataFile);
            // 先按长度卡一道：一个超大的孤儿文件不该驱动出巨额分配。
            if (length != entry.totalSize() || length > MAX_SINGLE_ASSET_BYTES) {
                throw new IOException("Scene part size mismatch: " + length + " != " + entry.totalSize());
            }
            byte[] fullBytes = Files.readAllBytes(dataFile);
            String fullHash = SceneAssetCodec.calculateSha256(fullBytes);
            if (!fullHash.equalsIgnoreCase(sha256)) {
                throw new IOException("Scene asset SHA-256 mismatch");
            }
            if (!registry.transition(entry, AssetTransferRegistry.State.DECODING)) {
                partialStore.discard(sha256);
                return;
            }
            data = SceneAssetCodec.decode(fullBytes);
            File finalFile = new File(cacheDir, sha256 + CACHED_SUFFIX);
            Files.move(dataFile, finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            long now = System.currentTimeMillis();
            finalFile.setLastModified(now);
            cacheIndex.touch(sha256, now);
            enforceCacheLimit(sha256);
        } catch (Exception e) {
            LOGGER.error("完整场景资产校验或解码失败: " + sha256, e);
        } finally {
            // 成功时数据已被 move 走（这里只剩元数据要清）；失败时连数据一起清掉，
            // 避免一个不可信的前缀被反复续传成死循环。
            partialStore.discard(sha256);
        }

        SceneAssetCodec.AssetData loaded = moved ? data : null;
        if (loaded == null) {
            onClientThread(() -> {
                if (!isLive(entry)) return;
                queue.onDownloadComplete(entry);
                List<Consumer<SceneAssetCodec.AssetData>> waiters = registry.completeFailure(entry);
                AssetTransferRegistry.fireWaiters(waiters, null);
            });
            return;
        }
        onClientThread(() -> {
            if (!isLive(entry)) return;
            // 调度职责到此为止：在整文件校验真正结束之前，队列仍要保有这条票，
            // 否则最后一片之后才暴露的校验/解码失败会找不到票而静默卡住。
            queue.onDownloadComplete(entry);
            putMemory(sha256, loaded);
            List<Consumer<SceneAssetCodec.AssetData>> waiters = registry.completeSuccess(entry);
            AssetTransferRegistry.fireWaiters(waiters, loaded);
        });
    }

    // ---- 内存配额 ----

    /** 写入解码缓存并结算配额；必须在客户端线程调用。 */
    private void putMemory(String sha256, SceneAssetCodec.AssetData data) {
        cacheDirHasFiles = true;
        memoryCache.put(sha256, data);
        memoryBudget.put(sha256, SceneAssetCodec.estimateRetainedBytes(data));
        evictMemoryIfNeeded(sha256);
    }

    /**
     * 超出内存配额时按最久未用淘汰。
     *
     * <p>保护集合 = 场景运行时此刻真正在用的 hash（当前/预备/预览/附加/预取中）∪ registry 里
     * 仍在推进的 hash ∪ 本次刚写入的 hash。淘汰是安全的：所有消费者都持有自己那份强引用，
     * 且 {@code .hscene} 文件仍在磁盘上，下次未命中走的是「重新读盘 + 解码」而不是重新下载。
     * <b>解码缓存是纯性能缓存，绝不是正确性依赖。</b></p>
     */
    private void evictMemoryIfNeeded(String justAddedHash) {
        if (!memoryBudget.isOverQuota()) return;
        Set<String> protectedHashes = new HashSet<>();
        protectedHashes.addAll(registry.snapshot().stream()
                .map(AssetTransferRegistry.AssetEntry::sha256).toList());
        protectedHashes.addAll(SceneRenderRuntime.getInstance().activeAssetHashes());
        if (justAddedHash != null) protectedHashes.add(justAddedHash);

        List<String> victims = memoryBudget.selectEvictions(protectedHashes);
        for (String victim : victims) {
            SceneAssetCodec.AssetData removed = memoryCache.remove(victim);
            if (removed == null) continue;
            long freed = SceneAssetCodec.estimateRetainedBytes(removed);
            memoryBudget.remove(victim);
            memoryBudget.recordEvicted(freed);
            LOGGER.debug("按内存配额淘汰已解码场景资产: hash={}, bytes={}", shortHash(victim), freed);
        }
        if (memoryBudget.isOverQuota()) {
            LOGGER.debug("场景资产解码缓存仍在配额之上（在用资产不可淘汰）: used={} MiB, quota={} MiB",
                    memoryBudget.usedBytes() / (1024 * 1024), memoryBudget.quotaBytes() / (1024 * 1024));
        }
    }

    /** 已解码缓存的字节占用（诊断用）。 */
    public long decodedCacheBytes() {
        return memoryBudget.usedBytes();
    }

    // ---- 淘汰 ----

    /**
     * 按**使用热度**淘汰，而不是按文件 mtime：内存命中不读盘，只看 mtime 会把最常用的资产
     * 当成旧文件删掉。正在使用/预取的资产一律不淘汰。
     *
     * @param justWrittenHash 本次刚写成的 hash——它在 {@code memoryCache.put} 之前就跑了淘汰，
     *                        必须显式保护，否则可能刚下完就被自己删掉
     */
    private void enforceCacheLimit(String justWrittenHash) {
        File[] files = listCacheFiles();
        if (files == null) return;
        cacheIndex.loadIfNeeded();

        long total = 0L;
        for (File file : files) total += file.length();
        long quota = diskCacheQuotaBytes;
        if (total > quota) {
            total = evictUntilUnderQuota(files, total, quota, justWrittenHash);
        }
        cacheIndex.prune(liveHashes(listCacheFiles()));
        cacheIndex.flushThrottled();
    }

    private long evictUntilUnderQuota(File[] files, long total, long quota, String justWrittenHash) {
        Set<String> protectedHashes = new HashSet<>(memoryCache.keySet());
        if (justWrittenHash != null) {
            protectedHashes.add(justWrittenHash);
        }
        // 内存配额可能已经把某些在用资产从解码缓存里挤掉，但它们此刻仍在屏幕上，
        // 对应的 .hscene 也不该被删——否则下次切回该地图要重新走一遍网络。
        protectedHashes.addAll(SceneRenderRuntime.getInstance().activeAssetHashes());
        for (AssetTransferRegistry.AssetEntry entry : registry.snapshot()) {
            protectedHashes.add(entry.sha256());
        }
        // 正在被应用的补丁的底：删了它，那枚补丁立刻变成废纸，还得再下一次整份。
        protectedHashes.addAll(pinnedHashes);

        List<File> candidates = new ArrayList<>(Arrays.asList(files));
        candidates.sort(Comparator.comparingLong(file -> cacheIndex.lastUsed(hashOf(file), file.lastModified())));
        for (File file : candidates) {
            if (total <= quota) break;
            if (protectedHashes.contains(hashOf(file))) continue;
            long size = file.length();
            if (file.delete()) {
                total -= size;
            }
        }
        return total;
    }

    private static Set<String> liveHashes(File[] files) {
        Set<String> hashes = new HashSet<>();
        if (files == null) return hashes;
        for (File file : files) {
            hashes.add(hashOf(file));
        }
        return hashes;
    }

    private static String hashOf(File cachedFile) {
        String name = cachedFile.getName();
        if (name.endsWith(CACHED_SUFFIX)) return name.substring(0, name.length() - CACHED_SUFFIX.length());
        if (name.endsWith(PATCH_SUFFIX)) return name.substring(0, name.length() - PATCH_SUFFIX.length());
        return name;
    }

    /**
     * JOIN/DISCONNECT/换服时作废所有链路并清空内存缓存，避免跨会话串包。
     *
     * <p><b>不再删除部分文件</b>：改由写线程落检查点后保留，下次进同一张图只补缺失的字节。
     * 只有断点续传被关闭、或该条目一个字节都没落盘时才会清掉。</p>
     *
     * <p>被作废链路的等待者必须收到 {@code null}：{@code SceneRenderRuntime.prepareMesh}
     * 的 future 依赖这个回调才能完成，静默丢弃会让加载页永远等下去。</p>
     */
    public void resetSession() {
        queue.resetAll();
        List<AssetTransferRegistry.AssetEntry> invalidated = registry.invalidateAll();
        deltaProbes.reset();
        if (partialResumeEnabled) {
            writePipeline.suspendAll();
        } else {
            for (AssetTransferRegistry.AssetEntry entry : invalidated) {
                writePipeline.abandon(entry);
            }
        }
        List<Consumer<SceneAssetCodec.AssetData>> orphaned = new ArrayList<>();
        for (AssetTransferRegistry.AssetEntry entry : invalidated) {
            orphaned.addAll(registry.detachWaiters(entry));
        }
        memoryCache.clear();
        memoryBudget.clear();
        // 增量协商的会话态：换服/换会话后服务端的补丁表可能完全不同，必须重新问。
        deltaOffers.clear();
        deltaDisabled.clear();
        pinnedHashes.clear();
        cacheDirHasFiles = null;
        IO_EXECUTOR.execute(() -> cacheIndex.flush());
        AssetTransferRegistry.fireWaiters(orphaned, null);
    }

    public SceneAssetCodec.AssetData getFromMemory(String sha256) {
        return sha256 != null ? memoryCache.get(sha256) : null;
    }

    public void clearMemoryCache() {
        memoryCache.clear();
        memoryBudget.clear();
    }

    /** 当前仍在传输中的资产数量（诊断用）。 */
    public int activeTransfers() {
        return registry.activeCount();
    }

    /** 当前仍在写盘流水线上的资产数量（诊断用）。 */
    public int activeWriteSlots() {
        return writePipeline.activeSlots();
    }

    private static void onClientThread(Runnable task) {
        Minecraft.getInstance().execute(task);
    }

    private static void invokeQuietly(Consumer<SceneAssetCodec.AssetData> consumer,
                                      SceneAssetCodec.AssetData data) {
        if (consumer == null) return;
        try {
            consumer.accept(data);
        } catch (Throwable t) {
            LOGGER.warn("场景资产完成回调异常", t);
        }
    }

    private static String shortHash(String sha256) {
        return sha256 == null || sha256.length() < 8 ? String.valueOf(sha256) : sha256.substring(0, 8);
    }
}
