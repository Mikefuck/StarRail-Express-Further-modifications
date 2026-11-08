package com.habitrain.core.scene.client;

import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.api.client.scene.compat.SceneMaterialKey;
import com.habitrain.core.api.scene.SceneInstanceAnchor;
import com.habitrain.core.client.mixin.FrustumAccessor;
import com.habitrain.core.api.scene.model.SceneInstance;
import com.habitrain.core.api.scene.model.SceneInstanceBounds;
import com.habitrain.core.api.scene.model.SceneProfile;
import com.habitrain.core.api.scene.model.SceneMotionMath;
import com.habitrain.core.api.scene.model.SceneMotionMode;
import com.habitrain.core.api.scene.model.SceneOrbitAxis;
import com.habitrain.core.api.scene.model.SceneOrbitMath;
import com.habitrain.core.api.scene.model.SceneOrbitSettings;
import com.habitrain.core.api.scene.model.SceneInstanceTransform;
import com.habitrain.core.api.scene.model.SceneRotation;
import com.habitrain.core.api.scene.model.SceneRuntimeState;
import com.habitrain.core.api.scene.model.SceneShakeSettings;
import com.habitrain.core.api.scene.model.SceneSoundSettings;
import com.habitrain.core.scene.network.SceneAssetReadyC2S;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端移动场景运行时渲染器（根据零Tick网络同步的开局时间戳与位移参数在客户端平滑计算位置并渲染两份无缝循环副本）。
 */
public final class SceneRenderRuntime implements com.habitrain.core.api.spi.SceneClientBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneRenderRuntime.class.getSimpleName());

    private static final SceneRenderRuntime INSTANCE = new SceneRenderRuntime();

    public static SceneRenderRuntime getInstance() {
        return INSTANCE;
    }

    private volatile SceneRuntimeState currentState = SceneRuntimeState.INACTIVE;
    private SceneMeshSet currentMeshSet = null;
    private volatile String loadedAssetHash = "";
    private SceneMeshSet preparedMeshSet = null;
    private volatile String preparedAssetHash = "";
    private volatile String preparingAssetHash = "";
    private CompletableFuture<Boolean> preparingFuture = null;
    private CompletableFuture<SceneMeshBuilder.MeshBuildResult> preparingBuildFuture = null;
    private final ScenePrefetchWaiters primaryPrefetchWaiters =
            new ScenePrefetchWaiters(SceneRenderRuntime::reportPrefetchResult);
    private final Map<String, SceneAssetDescriptor> manifestsByHash = new ConcurrentHashMap<>();
    private final Map<String, SceneAssetDescriptor> manifestsByMap = new ConcurrentHashMap<>();
    private final Map<String, SceneCompatibilityReport> compatibilityReportsByHash = new ConcurrentHashMap<>();
    private volatile List<SceneRuntimeState> additionalRuntimeStates = List.of();
    /**
     * API 场景实例（外部 Mod 通过 {@code SceneApi} 注册）：id → 服务端权威快照。
     *
     * <p>与地图级附加背景不同，这里<b>没有数量上限</b>：条目数只取决于服务端注册了多少个，
     * 每个条目共用 {@link #additionalMeshesByHash} 里按内容哈希去重的网格，因此 N 个实例
     * 引用同一份资产时只会占用一份 GPU 网格。</p>
     */
    private final Map<String, SceneInstance> dynamicInstances = new ConcurrentHashMap<>();
    private volatile List<SceneInstance> sortedDynamicInstances = List.of();
    private final Map<String, AnchoredProfileCache> anchoredProfiles = new ConcurrentHashMap<>();
    private final Map<String, SceneMeshSet> additionalMeshesByHash = new ConcurrentHashMap<>();
    private final java.util.Set<String> additionalLoadingHashes = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> additionalLoadTickets = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<SceneMeshBuilder.MeshBuildResult>> additionalBuildFutures = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> additionalPrefetchWaiters = new ConcurrentHashMap<>();
    private long additionalMeshGeneration = 0L;
    private long meshLoadGeneration = 0L;
    /** 附加网格的最近使用序号：显存预算超限时据此淘汰"已不被需要"的那些。 */
    private final Map<String, Long> additionalMeshUseSeq = new ConcurrentHashMap<>();
    private long meshUseCounter = 0L;
    /** 显存估算配额；-1 表示尚未从配置读取。 */
    private volatile long meshCacheQuotaBytes = -1L;
    private volatile SceneCompatibilityReport lastCompatibilityReport = new SceneCompatibilityReport();
    private volatile com.habitrain.core.api.scene.model.ScenePublishPolicy publishPolicy = com.habitrain.core.api.scene.model.ScenePublishPolicy.STRICT;

    // 预览模式覆盖
    private boolean previewActive = false;
    private String previewMapKey = "";
    private SceneProfile previewProfile = null;
    private volatile String previewAssetHash = "";
    private long previewStartNanos = 0L;
    private volatile int lastConfiguredOrbitInstances = 0;
    private volatile int lastVisibleOrbitInstances = 0;

    private record AnchoredProfileCache(long revision, SceneProfile profile) {}

    private SceneRenderRuntime() {}

    @Override
    public void requestInstanceResync() {
        SceneClientRuntime.requestInstanceResync();
    }

    public SceneCompatibilityReport getLastCompatibilityReport() {
        return lastCompatibilityReport;
    }

    public SceneCompatibilityReport getCompatibilityReport(String assetHash) {
        return assetHash == null ? new SceneCompatibilityReport()
                : compatibilityReportsByHash.getOrDefault(assetHash, new SceneCompatibilityReport());
    }

    public com.habitrain.core.api.scene.model.ScenePublishPolicy getPublishPolicy() {
        return publishPolicy;
    }

    /** Latest orbit workload sample for the editor preview summary. */
    public int getLastConfiguredOrbitInstances() {
        return lastConfiguredOrbitInstances;
    }

    /** Latest number of orbit instances left after distance and frustum culling. */
    public int getLastVisibleOrbitInstances() {
        return lastVisibleOrbitInstances;
    }

    public void setPublishPolicy(com.habitrain.core.api.scene.model.ScenePublishPolicy policy) {
        this.publishPolicy = policy != null ? policy : com.habitrain.core.api.scene.model.ScenePublishPolicy.STRICT;
    }

    /**
     * 接收服务端下发的运行时状态。
     */
    public synchronized void updateRuntimeState(SceneRuntimeState newState) {
        this.currentState = newState != null ? newState : SceneRuntimeState.INACTIVE;

        // A settings preview owns the currently loaded mesh until it is explicitly stopped.
        // Still remember the server state so stopPreview() can restore it afterwards.
        if (previewActive) {
            return;
        }

        if (!currentState.isActive()) {
            // 停用并清理显存
            clearMesh();
            disableRuntimeEffects();
            return;
        }

        logMotionSetup("正式场景", currentState.getProfile());
        // Sound and carriage shake are map-wide settings owned by the default entry;
        // they do not depend on any individual background asset becoming ready.
        enableRuntimeEffects();

        String hash = currentState.getAssetHash();
        if (hash != null && !hash.isBlank() && Objects.equals(hash, loadedAssetHash)
                && currentMeshSet != null && !currentMeshSet.isEmpty()) {
            enableRuntimeEffects();
        } else if (hash != null && !hash.isBlank() && Objects.equals(hash, preparedAssetHash)) {
            activatePreparedMesh(hash);
        } else if (hash != null && !hash.isBlank()) {
            SceneAssetDescriptor descriptor = manifestsByHash.get(hash);
            if (descriptor != null && descriptor.isValid()) {
                loadMesh(descriptor);
            } else {
                LOGGER.debug("运行状态先于 Manifest 到达，等待资产描述符: hash={}", shortHash(hash));
            }
        }
    }

    // ---- 分批裁剪的诊断计数（最近一帧） ----

    private volatile int lastTotalBatches;
    private volatile int lastDrawnBatches;
    private volatile long lastCulledBatchBytes;

    /** 最近一帧参与裁剪的批次数（未分批时为 0）。 */
    public int getLastTotalBatches() { return lastTotalBatches; }

    /** 最近一帧实际提交的批次数。与总数一起看，就是分批裁剪到底省了多少。 */
    public int getLastDrawnBatches() { return lastDrawnBatches; }

    /** 最近一帧因视锥裁剪而未提交的批次估算字节数。 */
    public long getLastCulledBatchBytes() { return lastCulledBatchBytes; }

    /** 见到的服务端场景协议版本；决定客户端能不能发起增量补丁协商。 */
    private volatile int lastServerProtocolVersion = com.habitrain.core.scene.network.SceneProtocol.LEGACY_VERSION;

    /** 保存服务端权威 Manifest，并协调 Manifest/Runtime 任意到达顺序。 */
    public synchronized void acceptManifest(String mapKey, SceneAssetDescriptor descriptor) {
        acceptManifest(mapKey, descriptor, com.habitrain.core.scene.network.SceneProtocol.LEGACY_VERSION);
    }

    /**
     * 带协议版本的重载：版本只用来决定"能不能跟对面协商增量补丁"，不参与任何拒绝逻辑。
     */
    public synchronized void acceptManifest(String mapKey, SceneAssetDescriptor descriptor,
                                            int serverProtocolVersion) {
        if (serverProtocolVersion > lastServerProtocolVersion) {
            lastServerProtocolVersion = serverProtocolVersion;
        }
        if (descriptor == null || !descriptor.isValid()) return;
        // 本地还没有这一版时问一句有没有增量补丁；有也不影响下面的正常流程。
        SceneAssetCache.getInstance().considerDeltaProbe(mapKey, descriptor, serverProtocolVersion);
        manifestsByHash.put(descriptor.sha256(), descriptor);
        if (mapKey != null && !mapKey.isBlank()) {
            manifestsByMap.put(mapKey, descriptor);
        }
        if ((currentState.isActive() && Objects.equals(currentState.getAssetHash(), descriptor.sha256()))
                || (previewActive && Objects.equals(previewAssetHash, descriptor.sha256())
                && !Objects.equals(loadedAssetHash, descriptor.sha256()))) {
            loadMesh(descriptor);
        }
        if (isWantedAdditionalHash(descriptor.sha256())) {
            loadAdditionalMesh(descriptor);
        }
    }

    public synchronized void updateAdditionalRuntimeStates(List<SceneRuntimeState> states) {
        this.additionalRuntimeStates = states == null ? List.of() : states.stream()
                .filter(Objects::nonNull).filter(SceneRuntimeState::isActive).limit(4).toList();
        reconcileWantedMeshes();
    }

    /**
     * 应用服务端下发的 API 场景实例增量（新增/更新 + 删除 + 全清）。
     *
     * <p>幂等：同一个实例以更高 revision 重发只会覆盖本地快照。清空语义只影响 API 实例，
     * 不会动地图级主/附加背景。</p>
     */
    public synchronized void applyInstanceOps(List<SceneInstance> upserts, List<String> removals, boolean clear) {
        if (clear) {
            dynamicInstances.clear();
            anchoredProfiles.clear();
        }
        if (removals != null) {
            for (String id : removals) {
                if (id != null) {
                    dynamicInstances.remove(id);
                    anchoredProfiles.remove(id);
                }
            }
        }
        if (upserts != null) {
            for (SceneInstance instance : upserts) {
                if (instance == null || instance.id() == null) continue;
                dynamicInstances.put(instance.id(), instance);
                anchoredProfiles.remove(instance.id());
            }
        }
        rebuildSortedDynamicInstances();
        reconcileWantedMeshes();
    }

    private void rebuildSortedDynamicInstances() {
        if (dynamicInstances.isEmpty()) {
            sortedDynamicInstances = List.of();
            return;
        }
        List<SceneInstance> snapshot = new ArrayList<>(dynamicInstances.values());
        snapshot.sort(Comparator.comparingInt(SceneInstance::priority)
                .thenComparingLong(SceneInstance::createdAtMillis));
        sortedDynamicInstances = List.copyOf(snapshot);
    }

    /** 本地已知的全部 API 场景实例（按 priority 升序，即先画低优先级）。 */
    public synchronized List<SceneInstance> dynamicInstances() {
        return new ArrayList<>(sortedDynamicInstances);
    }

    /** 按 ID 查询本地已知的 API 场景实例。 */
    public synchronized SceneInstance dynamicInstance(String instanceId) {
        return instanceId != null ? dynamicInstances.get(instanceId) : null;
    }

    /** 本地已知的 API 场景实例数量。 */
    public synchronized int dynamicInstanceCount() {
        return dynamicInstances.size();
    }

    /** 某实例的网格是否已经烘焙完成、可以立即绘制。 */
    public synchronized boolean isDynamicInstanceMeshReady(String instanceId) {
        SceneInstance instance = dynamicInstances.get(instanceId);
        if (instance == null || !instance.hasAsset()) return false;
        SceneMeshSet mesh = additionalMeshesByHash.get(instance.assetHash());
        return mesh != null && !mesh.isEmpty() && !mesh.isClosed();
    }

    /**
     * 重新对齐"当前被需要的附加网格"：地图级附加背景 ∪ API 场景实例。
     * 预取或历史缓存的网格保留在 {@link #additionalMeshesByHash} 中交由 {@link #enforceMeshBudget()} 按配额管理，
     * 避免投票预取刚完成的附加网格被立即释放。
     */
    private void reconcileWantedMeshes() {
        Set<String> wanted = wantedHashes();
        // Invalidate ownership before cancellation callbacks run, including downloads
        // that have not produced a build future yet.
        for (String hash : new HashSet<>(additionalLoadingHashes)) {
            if (wanted.contains(hash) || additionalPrefetchWaiters.containsKey(hash)) continue;
            additionalLoadTickets.remove(hash);
            additionalLoadingHashes.remove(hash);
            var obsolete = additionalBuildFutures.remove(hash);
            if (obsolete != null) obsolete.cancel(false);
        }
        for (String hash : wanted) {
            if (additionalMeshesByHash.containsKey(hash)) {
                additionalMeshUseSeq.put(hash, ++meshUseCounter);
                continue;
            }
            SceneAssetDescriptor descriptor = manifestsByHash.get(hash);
            if (descriptor != null && descriptor.isValid()) loadAdditionalMesh(descriptor);
        }
        enforceMeshBudget();
    }

    /** 当前被需要的全部资产哈希（地图级附加背景 + API 实例）。 */
    private Set<String> wantedHashes() {
        Set<String> wanted = new HashSet<>();
        for (SceneRuntimeState state : additionalRuntimeStates) {
            if (state != null) addIfPresent(wanted, state.getAssetHash());
        }
        for (SceneInstance instance : dynamicInstances.values()) {
            if (instance != null) addIfPresent(wanted, instance.assetHash());
        }
        return wanted;
    }

    private boolean isWantedAdditionalHash(String hash) {
        if (hash == null) return false;
        if (additionalRuntimeStates.stream().anyMatch(state -> Objects.equals(hash, state.getAssetHash()))) {
            return true;
        }
        return dynamicInstances.values().stream().anyMatch(instance -> Objects.equals(hash, instance.assetHash()));
    }

    private void loadAdditionalMesh(SceneAssetDescriptor descriptor) {
        String hash = descriptor.sha256();
        SceneMeshSet existing = additionalMeshesByHash.get(hash);
        if (existing != null && !existing.isEmpty() && !existing.isClosed()) {
            reportAdditionalPrefetchWaiters(hash, true);
            return;
        }
        if (!additionalLoadingHashes.add(hash)) return;
        Object ticket = new Object();
        additionalLoadTickets.put(hash, ticket);
        long generation = additionalMeshGeneration;
        SceneAssetCache.getInstance().getOrFetchAsset(descriptor,
                SceneAssetDownloadQueue.Priority.ADDITIONAL_RUNTIME, assetData -> {
            if (generation != additionalMeshGeneration || additionalLoadTickets.get(hash) != ticket) return;
            if (assetData == null) {
                synchronized (this) {
                    additionalLoadingHashes.remove(hash);
                    additionalBuildFutures.remove(hash);
                    additionalLoadTickets.remove(hash, ticket);
                    reportAdditionalPrefetchWaiters(hash, false);
                }
                return;
            }
            CompletableFuture<SceneMeshBuilder.MeshBuildResult> buildFuture =
                    SceneMeshBuilder.buildMeshWithReportAsync(assetData, false,
                            SceneBuildScheduler.Priority.ADDITIONAL);
            synchronized (this) {
                if (generation != additionalMeshGeneration || additionalLoadTickets.get(hash) != ticket) {
                    buildFuture.cancel(false);
                    return;
                }
                additionalBuildFutures.put(hash, buildFuture);
            }
            buildFuture.thenAccept(result -> {
                synchronized (this) {
                    if (generation != additionalMeshGeneration || additionalLoadTickets.get(hash) != ticket) {
                        if (result != null && result.meshSet() != null) result.meshSet().close();
                        return;
                    }
                    additionalLoadTickets.remove(hash, ticket);
                    additionalLoadingHashes.remove(hash);
                    additionalBuildFutures.remove(hash, buildFuture);
                    SceneMeshSet mesh = result != null ? result.meshSet() : null;
                    SceneCompatibilityReport report = result != null ? result.report() : null;
                    boolean fatal = report != null && report.hasBlockingIssues(
                            com.habitrain.core.api.scene.model.ScenePublishPolicy.SKIP_AND_WARN);
                    boolean wantedByPrefetch = additionalPrefetchWaiters.containsKey(hash);
                    if (generation != additionalMeshGeneration
                            || (!isWantedAdditionalHash(hash) && !wantedByPrefetch)
                            || mesh == null || mesh.isEmpty() || mesh.isClosed() || fatal) {
                        if (mesh != null) mesh.close();
                        reportAdditionalPrefetchWaiters(hash, false);
                        return;
                    }
                    if (report != null) compatibilityReportsByHash.put(hash, report);
                    SceneMeshSet previous = additionalMeshesByHash.put(hash, mesh);
                    if (previous != null && previous != mesh) previous.close();
                    additionalMeshUseSeq.put(hash, ++meshUseCounter);
                    enforceMeshBudget();
                    LOGGER.info("已启用附加动态背景 GPU 网格: hash={}", shortHash(hash));
                    reportAdditionalPrefetchWaiters(hash, true);
                }
            }).exceptionally(ex -> {
                synchronized (this) {
                    if (generation != additionalMeshGeneration || additionalLoadTickets.get(hash) != ticket) return null;
                    LOGGER.warn("构建附加场景网格失败: hash={}", shortHash(hash), ex);
                    additionalLoadTickets.remove(hash, ticket);
                    additionalLoadingHashes.remove(hash);
                    additionalBuildFutures.remove(hash, buildFuture);
                    reportAdditionalPrefetchWaiters(hash, false);
                }
                return null;
            });
        });
    }

    /** Metadata lookup for the editor; this does not trigger an asset download. */
    public synchronized SceneAssetDescriptor getManifest(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) return SceneAssetDescriptor.EMPTY;
        SceneAssetDescriptor descriptor = manifestsByMap.get(mapKey);
        if (descriptor != null && descriptor.isValid()) return descriptor;
        if (com.habitrain.core.api.scene.model.SceneBackgroundKey.isDefault(
                com.habitrain.core.api.scene.model.SceneBackgroundKey.backgroundIdFromAssetKey(mapKey))) {
            String bare = com.habitrain.core.api.scene.model.SceneBackgroundKey.mapKeyFromAssetKey(mapKey);
            descriptor = manifestsByMap.get(bare);
            if (descriptor != null && descriptor.isValid()) return descriptor;
        }
        return SceneAssetDescriptor.EMPTY;
    }

    private synchronized void loadMesh(SceneAssetDescriptor descriptor) {
        if (descriptor == null || !descriptor.isValid()) return;
        String sha256 = descriptor.sha256();
        prepareMesh(descriptor).thenAccept(success -> {
            synchronized (this) {
                if (!success) return;
                boolean wantedByPreview = previewActive && Objects.equals(previewAssetHash, sha256);
                boolean wantedByRuntime = !previewActive && currentState.isActive()
                        && Objects.equals(currentState.getAssetHash(), sha256);
                if (wantedByPreview || wantedByRuntime) activatePreparedMesh(sha256);
            }
        });
    }

    /** Starts vote-loading prefetch and reports ready only after GPU mesh compilation succeeds. */
    public void prefetchAsset(long sessionId, String mapKey, SceneAssetDescriptor descriptor) {
        if (descriptor == null || !descriptor.isValid()) {
            reportPrefetchResult(sessionId, descriptor == null ? "" : descriptor.sha256(), false);
            return;
        }
        // 预取包本身不带协议版本，用 manifest 里见到的那个（同一会话内先到的一般就是它）。
        SceneAssetCache.getInstance().considerDeltaProbe(mapKey, descriptor, lastServerProtocolVersion);
        if (!com.habitrain.core.api.scene.model.SceneBackgroundKey.DEFAULT_ID.equals(
                com.habitrain.core.api.scene.model.SceneBackgroundKey.backgroundIdFromAssetKey(mapKey))) {
            prefetchAdditionalAsset(sessionId, mapKey, descriptor);
            return;
        }
        synchronized (this) {
            manifestsByHash.put(descriptor.sha256(), descriptor);
            if (mapKey != null && !mapKey.isBlank()) manifestsByMap.put(mapKey, descriptor);
            primaryPrefetchWaiters.await(sessionId, descriptor.sha256(), prepareMesh(descriptor));
        }
    }

    private void prefetchAdditionalAsset(long sessionId, String assetKey, SceneAssetDescriptor descriptor) {
        String hash = descriptor.sha256();
        Object ticket = new Object();
        long generation;
        synchronized (this) {
            manifestsByHash.put(hash, descriptor);
            if (assetKey != null && !assetKey.isBlank()) manifestsByMap.put(assetKey, descriptor);
            SceneMeshSet ready = additionalMeshesByHash.get(hash);
            if (ready != null && !ready.isEmpty() && !ready.isClosed()) {
                additionalMeshUseSeq.put(hash, ++meshUseCounter);
                reportPrefetchResult(sessionId, hash, true);
                return;
            }
            additionalPrefetchWaiters.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(sessionId);
            if (!additionalLoadingHashes.add(hash)) return;
            additionalLoadTickets.put(hash, ticket);
            generation = additionalMeshGeneration;
        }
        SceneAssetCache.getInstance().getOrFetchAsset(descriptor,
                SceneAssetDownloadQueue.Priority.PREFETCH, assetData -> {
            if (generation != additionalMeshGeneration || additionalLoadTickets.get(hash) != ticket) return;
            if (assetData == null) {
                synchronized (this) {
                    additionalLoadingHashes.remove(hash);
                    additionalBuildFutures.remove(hash);
                    additionalLoadTickets.remove(hash, ticket);
                    reportAdditionalPrefetchWaiters(hash, false);
                }
                return;
            }
            CompletableFuture<SceneMeshBuilder.MeshBuildResult> buildFuture =
                    SceneMeshBuilder.buildMeshWithReportAsync(assetData, false,
                            SceneBuildScheduler.Priority.PREFETCH);
            synchronized (this) {
                if (generation != additionalMeshGeneration || additionalLoadTickets.get(hash) != ticket) {
                    buildFuture.cancel(false);
                    return;
                }
                additionalBuildFutures.put(hash, buildFuture);
            }
            buildFuture.thenAccept(result -> {
                boolean success;
                synchronized (this) {
                    if (generation != additionalMeshGeneration || additionalLoadTickets.get(hash) != ticket) {
                        if (result != null && result.meshSet() != null) result.meshSet().close();
                        return;
                    }
                    additionalLoadTickets.remove(hash, ticket);
                    additionalLoadingHashes.remove(hash);
                    additionalBuildFutures.remove(hash, buildFuture);
                    SceneMeshSet mesh = result != null ? result.meshSet() : null;
                    SceneCompatibilityReport report = result != null ? result.report() : null;
                    boolean fatal = report != null && report.hasBlockingIssues(
                            com.habitrain.core.api.scene.model.ScenePublishPolicy.SKIP_AND_WARN);
                    success = generation == additionalMeshGeneration && mesh != null
                            && !mesh.isEmpty() && !mesh.isClosed() && !fatal;
                    if (success) {
                        if (report != null) compatibilityReportsByHash.put(hash, report);
                        SceneMeshSet previous = additionalMeshesByHash.put(hash, mesh);
                        if (previous != null && previous != mesh) previous.close();
                        additionalMeshUseSeq.put(hash, ++meshUseCounter);
                    } else if (mesh != null) {
                        mesh.close();
                    }
                    enforceMeshBudget();
                    reportAdditionalPrefetchWaiters(hash, success);
                }
            }).exceptionally(ex -> {
                synchronized (this) {
                    if (generation != additionalMeshGeneration || additionalLoadTickets.get(hash) != ticket) return null;
                    LOGGER.warn("预取构建附加场景网格失败: hash={}", shortHash(hash), ex);
                    additionalLoadTickets.remove(hash, ticket);
                    additionalLoadingHashes.remove(hash);
                    additionalBuildFutures.remove(hash, buildFuture);
                    reportAdditionalPrefetchWaiters(hash, false);
                }
                return null;
            });
        });
    }

    private void reportAdditionalPrefetchWaiters(String hash, boolean success) {
        List<Long> sessions = additionalPrefetchWaiters.remove(hash);
        if (sessions == null) return;
        for (Long sessionId : sessions) {
            if (sessionId != null) reportPrefetchResult(sessionId, hash, success);
        }
    }

    // ---- 显存预算 ----

    /**
     * 此刻真正在用的资产 hash 集合。
     *
     * <p>供 {@link SceneAssetCache} 的内存与磁盘淘汰保护使用：这些资产正在屏幕上、正在构建
     * 或正在预取，任何一层缓存把它们丢掉都会立刻表现为重新下载或重新烘焙。</p>
     *
     * <p>无锁读取 volatile 快照与并发集合，避免后台 IO 线程与渲染线程争抢实例监视器锁。</p>
     */
    public Set<String> activeAssetHashes() {
        Set<String> hashes = new HashSet<>();
        addIfPresent(hashes, loadedAssetHash);
        addIfPresent(hashes, preparedAssetHash);
        addIfPresent(hashes, preparingAssetHash);
        addIfPresent(hashes, previewAssetHash);
        SceneRuntimeState stateSnapshot = currentState;
        if (stateSnapshot != null) addIfPresent(hashes, stateSnapshot.getAssetHash());
        List<SceneRuntimeState> additionalSnapshot = additionalRuntimeStates;
        if (additionalSnapshot != null) {
            for (SceneRuntimeState state : additionalSnapshot) {
                if (state != null) addIfPresent(hashes, state.getAssetHash());
            }
        }
        for (SceneInstance instance : dynamicInstances.values()) {
            if (instance != null) addIfPresent(hashes, instance.assetHash());
        }
        hashes.addAll(additionalMeshesByHash.keySet());
        hashes.addAll(additionalLoadingHashes);
        hashes.addAll(additionalPrefetchWaiters.keySet());
        hashes.addAll(primaryPrefetchWaiters.assetHashes());
        return hashes;
    }

    private static void addIfPresent(Set<String> target, String hash) {
        if (hash != null && !hash.isBlank()) target.add(hash);
    }

    /** 读入显存配额；首次访问时从配置读取，之后由配置页显式刷新。 */
    public void applyClientConfig() {
        meshCacheQuotaBytes = com.habitrain.core.client.config.SceneClientPerformanceRules.quotaBytes(
                com.habitrain.core.client.config.ClientVisualPreferences.getMeshCacheQuotaMiB());
    }

    /** 全部场景网格的显存占用估算（诊断用）。 */
    public synchronized long totalMeshBytes() {
        long total = 0L;
        if (currentMeshSet != null) total += currentMeshSet.estimatedBytes();
        if (preparedMeshSet != null) total += preparedMeshSet.estimatedBytes();
        for (SceneMeshSet mesh : additionalMeshesByHash.values()) {
            total += mesh.estimatedBytes();
        }
        return total;
    }

    /**
     * 超出显存配额时淘汰"已经不被需要"的附加网格。
     *
     * <p>只淘汰不在当前 {@code additionalRuntimeStates} 里的条目——也就是预取回来却一直没被
     * 启用的那些。当前主背景、预备网格与正在显示的附加背景<b>永不</b>在这里被关闭：
     * 它们的所有权是排他的，没有引用计数可以让"另一个持有者"安全地继续用一个已释放的
     * {@code VertexBuffer}。</p>
     *
     * <p>调用点都在 {@code synchronized(this)} 内，与 {@code render()} 互斥，因此关闭网格
     * 不会与正在进行的绘制交错。</p>
     */
    private void enforceMeshBudget() {
        long quota = meshCacheQuotaBytes;
        if (quota < 0L) {
            applyClientConfig();
            quota = meshCacheQuotaBytes;
        }
        if (quota <= 0L) return;
        long total = totalMeshBytes();
        if (total <= quota) return;

        Set<String> wanted = wantedHashes();
        List<String> candidates = new ArrayList<>();
        for (String hash : additionalMeshesByHash.keySet()) {
            if (!wanted.contains(hash)) candidates.add(hash);
        }
        candidates.sort(Comparator.comparingLong(hash -> additionalMeshUseSeq.getOrDefault(hash, 0L)));

        for (String hash : candidates) {
            if (total <= quota) break;
            SceneMeshSet removed = additionalMeshesByHash.remove(hash);
            if (removed == null) continue;
            total -= removed.estimatedBytes();
            additionalMeshUseSeq.remove(hash);
            removed.close();
            LOGGER.debug("按显存配额淘汰附加场景网格: hash={}", shortHash(hash));
        }
        if (total > quota) {
            LOGGER.debug("场景网格显存仍在配额之上（在用网格不可淘汰）: used={} MiB, quota={} MiB",
                    total / (1024 * 1024), quota / (1024 * 1024));
        }
    }

    /**
     * Downloads, decodes and bakes a private staging asset without installing it as the
     * map's official manifest or activating it for players.
     */
    public synchronized CompletableFuture<Boolean> inspectStagingAsset(SceneAssetDescriptor descriptor) {
        return inspectStagingAssetDetailed(descriptor).thenApply(StagingInspectionResult::accepted);
    }

    /**
     * Builds or reuses the candidate mesh, then applies the administrator's publication policy.
     * Publication policy is deliberately evaluated here instead of in {@link #prepareMesh}:
     * an already-published asset must remain renderable for ordinary clients even when it was
     * published with SKIP_AND_WARN and their local editor defaults to STRICT.
     */
    public synchronized CompletableFuture<StagingInspectionResult> inspectStagingAssetDetailed(
            SceneAssetDescriptor descriptor) {
        if (descriptor == null || !descriptor.isValid()) {
            return CompletableFuture.completedFuture(new StagingInspectionResult(
                    "", false, false, publishPolicy, new SceneCompatibilityReport()));
        }
        String sha256 = descriptor.sha256();
        manifestsByHash.put(sha256, descriptor);
        com.habitrain.core.api.scene.model.ScenePublishPolicy inspectionPolicy = publishPolicy;
        return prepareMesh(descriptor).thenApply(meshReady -> {
            SceneCompatibilityReport report = compatibilityReportsByHash.getOrDefault(
                    sha256, new SceneCompatibilityReport());
            lastCompatibilityReport = report;
            boolean accepted = meshReady && report.isCompatible(inspectionPolicy);
            logCompatibilityIssues(sha256, report, inspectionPolicy);
            return new StagingInspectionResult(
                    sha256, meshReady, accepted, inspectionPolicy, report);
        });
    }

    public record StagingInspectionResult(
            String assetHash,
            boolean meshReady,
            boolean accepted,
            com.habitrain.core.api.scene.model.ScenePublishPolicy policy,
            SceneCompatibilityReport report
    ) {
        public boolean isPolicyOnlyFailure() {
            return meshReady && !accepted
                    && policy == com.habitrain.core.api.scene.model.ScenePublishPolicy.STRICT
                    && report != null
                    && report.isCompatible(com.habitrain.core.api.scene.model.ScenePublishPolicy.SKIP_AND_WARN);
        }
    }

    private synchronized CompletableFuture<Boolean> prepareMesh(SceneAssetDescriptor descriptor) {
        String sha256 = descriptor.sha256();
        if (Objects.equals(sha256, loadedAssetHash) && currentMeshSet != null
                && !currentMeshSet.isEmpty() && !currentMeshSet.isClosed()) {
            return CompletableFuture.completedFuture(true);
        }
        if (Objects.equals(sha256, preparedAssetHash) && preparedMeshSet != null
                && !preparedMeshSet.isEmpty() && !preparedMeshSet.isClosed()) {
            return CompletableFuture.completedFuture(true);
        }
        if (Objects.equals(sha256, preparingAssetHash) && preparingFuture != null) {
            return preparingFuture;
        }

        final long generation = ++meshLoadGeneration;
        var oldBuild = preparingBuildFuture;
        var oldResult = preparingFuture;
        preparingBuildFuture = null;
        if (preparedMeshSet != null) preparedMeshSet.close();
        preparedMeshSet = null;
        preparedAssetHash = "";
        preparingAssetHash = sha256;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        preparingFuture = result;
        if (oldBuild != null) oldBuild.cancel(false);
        if (oldResult != null) oldResult.complete(false);

        SceneAssetCache.getInstance().getOrFetchAsset(descriptor,
                SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME, assetData -> {
            synchronized (this) {
                if (generation != meshLoadGeneration || !Objects.equals(sha256, preparingAssetHash)) {
                    result.complete(false);
                    return;
                }
            }
            if (assetData == null) {
                finishPrepare(generation, sha256, null, result);
                return;
            }
            CompletableFuture<SceneMeshBuilder.MeshBuildResult> buildFuture =
                    SceneMeshBuilder.buildMeshWithReportAsync(assetData, false,
                            SceneBuildScheduler.Priority.PRIMARY);
            synchronized (this) {
                if (generation != meshLoadGeneration || !Objects.equals(sha256, preparingAssetHash)) {
                    buildFuture.cancel(false);
                    result.complete(false);
                    return;
                }
                preparingBuildFuture = buildFuture;
            }
            buildFuture.thenAccept(buildResult -> finishPrepare(generation, sha256, buildResult, result))
                    .exceptionally(ex -> {
                        LOGGER.warn("构建主场景网格失败: hash={}", shortHash(sha256), ex);
                        finishPrepare(generation, sha256, null, result);
                        return null;
                    });
        });
        return result;
    }

    private void finishPrepare(long generation, String sha256, SceneMeshBuilder.MeshBuildResult buildResult,
                               CompletableFuture<Boolean> result) {
        boolean success;
        synchronized (this) {
            if (generation != meshLoadGeneration || !Objects.equals(sha256, preparingAssetHash)) {
                if (buildResult != null && buildResult.meshSet() != null) buildResult.meshSet().close();
                result.complete(false);
                return;
            }
            preparingAssetHash = "";
            preparingFuture = null;
            preparingBuildFuture = null;
            SceneMeshSet meshSet = buildResult != null ? buildResult.meshSet() : null;
            SceneCompatibilityReport report = buildResult != null ? buildResult.report() : null;
            if (report != null) {
                this.lastCompatibilityReport = report;
                compatibilityReportsByHash.put(sha256, report);
            }
            // Editor policy is a publication gate, not a runtime render gate. Published
            // SKIP_AND_WARN assets intentionally contain entries for omitted unsupported blocks;
            // rejecting the whole VBO here made every supported block disappear too. Missing
            // textures/invalid atlases remain an unconditional client-side safety failure.
            boolean hasFatalMaterialIssue = report != null && report.hasBlockingIssues(
                    com.habitrain.core.api.scene.model.ScenePublishPolicy.SKIP_AND_WARN);
            if (hasFatalMaterialIssue) {
                LOGGER.warn("拒绝加载存在严重材质问题的场景资产: hash={}, blocking={}",
                        shortHash(sha256), report.blockingIssueCount(
                                com.habitrain.core.api.scene.model.ScenePublishPolicy.SKIP_AND_WARN));
            }
            success = meshSet != null && !meshSet.isEmpty() && !meshSet.isClosed()
                    && !hasFatalMaterialIssue;
            if (success) {
                preparedMeshSet = meshSet;
                preparedAssetHash = sha256;
                boolean wantedByPreview = previewActive && Objects.equals(previewAssetHash, sha256);
                boolean wantedByRuntime = !previewActive && currentState.isActive()
                        && Objects.equals(currentState.getAssetHash(), sha256);
                if (wantedByPreview || wantedByRuntime) {
                    activatePreparedMesh(sha256);
                } else {
                    enforceMeshBudget();
                }
            } else if (meshSet != null) {
                meshSet.close();
            }
        }
        result.complete(success);
    }

    private static void logCompatibilityIssues(
            String sha256,
            SceneCompatibilityReport report,
            com.habitrain.core.api.scene.model.ScenePublishPolicy policy) {
        if (report == null || report.totalIssueCount() == 0L) return;
        java.util.List<SceneCompatibilityReport.Entry> issues = report.getIssues();
        long blockingCount = report.blockingIssueCount(policy);
        // 计数用精确值（不受抽样上限影响），逐条打印只用保留的抽样。
        LOGGER.warn("场景资产兼容性诊断: hash={}, policy={}, issues={}, blocking={}, sampled={}",
                shortHash(sha256), policy, report.totalIssueCount(), blockingCount, issues.size());
        int limit = Math.min(16, issues.size());
        for (int i = 0; i < limit; i++) {
            SceneCompatibilityReport.Entry issue = issues.get(i);
            LOGGER.warn("场景兼容问题[{}/{}]: block={}, localPos={}, type={}, blocking={}, detail={}",
                    i + 1, report.totalIssueCount(), issue.blockId(), issue.localPos(),
                    issue.issueType(), issue.isBlocking(policy), issue.description());
        }
        long notLogged = report.totalIssueCount() - limit;
        if (notLogged > 0L) {
            LOGGER.warn("另有 {} 条场景兼容问题未逐条写入日志（抽样保留 {} 条）",
                    notLogged, issues.size());
        }
    }

    private synchronized void activatePreparedMesh(String sha256) {
        if (!Objects.equals(sha256, preparedAssetHash) || preparedMeshSet == null) return;
        if (currentMeshSet != null && currentMeshSet != preparedMeshSet) currentMeshSet.close();
        currentMeshSet = preparedMeshSet;
        loadedAssetHash = sha256;
        preparedMeshSet = null;
        preparedAssetHash = "";
        enforceMeshBudget();
        LOGGER.info("已启用预编译场景 GPU 网格: hash={}", shortHash(sha256));
        if (!previewActive && currentState.isActive()
                && Objects.equals(currentState.getAssetHash(), sha256)) {
            enableRuntimeEffects();
        }
    }

    private static void reportPrefetchResult(long sessionId, String sha256, boolean success) {
        if (ClientPlayNetworking.canSend(SceneAssetReadyC2S.TYPE)) {
            ClientPlayNetworking.send(new SceneAssetReadyC2S(sessionId, sha256, success));
        }
    }

    private synchronized void enableRuntimeEffects() {
        if (previewActive || currentState == null || !currentState.isActive()) return;
        SceneAmbientSoundController.getInstance().playSound(currentState.getProfile().getOutsideSound());
        SceneShakeController.getInstance().updateSettings(currentState.getProfile().getShake(), true);
    }

    private static void disableRuntimeEffects() {
        SceneAmbientSoundController.getInstance().stopSound();
        SceneShakeController.getInstance().updateSettings(null, false);
    }

    public synchronized boolean isRuntimeMeshReady() {
        return currentState != null && currentState.isActive()
                && currentMeshSet != null && !currentMeshSet.isEmpty() && !currentMeshSet.isClosed()
                && Objects.equals(currentState.getAssetHash(), loadedAssetHash);
    }

    /**
     * Compatibility entrypoint for callers that do not associate the preview with an editor map.
     */
    public synchronized void startPreview(SceneProfile profile, SceneAssetDescriptor descriptor) {
        startPreview("", profile, descriptor);
    }

    public synchronized void startPreview(String mapKey, SceneProfile profile, SceneAssetDescriptor descriptor) {
        this.previewActive = true;
        this.previewMapKey = mapKey != null ? mapKey : "";
        this.previewProfile = profile != null ? profile.copy() : new SceneProfile();
        this.previewAssetHash = descriptor != null && descriptor.isValid() ? descriptor.sha256() : "";
        this.previewStartNanos = System.nanoTime();
        logMotionSetup("预览", this.previewProfile);

        if (descriptor != null && descriptor.isValid() && !Objects.equals(descriptor.sha256(), loadedAssetHash)) {
            manifestsByHash.put(descriptor.sha256(), descriptor);
            loadMesh(descriptor);
        }
    }

    public synchronized void stopPreview() {
        this.previewActive = false;
        this.previewMapKey = "";
        this.previewProfile = null;
        this.previewAssetHash = "";

        if (currentState != null && currentState.isActive()) {
            String officialHash = currentState.getAssetHash();
            if (officialHash != null && !officialHash.isBlank()
                    && !Objects.equals(officialHash, loadedAssetHash)) {
                SceneAssetDescriptor descriptor = manifestsByHash.get(officialHash);
                if (descriptor != null && descriptor.isValid()) {
                    loadMesh(descriptor);
                }
            }
        } else {
            clearMesh();
        }
    }

    public synchronized boolean isPreviewActive() {
        return previewActive;
    }

    /** Returns whether the live preview belongs to the map currently shown by the editor. */
    public synchronized boolean isPreviewActiveFor(String mapKey) {
        return previewActive && Objects.equals(previewMapKey, mapKey != null ? mapKey : "");
    }

    /** Keep an already running preview in sync with values committed by the Save action. */
    public synchronized void updatePreviewProfile(String mapKey, SceneProfile profile) {
        if (!isPreviewActiveFor(mapKey) || profile == null) return;
        SceneProfile updated = profile.copy();
        if (updated.equals(this.previewProfile)) return;
        this.previewProfile = updated;
        logMotionSetup("预览更新", this.previewProfile);
    }

    public synchronized SceneRuntimeState getCurrentState() {
        return currentState;
    }

    public synchronized void render(WorldRenderContext context) {
        try (var stateGuard = com.habitrain.core.client.render.ClientRenderState.capture()) {
        if (context == null || context.camera() == null) return;
        // Capture the projection even when no scene is currently active. This lets the
        // administrator diagnostics page report the matrix the world renderer actually used.
        SceneProjectionDiagnostics.recordProjection(context.projectionMatrix());

        Minecraft mc = Minecraft.getInstance();
        Camera camera = context.camera();
        Vec3 camPos = camera.getPosition();
        Matrix4f viewMatrix = context.positionMatrix();
        Matrix4f projectionMatrix = context.projectionMatrix();
        Frustum frustum = context.frustum();
        float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);

        List<RenderSubmission> submissions = new ArrayList<>();

        boolean active = previewActive || (currentState != null && currentState.isActive());
        if (active) {
            String expectedHash = previewActive ? previewAssetHash : currentState.getAssetHash();
            boolean primaryMeshReady = currentMeshSet != null && !currentMeshSet.isEmpty()
                    && !currentMeshSet.isClosed() && expectedHash != null && !expectedHash.isBlank()
                    && Objects.equals(expectedHash, loadedAssetHash);
            SceneProfile profile = previewActive ? previewProfile : currentState.getProfile();
            if (primaryMeshReady && profile != null && profile.isEnabled()) {
                double elapsedSeconds;
                if (previewActive) {
                    elapsedSeconds = Math.max(0L, System.nanoTime() - previewStartNanos) / 1_000_000_000.0;
                } else {
                    long clientGameTime = mc.level != null ? mc.level.getGameTime() : 0L;
                    elapsedSeconds = currentState.calculateElapsedSeconds(clientGameTime, partialTick);
                }
                collectSceneSubmissions(currentMeshSet, viewMatrix, camPos, frustum,
                        profile, elapsedSeconds, true, submissions);
            }
        }

        if (!previewActive) {
            collectAdditionalScenes(viewMatrix, camPos, frustum, partialTick, submissions);
            collectDynamicInstances(viewMatrix, camPos, frustum, partialTick, submissions);
        }

        if (submissions.isEmpty()) {
            return;
        }

        int frameTotalBatches = 0;
        int frameDrawnBatches = 0;
        long frameCulledBatchBytes = 0L;
        boolean anyBatched = false;

        // 第一阶段：先绘制全部场景实例/副本的不透明与裁切层，防止后绘制副本的不透明面遮挡先绘制副本的半透明面
        for (RenderSubmission sub : submissions) {
            if (sub.mesh().isBatched()) {
                anyBatched = true;
            }
            try (var modelScope = SceneShaders.model(sub.modelMatrix())) {
            BatchStats stats = drawMeshInstanceOpaque(
                    sub.mesh(), sub.modelMatrix(), sub.modelView(), projectionMatrix,
                    camPos, frustum, sub.chunkOffsetX(), sub.chunkOffsetY(), sub.chunkOffsetZ());
            frameTotalBatches += stats.total();
            frameDrawnBatches += stats.drawn();
            frameCulledBatchBytes += stats.culledBytes();
            }
        }
        if (anyBatched) {
            lastTotalBatches = frameTotalBatches;
            lastDrawnBatches = frameDrawnBatches;
            lastCulledBatchBytes = frameCulledBatchBytes;
        }

        // 第二阶段：收集启用了半透明渲染的全部实例/副本，按包围球中心离相机从远到近排序后统一绘制半透明层
        List<RenderSubmission> translucentSubmissions = new ArrayList<>(submissions.size());
        for (RenderSubmission sub : submissions) {
            if (sub.renderTranslucent()) {
                translucentSubmissions.add(sub);
            }
        }
        if (translucentSubmissions.size() > 1) {
            translucentSubmissions.sort(Comparator.comparingDouble((RenderSubmission sub) ->
                    sub.bounds().distanceSquaredTo(camPos.x, camPos.y, camPos.z)).reversed());
        }
        for (RenderSubmission sub : translucentSubmissions) {
            try (var modelScope = SceneShaders.model(sub.modelMatrix())) {
            drawMeshInstanceTranslucent(
                    sub.mesh(), sub.modelView(), projectionMatrix, true,
                    sub.chunkOffsetX(), sub.chunkOffsetY(), sub.chunkOffsetZ());
            }
        }
    
        }
    }

    private void collectSceneSubmissions(SceneMeshSet mesh, Matrix4f viewMatrix, Vec3 camPos,
                                         Frustum frustum, SceneProfile profile, double elapsedSeconds,
                                         boolean recordOrbitMetrics, List<RenderSubmission> out) {
        if (mesh == null || mesh.isEmpty() || mesh.isClosed() || profile == null || !profile.isEnabled()) {
            return;
        }
        if (profile.getMotionMode() == SceneMotionMode.ORBIT) {
            collectOrbitSubmissions(mesh, viewMatrix, camPos, frustum, profile, elapsedSeconds,
                    recordOrbitMetrics, out);
            return;
        }
        if (recordOrbitMetrics) {
            lastConfiguredOrbitInstances = 0;
            lastVisibleOrbitInstances = 0;
        }
        double speed = profile.getSpeedBlocksPerSecond();
        boolean loopEnabled = profile.getLoop().isEnabled();
        double loopDist = SceneMotionMath.effectiveLoopDistance(
                profile.getSourceBounds(), profile.getRotationDegrees(),
                profile.getDirection(), profile.getLoop());
        double phaseOffset = profile.getPhaseOffsetBlocks();
        double phase = SceneMotionMath.phase(loopEnabled, speed, elapsedSeconds, phaseOffset, loopDist);

        double[] dir = profile.getDirection();
        double motionX = dir[0] * phase;
        double motionY = dir[1] * phase;
        double motionZ = dir[2] * phase;

        double[] origin = profile.getDisplayOrigin();
        double[] pivot = profile.getPivotLocal();
        SceneRotation rot = profile.getRotationDegrees();
        boolean renderTranslucent = profile.getRender().isRenderTranslucent();
        double maxDistance = profile.getRender().getMaxDistanceBlocks();

        collectLinearCopy(mesh, viewMatrix, camPos, frustum, profile, origin,
                motionX, motionY, motionZ, 0.0, 0.0, 0.0,
                pivot, rot, renderTranslucent, maxDistance, out);

        if (loopEnabled) {
            collectLinearCopy(mesh, viewMatrix, camPos, frustum, profile, origin,
                    motionX, motionY, motionZ,
                    -dir[0] * loopDist, -dir[1] * loopDist, -dir[2] * loopDist,
                    pivot, rot, renderTranslucent, maxDistance, out);
        }
    }

    /**
     * 收集全部 API 场景实例（无数量上限）。
     *
     * <p>与地图级附加背景的区别：</p>
     * <ul>
     *   <li>这里的主循环<b>不依赖地图级场景是否激活</b>——没有地图场景时也会渲染。</li>
     *   <li>时间轴来自每个实例自己的 {@code startGameTime / timeScale / paused}。</li>
     *   <li>锚点模式会在每帧解析出实际显示原点（切换维度/实体不存在时回退静态原点）。</li>
     *   <li>只渲染属于玩家当前维度的实例；这不是"上限"，而是维度隔离。</li>
     * </ul>
     */
    private void collectDynamicInstances(Matrix4f viewMatrix, Vec3 camPos, Frustum frustum,
                                         float partialTick, List<RenderSubmission> out) {
        List<SceneInstance> ordered = sortedDynamicInstances;
        if (previewActive || ordered.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        String dimensionKey = mc.level.dimension().location().toString();
        long clientGameTime = mc.level.getGameTime();

        for (SceneInstance instance : ordered) {
            if (!instance.belongsTo(dimensionKey)) continue;
            SceneProfile rawProfile = instance.profile();
            if (rawProfile == null || !rawProfile.isEnabled()) continue;
            SceneMeshSet mesh = additionalMeshesByHash.get(instance.assetHash());
            if (mesh == null || mesh.isEmpty() || mesh.isClosed()) continue;

            SceneProfile profile = resolveAnchoredProfile(instance, rawProfile);
            double elapsed = instance.elapsedSeconds(clientGameTime, partialTick);
            collectSceneSubmissions(mesh, viewMatrix, camPos, frustum, profile, elapsed, false, out);
        }
    }

    /**
     * 解析实例的实际显示原点。
     *
     * <p>锚点为世界模式时直接返回原 profile（零分配）；玩家/实体模式按实例 ID 与 revision 缓存一份
     * {@link SceneProfile} 副本并原位更新 {@code displayOrigin}，避免每帧深拷贝 {@code SceneProfile}。</p>
     */
    private SceneProfile resolveAnchoredProfile(SceneInstance instance, SceneProfile profile) {
        SceneInstanceAnchor anchor = instance.anchor();
        if (anchor == null || !anchor.requiresRuntimeResolution()) return profile;
        double[] anchorPos = resolveAnchorPosition(anchor);
        if (anchorPos == null) return profile;
        AnchoredProfileCache cached = anchoredProfiles.get(instance.id());
        SceneProfile copy;
        if (cached != null && cached.revision() == instance.revision() && cached.profile() != null) {
            copy = cached.profile();
        } else {
            copy = profile.copy();
            anchoredProfiles.put(instance.id(), new AnchoredProfileCache(instance.revision(), copy));
        }
        copy.setDisplayOrigin(anchorPos[0] + anchor.offsetX(),
                anchorPos[1] + anchor.offsetY(),
                anchorPos[2] + anchor.offsetZ());
        return copy;
    }

    /** 解析锚点目标的世界坐标；目标不在本地世界时返回 {@code null}（调用方回退静态原点）。 */
    private static double[] resolveAnchorPosition(SceneInstanceAnchor anchor) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (anchor.isPlayer()) {
            var player = mc.level.getPlayerByUUID(anchor.playerId());
            if (player == null) return null;
            return new double[]{player.getX(), player.getY(), player.getZ()};
        }
        if (anchor.isEntity()) {
            var entity = mc.level.getEntity(anchor.entityId());
            if (entity == null) return null;
            return new double[]{entity.getX(), entity.getY(), entity.getZ()};
        }
        return null;
    }

    /**
     * 每个客户端 tick 对齐 API 实例的"持续型效果"：车外环境音与镜头微震。
     *
     * <p>几何是每帧按确定性时间轴算的，不需要 tick；但音效需要"起播/淡出"的边沿语义，
     * 微震是全局限单一通道，两者都放在 tick 里做集合对齐，天然自愈（客户端状态被清空后
     * 下一个 tick 就会重新补上）。</p>
     */
    public synchronized void tickDynamicInstances() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            SceneAmbientSoundController.getInstance().retainOnly(Map.of());
            return;
        }
        String dimensionKey = mc.level.dimension().location().toString();
        Map<String, SceneSoundSettings> desired = new java.util.LinkedHashMap<>();
        SceneShakeSettings bestShake = null;
        for (SceneInstance instance : sortedDynamicInstances) {
            if (!instance.belongsTo(dimensionKey)) continue;
            SceneProfile profile = instance.profile();
            if (profile == null || !profile.isEnabled()) continue;
            SceneSoundSettings sound = profile.getOutsideSound();
            if (sound != null && sound.isEnabled()) {
                desired.put(SceneAmbientSoundController.instanceKey(instance.id()), sound);
            }
            if (profile.getShake() != null && profile.getShake().isEnabled()) {
                // sortedDynamicInstances 已按 priority 升序：取最后一个 = 渲染层级最高者。
                bestShake = profile.getShake();
            }
        }

        // 地图级场景正在提供微震时不动它，避免两个来源互相覆盖。
        boolean primaryOwnsShake = !previewActive && currentState != null && currentState.isActive()
                && currentState.getProfile().getShake().isEnabled();
        if (!primaryOwnsShake) {
            SceneShakeController.getInstance().updateSettings(bestShake, bestShake != null);
        }

        // 维修模式/无实例时清空，其余保持"期望集合"，音轨自身负责淡入淡出。
        SceneAmbientSoundController.getInstance().retainOnly(desired);
    }

    private void collectAdditionalScenes(Matrix4f viewMatrix, Vec3 camPos, Frustum frustum,
                                         float partialTick, List<RenderSubmission> out) {
        if (previewActive || additionalRuntimeStates.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        long clientGameTime = mc.level != null ? mc.level.getGameTime() : 0L;
        for (SceneRuntimeState state : additionalRuntimeStates) {
            SceneProfile profile = state.getProfile();
            SceneMeshSet mesh = additionalMeshesByHash.get(state.getAssetHash());
            if (profile == null || !profile.isEnabled() || mesh == null || mesh.isEmpty() || mesh.isClosed()) continue;
            double elapsed = state.calculateElapsedSeconds(clientGameTime, partialTick);
            collectSceneSubmissions(mesh, viewMatrix, camPos, frustum, profile, elapsed, false, out);
        }
    }

    /**
     * 收集直线运动的一个副本（主副本或循环回绕副本）。
     *
     * <p>裁剪与环绕模式口径一致：先用<b>变换后的包围球</b>与 maxDistance 比距离，再用它的
     * 外接 AABB 做视锥裁剪。此前只拿「场景原点」到相机的距离判断，既看不到视锥（镜头背对
     * 时仍然提交整份网格），又会在「大场景边缘仍可见、原点已很远」时把可见几何过早剔除。</p>
     *
     * <p>注意 {@code renderX/Y/Z} 仍然按原点计算——包围球只参与裁剪，不参与模型矩阵。</p>
     */
    private void collectLinearCopy(SceneMeshSet mesh, Matrix4f viewMatrix, Vec3 camPos, Frustum frustum,
                                   SceneProfile profile, double[] origin, double mx, double my, double mz,
                                   double lx, double ly, double lz,
                                   double[] pivot, SceneRotation rot, boolean renderTranslucent,
                                   double maxDistance, List<RenderSubmission> out) {

        SceneInstanceBounds bounds = SceneMotionMath.calculateLinearInstanceBounds(
                profile, mx + lx, my + ly, mz + lz);
        if (!bounds.isWithinDistance(camPos.x, camPos.y, camPos.z, maxDistance)) return;
        if (frustum != null && !((FrustumAccessor) frustum).habitrain$cubeInFrustum(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            return;
        }

        double renderX = origin[0] + mx + lx - camPos.x;
        double renderY = origin[1] + my + ly - camPos.y;
        double renderZ = origin[2] + mz + lz - camPos.z;

        Matrix4f modelMatrix = buildSceneModelMatrix(
                renderX, renderY, renderZ, pivot, rot, null, 0.0, false);
        Matrix4f modelView = composeModelView(viewMatrix, modelMatrix);

        var sourceBounds = profile.getSourceBounds();
        double sizeX = sourceBounds != null ? sourceBounds.sizeX() : 0.0;
        double sizeY = sourceBounds != null ? sourceBounds.sizeY() : 0.0;
        double sizeZ = sourceBounds != null ? sourceBounds.sizeZ() : 0.0;
        float chunkOffsetX = computeChunkOffset(renderX, bounds.centerX() - camPos.x, sizeX, rot, false, 0.0);
        float chunkOffsetY = computeChunkOffset(renderY, bounds.centerY() - camPos.y, sizeY, rot, false, 0.0);
        float chunkOffsetZ = computeChunkOffset(renderZ, bounds.centerZ() - camPos.z, sizeZ, rot, false, 0.0);

        out.add(new RenderSubmission(mesh, modelMatrix, modelView, bounds, renderTranslucent,
                chunkOffsetX, chunkOffsetY, chunkOffsetZ));
    }

    private static float computeChunkOffset(double renderAxis, double relativeCenterAxis,
                                            double localSizeAxis,
                                            SceneRotation rot, boolean rotateWithOrbit, double orbitDeltaDegrees) {
        boolean hasRotation = (rot != null && !rot.isZero())
                || (rotateWithOrbit && Math.abs(orbitDeltaDegrees) > 1.0e-4);
        if (!hasRotation) {
            return (float) renderAxis;
        }
        double localCenterAxis = 0.5 * Math.max(0.0, localSizeAxis);
        return (float) (relativeCenterAxis - localCenterAxis);
    }

    private record BatchStats(int total, int drawn, long culledBytes) {}

    private BatchStats drawMeshInstanceOpaque(SceneMeshSet mesh, Matrix4f modelMatrix, Matrix4f modelView,
                                              Matrix4f projMat, Vec3 camPos, Frustum frustum,
                                              float chunkOffsetX, float chunkOffsetY, float chunkOffsetZ) {
        if (mesh == null) return new BatchStats(0, 0, 0L);
        if (mesh.isBatched()) {
            int total = 0;
            int drawn = 0;
            long culledBytes = 0L;
            for (SceneMeshSet.Batch batch : mesh.batches()) {
                total++;
                if (!isBatchInFrustum(batch, modelMatrix, camPos, frustum)) {
                    culledBytes += batch.mesh().estimatedBytes();
                    continue;
                }
                drawn++;
                float bCenterX = (float) batch.centerX();
                float bCenterY = (float) batch.centerY();
                float bCenterZ = (float) batch.centerZ();
                Vector3f batchCenterWorld = new Vector3f(bCenterX, bCenterY, bCenterZ);
                modelMatrix.transformPosition(batchCenterWorld);
                float batchOffsetX = batchCenterWorld.x - bCenterX;
                float batchOffsetY = batchCenterWorld.y - bCenterY;
                float batchOffsetZ = batchCenterWorld.z - bCenterZ;

                SceneMeshSet batchMesh = batch.mesh();
                batchMesh.renderLayer(SceneMeshSet.Layer.SOLID, modelView, projMat, RenderType.solid(),
                        batchOffsetX, batchOffsetY, batchOffsetZ);
                batchMesh.renderLayer(SceneMeshSet.Layer.CUTOUT_MIPPED, modelView, projMat, RenderType.cutoutMipped(),
                        batchOffsetX, batchOffsetY, batchOffsetZ);
                batchMesh.renderLayer(SceneMeshSet.Layer.CUTOUT, modelView, projMat, RenderType.cutout(),
                        batchOffsetX, batchOffsetY, batchOffsetZ);
                batchMesh.renderCustomOpaqueBatches(modelView, projMat,
                        batchOffsetX, batchOffsetY, batchOffsetZ);
            }
            return new BatchStats(total, drawn, culledBytes);
        }
        mesh.renderLayer(SceneMeshSet.Layer.SOLID, modelView, projMat, RenderType.solid(),
                chunkOffsetX, chunkOffsetY, chunkOffsetZ);
        mesh.renderLayer(SceneMeshSet.Layer.CUTOUT_MIPPED, modelView, projMat, RenderType.cutoutMipped(),
                chunkOffsetX, chunkOffsetY, chunkOffsetZ);
        mesh.renderLayer(SceneMeshSet.Layer.CUTOUT, modelView, projMat, RenderType.cutout(),
                chunkOffsetX, chunkOffsetY, chunkOffsetZ);
        mesh.renderCustomOpaqueBatches(modelView, projMat,
                chunkOffsetX, chunkOffsetY, chunkOffsetZ);
        return new BatchStats(0, 0, 0L);
    }

    private void drawMeshInstanceTranslucent(SceneMeshSet mesh, Matrix4f modelView,
                                             Matrix4f projMat, boolean renderTranslucent,
                                             float chunkOffsetX, float chunkOffsetY, float chunkOffsetZ) {
        if (mesh == null || !renderTranslucent) return;
        mesh.renderLayer(SceneMeshSet.Layer.TRANSLUCENT, modelView, projMat, RenderType.translucent(),
                chunkOffsetX, chunkOffsetY, chunkOffsetZ);
        mesh.renderCustomTranslucentBatches(modelView, projMat,
                chunkOffsetX, chunkOffsetY, chunkOffsetZ);
    }

    /**
     * 批次的世界 AABB 是否与视锥相交。
     *
     * <p>{@code modelMatrix} 把本地坐标映射到<b>相机相对</b>坐标，所以先变换再补回相机位置，
     * 得到 frustum 期望的世界坐标。8 个角点全部参与：场景可以带枢轴旋转，只取两个对角点会
     * 在旋转后得到错误的包围盒。</p>
     */
    private static boolean isBatchInFrustum(SceneMeshSet.Batch batch, Matrix4f modelMatrix,
                                            Vec3 camPos, Frustum frustum) {
        if (frustum == null) return true;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            Vector3f point = new Vector3f(
                    (corner & 1) == 0 ? (float) batch.minX() : (float) batch.maxX(),
                    (corner & 2) == 0 ? (float) batch.minY() : (float) batch.maxY(),
                    (corner & 4) == 0 ? (float) batch.minZ() : (float) batch.maxZ());
            modelMatrix.transformPosition(point);
            double x = point.x + camPos.x;
            double y = point.y + camPos.y;
            double z = point.z + camPos.z;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        return ((FrustumAccessor) frustum).habitrain$cubeInFrustum(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void collectOrbitSubmissions(SceneMeshSet mesh, Matrix4f viewMatrix, Vec3 camPos,
                                         Frustum frustum, SceneProfile profile, double elapsedSeconds,
                                         boolean recordOrbitMetrics, List<RenderSubmission> out) {
        SceneOrbitSettings orbit = profile.getOrbit();
        double[] pivot = profile.getPivotLocal();
        SceneRotation rot = profile.getRotationDegrees();
        boolean renderTranslucent = profile.getRender().isRenderTranslucent();
        double maxDistance = profile.getRender().getMaxDistanceBlocks();

        int count = Math.max(SceneOrbitSettings.MIN_INSTANCES,
                Math.min(SceneOrbitSettings.MAX_INSTANCES, orbit.getInstanceCount()));
        if (recordOrbitMetrics) {
            lastConfiguredOrbitInstances = count;
        }
        var sourceBounds = profile.getSourceBounds();
        double sizeX = sourceBounds != null ? sourceBounds.sizeX() : 0.0;
        double sizeY = sourceBounds != null ? sourceBounds.sizeY() : 0.0;
        double sizeZ = sourceBounds != null ? sourceBounds.sizeZ() : 0.0;
        int visibleCount = 0;
        for (int i = 0; i < count; i++) {
            SceneInstanceTransform transform = SceneOrbitMath.calculateInstanceTransform(profile, elapsedSeconds, i);
            SceneInstanceBounds bounds = SceneOrbitMath.calculateInstanceBounds(profile, transform);
            if (!bounds.isWithinDistance(camPos.x, camPos.y, camPos.z, maxDistance)) continue;
            if (frustum != null && !((FrustumAccessor) frustum).habitrain$cubeInFrustum(
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                continue;
            }
            visibleCount++;

            double[] pos = transform.position();
            double renderX = pos[0] - camPos.x;
            double renderY = pos[1] - camPos.y;
            double renderZ = pos[2] - camPos.z;

            Matrix4f modelMatrix = buildSceneModelMatrix(
                    renderX, renderY, renderZ, pivot, rot,
                    transform.axis(), transform.deltaAngleDegrees(), transform.rotateModelWithOrbit());
            Matrix4f modelView = composeModelView(viewMatrix, modelMatrix);

            float chunkOffsetX = computeChunkOffset(renderX, bounds.centerX() - camPos.x, sizeX,
                    rot, transform.rotateModelWithOrbit(), transform.deltaAngleDegrees());
            float chunkOffsetY = computeChunkOffset(renderY, bounds.centerY() - camPos.y, sizeY,
                    rot, transform.rotateModelWithOrbit(), transform.deltaAngleDegrees());
            float chunkOffsetZ = computeChunkOffset(renderZ, bounds.centerZ() - camPos.z, sizeZ,
                    rot, transform.rotateModelWithOrbit(), transform.deltaAngleDegrees());

            out.add(new RenderSubmission(mesh, modelMatrix, modelView, bounds, renderTranslucent,
                    chunkOffsetX, chunkOffsetY, chunkOffsetZ));
        }

        if (recordOrbitMetrics) {
            lastVisibleOrbitInstances = visibleCount;
        }
    }

    private record RenderSubmission(
            SceneMeshSet mesh,
            Matrix4f modelMatrix,
            Matrix4f modelView,
            SceneInstanceBounds bounds,
            boolean renderTranslucent,
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {}

    private static void applyOrbitRotation(PoseStack poseStack, SceneOrbitAxis axis, double deltaDegrees) {
        if (Math.abs(deltaDegrees) < 1.0e-5) return;
        switch (axis) {
            case Y -> poseStack.mulPose(Axis.YP.rotationDegrees((float) -deltaDegrees));
            case X -> poseStack.mulPose(Axis.XP.rotationDegrees((float) deltaDegrees));
            case Z -> poseStack.mulPose(Axis.ZP.rotationDegrees((float) -deltaDegrees));
        }
    }

    static Matrix4f composeModelView(Matrix4f viewMatrix, Matrix4f modelMatrix) {
        return new Matrix4f(viewMatrix).mul(modelMatrix);
    }

    static Matrix4f buildSceneModelMatrix(double x, double y, double z,
                                          double[] pivot, SceneRotation rotation,
                                          SceneOrbitAxis orbitAxis, double orbitDeltaDegrees,
                                          boolean rotateWithOrbit) {
        PoseStack poseStack = new PoseStack();
        poseStack.translate(x, y, z);
        if (rotateWithOrbit && Math.abs(orbitDeltaDegrees) > 1.0e-4) {
            applyOrbitRotation(poseStack,
                    orbitAxis != null ? orbitAxis : SceneOrbitAxis.Y, orbitDeltaDegrees);
        }

        double px = pivot != null && pivot.length > 0 ? pivot[0] : 0.0;
        double py = pivot != null && pivot.length > 1 ? pivot[1] : 0.0;
        double pz = pivot != null && pivot.length > 2 ? pivot[2] : 0.0;
        if (px != 0.0 || py != 0.0 || pz != 0.0) {
            poseStack.translate(px, py, pz);
            applyRotation(poseStack, rotation);
            poseStack.translate(-px, -py, -pz);
        } else {
            applyRotation(poseStack, rotation);
        }
        return new Matrix4f(poseStack.last().pose());
    }

    private static void applyRotation(PoseStack poseStack, SceneRotation rot) {
        if (rot == null) return;
        if (rot.yawDegrees() != 0) {
            poseStack.mulPose(Axis.YP.rotationDegrees((float) rot.yawDegrees()));
        }
        if (rot.pitchDegrees() != 0) {
            poseStack.mulPose(Axis.XP.rotationDegrees((float) rot.pitchDegrees()));
        }
        if (rot.rollDegrees() != 0) {
            poseStack.mulPose(Axis.ZP.rotationDegrees((float) rot.rollDegrees()));
        }
    }

    private void logMotionSetup(String mode, SceneProfile profile) {
        if (profile == null) return;
        double configured = profile.getLoop().getDistanceBlocks();
        double effective = SceneMotionMath.effectiveLoopDistance(
                profile.getSourceBounds(), profile.getRotationDegrees(),
                profile.getDirection(), profile.getLoop());
        double[] origin = profile.getDisplayOrigin();
        double[] direction = profile.getDirection();
        double[] pivot = profile.getPivotLocal();
        SceneRotation rotation = profile.getRotationDegrees();
        LOGGER.info("{}参数: enabled={}, origin=({},{},{}), direction=({},{},{}), speed={}, "
                        + "motionMode={}, pivot=({},{},{}), rotation=(yaw={},pitch={},roll={}), "
                        + "loop={}, distanceMode={}, configuredDistance={}, effectiveDistance={}, bounds={}",
                mode, profile.isEnabled(), format(origin[0]), format(origin[1]), format(origin[2]),
                format(direction[0]), format(direction[1]), format(direction[2]),
                format(profile.getSpeedBlocksPerSecond()), profile.getMotionMode(),
                format(pivot[0]), format(pivot[1]), format(pivot[2]),
                format(rotation.yawDegrees()), format(rotation.pitchDegrees()),
                format(rotation.rollDegrees()), profile.getLoop().isEnabled(),
                profile.getLoop().getDistanceMode(),
                format(configured), format(effective), profile.getSourceBounds());
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    public synchronized void onResourceReload() {
        LOGGER.info("收到客户端资源重载，重构场景网格与材质批次");
        SceneMaterialKey.clearRenderTypeCache();
        meshLoadGeneration++;
        additionalMeshGeneration++;
        additionalLoadTickets.clear();
        Set<String> prefetchHashes = new HashSet<>(additionalPrefetchWaiters.keySet());
        Set<String> primaryPrefetchHashes = primaryPrefetchWaiters.suspend();

        // The in-flight vote asset must survive even when an older map's mesh is still displayed.
        String toReload = primaryPrefetchHashes.contains(preparingAssetHash) ? preparingAssetHash
                : (!loadedAssetHash.isBlank() ? loadedAssetHash
                : (!preparedAssetHash.isBlank() ? preparedAssetHash
                : (!preparingAssetHash.isBlank() ? preparingAssetHash
                : (previewActive ? previewAssetHash
                : (currentState != null ? currentState.getAssetHash() : "")))));

        clearMesh();

        additionalMeshGeneration++;
        additionalLoadTickets.clear();
        for (CompletableFuture<SceneMeshBuilder.MeshBuildResult> f : additionalBuildFutures.values()) {
            f.cancel(false);
        }
        additionalBuildFutures.clear();
        additionalLoadingHashes.clear();
        additionalMeshesByHash.values().forEach(SceneMeshSet::close);
        additionalMeshesByHash.clear();
        additionalMeshUseSeq.clear();

        SceneBuildScheduler.getInstance().reset();

        if (toReload != null && !toReload.isBlank()) {
            SceneAssetDescriptor descriptor = manifestsByHash.get(toReload);
            if (descriptor != null && descriptor.isValid()) {
                loadMesh(descriptor);
            }
        }
        for (String hash : primaryPrefetchHashes) {
            SceneAssetDescriptor descriptor = manifestsByHash.get(hash);
            if (descriptor != null && descriptor.isValid()) {
                primaryPrefetchWaiters.resume(hash, prepareMesh(descriptor));
            } else {
                primaryPrefetchWaiters.fail(hash);
            }
        }
        // 地图级附加背景与 API 实例引用的资产统一重建
        reconcileWantedMeshes();
        for (String prefetchHash : prefetchHashes) {
            if (additionalLoadingHashes.contains(prefetchHash)) continue;
            SceneAssetDescriptor descriptor = manifestsByHash.get(prefetchHash);
            if (descriptor != null && descriptor.isValid()) {
                loadAdditionalMesh(descriptor);
            } else {
                reportAdditionalPrefetchWaiters(prefetchHash, false);
            }
        }
        if (!previewActive && currentState != null && currentState.isActive()) {
            enableRuntimeEffects();
        }
    }

    public synchronized void clearMesh() {
        meshLoadGeneration++;
        var build = preparingBuildFuture;
        var result = preparingFuture;
        SceneMeshSet current = currentMeshSet, prepared = preparedMeshSet;
        preparingBuildFuture = null;
        preparingFuture = null;
        currentMeshSet = null;
        preparedMeshSet = null;
        preparedAssetHash = "";
        preparingAssetHash = "";
        loadedAssetHash = "";
        if (current != null) current.close();
        if (prepared != null) prepared.close();
        // Complete callbacks only after the old ownership has been fully detached.
        if (build != null) build.cancel(false);
        if (result != null) result.complete(false);
    }

    public synchronized void reset() {
        lastServerProtocolVersion = com.habitrain.core.scene.network.SceneProtocol.LEGACY_VERSION;
        previewActive = false;
        previewMapKey = "";
        previewProfile = null;
        previewAssetHash = "";
        currentState = SceneRuntimeState.INACTIVE;
        additionalRuntimeStates = List.of();
        dynamicInstances.clear();
        sortedDynamicInstances = List.of();
        anchoredProfiles.clear();
        SceneAmbientSoundController.getInstance().stopAllSounds();
        additionalMeshGeneration++;
        additionalLoadTickets.clear();
        for (CompletableFuture<SceneMeshBuilder.MeshBuildResult> f : additionalBuildFutures.values()) {
            f.cancel(false);
        }
        additionalBuildFutures.clear();
        additionalLoadingHashes.clear();
        for (String hash : new HashSet<>(additionalPrefetchWaiters.keySet())) {
            reportAdditionalPrefetchWaiters(hash, false);
        }
        additionalPrefetchWaiters.clear();
        additionalMeshesByHash.values().forEach(SceneMeshSet::close);
        additionalMeshesByHash.clear();
        additionalMeshUseSeq.clear();
        lastConfiguredOrbitInstances = 0;
        lastVisibleOrbitInstances = 0;
        manifestsByHash.clear();
        manifestsByMap.clear();
        compatibilityReportsByHash.clear();
        primaryPrefetchWaiters.clear();
        clearMesh();
    }

    private static String shortHash(String hash) {
        return hash == null ? "" : hash.substring(0, Math.min(8, hash.length()));
    }
}
