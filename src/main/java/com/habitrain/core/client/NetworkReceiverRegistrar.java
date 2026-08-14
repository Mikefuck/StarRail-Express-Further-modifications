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
import com.habitrain.core.client.gui.GameEndOverlayState;
import com.habitrain.core.client.gui.OptionVoteScreen;
import com.habitrain.core.client.gui.OptionVoteState;
import com.habitrain.core.client.gui.VoteLaunchSession;
import com.habitrain.core.client.gui.VoteLaunchTransitionScreen;
import com.habitrain.core.client.gui.VoteLaunchOverlayState;
import com.habitrain.core.client.InstinctColorHelper;
import com.habitrain.core.client.menu.MenuAccessGuard;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.sre.CustomTaskBlockCache;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.network.BlackoutAnnouncePayload;
import com.habitrain.core.network.BlackoutHireResultPayload;
import com.habitrain.core.network.BlackoutPhoneOpenPayload;
import com.habitrain.core.network.BlackoutTimerPayload;
import com.habitrain.core.network.BlackoutVotePayload;
import com.habitrain.core.network.CustomTaskBlockPayload;
import com.habitrain.core.network.EliminatedRestPromptPayload;
import com.habitrain.core.network.FullConfigSyncPayload;
import com.habitrain.core.network.GreedTradePromptPayload;
import com.habitrain.core.network.MenuGatePayload;
import com.habitrain.core.network.MapVoteLaunchAbortPayload;
import com.habitrain.core.network.MapVoteLaunchTransitionPayload;
import com.habitrain.core.network.MapVoteProgressPayload;
import com.habitrain.core.network.MapVoteProfilePayload;
import com.habitrain.core.network.MapVoteStartConfirmedPayload;
import com.habitrain.core.network.OptionVotePayload;
import com.habitrain.core.network.RepairModeSyncPayload;
import com.habitrain.core.network.RoleActionS2CPayload;
import com.habitrain.core.network.ShaderConfigPayload;
import com.habitrain.core.network.TaskConfigPayload;
import com.habitrain.core.client.role.RoleActionClientState;
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

        ClientPlayNetworking.registerGlobalReceiver(EliminatedRestPromptPayload.TYPE, (payload, context) ->
                context.client().execute(() -> EliminatedRestPromptState.update(
                        payload.visible(), payload.canToggle())));

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

        // 5b) 接收服务端 Mod 菜单访问门控状态（专用服务器联机时锁定未授权玩家页面）
        ClientPlayNetworking.registerGlobalReceiver(MenuGatePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> MenuAccessGuard.update(payload));
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
                // 维修员不看投票/开局转场界面：状态照常更新，但绝不自动打开或强制切换画面。
                if (RepairModeClientState.isLocalRepairer()) {
                    if (result.shouldStartMapTransition()
                            && ctx.client().screen instanceof VoteLaunchTransitionScreen) {
                        VoteLaunchSession.clear();
                        ctx.client().setScreen(null); // 进入维修模式时残留的转场屏立即交还
                    }
                    return;
                }
                // Auto-open once per phase (inactive→active or voteId change).
                // 1Hz rebroadcasts must not re-force the screen if the player closed it.
                if (result.shouldStartMapTransition()) {
                    VoteLaunchSession.begin(result.resolvedOptionId());
                    Screen destination = ctx.client().screen;
                    ctx.client().setScreen(new VoteLaunchTransitionScreen(
                            destination, result.resolvedOptionId()));
                } else if (result.shouldClose()) {
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

        // 14b) 地图投票档案 S2C 接收器（一次性推送，仅存状态，解码在渲染线程懒做）
        ClientPlayNetworking.registerGlobalReceiver(MapVoteProfilePayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> OptionVoteState.applyProfiles(payload)));

        // 15) 开局加载进度 — 始终写入 Session（hide 后进度不断档），屏打开时同步 UI
        ClientPlayNetworking.registerGlobalReceiver(MapVoteProgressPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    if (RepairModeClientState.isLocalRepairer()) {
                        return; // 维修员不看加载转场
                    }
                    VoteLaunchSession.updateProgress(payload.progress(), payload.playerCount(),
                            payload.killerCount(), payload.mapId(), payload.modeId());
                    if (ctx.client().screen instanceof VoteLaunchTransitionScreen transition) {
                        transition.updateProgress(payload.progress(), payload.playerCount(),
                                payload.killerCount(), payload.mapId(), payload.modeId());
                    }
                }));

        // 15b) 判定点 A：地图重置完成 / trueStartGame 进入 STARTING。
        //      sticky 隐藏意图 → 左→右补盖 + 「对局开始」；可见 → 锁定 hide，必要时保险开屏。
        //      recover 路径禁止走 openSafetyCover（那是加载模式，会错误显示「开局加载中」）。
        ClientPlayNetworking.registerGlobalReceiver(MapVoteStartConfirmedPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    if (RepairModeClientState.isLocalRepairer()) {
                        return;
                    }
                    if (!VoteLaunchSession.isActive()) {
                        // 中途加入等未 begin 的客户端：不强制转场（与现产品一致）
                        return;
                    }
                    VoteLaunchSession.StartConfirmedResult result =
                            VoteLaunchSession.onStartConfirmed(payload.mapId());
                    boolean onTransition = ctx.client().screen instanceof VoteLaunchTransitionScreen;
                    if (result.recover()) {
                        // 始终重建补盖屏，保证左→右入场从零开始；内容直接是「对局开始」
                        Screen parent = ctx.client().screen;
                        if (parent instanceof VoteLaunchTransitionScreen open) {
                            parent = open.getDestinationOrNull();
                        }
                        ctx.client().setScreen(VoteLaunchTransitionScreen.openRecover(parent));
                        return;
                    }
                    // 可见路径：锁定 hide；若屏意外不在转场上，保险全屏盖住防 TP 露馅（仍是加载内容）
                    if (!onTransition) {
                        ctx.client().setScreen(VoteLaunchTransitionScreen.openSafetyCover(
                                ctx.client().screen));
                    } else {
                        ((VoteLaunchTransitionScreen) ctx.client().screen).pullFromSession();
                    }
                }));

        // 16) 判定点 B：开局环境就绪 — 可见路径原地切「对局开始」；补盖路径仅同步 mapId。
        //     若 A 漏处理但玩家曾 sticky 隐藏，先 promote 再开 recover，避免静默丢包。
        ClientPlayNetworking.registerGlobalReceiver(MapVoteLaunchTransitionPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    if (RepairModeClientState.isLocalRepairer()) {
                        return; // 维修员不看开局转场
                    }
                    // A 漏了但仍有 sticky hide：迟到 promote 为 recover，遮住后续 TP/世界
                    VoteLaunchSession.promoteStickyHideToRecoverIfNeeded();
                    VoteLaunchSession.onLaunchConfirmed(payload.winningMapId());
                    if (ctx.client().screen instanceof VoteLaunchTransitionScreen transition) {
                        // 若 sticky 已 promote 但当前仍是加载屏：重建 recover，避免继续显示加载中
                        if (VoteLaunchSession.isRecoverPath() && !transition.isRecoverPresentation()) {
                            Screen parent = transition.getDestinationOrNull();
                            VoteLaunchTransitionScreen recover =
                                    VoteLaunchTransitionScreen.openRecover(parent);
                            recover.confirmLaunch(payload.winningMapId());
                            ctx.client().setScreen(recover);
                            return;
                        }
                        transition.confirmLaunch(payload.winningMapId());
                        return;
                    }
                    // 补盖路径：强制开「对局开始」（世界 HUD / null 屏均可）
                    if (VoteLaunchSession.isActive() && VoteLaunchSession.isRecoverPath()) {
                        VoteLaunchTransitionScreen transition =
                                VoteLaunchTransitionScreen.openRecover(ctx.client().screen);
                        transition.confirmLaunch(payload.winningMapId());
                        ctx.client().setScreen(transition);
                        return;
                    }
                    // 可见路径：session 仍 active 但屏丢了 → 保险开屏并切标题
                    if (VoteLaunchSession.isActive()) {
                        VoteLaunchTransitionScreen transition =
                                VoteLaunchTransitionScreen.openSafetyCover(ctx.client().screen);
                        transition.confirmLaunch(payload.winningMapId());
                        ctx.client().setScreen(transition);
                        return;
                    }
                    // 遗留兜底：仍停在投票屏且无 session（极端时序）
                    if (!(ctx.client().screen instanceof OptionVoteScreen)) {
                        return;
                    }
                    VoteLaunchSession.begin(payload.winningMapId());
                    VoteLaunchSession.onLaunchConfirmed(payload.winningMapId());
                    Screen destination = ctx.client().screen;
                    VoteLaunchTransitionScreen transition = new VoteLaunchTransitionScreen(
                            destination, payload.winningMapId());
                    transition.confirmLaunch(payload.winningMapId());
                    ctx.client().setScreen(transition);
                }));

        // 16b) 开局中止 — 服务端在开局确认前发现游戏未真正启动（人数不足等）时广播，
        //      客户端立即交还画面，避免在加载/扫场画面无限等待。
        //      无论当前是否为转场屏，都清理覆盖层状态（黑场/相机屏蔽），防止动画残留。
        ClientPlayNetworking.registerGlobalReceiver(MapVoteLaunchAbortPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    if (RepairModeClientState.isLocalRepairer()) {
                        return; // 维修员无转场屏可交还
                    }
                    VoteLaunchSession.onAbort();
                    if (ctx.client().screen instanceof VoteLaunchTransitionScreen transition) {
                        transition.markGameAborted();
                    }
                    VoteLaunchOverlayState.scheduleGrace(0L);
                }));

        // 17) 贪婪匿名交易提示 — 打开专用双确认界面
        ClientPlayNetworking.registerGlobalReceiver(GreedTradePromptPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> ctx.client().setScreen(
                        new com.habitrain.core.client.gui.GreedTradePromptScreen(
                                ctx.client().screen, payload))));

        // 18) 对局结束转场 — STOPPING 时先发静态遮挡，赛后环境应用完成后再发动画阶段。
        //     对局结束是权威事件，无条件接管当前画面（此时不可能有投票屏；
        //     极端情况下开局转场屏残留也会被本屏覆盖）。维修员不接收（服务端也过滤）。
        ClientPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.network.GameEndTransitionPayload.TYPE, (payload, ctx) ->
                        ctx.client().execute(() -> {
                            if (RepairModeClientState.isLocalRepairer()) {
                                return; // 维修员不看对局结束转场
                            }
                            if (ctx.client().screen
                                    instanceof com.habitrain.core.client.gui.GameEndTransitionScreen transition) {
                                transition.update(payload);
                                return;
                            }
                            ctx.client().setScreen(
                                    new com.habitrain.core.client.gui.GameEndTransitionScreen(payload));
                        }));

        // 19) 维修模式状态同步 — 客户端据此屏蔽开局黑场/转场与结尾动画。
        //     进入时若正停留在投票/开局转场界面立即交还并清空覆盖层，避免残留画面。
        ClientPlayNetworking.registerGlobalReceiver(RepairModeSyncPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    RepairModeClientState.setRepairing(payload.isRepairing());
                    if (payload.isRepairing()) {
                        VoteLaunchSession.clear();
                        VoteLaunchOverlayState.scheduleGrace(0L);
                        GameEndOverlayState.scheduleGrace(0L);
                        if (ctx.client().screen instanceof OptionVoteScreen
                                || ctx.client().screen instanceof VoteLaunchTransitionScreen
                                || ctx.client().screen instanceof com.habitrain.core.client.gui.GameEndTransitionScreen) {
                            ctx.client().setScreen(null);
                        }
                    }
                }));

        // 20) 角色动作结果：push 走独立推送监听，否则按 (actionId, sequence) 解析挂起请求。
        ClientPlayNetworking.registerGlobalReceiver(RoleActionS2CPayload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    if (payload.push()) {
                        com.habitrain.core.client.role.RoleActionClientSession.INSTANCE
                                .onPush(payload.actionId(), payload.payload());
                    } else {
                        com.habitrain.core.client.role.RoleActionClientSession.INSTANCE
                                .onResult(payload.actionId(), payload.sequence(), payload.ok(),
                                        payload.reasonKey(), payload.payload());
                    }
                    RoleActionClientState.accept(
                            payload.actionId(),
                            payload.sequence(),
                            payload.ok(),
                            payload.reasonKey(),
                            payload.payload());
                }));

        // 20) 角色状态同步（per-slot SyncPolicy）→ 客户端镜像缓存（opaque 保存）
        ClientPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.network.RoleStateSyncPayload.TYPE, (payload, ctx) ->
                        ctx.client().execute(() ->
                                com.habitrain.core.client.role.RoleStateClientCache.accept(payload)));

        // 21) 角色扩展 manifest 握手（§14.2）→ 客户端握手状态（Mod Menu 页读取）
        ClientPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.network.RoleManifestPayload.TYPE, (payload, ctx) ->
                        ctx.client().execute(() ->
                                com.habitrain.core.client.role.RoleHandshakeState.INSTANCE
                                        .accept(payload.toManifest())));

        // 22) 角色扩展编译条目快照（§13.2）→ 客户端页面数据源
        ClientPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.network.RoleSnapshotPayload.TYPE, (payload, ctx) ->
                        ctx.client().execute(() ->
                                com.habitrain.core.client.role.RoleSnapshotState.INSTANCE.accept(payload)));
    }
}
