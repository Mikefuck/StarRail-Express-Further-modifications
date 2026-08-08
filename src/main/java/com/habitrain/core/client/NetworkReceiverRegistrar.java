package com.habitrain.core.client;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.cache.ActiveTaskCache;
import com.habitrain.core.client.gui.BlackoutHudOverlay;
import com.habitrain.core.client.gui.BlackoutPhoneHireScreen;
import com.habitrain.core.client.gui.BlackoutTaskShopScreen;
import com.habitrain.core.client.gui.BlackoutVoteScreen;
import com.habitrain.core.client.gui.BlackoutVoteState;
import com.habitrain.core.client.gui.BlackoutWelcomeRenderer;
import com.habitrain.core.client.gui.ClientBlackoutState;
import com.habitrain.core.client.gui.OptionVoteScreen;
import com.habitrain.core.client.gui.OptionVoteState;
import com.habitrain.core.client.InstinctColorHelper;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.network.BlackoutAnnouncePayload;
import com.habitrain.core.network.BlackoutHireResultPayload;
import com.habitrain.core.network.BlackoutPhoneOpenPayload;
import com.habitrain.core.network.BlackoutTimerPayload;
import com.habitrain.core.network.BlackoutVotePayload;
import com.habitrain.core.network.CustomTaskBlockPayload;
import com.habitrain.core.network.FullConfigSyncPayload;
import com.habitrain.core.network.GreedTradePromptPayload;
import com.habitrain.core.network.OptionVotePayload;
import com.habitrain.core.network.ShaderConfigPayload;
import com.habitrain.core.network.TaskConfigPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.Screen;

/**
 * 注册所有服务端到客户端（S2C）的网络载荷接收器。
 * <p>
 * 将 {@link HabiTrainCoreClient} 中的 S2C {@code registerGlobalReceiver} 调用
 * 收拢到此类，职责单一：网络接收注册。
 */
@Environment(EnvType.CLIENT)
public class NetworkReceiverRegistrar {

    public NetworkReceiverRegistrar() {
        registerReceivers();
    }

    private void registerReceivers() {
        // 1) 接收服务端任务配置同步
        ClientPlayNetworking.registerGlobalReceiver(TaskConfigPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                HabiTrainCore.LOGGER.info("收到服务端任务配置同步 ({} 个任务配置)", payload.getConfigs().size());

                // 使用 applySyncData 应用（抑制 save 回调，防止回环）
                ConfigManager.getInstance().applySyncData(
                        payload.getConfigs(),
                        ConfigManager.getInstance().getDlcProbabilityTarget()
                );
                InstinctColorHelper.markDirty();
            });
        });

        // 2) 接收服务端活跃自定义任务同步（用于多人模式下透视渲染）
        ClientPlayNetworking.registerGlobalReceiver(ActiveTaskPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                if (payload.isClear()) {
                    HabiTrainCore.LOGGER.info("收到活跃自定义任务清空信号 (isFake={})", payload.isFake());
                    if (payload.isFake()) {
                        ActiveTaskCache.clearFakeTask();
                    } else {
                        ActiveTaskCache.clear();
                    }
                } else {
                    HabiTrainCore.LOGGER.info("收到活跃自定义任务同步: {} (isFake={})",
                            payload.getTaskFullId(), payload.isFake());
                    if (payload.isFake()) {
                        ActiveTaskCache.setFakeTask(payload.getTaskFullId());
                    } else {
                        ActiveTaskCache.setActiveTask(payload.getTaskFullId());
                    }
                }
            });
        });

        // 3) 接收服务端自定义任务方块类型同步
        ClientPlayNetworking.registerGlobalReceiver(CustomTaskBlockPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                CustomTaskBlockCache.loadFromSnapshot(payload.getBlockTypeIds());
            });
        });

        // 4) 接收服务端光影白名单同步（仅更新内存，不触发 save 回调）
        ClientPlayNetworking.registerGlobalReceiver(ShaderConfigPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                HabiTrainCore.LOGGER.info("收到服务端光影白名单同步: {}",
                        payload.isEnabled() ? "启用, " + payload.getWhitelist().size() + "个光影" : "禁用");
                ConfigManager cfg = ConfigManager.getInstance();
                cfg.applyShaderWhitelistSync(payload.isEnabled(), payload.getWhitelist());
            });
        });

        // 5) 接收服务端完整配置同步（global + tasks + gameModes + minigames），
        //    让客户端配置界面显示服务端真实值，避免 OP 联机保存时用本地过期全局项覆盖服务端。
        //    applySyncFromJson 抑制 save 回调，防止回环广播。
        ClientPlayNetworking.registerGlobalReceiver(FullConfigSyncPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                HabiTrainCore.LOGGER.info("收到服务端完整配置同步 ({} 字节)", payload.getConfigJson().length());
                ConfigManager.getInstance().applySyncFromJson(payload.getConfigJson());
                InstinctColorHelper.markDirty();
                // Refresh role override engine with synced config
                com.habitrain.core.client.role.RoleOverrideRefreshDispatcher.refresh();
            });
        });

        // =========================================================
        //  停电模式 — S2C 接收器
        // =========================================================

        // 6) 时间同步
        // remaining<=0 且 endTimeTick==0：局终/重置包 → 拆 HUD
        // remaining<=0 但 endTimeTick>0：仍在对局时钟内（极短瞬间）→ 更新，不拆 active
        ClientPlayNetworking.registerGlobalReceiver(BlackoutTimerPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                boolean sessionReset = payload.totalTimeRemaining() <= 0 && payload.endTimeTick() <= 0L;
                if (sessionReset) {
                    BlackoutHudOverlay.reset();
                    BlackoutWelcomeRenderer.reset();
                    ClientBlackoutState.setBlackoutModeActive(false);
                    return;
                }

                ClientBlackoutState.setBlackoutModeActive(true);
                BlackoutHudOverlay.updateTime(
                    Math.max(0, payload.totalTimeRemaining()),
                    payload.endTimeTick(),
                    payload.blackoutActive(),
                    payload.phase());
            });
        });

        // 7) 电话打开状态 S2C 接收器
        ClientPlayNetworking.registerGlobalReceiver(BlackoutPhoneOpenPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                if (ctx.client().screen instanceof BlackoutPhoneHireScreen phoneScreen) {
                    phoneScreen.updateState(payload);
                } else {
                    ctx.client().setScreen(new BlackoutPhoneHireScreen(ctx.client().screen, payload));
                }
            });
        });

        // 9) 电话聘请结果 S2C 接收器
        ClientPlayNetworking.registerGlobalReceiver(BlackoutHireResultPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                if (ctx.client().screen instanceof BlackoutPhoneHireScreen phoneScreen) {
                    phoneScreen.onHireResult(payload);
                }
            });
        });

        // 10) 开局报幕 S2C 接收器
        ClientPlayNetworking.registerGlobalReceiver(BlackoutAnnouncePayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                BlackoutWelcomeRenderer.startWelcome(
                    payload.roleName(), payload.subtitle(), payload.goal());
            });
        });

        // 11) 通用投票（放逐等） S2C 接收器
        ClientPlayNetworking.registerGlobalReceiver(BlackoutVotePayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                BlackoutVoteState.update(payload);
                // Tip-only UX: never auto-open the vote screen (1Hz rebroadcast would re-force it).
                // Player opens via keybind; close only when vote ends if screen is open.
                if (!payload.active() && ctx.client().screen instanceof BlackoutVoteScreen) {
                    ctx.client().setScreen(null);
                }
            });
        });

        // 12) 停电任务商店打开 S2C 接收器
        ClientPlayNetworking.registerGlobalReceiver(com.habitrain.core.network.BlackoutTaskShopOpenPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                if (ctx.client().screen instanceof BlackoutTaskShopScreen shopScreen) {
                    shopScreen.updateState(payload);
                } else {
                    BlackoutTaskShopScreen screen = new BlackoutTaskShopScreen(ctx.client().screen);
                    screen.updateState(payload);
                    ctx.client().setScreen(screen);
                }
            });
        });

        // 13) 停电任务商店购买结果 S2C 接收器
        ClientPlayNetworking.registerGlobalReceiver(com.habitrain.core.network.BlackoutTaskShopResultPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                if (ctx.client().screen instanceof BlackoutTaskShopScreen shopScreen) {
                    shopScreen.onPurchaseResult(payload.success(), payload.reason());
                }
            });
        });

        // 14) 通用选项投票（模式/地图等） S2C 接收器
        ClientPlayNetworking.registerGlobalReceiver(OptionVotePayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                OptionVoteState.UpdateResult result = OptionVoteState.update(payload);
                // Auto-open once per phase (inactive→active or voteId change).
                // 1Hz rebroadcasts must not re-force the screen if the player closed it.
                if (result.shouldClose()) {
                    if (ctx.client().screen instanceof OptionVoteScreen) {
                        ctx.client().setScreen(null);
                    }
                } else if (result.shouldAutoOpen()) {
                    Screen parent = ctx.client().screen;
                    // Rebuild if already open (mode→map) so title/list refresh; unwrap nested parents.
                    while (parent instanceof OptionVoteScreen open) {
                        parent = open.getParentScreen();
                    }
                    ctx.client().setScreen(new OptionVoteScreen(parent));
                }
            });
        });

        // 15) 贪婪匿名交易提示 — 打开专用双确认界面
        ClientPlayNetworking.registerGlobalReceiver(GreedTradePromptPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> ctx.client().setScreen(
                        new com.habitrain.core.client.gui.GreedTradePromptScreen(
                                ctx.client().screen, payload))));

        // 16) 对局结束结算画面 — 打开/更新 GameEndTransitionScreen
        ClientPlayNetworking.registerGlobalReceiver(com.habitrain.core.network.GameEndTransitionPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                if (ctx.client().screen instanceof com.habitrain.core.client.gui.GameEndTransitionScreen endScreen) {
                    endScreen.update(payload);
                    if (payload.environmentReady()) {
                        endScreen.markGameFinished();
                    }
                } else {
                    ctx.client().setScreen(new com.habitrain.core.client.gui.GameEndTransitionScreen(payload));
                }
            });
        });
    }
}
