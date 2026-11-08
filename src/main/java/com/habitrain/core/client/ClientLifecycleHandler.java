package com.habitrain.core.client;

import com.habitrain.core.client.cache.ActiveTaskCache;
import com.habitrain.core.client.cache.ClientMapIntroCache;
import com.habitrain.core.client.gui.MapVotePreviewCache;
import com.habitrain.core.client.gui.OptionVoteState;
import com.habitrain.core.client.gui.VoteLaunchSession;
import com.habitrain.core.client.gui.menu.ConfigUpdateContext;
import com.habitrain.core.client.menu.MenuAccessGuard;
import com.habitrain.core.client.render.GameRunningCache;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.client.cache.ClientTaskBlockCache;
import io.wifi.starrailexpress.event.client.OnGameFinishedClient;
import io.wifi.starrailexpress.event.client.OnGameStartedClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/**
 * 客户端生命周期事件处理。
 * <p>
 * 管理 JOIN / DISCONNECT 事件、游戏结束事件、配置保存回调以及场景提示 tick，
 * 在这些生命周期节点执行状态重置与 {@link ShaderMonitor} 的启动/停止。
 */
@Environment(EnvType.CLIENT)
public class ClientLifecycleHandler {

    /** Netty disconnect cleanup is also drained by the render-thread tick. */
    private final java.util.concurrent.atomic.AtomicReference<Runnable> pendingDisconnectCleanup =
            new java.util.concurrent.atomic.AtomicReference<>();


    public ClientLifecycleHandler(ShaderMonitor shaderMonitor) {

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            MapVotePreviewCache.shutdown();
            com.habitrain.core.client.render.CustomTitlePanoramaRenderer.release();
            com.habitrain.core.scene.client.SceneRenderRuntime.getInstance().reset();
            com.habitrain.core.scene.client.SceneBuildScheduler.getInstance().reset();
        });

        // INIT precedes play snapshots. JOIN may arrive after the first payloads.
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            ClientConnectionSession.begin(handler);
            pendingDisconnectCleanup.set(null);
            Runnable initialize = () -> {
                if (!ClientConnectionSession.isCurrent(handler)) return;
                ConfigManager.getInstance().markClientConnected();
                resetState(true, false, false, true);
            };
            if (client.isSameThread()) initialize.run(); else client.execute(initialize);
        });

        // 玩家加入服务器 → 清除上一局残留的 HUD 状态 + 报告当前光影包 + 启动监测
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            client.execute(() -> {
                if (!ClientConnectionSession.isCurrent(handler)
                        || client.getConnection() != handler || !handler.getConnection().isConnected()) return;
                com.habitrain.core.client.render.CustomTitlePanoramaRenderer.release();
                // INIT 已清理上一连接；JOIN 保留刚到达的服务端 snapshot、握手与场景状态。
                // 结束转场覆盖层独立于 resetState()（后者在 OnGameFinishedClient 当帧被调用），
                // 换世界/断线时在此显式释放，避免黑场屏蔽残留。
                com.habitrain.core.client.gui.GameEndOverlayState.scheduleGrace(0L);
                shaderMonitor.start();
            });
        });

        // 玩家断开连接 → 停止光影监测 + 重置 HUD
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (!ClientConnectionSession.disconnect(handler)) return;
            shaderMonitor.stop();
            Runnable cleanup = () -> {
                if (!ClientConnectionSession.isDisconnected()) return;
                ConfigManager.getInstance().clearClientSession();
                boolean integratedHost = Minecraft.getInstance().getSingleplayerServer() != null;
                resetState(ClientSessionResetPolicy.clearEspCachesOnDisconnect(integratedHost),
                        ClientSessionResetPolicy.reloadLocalConfigOnDisconnect(integratedHost),
                        false,
                        true);
                com.habitrain.core.scene.client.SceneProjectionDiagnostics.reset();
                com.habitrain.core.client.gui.GameEndOverlayState.scheduleGrace(0L);
            };
            pendingDisconnectCleanup.set(cleanup);
            if (client.isSameThread()) drainDisconnectCleanup();
            else client.execute(this::drainDisconnectCleanup);
        });

        // 客户端 tick：场景视距提示（独立于光影监测）
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            drainDisconnectCleanup();
            MapVotePreviewCache.tick();
            com.habitrain.core.client.gui.scene.SceneOriginPlacementController.getInstance().tick(client);
            com.habitrain.core.scene.client.SceneViewDistanceWarningController.getInstance().tick(client);
        });

        // All connected saves use the server-authoritative submission service.
        ConfigManager.setClientSaveHandler(com.habitrain.core.client.config.ClientConfigSubmission::submit);
        // 监听 SRE 游戏结束事件 → 交还结束转场（滑出）+ 立即隐藏 HUD + 刷新游戏运行缓存
        // 必须先 markGameFinished() 再 resetState()：resetState() 只清开局转场覆盖层，
        // 结束转场的覆盖层必须保留到滑出完成（fade-block 持续屏蔽 SRE 黑场淡出）。
        OnGameFinishedClient.EVENT.register(() -> {
            var expected = Minecraft.getInstance().getConnection();
            Minecraft.getInstance().execute(() -> {
                if (expected == null || !ClientConnectionSession.isCurrent(expected)) return;
                if (Minecraft.getInstance().screen
                        instanceof com.habitrain.core.client.gui.GameEndTransitionScreen transition) {
                    transition.markGameFinished();
                }
                GameRunningCache.invalidate();
                resetState(ClientSessionResetPolicy.clearEspCachesOnGameFinished(), false, true);
            });
        });

        // 监听 SRE 游戏开始事件 → 刷新游戏运行缓存 + 交还开局转场（游戏进入 ACTIVE）
        OnGameStartedClient.EVENT.register(() -> {
            Minecraft.getInstance().execute(() -> {
                // 正式状态必须接管渲染，不能继续被设置页 preview 覆盖。
                com.habitrain.core.scene.client.SceneRenderRuntime.getInstance().stopPreview();
                com.habitrain.core.scene.client.SceneViewDistanceWarningController.getInstance().onRoundStarted();
                GameRunningCache.invalidate();
                VoteLaunchSession.onGameActive();
                if (Minecraft.getInstance().screen
                        instanceof com.habitrain.core.client.gui.VoteLaunchTransitionScreen transition) {
                    transition.markGameActive();
                }
            });
        });
    }

    private void drainDisconnectCleanup() {
        Runnable cleanup = pendingDisconnectCleanup.getAndSet(null);
        if (cleanup != null) cleanup.run();
    }

    private static void resetState(boolean clearEspCaches, boolean reloadLocalConfig) {
        resetState(clearEspCaches, reloadLocalConfig, false, false);
    }

    private static void resetState(boolean clearEspCaches, boolean reloadLocalConfig, boolean matchFinished) {
        resetState(clearEspCaches, reloadLocalConfig, matchFinished, false);
    }

    private static void resetState(boolean clearEspCaches, boolean reloadLocalConfig, boolean matchFinished, boolean clearMapCaches) {
        GameRunningCache.invalidate();
        ConfigUpdateContext.reset();
        RepairModeClientState.reset();
        if (ClientSessionResetPolicy.clearConnectionState(matchFinished)) {
            MenuAccessGuard.reset();
        }
        OptionVoteState.clear();
        VoteLaunchSession.clear();
        com.habitrain.core.client.gui.MapResetProgressHud.reset();
        if (clearMapCaches) {
            MapVotePreviewCache.clearAll();
            ClientMapIntroCache.clear();
        }
        if (clearEspCaches) {
            ActiveTaskCache.clear();
            ClientTaskBlockCache.clear();
        }
        if (reloadLocalConfig) {
            ConfigManager.getInstance().load();
            InstinctColorHelper.markDirty();
        }
        // 开局转场覆盖层在换世界/断线时释放，避免阻塞残留到下一局。
        com.habitrain.core.client.gui.VoteLaunchOverlayState.setActive(false);
        com.habitrain.core.client.gui.VoteLaunchOverlayState.scheduleGrace(0L);
        // 角色状态同步镜像与角色动作回调在断线/换世界时清空，防止陈旧数据泄漏到下一服务器。
        if (ClientSessionResetPolicy.clearConnectionState(matchFinished)) {
            com.habitrain.core.client.role.RoleStateClientCache.clear();
            com.habitrain.core.client.role.RoleHandshakeState.INSTANCE.reset();
            com.habitrain.core.client.role.RoleSnapshotState.INSTANCE.reset();
            com.habitrain.core.client.gui.scene.SceneOriginPlacementController.getInstance().reset();
        }
        com.habitrain.core.client.role.RoleNameRenderHelper.clearCache();
        com.habitrain.core.client.role.RoleActionClientSession.INSTANCE.clear();
        com.habitrain.core.client.role.RoleActionClientState.clear();
        // 角色扩展握手/快照在断线时清空，避免把上一服务器的 manifest 带入下一服务器。
        com.habitrain.core.scene.client.SceneViewDistanceWarningController.getInstance().reset();
        if (matchFinished) {
            com.habitrain.core.scene.client.SceneClientRuntime.onMatchFinished();
        } else {
            com.habitrain.core.scene.client.SceneClientRuntime.reset("client_state_reset");
        }
    }
}
