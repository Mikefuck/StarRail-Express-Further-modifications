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

/** Transaction boundary between scene capture and publication. */
public final class SceneStagingService {
    public static final long DEFAULT_EXPIRY_MS = 10L * 60L * 1000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneStagingService.class.getSimpleName());
    private static final SceneStagingService INSTANCE = new SceneStagingService();

    public static SceneStagingService getInstance() { return INSTANCE; }

    private final Map<String, SceneStagingSession> sessions = new LinkedHashMap<>();

    private SceneStagingService() {}

    public synchronized boolean stage(MinecraftServer server, UUID requesterId, String mapKey,
                                      String dimensionId, String toolSessionId, byte[] compressed,
                                      SceneAssetDescriptor descriptor,
                                      SceneDeltaStore.PatchBlob delta) {
        if (server == null || requesterId == null || descriptor == null || !descriptor.isValid()
                || toolSessionId == null || toolSessionId.isBlank()) return false;
        discardForPlayer(requesterId);
        String stagingId = UUID.randomUUID().toString();
        if (!SceneAssetStore.getInstance().stageAsset(stagingId, compressed, descriptor)) return false;

        // 补丁是纯附加件：它写不进去只是"这次没有增量"，绝不能影响正式资产的暂存与发布。
        SceneDeltaStore.DeltaInfo deltaInfo = null;
        if (delta != null && delta.isValid()
                && SceneDeltaStore.getInstance().stageDelta(stagingId, delta)) {
            deltaInfo = new SceneDeltaStore.DeltaInfo(delta.baseSha256(), delta.patchSha256(),
                    delta.bytes().length, System.currentTimeMillis());
        }

        long expiresAt = System.currentTimeMillis() + DEFAULT_EXPIRY_MS;
        SceneStagingSession session = new SceneStagingSession(stagingId, requesterId, mapKey,
                dimensionId, toolSessionId, descriptor, deltaInfo, expiresAt);
        sessions.put(stagingId, session);
        SceneTransferService.getInstance().authorizeStaging(
                requesterId, stagingId, descriptor.sha256(), descriptor.compressedSize());

        server.execute(() -> offer(server, session));
        return true;
    }

    private void offer(MinecraftServer server, SceneStagingSession session) {
        ServerPlayer player = server.getPlayerList().getPlayer(session.requesterId());
        if (player == null || player.hasDisconnected()) {
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
        SceneStagingSession.ValidationFailure failure = validate(player, session,
                report.stagingId(), report.mapKey(), report.assetHash());
        if (failure != SceneStagingSession.ValidationFailure.NONE) {
            rejectAndDiscard(player, session, report.stagingId(), failure);
            return;
        }
        if (!session.acceptStrictReport(report.success(), report.registryFingerprint(),
                report.clientEnvironment())) {
            LOGGER.warn("暂存资产严格诊断失败: mapKey={}, stagingId={}, client={}",
                    session.mapKey(), session.stagingId(), report.clientEnvironment());
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

        boolean promoted = SceneAssetStore.getInstance().promoteStagedAsset(
                session.stagingId(), session.mapKey(), session.descriptor());
        if (!promoted) {
            sendProgress(player, session.mapKey(), "AWAITING_CONFIRMATION", 0.95f,
                    "原子发布失败；旧正式资产保持不变，可重新确认或丢弃");
            return;
        }
        sessions.remove(session.stagingId());
        SceneTransferService.getInstance().revokeStaging(session.stagingId());
        publishDelta(session);
        SceneRuntimeCoordinator.getInstance().onAssetPublished(server, session.mapKey(), session.descriptor());
        sendProgress(player, session.mapKey(), "COMPLETED", 1.0f,
                "资产已确认发布！SHA-256: " + session.descriptor().shortHash());
    }

    /**
     * 资产已经发布成功之后才落补丁。
     *
     * <p>顺序是刻意的：补丁只是"让老客户端少下一段"的加速件，它失败不该把已经原子替换好的
     * 正式资产回滚掉，也不该让操作者看到"发布失败"。失败时只记一条 warn，客户端照常全量下载。</p>
     */
    private void publishDelta(SceneStagingSession session) {
        SceneDeltaStore.DeltaInfo info = session == null ? null : session.delta();
        if (session == null) return;
        if (info == null) {
            SceneDeltaStore.getInstance().discard(session.mapKey());
            return;
        }
        File staged = SceneDeltaStore.getInstance().stagedPatchFile(session.stagingId());
        if (!SceneDeltaStore.getInstance().publish(session.mapKey(), info, staged)) {
            SceneDeltaStore.getInstance().discard(session.mapKey());
            LOGGER.warn("增量补丁未能发布，客户端将走全量下载: mapKey={}, patch={}",
                    session.mapKey(), info.shortPatch());
        }
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
        if (session != null) discard(session.stagingId());
        else SceneTransferService.getInstance().revokeStaging(suppliedStagingId);
        LOGGER.warn("拒绝暂存资产操作: player={}, stagingId={}, reason={}",
                player.getGameProfile().getName(), suppliedStagingId, failure);
        player.sendSystemMessage(Component.literal("§c暂存资产操作被拒绝：" + failure.name()));
        if (session != null) sendProgress(player, session.mapKey(), "FAILED", 0.0f,
                "暂存资产校验失败并已丢弃；旧正式资产不变");
    }

    private synchronized void discardForPlayer(UUID playerId) {
        for (SceneStagingSession session : new ArrayList<>(sessions.values())) {
            if (session.requesterId().equals(playerId)) discard(session.stagingId());
        }
    }

    private synchronized void discard(String stagingId) {
        SceneStagingSession removed = sessions.remove(stagingId);
        SceneAssetStore.getInstance().discardStagedAsset(stagingId);
        SceneTransferService.getInstance().revokeStaging(stagingId);
        if (removed != null) LOGGER.info("已丢弃暂存场景资产: stagingId={}, mapKey={}", stagingId, removed.mapKey());
    }

    public synchronized void tick(MinecraftServer server) {
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
        if (playerId != null) discardForPlayer(playerId);
    }

    public synchronized void shutdown() {
        for (String stagingId : new ArrayList<>(sessions.keySet())) discard(stagingId);
        sessions.clear();
        SceneAssetStore.getInstance().discardAllStagingFiles();
    }

    private static void sendProgress(ServerPlayer player, String mapKey, String state,
                                     float progress, String message) {
        if (player != null && !player.hasDisconnected()) {
            ServerPlayNetworking.send(player,
                    new SceneAssetBuildProgressS2C(mapKey, state, progress, 0, 0, message));
        }
    }
}
