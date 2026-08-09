package com.habitrain.core.client;

import com.habitrain.core.client.cache.ActiveTaskCache;
import com.habitrain.core.client.gui.BlackoutHudOverlay;
import com.habitrain.core.client.gui.BlackoutTaskShopState;
import com.habitrain.core.client.gui.BlackoutVoteState;
import com.habitrain.core.client.gui.BlackoutWelcomeRenderer;
import com.habitrain.core.client.gui.ClientBlackoutState;
import com.habitrain.core.client.gui.OptionVoteState;
import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.client.render.GameRunningCache;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
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
 * 管理 JOIN / DISCONNECT 事件、游戏结束事件、配置保存回调以及报幕 tick，
 * 在这些生命周期节点执行状态重置与 {@link ShaderMonitor} 的启动/停止。
 */
@Environment(EnvType.CLIENT)
public class ClientLifecycleHandler {

    private final ShaderMonitor shaderMonitor;

    public ClientLifecycleHandler(ShaderMonitor shaderMonitor) {
        this.shaderMonitor = shaderMonitor;

        // 玩家加入服务器 → 清除上一局残留的 HUD 状态 + 报告当前光影包 + 启动监测
        // ★ 必须在 JOIN 时重置停电 HUD：退出游戏时 DISCONNECT 与排队中的 timer payload
        //   存在竞态，reset() 可能先于 payload 执行，导致 showHud 被重新置 true 并残留到下一世界。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            client.execute(() -> {
                resetState();
                // 结束转场覆盖层独立于 resetState()（后者在 OnGameFinishedClient 当帧被调用），
                // 换世界/断线时在此显式释放，避免黑场屏蔽残留。
                com.habitrain.core.client.gui.GameEndOverlayState.scheduleGrace(0L);
                shaderMonitor.start();
            });
        });

        // 玩家断开连接 → 停止光影监测 + 重置 HUD
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            shaderMonitor.stop();
            resetState();
            com.habitrain.core.client.gui.GameEndOverlayState.scheduleGrace(0L);
        });

        // 客户端 tick：报幕 tick（每帧执行，独立于光影监测）
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            BlackoutWelcomeRenderer.tick();
        });

        // 配置保存回调：修改后自动同步到服务端（仅 OP 会生效）
        ConfigManager.setOnSaveCallback(() -> {
            var mc = Minecraft.getInstance();
            if (mc.getConnection() == null) return;

            // Refresh local registry views, live role cards, and an open role book.
            com.habitrain.core.client.role.RoleOverrideRefreshDispatcher.refresh();

            // ★ 单机模式（集成服务器）：配置已保存在本地，无需同步
            //    mc.getSingleplayerServer() != null 表示当前正在运行本地集成服务器
            if (mc.getSingleplayerServer() != null) {
                mc.getSingleplayerServer().execute(() ->
                        com.habitrain.core.game.sre.roleoverride.SreRoleOverrideRefreshService
                                .refreshServer(mc.getSingleplayerServer()));
                return;
            }
            if (!MenuPermissions.canEditRemoteConfigs()) return;

            // 发送当前完整配置到服务端
            // 服务端会校验 OP 权限，非 OP 的请求会被拒绝
            String configJson = ConfigManager.getInstance().toJsonString();
            InstinctColorHelper.markDirty();
            PayloadSenders.sendConfigUpdate(configJson);
        });

                // 监听 SRE 游戏结束事件 → 交还结束转场（滑出）+ 立即隐藏 HUD + 刷新游戏运行缓存
        // 必须先 markGameFinished() 再 resetState()：resetState() 只清开局转场覆盖层，
        // 结束转场的覆盖层必须保留到滑出完成（fade-block 持续屏蔽 SRE 黑场淡出）。
        OnGameFinishedClient.EVENT.register(() -> {
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().screen
                        instanceof com.habitrain.core.client.gui.GameEndTransitionScreen transition) {
                    transition.markGameFinished();
                }
                GameRunningCache.invalidate();
                resetState();
            });
        });

        // 监听 SRE 游戏开始事件 → 刷新游戏运行缓存 + 交还开局转场（游戏进入 ACTIVE）
        OnGameStartedClient.EVENT.register(() -> {
            Minecraft.getInstance().execute(() -> {
                GameRunningCache.invalidate();
                if (Minecraft.getInstance().screen
                        instanceof com.habitrain.core.client.gui.VoteLaunchTransitionScreen transition) {
                    transition.markGameActive();
                }
            });
        });
    }

    private static void resetState() {
        GameRunningCache.invalidate();
        BlackoutHudOverlay.reset();
        BlackoutWelcomeRenderer.reset();
        BlackoutVoteState.clear();
        OptionVoteState.clear();
        ClientBlackoutState.setBlackoutModeActive(false);
        // 清活动任务/扫描方块缓存与商店状态，避免换世界后陈旧 ESP 轮廓与商店状态残留（P1-22/P1-23）
        ActiveTaskCache.clear();
        CustomTaskBlockCache.clear();
        BlackoutTaskShopState.clear();
        // 开局转场覆盖层在换世界/断线时释放，避免阻塞残留到下一局。
        com.habitrain.core.client.gui.VoteLaunchOverlayState.setActive(false);
        com.habitrain.core.client.gui.VoteLaunchOverlayState.scheduleGrace(0L);
    }
}
