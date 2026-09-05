package com.habitrain.core.scene.server;

import com.habitrain.core.scene.asset.SceneAssetDescriptor;
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
                                      SceneAssetDescriptor descriptor) {
        if (server == null || requesterId == null || descriptor == null || !descriptor.isValid()
                || toolSessionId == null || toolSessionId.isBlank()) return false;
        discardForPlayer(requesterId);
        String stagingId = UUID.randomUUID().toString();
        if (!SceneAssetStore.getInstance().stageAsset(stagingId, compressed, descriptor)) return false;

        long expiresAt = System.currentTimeMillis() + DEFAULT_EXPIRY_MS;
        SceneStagingSession session = new SceneStagingSession(stagingId, requesterId, mapKey,
                dimensionId, toolSessionId, descriptor, expiresAt);
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
                "诊断通过；请在设置页显式点击“发布”，正式资产才会替换");
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
        SceneRuntimeCoordinator.getInstance().onAssetPublished(server, session.mapKey(), session.descriptor());
        sendProgress(player, session.mapKey(), "COMPLETED", 1.0f,
                "资产已确认发布！SHA-256: " + session.descriptor().shortHash());
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
