package com.habitrain.core.scene.client;

import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneMotionMath;
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
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private long meshLoadGeneration = 0L;

    // 预览模式覆盖
    private boolean previewActive = false;
    private String previewMapKey = "";
    private SceneProfile previewProfile = null;
    private String previewAssetHash = "";
    private long previewStartNanos = 0L;

    private SceneRenderRuntime() {}

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

        String hash = currentState.getAssetHash();
        if (hash != null && !hash.isBlank() && Objects.equals(hash, loadedAssetHash)
                && currentMeshSet != null && !currentMeshSet.isEmpty()) {
            enableRuntimeEffects();
        } else if (hash != null && !hash.isBlank() && Objects.equals(hash, preparedAssetHash)) {
            activatePreparedMesh(hash);
        } else if (hash != null && !hash.isBlank()) {
            if (!previewActive) disableRuntimeEffects();
            SceneAssetDescriptor descriptor = manifestsByHash.get(hash);
            if (descriptor != null && descriptor.isValid()) {
                loadMesh(descriptor);
            } else {
                LOGGER.debug("运行状态先于 Manifest 到达，等待资产描述符: hash={}", shortHash(hash));
            }
        } else {
            disableRuntimeEffects();
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
    }

    /** Metadata lookup for the editor; this does not trigger an asset download. */
    public synchronized SceneAssetDescriptor getManifest(String mapKey) {
        if (mapKey == null || mapKey.isBlank()) return SceneAssetDescriptor.EMPTY;
        return manifestsByMap.getOrDefault(mapKey, SceneAssetDescriptor.EMPTY);
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
        CompletableFuture<Boolean> future;
        synchronized (this) {
            manifestsByHash.put(descriptor.sha256(), descriptor);
            if (mapKey != null && !mapKey.isBlank()) manifestsByMap.put(mapKey, descriptor);
            future = prepareMesh(descriptor);
        }
        future.thenAccept(success -> reportPrefetchResult(sessionId, descriptor.sha256(), success));
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
            SceneMeshBuilder.buildMeshAsync(assetData)
                    .thenAccept(meshSet -> finishPrepare(generation, sha256, meshSet, result));
        });
        return result;
    }

    private void finishPrepare(long generation, String sha256, SceneMeshSet meshSet,
                               CompletableFuture<Boolean> result) {
        boolean success;
        synchronized (this) {
            if (generation != meshLoadGeneration || !Objects.equals(sha256, preparingAssetHash)) {
                if (meshSet != null) meshSet.close();
                result.complete(false);
                return;
            }
            preparingAssetHash = "";
            preparingFuture = null;
            success = meshSet != null && !meshSet.isEmpty() && !meshSet.isClosed();
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
        this.previewProfile = profile.copy();
    }

    public synchronized SceneRuntimeState getCurrentState() {
        return currentState;
    }

    public synchronized void render(WorldRenderContext context) {
        boolean active = previewActive || (currentState != null && currentState.isActive());
        if (!active || currentMeshSet == null || currentMeshSet.isEmpty() || currentMeshSet.isClosed()) {
            return;
        }
        String expectedHash = previewActive ? previewAssetHash : currentState.getAssetHash();
        if (expectedHash == null || expectedHash.isBlank() || !Objects.equals(expectedHash, loadedAssetHash)) {
            return;
        }

        SceneProfile profile = previewActive ? previewProfile : currentState.getProfile();
        if (profile == null || !profile.isEnabled()) return;

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

        double speed = profile.getSpeedBlocksPerSecond();
        boolean loopEnabled = profile.getLoop().isEnabled();
        double loopDist = SceneMotionMath.seamlessLoopDistance(
                profile.getSourceBounds(), profile.getDirection(), profile.getLoop().getDistanceBlocks());
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

        PoseStack poseStack = new PoseStack();
        poseStack.translate(renderX, renderY, renderZ);

        // 枢轴点旋转
        if (pivot[0] != 0 || pivot[1] != 0 || pivot[2] != 0) {
            poseStack.translate(pivot[0], pivot[1], pivot[2]);
            applyRotation(poseStack, rot);
            poseStack.translate(-pivot[0], -pivot[1], -pivot[2]);
        } else {
            applyRotation(poseStack, rot);
        }

        Matrix4f modelView = composeModelView(viewMatrix, poseStack.last().pose());

        // 绘制各图层
        currentMeshSet.renderLayer(SceneMeshSet.Layer.SOLID, modelView, projMat, RenderType.solid());
        currentMeshSet.renderLayer(SceneMeshSet.Layer.CUTOUT_MIPPED, modelView, projMat, RenderType.cutoutMipped());
        currentMeshSet.renderLayer(SceneMeshSet.Layer.CUTOUT, modelView, projMat, RenderType.cutout());

        if (renderTranslucent) {
            currentMeshSet.renderLayer(SceneMeshSet.Layer.TRANSLUCENT, modelView, projMat, RenderType.translucent());
        }

    }

    static Matrix4f composeModelView(Matrix4f viewMatrix, Matrix4f modelMatrix) {
        return new Matrix4f(viewMatrix).mul(modelMatrix);
    }

    private void applyRotation(PoseStack poseStack, SceneRotation rot) {
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
        double effective = SceneMotionMath.seamlessLoopDistance(
                profile.getSourceBounds(), profile.getDirection(), configured);
        double[] origin = profile.getDisplayOrigin();
        double[] direction = profile.getDirection();
        LOGGER.info("{}参数: enabled={}, origin=({},{},{}), direction=({},{},{}), speed={}, "
                        + "loop={}, configuredDistance={}, effectiveDistance={}, bounds={}",
                mode, profile.isEnabled(), format(origin[0]), format(origin[1]), format(origin[2]),
                format(direction[0]), format(direction[1]), format(direction[2]),
                format(profile.getSpeedBlocksPerSecond()), profile.getLoop().isEnabled(),
                format(configured), format(effective), profile.getSourceBounds());
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
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
        manifestsByHash.clear();
        manifestsByMap.clear();
        clearMesh();
    }

    private static String shortHash(String hash) {
        return hash == null ? "" : hash.substring(0, Math.min(8, hash.length()));
    }
}
