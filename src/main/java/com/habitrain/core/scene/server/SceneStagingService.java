package com.habitrain.core.scene.server;

import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.api.scene.asset.SceneAssetSizeReport;
import com.habitrain.core.scene.network.SceneAssetBuildProgressS2C;
import com.habitrain.core.scene.network.SceneStagingDecisionC2S;
import com.habitrain.core.scene.network.SceneStagingOfferS2C;
import com.habitrain.core.scene.network.SceneStagingReportC2S;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Transaction boundary between scene capture and publication. */
public final class SceneStagingService {
    public static final long DEFAULT_EXPIRY_MS = 10L * 60L * 1000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneStagingService.class.getSimpleName());
    private static final SceneStagingService INSTANCE = new SceneStagingService();

    public static SceneStagingService getInstance() { return INSTANCE; }

    private final Map<String, SceneStagingSession> sessions = new LinkedHashMap<>();
    private final Map<String, SceneAssetStore.StorageLocation> sessionOwners = new LinkedHashMap<>();
    private boolean accepting = true;
    private long generation = 1L;
    private ExecutorService promoteExecutor = createPromoteExecutor();

    private static ExecutorService createPromoteExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(16), r -> {
            Thread t = new Thread(r, "HabiTrain-SceneStaging-IO");
            t.setDaemon(true);
            return t;
        });
    }

    private SceneStagingService() {}

    public synchronized long currentGeneration() {
        return generation;
    }

    public boolean stage(MinecraftServer server, UUID requesterId, String mapKey,
                         String dimensionId, String toolSessionId, byte[] compressed,
                         SceneAssetDescriptor descriptor,
                         SceneDeltaStore.PatchBlob delta) {
        return stage(server, requesterId, mapKey, dimensionId, toolSessionId,
                compressed, descriptor, delta, currentGeneration());
    }

    public boolean stage(MinecraftServer server, UUID requesterId, String mapKey,
                         String dimensionId, String toolSessionId, byte[] compressed,
                         SceneAssetDescriptor descriptor,
                         SceneDeltaStore.PatchBlob delta,
                         long expectedGeneration) {
        return stage(server, requesterId, mapKey, dimensionId, toolSessionId, compressed, descriptor,
                delta, expectedGeneration, () -> true);
    }

    boolean stage(MinecraftServer server, UUID requesterId, String mapKey,
                  String dimensionId, String toolSessionId, byte[] compressed,
                  SceneAssetDescriptor descriptor, SceneDeltaStore.PatchBlob delta,
                  long expectedGeneration, java.util.function.BooleanSupplier captureActive) {
        if (server == null || requesterId == null || descriptor == null || !descriptor.isValid()
                || toolSessionId == null || toolSessionId.isBlank()) return false;
        SceneAssetStore.StorageLocation owner;
        synchronized (this) {
            owner = SceneAssetStore.getInstance().currentLocation();
            if (!captureActive.getAsBoolean() || !accepting || owner == null || generation != expectedGeneration
                    || !toolSessionId.equals(SceneSelectionSessionManager.getInstance().getEditorSessionId(requesterId))) return false;
        }
        String stagingId = UUID.randomUUID().toString();
        if (!SceneAssetStore.getInstance().stageAsset(owner, stagingId, compressed, descriptor)) {
            SceneAssetStore.getInstance().discardStagedAsset(owner, stagingId);
            return false;
        }

        // 补丁是纯附加件：它写不进去只是"这次没有增量"，绝不能影响正式资产的暂存与发布。
        SceneDeltaStore.DeltaInfo deltaInfo = null;
        if (delta != null && delta.isValid()
                && SceneDeltaStore.getInstance().stageDelta(owner, stagingId, delta)) {
            deltaInfo = new SceneDeltaStore.DeltaInfo(delta.baseSha256(), descriptor.sha256(),
                    delta.patchSha256(), delta.bytes().length, System.currentTimeMillis());
        }

        long expiresAt = System.currentTimeMillis() + DEFAULT_EXPIRY_MS;
        SceneStagingSession session = new SceneStagingSession(stagingId, requesterId, mapKey,
                dimensionId, toolSessionId, descriptor, deltaInfo, expiresAt);
        synchronized (this) {
            if (!captureActive.getAsBoolean() || !accepting || generation != expectedGeneration || !SceneAssetStore.getInstance().isCurrent(owner)
                    || !toolSessionId.equals(SceneSelectionSessionManager.getInstance().getEditorSessionId(requesterId))) {
                SceneAssetStore.getInstance().discardStagedAsset(owner, stagingId);
                return false;
            }
            discardForPlayer(requesterId);
            sessions.put(stagingId, session);
            sessionOwners.put(stagingId, owner);
            SceneTransferService.getInstance().authorizeStaging(
                    requesterId, stagingId, descriptor.sha256(), descriptor.compressedSize());
        }

        server.execute(() -> offer(server, session));
        return true;
    }

    private void offer(MinecraftServer server, SceneStagingSession session) {
        synchronized (this) {
            if (sessions.get(session.stagingId()) != session) {
                return;
            }
        }
        ServerPlayer player = server.getPlayerList().getPlayer(session.requesterId());
        if (player == null || player.hasDisconnected()
                || !session.toolSessionId().equals(SceneSelectionSessionManager.getInstance().getEditorSessionId(session.requesterId()))
                || !session.dimensionId().equals(player.serverLevel().dimension().location().toString())) {
            discard(session.stagingId());
            return;
        }
        ServerPlayNetworking.send(player, new SceneStagingOfferS2C(
                session.stagingId(), session.mapKey(), session.descriptor(), session.expiresAt()));
        sendProgress(player, session.mapKey(), "DIAGNOSING", 0.9f,
                "暂存资产已生成，正在由管理员客户端诊断；正式资产尚未改变");
    }

    public synchronized void handleReport(ServerPlayer player, SceneStagingReportC2S report) {
        if (player == null || report == null) return;
        SceneStagingSession session = sessions.get(report.stagingId());
        if (session != null && session.isCommitted()) return;
        if (!player.hasPermissions(2) || com.habitrain.core.api.menu.MenuGateApi.isBlocked(player, player.getServer())) {
            if (session != null && session.requesterId().equals(player.getUUID())) {
                rejectAndDiscard(player, session, report.stagingId(),
                        SceneStagingSession.ValidationFailure.PERMISSION_LOST);
            } else {
                LOGGER.warn("拒绝非管理员暂存诊断报告: player={}, stagingId={}",
                        player.getGameProfile().getName(), sanitizeLog(report.stagingId()));
                player.sendSystemMessage(Component.literal("§c暂存资产操作被拒绝：权限不足"));
            }
            return;
        }
        SceneStagingSession.ValidationFailure failure = validate(player, session,
                report.stagingId(), report.mapKey(), report.assetHash());
        if (failure != SceneStagingSession.ValidationFailure.NONE) {
            rejectAndDiscard(player, session, report.stagingId(), failure);
            return;
        }
        if (!session.acceptStrictReport(report.success(), report.registryFingerprint(),
                report.clientEnvironment())) {
            LOGGER.warn("暂存资产严格诊断失败: mapKey={}, stagingId={}, client={}",
                    sanitizeLog(session.mapKey()), session.stagingId(),
                    sanitizeLog(report.clientEnvironment()));
            discard(session.stagingId());
            sendProgress(player, session.mapKey(), "FAILED", 0.0f,
                    "客户端诊断失败或注册表指纹不一致；已丢弃暂存资产，旧正式资产不变");
            return;
        }
        sendProgress(player, session.mapKey(), "AWAITING_CONFIRMATION", 0.95f,
                "诊断通过；" + describeSize(session.descriptor())
                        + "；请在设置页显式点击“发布”，正式资产才会替换");
    }

    /**
     * 让操作者在按下"发布"之前就看到这次要发布多大的东西。
     *
     * <p>报告里最实在的一条结论是"真正决定字节数的是选区大小"，而这条提示正是选区决策的唯一依据：
     * 压缩后体积就是每个玩家冷缓存时要下载的量。</p>
     */
    private static String describeSize(SceneAssetDescriptor descriptor) {
        if (descriptor == null) return "资产体积未知";
        return "本资产压缩后 " + SceneAssetSizeReport.humanBytes(descriptor.compressedSize())
                + "（原始约 " + SceneAssetSizeReport.humanBytes(descriptor.uncompressedSize())
                + "，共 " + descriptor.sectionCount() + " Section）";
    }

    public synchronized void handleDecision(MinecraftServer server, ServerPlayer player,
                                            SceneStagingDecisionC2S decision) {
        if (server == null || player == null || decision == null) return;
        SceneStagingSession session = sessions.get(decision.stagingId());
        if (session != null && session.isCommitted() && session.requesterId().equals(player.getUUID())) {
            sendProgress(player, session.mapKey(), "SAVING", 0.99f, "资产已提交，正在同步发布结果");
            return;
        }
        if (!player.hasPermissions(2) || com.habitrain.core.api.menu.MenuGateApi.isBlocked(player, player.getServer())) {
            if (session != null && session.requesterId().equals(player.getUUID())) {
                rejectAndDiscard(player, session, decision.stagingId(),
                        SceneStagingSession.ValidationFailure.PERMISSION_LOST);
            } else {
                LOGGER.warn("拒绝非管理员暂存发布决策: player={}, stagingId={}",
                        player.getGameProfile().getName(), sanitizeLog(decision.stagingId()));
                player.sendSystemMessage(Component.literal("§c暂存资产操作被拒绝：权限不足"));
            }
            return;
        }
        SceneStagingSession.ValidationFailure failure = validate(player, session,
                decision.stagingId(), decision.mapKey(), decision.assetHash());
        if (failure != SceneStagingSession.ValidationFailure.NONE) {
            rejectAndDiscard(player, session, decision.stagingId(), failure);
            return;
        }
        if (!decision.publish()) {
            discard(session.stagingId());
            sendProgress(player, session.mapKey(), "CANCELLED", 0.0f,
                    "已丢弃暂存资产；旧正式资产不变");
            return;
        }
        if (!session.reportAccepted()) {
            sendProgress(player, session.mapKey(), "FAILED", 0.0f,
                    "尚无通过服务端校验的诊断报告，不能发布");
            return;
        }
        if (!session.tryBeginPromote()) {
            return;
        }

        final long expectedGen = this.generation;
        final UUID playerId = player.getUUID();
        sendProgress(player, session.mapKey(), "SAVING", 0.98f, "正在后台原子写入并发布正式资产...");
        ExecutorService executor = ensurePromoteExecutor(expectedGen);
        if (executor == null) {
            session.resetPromote();
            return;
        }
        try {
            SceneAssetStore.StorageLocation owner = sessionOwners.get(session.stagingId());
            executor.execute(() -> publishPrepared(server, playerId, session, expectedGen, owner));
        } catch (RejectedExecutionException e) {
            session.resetPromote();
            sendProgress(player, session.mapKey(), "AWAITING_CONFIRMATION", 0.95f,
                    "发布队列繁忙，请稍后重试");
        }
    }

    private void applyPublishedDecision(MinecraftServer server, UUID playerId,
                                        SceneStagingSession session, boolean promoted,
                                        long expectedGen) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        synchronized (this) {
            if (!accepting || this.generation != expectedGen || sessions.get(session.stagingId()) != session) {
                session.resetPromote();
                return;
            }
            if (!promoted) {
                session.resetPromote();
                sendProgress(player, session.mapKey(), "AWAITING_CONFIRMATION", 0.95f,
                        "原子发布失败；旧正式资产保持不变，可重新确认或丢弃");
                return;
            }
            sessions.remove(session.stagingId(), session);
            sessionOwners.remove(session.stagingId());
            SceneTransferService.getInstance().revokeStaging(session.stagingId());
        }
        if (session.descriptor().equals(SceneAssetStore.getInstance().getDescriptor(session.mapKey()))) {
            SceneRuntimeCoordinator.getInstance().onAssetPublished(server, session.mapKey(), session.descriptor());
        }
        sendProgress(player, session.mapKey(), "COMPLETED", 1.0f,
                "资产已确认发布！SHA-256: " + session.descriptor().shortHash());
    }

    private synchronized ExecutorService ensurePromoteExecutor(long expectedGen) {
        if (!accepting || this.generation != expectedGen) return null;
        if (promoteExecutor == null || promoteExecutor.isShutdown()) {
            promoteExecutor = createPromoteExecutor();
        }
        return promoteExecutor;
    }

    private void publishPrepared(MinecraftServer server, UUID playerId, SceneStagingSession session,
                                 long expectedGen, SceneAssetStore.StorageLocation owner) {
        SceneAssetStore assets = SceneAssetStore.getInstance();
        SceneDeltaStore deltas = SceneDeltaStore.getInstance();
        synchronized (this) {
            if (!canCommit(session, expectedGen, owner)) { session.resetPromote(); return; }
        }
        // No lifecycle monitor is held while reading/hashing/copying/fsyncing either asset or delta.
        try (SceneAssetStore.PreparedPromotion asset = assets.preparePromotion(
                owner, session.stagingId(), session.mapKey(), session.descriptor())) {
            SceneDeltaStore.PreparedPublication delta = asset == null ? null : deltas.preparePublication(owner,
                    session.mapKey(), session.delta(), deltas.stagedPatchFile(owner, session.stagingId()),
                    session.descriptor().sha256());
            if (asset != null && delta == null && session.delta() != null) {
                // A damaged optional patch must not prevent full-asset publication.
                delta = deltas.preparePublication(owner, session.mapKey(), null, null, session.descriptor().sha256());
            }
            boolean promoted;
            try (SceneDeltaStore.PreparedPublication preparedDelta = delta) {
                synchronized (this) {
                    if (!canCommit(session, expectedGen, owner)) { session.resetPromote(); return; }
                    promoted = asset != null && asset.owner == owner && assets.commitPromotion(asset);
                    if (promoted) session.markCommitted();
                    if (promoted && !deltas.commitPublication(preparedDelta)) {
                        LOGGER.warn("增量补丁未能发布，客户端将走全量下载: mapKey={}", session.mapKey());
                    }
                }
                if (promoted) {
                    deltas.finishPublication(preparedDelta);
                    assets.finishPromotion(asset);
                }
                server.execute(() -> applyPublishedDecision(server, playerId, session, promoted, expectedGen));
            }
        } catch (Exception failure) {
            session.resetPromote();
            LOGGER.warn("场景发布后台任务失败: mapKey=" + session.mapKey(), failure);
            try { server.execute(() -> applyPublishedDecision(server, playerId, session, false, expectedGen)); }
            catch (RejectedExecutionException stopped) { /* Lifecycle checks reject stopped results. */ }
        }
    }

    /** All publication decisions share this short critical section with shutdown/discard. */
    private boolean canCommit(SceneStagingSession session, long expectedGen, SceneAssetStore.StorageLocation owner) {
        return accepting && generation == expectedGen && sessions.get(session.stagingId()) == session
                && sessionOwners.get(session.stagingId()) == owner && owner != null
                && SceneAssetStore.getInstance().isCurrent(owner)
                && System.currentTimeMillis() < session.expiresAt()
                && session.toolSessionId().equals(SceneSelectionSessionManager.getInstance().getEditorSessionId(session.requesterId()))
                && session.mapKey().equals(SceneSelectionSessionManager.getInstance().getEditorMapKey(session.requesterId()));
    }

    private SceneStagingSession.ValidationFailure validate(ServerPlayer player,
                                                            SceneStagingSession session,
                                                            String stagingId, String mapKey,
                                                            String assetHash) {
        if (session == null) return SceneStagingSession.ValidationFailure.IDENTITY_MISMATCH;
        SceneSelectionSessionManager selections = SceneSelectionSessionManager.getInstance();
        return session.validate(player.getUUID(), player.hasPermissions(2), stagingId, mapKey,
                assetHash, selections.getEditorSessionId(player.getUUID()),
                selections.getEditorMapKey(player.getUUID()),
                player.serverLevel().dimension().location().toString(), System.currentTimeMillis());
    }

    private void rejectAndDiscard(ServerPlayer player, SceneStagingSession session,
                                  String suppliedStagingId,
                                  SceneStagingSession.ValidationFailure failure) {
        boolean ownsSession = session != null && session.requesterId().equals(player.getUUID());
        if (ownsSession) {
            discard(session.stagingId());
        }
        LOGGER.warn("拒绝暂存资产操作: player={}, stagingId={}, reason={}",
                player.getGameProfile().getName(), sanitizeLog(suppliedStagingId), failure);
        player.sendSystemMessage(Component.literal("§c暂存资产操作被拒绝：" + failure.name()));
        if (ownsSession) {
            sendProgress(player, session.mapKey(), "FAILED", 0.0f,
                    "暂存资产校验失败并已丢弃；旧正式资产不变");
        }
    }

    private static String sanitizeLog(String value) {
        if (value == null) return "null";
        String cleaned = value.replace('\r', '_').replace('\n', '_').replace('\t', '_');
        return cleaned.length() > 128 ? cleaned.substring(0, 128) + "..." : cleaned;
    }

    synchronized void discardForPlayer(UUID playerId) {
        for (SceneStagingSession session : new ArrayList<>(sessions.values())) {
            if (session.requesterId().equals(playerId)) discard(session.stagingId());
        }
    }

    private synchronized void discard(String stagingId) {
        SceneStagingSession current = sessions.get(stagingId);
        if (current != null && current.isCommitted()) return; // Publication already won the cancellation race.
        SceneStagingSession removed = sessions.remove(stagingId);
        SceneAssetStore.StorageLocation owner = sessionOwners.remove(stagingId);
        if (owner != null) {
            ExecutorService worker = ensurePromoteExecutor(generation);
            if (worker != null) try {
                worker.execute(() -> SceneAssetStore.getInstance().discardStagedAsset(owner, stagingId));
            } catch (RejectedExecutionException busy) {
                LOGGER.warn("暂存清理队列繁忙，文件将在下次世界绑定时回收: {}", stagingId);
            }
        }
        SceneTransferService.getInstance().revokeStaging(stagingId);
        if (removed != null) LOGGER.info("已丢弃暂存场景资产: stagingId={}, mapKey={}", stagingId, removed.mapKey());
    }

    public synchronized void tick(MinecraftServer server) {
        SceneSelectionSessionManager.getInstance().tick(server);
        long now = System.currentTimeMillis();
        for (SceneStagingSession session : new ArrayList<>(sessions.values())) {
            if (now >= session.expiresAt()) {
                ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(session.requesterId());
                discard(session.stagingId());
                if (player != null) sendProgress(player, session.mapKey(), "EXPIRED", 0.0f,
                        "暂存资产已过期并清理；旧正式资产不变");
            }
        }
    }

    public synchronized void onPlayerDisconnect(UUID playerId) {
        if (playerId != null) {
            discardForPlayer(playerId);
            SceneInstanceService.getInstance().onPlayerDisconnect(playerId);
        }
    }

    public synchronized void start() { accepting = true; }

    public void shutdown() {
        Map<String, SceneAssetStore.StorageLocation> discarded;
        ExecutorService executor;
        synchronized (this) {
            accepting = false;
            generation++;
            executor = promoteExecutor;
            discarded = new LinkedHashMap<>(sessionOwners);
            sessions.clear();
            sessionOwners.clear();
            discarded.keySet().forEach(SceneTransferService.getInstance()::revokeStaging);
        }
        if (executor != null) executor.shutdownNow();
        // Workers clean their own captured paths if they finish after this snapshot.
        discarded.forEach((id, owner) -> SceneAssetStore.getInstance().discardStagedAsset(owner, id));
    }

    private static void sendProgress(ServerPlayer player, String mapKey, String state,
                                     float progress, String message) {
        if (player != null && !player.hasDisconnected()) {
            ServerPlayNetworking.send(player,
                    new SceneAssetBuildProgressS2C(mapKey, state, progress, 0, 0, message));
        }
    }

    /** Wait outside service locks so cancelled workers can finish their finally blocks. */
    public void awaitStopped() {
        awaitExecutor(promoteExecutor);
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
