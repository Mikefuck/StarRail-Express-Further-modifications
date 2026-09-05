package com.habitrain.core.scene.client;

import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.client.mixin.FrustumAccessor;
import com.habitrain.core.scene.model.SceneInstanceBounds;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneMotionMath;
import com.habitrain.core.scene.model.SceneMotionMode;
import com.habitrain.core.scene.model.SceneOrbitAxis;
import com.habitrain.core.scene.model.SceneOrbitMath;
import com.habitrain.core.scene.model.SceneOrbitSettings;
import com.habitrain.core.scene.model.SceneInstanceTransform;
import com.habitrain.core.scene.model.SceneRotation;
import com.habitrain.core.scene.model.SceneRuntimeState;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端移动场景运行时渲染器（根据零Tick网络同步的开局时间戳与位移参数在客户端平滑计算位置并渲染两份无缝循环副本）。
 */
public final class SceneRenderRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneRenderRuntime.class.getSimpleName());

    private static final SceneRenderRuntime INSTANCE = new SceneRenderRuntime();

    public static SceneRenderRuntime getInstance() {
        return INSTANCE;
    }

    private SceneRuntimeState currentState = SceneRuntimeState.INACTIVE;
    private SceneMeshSet currentMeshSet = null;
    private String loadedAssetHash = "";
    private SceneMeshSet preparedMeshSet = null;
    private String preparedAssetHash = "";
    private String preparingAssetHash = "";
    private CompletableFuture<Boolean> preparingFuture = null;
    private final Map<String, SceneAssetDescriptor> manifestsByHash = new ConcurrentHashMap<>();
    private final Map<String, SceneAssetDescriptor> manifestsByMap = new ConcurrentHashMap<>();
    private final Map<String, SceneCompatibilityReport> compatibilityReportsByHash = new ConcurrentHashMap<>();
    private List<SceneRuntimeState> additionalRuntimeStates = List.of();
    private final Map<String, SceneMeshSet> additionalMeshesByHash = new ConcurrentHashMap<>();
    private final java.util.Set<String> additionalLoadingHashes = ConcurrentHashMap.newKeySet();
    private final Map<String, List<Long>> additionalPrefetchWaiters = new ConcurrentHashMap<>();
    private long additionalMeshGeneration = 0L;
    private long meshLoadGeneration = 0L;
    private volatile SceneCompatibilityReport lastCompatibilityReport = new SceneCompatibilityReport();
    private volatile com.habitrain.core.scene.model.ScenePublishPolicy publishPolicy = com.habitrain.core.scene.model.ScenePublishPolicy.STRICT;

    // 预览模式覆盖
    private boolean previewActive = false;
    private String previewMapKey = "";
    private SceneProfile previewProfile = null;
    private String previewAssetHash = "";
    private long previewStartNanos = 0L;
    private volatile int lastConfiguredOrbitInstances = 0;
    private volatile int lastVisibleOrbitInstances = 0;

    private SceneRenderRuntime() {}

    public SceneCompatibilityReport getLastCompatibilityReport() {
        return lastCompatibilityReport;
    }

    public com.habitrain.core.scene.model.ScenePublishPolicy getPublishPolicy() {
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

    public void setPublishPolicy(com.habitrain.core.scene.model.ScenePublishPolicy policy) {
        this.publishPolicy = policy != null ? policy : com.habitrain.core.scene.model.ScenePublishPolicy.STRICT;
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

    /** 保存服务端权威 Manifest，并协调 Manifest/Runtime 任意到达顺序。 */
    public synchronized void acceptManifest(String mapKey, SceneAssetDescriptor descriptor) {
        if (descriptor == null || !descriptor.isValid()) return;
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
        java.util.Set<String> wanted = this.additionalRuntimeStates.stream()
                .map(SceneRuntimeState::getAssetHash).filter(hash -> hash != null && !hash.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        additionalMeshesByHash.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
        for (String hash : wanted) {
            SceneAssetDescriptor descriptor = manifestsByHash.get(hash);
            if (descriptor != null && descriptor.isValid()) loadAdditionalMesh(descriptor);
        }
    }

    private boolean isWantedAdditionalHash(String hash) {
        return hash != null && additionalRuntimeStates.stream()
                .anyMatch(state -> Objects.equals(hash, state.getAssetHash()));
    }

    private void loadAdditionalMesh(SceneAssetDescriptor descriptor) {
        String hash = descriptor.sha256();
        if (additionalMeshesByHash.containsKey(hash) || !additionalLoadingHashes.add(hash)) return;
        long generation = additionalMeshGeneration;
        SceneAssetCache.getInstance().getOrFetchAsset(descriptor, assetData -> {
            if (assetData == null) {
                synchronized (this) {
                    additionalLoadingHashes.remove(hash);
                    reportAdditionalPrefetchWaiters(hash, false);
                }
                return;
            }
            SceneMeshBuilder.buildMeshWithReportAsync(assetData).thenAccept(result -> {
                synchronized (this) {
                    additionalLoadingHashes.remove(hash);
                    SceneMeshSet mesh = result != null ? result.meshSet() : null;
                    SceneCompatibilityReport report = result != null ? result.report() : null;
                    boolean fatal = report != null && report.hasBlockingIssues(
                            com.habitrain.core.scene.model.ScenePublishPolicy.SKIP_AND_WARN);
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
                    LOGGER.info("已启用附加动态背景 GPU 网格: hash={}", shortHash(hash));
                    reportAdditionalPrefetchWaiters(hash, true);
                }
            });
        });
    }

    /** Metadata lookup for the editor; this does not trigger an asset download. */
    public synchronized SceneAssetDescriptor getManifest(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) return SceneAssetDescriptor.EMPTY;
        SceneAssetDescriptor descriptor = manifestsByMap.get(mapKey);
        if (descriptor != null && descriptor.isValid()) return descriptor;
        if (com.habitrain.core.scene.model.SceneBackgroundKey.isDefault(
                com.habitrain.core.scene.model.SceneBackgroundKey.backgroundIdFromAssetKey(mapKey))) {
            String bare = com.habitrain.core.scene.model.SceneBackgroundKey.mapKeyFromAssetKey(mapKey);
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
        if (!com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID.equals(
                com.habitrain.core.scene.model.SceneBackgroundKey.backgroundIdFromAssetKey(mapKey))) {
            prefetchAdditionalAsset(sessionId, mapKey, descriptor);
            return;
        }
        CompletableFuture<Boolean> future;
        synchronized (this) {
            manifestsByHash.put(descriptor.sha256(), descriptor);
            if (mapKey != null && !mapKey.isBlank()) manifestsByMap.put(mapKey, descriptor);
            future = prepareMesh(descriptor);
        }
        future.thenAccept(success -> reportPrefetchResult(sessionId, descriptor.sha256(), success));
    }

    private void prefetchAdditionalAsset(long sessionId, String assetKey, SceneAssetDescriptor descriptor) {
        String hash = descriptor.sha256();
        synchronized (this) {
            manifestsByHash.put(hash, descriptor);
            if (assetKey != null && !assetKey.isBlank()) manifestsByMap.put(assetKey, descriptor);
            SceneMeshSet ready = additionalMeshesByHash.get(hash);
            if (ready != null && !ready.isEmpty() && !ready.isClosed()) {
                reportPrefetchResult(sessionId, hash, true);
                return;
            }
            additionalPrefetchWaiters.computeIfAbsent(hash, ignored -> new ArrayList<>()).add(sessionId);
            if (!additionalLoadingHashes.add(hash)) {
                return;
            }
        }
        long generation;
        synchronized (this) { generation = additionalMeshGeneration; }
        SceneAssetCache.getInstance().getOrFetchAsset(descriptor, assetData -> {
            if (assetData == null) {
                synchronized (this) {
                    additionalLoadingHashes.remove(hash);
                    reportAdditionalPrefetchWaiters(hash, false);
                }
                return;
            }
            SceneMeshBuilder.buildMeshWithReportAsync(assetData).thenAccept(result -> {
                boolean success;
                synchronized (this) {
                    additionalLoadingHashes.remove(hash);
                    SceneMeshSet mesh = result != null ? result.meshSet() : null;
                    SceneCompatibilityReport report = result != null ? result.report() : null;
                    boolean fatal = report != null && report.hasBlockingIssues(
                            com.habitrain.core.scene.model.ScenePublishPolicy.SKIP_AND_WARN);
                    success = generation == additionalMeshGeneration && mesh != null
                            && !mesh.isEmpty() && !mesh.isClosed() && !fatal;
                    if (success) {
                        if (report != null) compatibilityReportsByHash.put(hash, report);
                        SceneMeshSet previous = additionalMeshesByHash.put(hash, mesh);
                        if (previous != null && previous != mesh) previous.close();
                    } else if (mesh != null) {
                        mesh.close();
                    }
                }
                synchronized (this) { reportAdditionalPrefetchWaiters(hash, success); }
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
        com.habitrain.core.scene.model.ScenePublishPolicy inspectionPolicy = publishPolicy;
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
            com.habitrain.core.scene.model.ScenePublishPolicy policy,
            SceneCompatibilityReport report
    ) {
        public boolean isPolicyOnlyFailure() {
            return meshReady && !accepted
                    && policy == com.habitrain.core.scene.model.ScenePublishPolicy.STRICT
                    && report != null
                    && report.isCompatible(com.habitrain.core.scene.model.ScenePublishPolicy.SKIP_AND_WARN);
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

        if (preparedMeshSet != null) preparedMeshSet.close();
        preparedMeshSet = null;
        preparedAssetHash = "";
        final long generation = ++meshLoadGeneration;
        preparingAssetHash = sha256;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        preparingFuture = result;

        SceneAssetCache.getInstance().getOrFetchAsset(descriptor, assetData -> {
            if (assetData == null) {
                finishPrepare(generation, sha256, null, result);
                return;
            }
            SceneMeshBuilder.buildMeshWithReportAsync(assetData)
                    .thenAccept(buildResult -> finishPrepare(generation, sha256, buildResult, result));
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
                    com.habitrain.core.scene.model.ScenePublishPolicy.SKIP_AND_WARN);
            if (hasFatalMaterialIssue) {
                LOGGER.warn("拒绝加载存在严重材质问题的场景资产: hash={}, blocking={}",
                        shortHash(sha256), report.getBlockingIssues(
                                com.habitrain.core.scene.model.ScenePublishPolicy.SKIP_AND_WARN).size());
            }
            success = meshSet != null && !meshSet.isEmpty() && !meshSet.isClosed()
                    && !hasFatalMaterialIssue;
            if (success) {
                preparedMeshSet = meshSet;
                preparedAssetHash = sha256;
                boolean wantedByPreview = previewActive && Objects.equals(previewAssetHash, sha256);
                boolean wantedByRuntime = !previewActive && currentState.isActive()
                        && Objects.equals(currentState.getAssetHash(), sha256);
                if (wantedByPreview || wantedByRuntime) activatePreparedMesh(sha256);
            } else if (meshSet != null) {
                meshSet.close();
            }
        }
        result.complete(success);
    }

    private static void logCompatibilityIssues(
            String sha256,
            SceneCompatibilityReport report,
            com.habitrain.core.scene.model.ScenePublishPolicy policy) {
        if (report == null || report.getIssues().isEmpty()) return;
        java.util.List<SceneCompatibilityReport.Entry> blocking = report.getBlockingIssues(policy);
        LOGGER.warn("场景资产兼容性诊断: hash={}, policy={}, issues={}, blocking={}",
                shortHash(sha256), policy, report.getIssues().size(), blocking.size());
        int limit = Math.min(16, report.getIssues().size());
        for (int i = 0; i < limit; i++) {
            SceneCompatibilityReport.Entry issue = report.getIssues().get(i);
            LOGGER.warn("场景兼容问题[{}/{}]: block={}, localPos={}, type={}, blocking={}, detail={}",
                    i + 1, report.getIssues().size(), issue.blockId(), issue.localPos(),
                    issue.issueType(), issue.isBlocking(policy), issue.description());
        }
        if (report.getIssues().size() > limit) {
            LOGGER.warn("另有 {} 条场景兼容问题未逐条写入日志", report.getIssues().size() - limit);
        }
    }

    private synchronized void activatePreparedMesh(String sha256) {
        if (!Objects.equals(sha256, preparedAssetHash) || preparedMeshSet == null) return;
        if (currentMeshSet != null && currentMeshSet != preparedMeshSet) currentMeshSet.close();
        currentMeshSet = preparedMeshSet;
        loadedAssetHash = sha256;
        preparedMeshSet = null;
        preparedAssetHash = "";
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
        // Capture the projection even when no scene is currently active. This lets the
        // administrator diagnostics page report the matrix the world renderer actually used.
        SceneProjectionDiagnostics.recordProjection(context.projectionMatrix());
        boolean active = previewActive || (currentState != null && currentState.isActive());
        if (!active) {
            return;
        }
        String expectedHash = previewActive ? previewAssetHash : currentState.getAssetHash();
        boolean primaryMeshReady = currentMeshSet != null && !currentMeshSet.isEmpty()
                && !currentMeshSet.isClosed() && expectedHash != null && !expectedHash.isBlank()
                && Objects.equals(expectedHash, loadedAssetHash);
        if (!primaryMeshReady) {
            if (!previewActive) {
                Camera camera = context.camera();
                float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
                renderAdditionalScenes(context, context.positionMatrix(), context.projectionMatrix(),
                        camera.getPosition(), partialTick);
            }
            return;
        }

        SceneProfile profile = previewActive ? previewProfile : currentState.getProfile();
        if (profile == null || !profile.isEnabled()) {
            if (!previewActive) {
                Camera camera = context.camera();
                float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
                renderAdditionalScenes(context, context.positionMatrix(), context.projectionMatrix(),
                        camera.getPosition(), partialTick);
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Camera camera = context.camera();
        Vec3 camPos = camera.getPosition();
        Matrix4f viewMatrix = context.positionMatrix();
        Matrix4f projectionMatrix = context.projectionMatrix();

        float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
        double elapsedSeconds;

        if (previewActive) {
            elapsedSeconds = Math.max(0L, System.nanoTime() - previewStartNanos) / 1_000_000_000.0;
        } else {
            long clientGameTime = mc.level != null ? mc.level.getGameTime() : 0L;
            elapsedSeconds = currentState.calculateElapsedSeconds(clientGameTime, partialTick);
        }

        if (profile.getMotionMode() == SceneMotionMode.ORBIT) {
            renderOrbitScene(context, viewMatrix, projectionMatrix, camPos, profile, elapsedSeconds);
            renderAdditionalScenes(context, viewMatrix, projectionMatrix, camPos, partialTick);
            return;
        }
        lastConfiguredOrbitInstances = 0;
        lastVisibleOrbitInstances = 0;

        double speed = profile.getSpeedBlocksPerSecond();
        boolean loopEnabled = profile.getLoop().isEnabled();
        double loopDist = SceneMotionMath.effectiveLoopDistance(
                profile.getSourceBounds(), profile.getDirection(), profile.getLoop());
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

        // 渲染 2 份循环副本
        double maxDistance = profile.getRender().getMaxDistanceBlocks();
        renderCopy(viewMatrix, projectionMatrix, camPos, origin, motionX, motionY, motionZ,
                0, 0, 0, pivot, rot, renderTranslucent, maxDistance);

        if (loopEnabled) {
            double loopOffsetX = -dir[0] * loopDist;
            double loopOffsetY = -dir[1] * loopDist;
            double loopOffsetZ = -dir[2] * loopDist;
            renderCopy(viewMatrix, projectionMatrix, camPos, origin, motionX, motionY, motionZ,
                    loopOffsetX, loopOffsetY, loopOffsetZ, pivot, rot, renderTranslucent, maxDistance);
        }
        renderAdditionalScenes(context, viewMatrix, projectionMatrix, camPos, partialTick);
    }

    private void renderAdditionalScenes(WorldRenderContext context, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                        Vec3 camPos, float partialTick) {
        if (previewActive || additionalRuntimeStates.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        long clientGameTime = mc.level != null ? mc.level.getGameTime() : 0L;
        SceneMeshSet primaryMesh = currentMeshSet;
        try {
            for (SceneRuntimeState state : additionalRuntimeStates) {
                SceneProfile profile = state.getProfile();
                SceneMeshSet mesh = additionalMeshesByHash.get(state.getAssetHash());
                if (profile == null || !profile.isEnabled() || mesh == null || mesh.isEmpty() || mesh.isClosed()) continue;
                currentMeshSet = mesh;
                double elapsed = state.calculateElapsedSeconds(clientGameTime, partialTick);
                if (profile.getMotionMode() == SceneMotionMode.ORBIT) {
                    renderOrbitScene(context, viewMatrix, projectionMatrix, camPos, profile, elapsed);
                    continue;
                }
                double loopDistance = SceneMotionMath.effectiveLoopDistance(
                        profile.getSourceBounds(), profile.getDirection(), profile.getLoop());
                double phase = SceneMotionMath.phase(profile.getLoop().isEnabled(),
                        profile.getSpeedBlocksPerSecond(), elapsed, profile.getPhaseOffsetBlocks(), loopDistance);
                double[] direction = profile.getDirection();
                double[] origin = profile.getDisplayOrigin();
                double[] pivot = profile.getPivotLocal();
                SceneRotation rotation = profile.getRotationDegrees();
                boolean translucent = profile.getRender().isRenderTranslucent();
                double maxDistance = profile.getRender().getMaxDistanceBlocks();
                renderCopy(viewMatrix, projectionMatrix, camPos, origin,
                        direction[0] * phase, direction[1] * phase, direction[2] * phase,
                        0, 0, 0, pivot, rotation, translucent, maxDistance);
                if (profile.getLoop().isEnabled()) {
                    renderCopy(viewMatrix, projectionMatrix, camPos, origin,
                            direction[0] * phase, direction[1] * phase, direction[2] * phase,
                            -direction[0] * loopDistance, -direction[1] * loopDistance,
                            -direction[2] * loopDistance, pivot, rotation, translucent, maxDistance);
                }
            }
        } finally {
            currentMeshSet = primaryMesh;
        }
    }

    private void renderCopy(Matrix4f viewMatrix, Matrix4f projMat, Vec3 camPos,
                            double[] origin, double mx, double my, double mz,
                            double lx, double ly, double lz,
                            double[] pivot, SceneRotation rot, boolean renderTranslucent,
                            double maxDistance) {

        double renderX = origin[0] + mx + lx - camPos.x;
        double renderY = origin[1] + my + ly - camPos.y;
        double renderZ = origin[2] + mz + lz - camPos.z;
        if (renderX * renderX + renderY * renderY + renderZ * renderZ > maxDistance * maxDistance) return;

        Matrix4f modelMatrix = buildSceneModelMatrix(
                renderX, renderY, renderZ, pivot, rot, null, 0.0, false);
        Matrix4f modelView = composeModelView(viewMatrix, modelMatrix);

        // 绘制各图层
        currentMeshSet.renderLayer(SceneMeshSet.Layer.SOLID, modelView, projMat, RenderType.solid());
        currentMeshSet.renderLayer(SceneMeshSet.Layer.CUTOUT_MIPPED, modelView, projMat, RenderType.cutoutMipped());
        currentMeshSet.renderLayer(SceneMeshSet.Layer.CUTOUT, modelView, projMat, RenderType.cutout());
        currentMeshSet.renderCustomOpaqueBatches(modelView, projMat);

        if (renderTranslucent) {
            currentMeshSet.renderLayer(SceneMeshSet.Layer.TRANSLUCENT, modelView, projMat, RenderType.translucent());
            currentMeshSet.renderCustomTranslucentBatches(modelView, projMat);
        }
    }

    private void renderOrbitScene(WorldRenderContext context, Matrix4f viewMatrix, Matrix4f projMat, Vec3 camPos,
                                  SceneProfile profile, double elapsedSeconds) {
        SceneOrbitSettings orbit = profile.getOrbit();
        double[] pivot = profile.getPivotLocal();
        SceneRotation rot = profile.getRotationDegrees();
        boolean renderTranslucent = profile.getRender().isRenderTranslucent();
        double maxDistance = profile.getRender().getMaxDistanceBlocks();

        int count = Math.max(SceneOrbitSettings.MIN_INSTANCES,
                Math.min(SceneOrbitSettings.MAX_INSTANCES, orbit.getInstanceCount()));
        lastConfiguredOrbitInstances = count;
        List<OrbitRenderEntry> visible = new ArrayList<>(count);
        Frustum frustum = context.frustum();
        for (int i = 0; i < count; i++) {
            SceneInstanceTransform transform = SceneOrbitMath.calculateInstanceTransform(profile, elapsedSeconds, i);
            SceneInstanceBounds bounds = SceneOrbitMath.calculateInstanceBounds(profile, transform);
            if (!bounds.isWithinDistance(camPos.x, camPos.y, camPos.z, maxDistance)) continue;
            if (frustum != null && !((FrustumAccessor) frustum).habitrain$cubeInFrustum(
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                continue;
            }

            double[] pos = transform.position();
            double renderX = pos[0] - camPos.x;
            double renderY = pos[1] - camPos.y;
            double renderZ = pos[2] - camPos.z;

            Matrix4f modelMatrix = buildSceneModelMatrix(
                    renderX, renderY, renderZ, pivot, rot,
                    transform.axis(), transform.deltaAngleDegrees(), transform.rotateModelWithOrbit());
            Matrix4f modelView = composeModelView(viewMatrix, modelMatrix);
            visible.add(new OrbitRenderEntry(modelView, bounds));
        }

        lastVisibleOrbitInstances = visible.size();

        // 同一份 VBO 只上传一次；每个可见副本只提交不同的模型矩阵。
        // 先完成全部不透明/裁切层，避免后续副本的不透明面覆盖先绘制的透明层。
        for (OrbitRenderEntry entry : visible) {
            Matrix4f modelView = entry.modelView();
            currentMeshSet.renderLayer(SceneMeshSet.Layer.SOLID, modelView, projMat, RenderType.solid());
            currentMeshSet.renderLayer(SceneMeshSet.Layer.CUTOUT_MIPPED, modelView, projMat, RenderType.cutoutMipped());
            currentMeshSet.renderLayer(SceneMeshSet.Layer.CUTOUT, modelView, projMat, RenderType.cutout());
            currentMeshSet.renderCustomOpaqueBatches(modelView, projMat);
        }

        if (renderTranslucent) {
            // 副本级透明层按球心离相机从远到近提交；网格内部仍沿用原有批次语义。
            visible.sort(Comparator.comparingDouble((OrbitRenderEntry entry) ->
                    entry.bounds().distanceSquaredTo(camPos.x, camPos.y, camPos.z)).reversed());
            for (OrbitRenderEntry entry : visible) {
                Matrix4f modelView = entry.modelView();
                currentMeshSet.renderLayer(SceneMeshSet.Layer.TRANSLUCENT, modelView, projMat, RenderType.translucent());
                currentMeshSet.renderCustomTranslucentBatches(modelView, projMat);
            }
        }
    }

    private record OrbitRenderEntry(Matrix4f modelView, SceneInstanceBounds bounds) {}

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
                profile.getSourceBounds(), profile.getDirection(), profile.getLoop());
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
        additionalMeshGeneration++;
        additionalLoadingHashes.clear();
        additionalPrefetchWaiters.clear();
        additionalMeshesByHash.values().forEach(SceneMeshSet::close);
        additionalMeshesByHash.clear();
        if (currentMeshSet != null) {
            currentMeshSet.close();
            currentMeshSet = null;
        }
        String toReload = loadedAssetHash;
        loadedAssetHash = "";
        if (toReload != null && !toReload.isBlank()) {
            SceneAssetDescriptor descriptor = manifestsByHash.get(toReload);
            if (descriptor != null && descriptor.isValid()) {
                loadMesh(descriptor);
            }
        }
        for (SceneRuntimeState state : additionalRuntimeStates) {
            SceneAssetDescriptor descriptor = manifestsByHash.get(state.getAssetHash());
            if (descriptor != null && descriptor.isValid()) loadAdditionalMesh(descriptor);
        }
    }

    public synchronized void clearMesh() {
        meshLoadGeneration++;
        if (currentMeshSet != null) {
            currentMeshSet.close();
            currentMeshSet = null;
        }
        if (preparedMeshSet != null) {
            preparedMeshSet.close();
            preparedMeshSet = null;
        }
        preparedAssetHash = "";
        preparingAssetHash = "";
        if (preparingFuture != null) preparingFuture.complete(false);
        preparingFuture = null;
        loadedAssetHash = "";
    }

    public synchronized void reset() {
        previewActive = false;
        previewMapKey = "";
        previewProfile = null;
        previewAssetHash = "";
        currentState = SceneRuntimeState.INACTIVE;
        additionalRuntimeStates = List.of();
        additionalMeshGeneration++;
        additionalLoadingHashes.clear();
        additionalPrefetchWaiters.clear();
        additionalMeshesByHash.values().forEach(SceneMeshSet::close);
        additionalMeshesByHash.clear();
        lastConfiguredOrbitInstances = 0;
        lastVisibleOrbitInstances = 0;
        manifestsByHash.clear();
        manifestsByMap.clear();
        compatibilityReportsByHash.clear();
        clearMesh();
    }

    private static String shortHash(String hash) {
        return hash == null ? "" : hash.substring(0, Math.min(8, hash.length()));
    }
}
