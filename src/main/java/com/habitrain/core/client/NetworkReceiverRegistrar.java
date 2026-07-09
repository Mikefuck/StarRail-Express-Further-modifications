package com.habitrain.core.client;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.cache.ActiveTaskCache;
import com.habitrain.core.client.gui.BlackoutHudOverlay;
import com.habitrain.core.client.gui.BlackoutPhoneHireScreen;
import com.habitrain.core.client.gui.BlackoutSheriffVoteScreen;
import com.habitrain.core.client.gui.BlackoutSheriffVoteState;
import com.habitrain.core.client.gui.BlackoutVoteScreen;
import com.habitrain.core.client.gui.BlackoutVoteState;
import com.habitrain.core.client.gui.BlackoutWelcomeRenderer;
import com.habitrain.core.client.gui.ClientBlackoutState;
import com.habitrain.core.client.InstinctColorHelper;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.network.BlackoutAnnouncePayload;
import com.habitrain.core.network.BlackoutHireResultPayload;
import com.habitrain.core.network.BlackoutPhoneOpenPayload;
import com.habitrain.core.network.BlackoutSheriffVotePayload;
import com.habitrain.core.network.BlackoutTimerPayload;
import com.habitrain.core.network.BlackoutVotePayload;
import com.habitrain.core.network.CustomTaskBlockPayload;
import com.habitrain.core.network.FullConfigSyncPayload;
import com.habitrain.core.network.ShaderConfigPayload;
import com.habitrain.core.network.TaskConfigPayload;
import com.habitrain.core.network.VotePurpose;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

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
            });
        });

        // =========================================================
        //  停电模式 — S2C 接收器
        // =========================================================

        // 6) 时间同步
        ClientPlayNetworking.registerGlobalReceiver(BlackoutTimerPayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                if (payload.totalTimeRemaining() <= 0) {
                    BlackoutHudOverlay.reset();
                    BlackoutWelcomeRenderer.reset();
                    ClientBlackoutState.setBlackoutModeActive(false);
                    return;
                }

                ClientBlackoutState.setBlackoutModeActive(true);
                BlackoutHudOverlay.updateTime(
                    payload.totalTimeRemaining(), payload.endTimeTick(), payload.blackoutActive(), payload.phase());
            });
        });

        // 7) 警长投票 S2C 接收器
        ClientPlayNetworking.registerGlobalReceiver(BlackoutSheriffVotePayload.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                BlackoutSheriffVoteState.update(payload);
                if (!payload.active() && ctx.client().screen instanceof BlackoutSheriffVoteScreen) {
                    ctx.client().setScreen(null);
                }
            });
        });

        // 8) 电话打开状态 S2C 接收器
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
                if (!payload.active() && ctx.client().screen instanceof BlackoutVoteScreen) {
                    ctx.client().setScreen(null);
                }
                if (payload.active() && VotePurpose.EXILE.equals(payload.purpose())) {
                    // 放逐投票自动打开 GUI（如果当前不在其他 screen）
                    if (!(ctx.client().screen instanceof BlackoutVoteScreen) && ctx.client().screen == null) {
                        ctx.client().setScreen(new BlackoutVoteScreen(null));
                    }
                }
            });
        });
    }
}
