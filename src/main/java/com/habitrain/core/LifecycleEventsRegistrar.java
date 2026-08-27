package com.habitrain.core;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.internal.CoreBootstrap;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.betel.BetelLeafHandler;
import com.habitrain.core.betel.BetelQuestState;
import com.habitrain.core.game.blackout.BlackoutExileVoteManager;
import com.habitrain.core.game.blackout.BlackoutPoliceHireService;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.game.sre.role.sins.trade.GreedTradeManager;
import com.habitrain.core.game.sre.EnvironmentController;
import com.habitrain.core.game.sre.SREGameModeBase;
import com.habitrain.core.game.sre.SREModeStartAdapter;
import com.habitrain.core.misc.EffectOwnershipTracker;
import com.habitrain.core.network.CustomTaskBlockPayload;
import com.habitrain.core.network.FullConfigSyncPayload;
import com.habitrain.core.network.MenuGatePayload;
import com.habitrain.core.task.BackpackQuestState;
import com.habitrain.core.task.BackpackSearchHandler;
import com.habitrain.core.task.SlownessReapplyManager;
import com.habitrain.core.vote.ModeMapVoteOrchestrator;
import com.habitrain.core.vote.OptionVoteManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生命周期事件注册器 — 负责注册 SERVER_STARTED / SERVER_STOPPING / JOIN / DISCONNECT 事件。
 * <p>在 {@link HabiTrainCore#onInitialize()} 中调用 {@link #init()}。</p>
 */
public final class LifecycleEventsRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|LifecycleEventsRegistrar");

    /**
     * Last observed camera/dimension per player, used to detect tracking-target
     * switches and dimension changes (review P2) so the role-state full snapshot
     * is re-pushed and the client drops mirrors it no longer has receive rights
     * to. Purely server-side bookkeeping; cleared on disconnect.
     */
    private static final java.util.Map<java.util.UUID, TrackedView> LAST_VIEW =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<java.util.UUID> PENDING_ROLE_STATE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A player's observation identity: camera entity + dimension. */
    private record TrackedView(java.util.UUID camera, net.minecraft.resources.ResourceKey<Level> dimension) {}

    private LifecycleEventsRegistrar() {}

    public static void init() {
        // 服务器启动后加载配置
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ConfigManager.getInstance().load();
            // 第二次加载发生在全部任务注册完成后；立即持久化一次性任务 ID 迁移。
            ConfigManager.getInstance().save();
            ConfigManager.getInstance().setServer(server);
            ConfigManager.getInstance().applyMinigameEnforcement(server);
            // 角色状态 v2：绑定实时 server 引用供 CCA store 与状态同步解析
            com.habitrain.core.role.state.RuntimeRoleServer.INSTANCE.bind(server);
            // 所有 entrypoint（含本 mod 与依赖 DLC）已在此前完成注册，
            // 现在冻结注册表，禁止运行期注册导致 CME 与状态不一致。
            // freeze() 仅在 CoreBootstrap 内生效，防止 DLC 误调提前冻住注册表。
            CoreBootstrap.run(() -> {
                TaskRegistry.freeze();
                GameModeRegistry.freeze();
            });
            // 角色覆盖引擎在配置加载后重建，确保读取真实配置而非默认值。
            com.habitrain.core.role.override.RoleOverrideLifecycleHandler.rebuildAfterConfigLoad();
            LOGGER.info("配置已加载，共 {} 个已注册任务（注册表已冻结）", TaskRegistry.size());

            // Seed modeMapVote defaults so ModMenu maps list is usable before first vote.
            // Map discovery reads both ServerMapConfig (train_vote_maps.json) and MapManager
            // (train_maps/ folder), populating display names, player counts, and pruning deleted maps.
            try {
                ServerLevel overworld = server.getLevel(Level.OVERWORLD);
                if (overworld != null) {
                    var discoveredMaps = com.habitrain.core.config.SREIntegration.discoverServerMaps(overworld);
                    boolean updated = ConfigManager.getInstance().syncAndPruneMaps(
                            GameModeRegistry.getAllIds(),
                            discoveredMaps);
                    if (updated) {
                        ConfigManager.getInstance().save();
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("modeMapVote syncAndPruneMaps on SERVER_STARTED skipped", t);
            }

            // 服务端启动后应用大厅环境（时间/天气/雪雾等）
            try {
                ServerLevel overworld = server.getLevel(Level.OVERWORLD);
                if (overworld != null) {
                    EnvironmentController.applyLobby(overworld);
                }
            } catch (Throwable t) {
                LOGGER.debug("initial lobby env apply skipped", t);
            }

            // Crash/restart: SRE may persist ACTIVE/STARTING blackout while core
            // BlackoutMode rounds are empty. Stop the stuck SRE game so votes work.
            try {
                stopStuckBlackoutSreRounds(server);
            } catch (Throwable t) {
                LOGGER.error("[Lifecycle] stuck-blackout scan failed", t);
            }
        });
        // 服务器关闭时清理停电模式各 manager 的 per-level 静态 Map 条目。
        // 单机模式下集成服务器停止后客户端 JVM 仍存活，static 字段不会重置，
        // 不清理会导致下一局残留状态（计时器/角色/商店/投票）误用。
        // 注：fabric-api 此版本无 ServerLevelEvents.UNLOAD，故在 SERVER_STOPPING 遍历所有 level 清理。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // G5-F012/F016：先把脏主配置和上次失败的角色 v2 配置落盘，再清 server 引用。
            try {
                ConfigManager.getInstance().save();
            } catch (Exception e) {
                LOGGER.error("停服保存主配置失败", e);
            }
            try {
                var roleCfg = com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE;
                if (roleCfg.lastSaveError() != null) {
                    roleCfg.save();
                }
            } catch (Exception e) {
                LOGGER.error("停服重试角色扩展配置失败", e);
            }
            ConfigManager.getInstance().setServer(null);
            try {
                io.wifi.starrailexpress.game.GameUtils.isStartingGame = false;
            } catch (Throwable t) {
                LOGGER.debug("clear GameUtils.isStartingGame skipped", t);
            }
            for (ServerLevel level : server.getAllLevels()) {
                if (GameModeRegistry.isActiveInLevel(level)) {
                    GameModeRegistry.stop(level);
                }
                BlackoutRoleManager.clear(level);
                BlackoutTimerSystem.reset(level);
                BlackoutPoliceHireService.cleanup(level);
                BlackoutExileVoteManager.reset(level);
                OptionVoteManager.reset(level);
                ModeMapVoteOrchestrator.reset(level);
                com.habitrain.core.game.sre.MapVoteLoadCoordinator.reset(level);
            }
            com.habitrain.core.game.sre.MapVoteLoadCoordinator.resetAll();
            OptionVoteManager.resetAll();
            ModeMapVoteOrchestrator.resetAll();
            com.habitrain.core.vote.MapFileMonitor.reset();
            // 清理所有跨局残留状态
            com.habitrain.core.game.sre.GameEndTransitionCoordinator.resetAll();
            com.habitrain.core.game.sre.MvpScoreTracker.resetAll();
            // 维修人员模式：停服前恢复所有维修员参与状态与游戏模式，避免 NBT 残留「不参与」
            com.habitrain.core.game.sre.RepairModeManager.resetAll(server);
            SlownessReapplyManager.clearAll();
            BetelLeafHandler.clearAllHarvests();
            BackpackSearchHandler.clearAllSearches();
            com.habitrain.core.game.blackout.task.AddCoalHandler.clearAll();
            com.habitrain.core.game.blackout.task.FurnaceExplosionHandler.clearAll();
            com.habitrain.core.game.blackout.task.MaintainPowerHandler.clearAll();
            com.habitrain.core.game.blackout.task.RestorePowerHandler.clearAll();
            com.habitrain.core.game.blackout.task.RepairWiringHandler.clearAll();
            com.habitrain.core.misc.EffectOwnershipTracker.clearAll();
            BetelQuestState.resetGameState();
            BackpackQuestState.getInstance().resetAll();
            // C11: 集成服务器同 JVM 重启时，静态环境/天气标志必须清掉
            EnvironmentController.clearRuntimeState();
            com.habitrain.core.game.sre.SREWeatherController.resetAll();
            GreedTradeManager.clearAll();
            // 电话会话 / 汽笛确认窗：集成服同 JVM 重启后 static 不重置
            com.habitrain.core.game.blackout.BlackoutPhoneSessionGate.clearAll();
            com.habitrain.core.game.blackout.BlackoutHornVoteHandler.clearAll();
            // 角色扩展 v2：恢复所有 MODIFY overlay 到基线，清空快照会话状态
            //（定义只加载一次；会话状态在 SERVER_STOPPED 清除）。
            GameModeRegistry.clearActiveModes();
            com.habitrain.core.role.override.RoleOverrideTickApplier.serverStop();
            com.habitrain.core.role.extension.RoleRuntimeOverlayApplier.serverStop();
            // 角色状态 v2：清空 transient + round 会话状态，保留 WORLD/PERMANENT 持久槽
            //（真实世界组件随 world NBT 在下次启动恢复，fix-doc §20.2）。
            ((com.habitrain.core.role.state.RoleStateServiceImpl)
                    com.habitrain.core.api.role.v2.state.RoleStateApi.instance()).serverStop();
            // 角色状态 v2：解绑 server 引用，避免集成服务器同 JVM 重启后残留陈旧引用。
            com.habitrain.core.role.state.RuntimeRoleServer.INSTANCE.unbind();
            // UUID 任务表跨集成服存档会串局：停服时清空活跃任务与离线回收队列。
            com.habitrain.core.task.TaskManager.getInstance().clearAll();
        });
        // 玩家加入
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            try {
                // G5-F017：局终只清在线玩家；离线/崩溃残留的局内 CCA 在进服时无条件清掉，
                // 再按本局角色 init（卖花女进行中则保留已持久的 stillTicks/rewarded）。
                com.habitrain.core.game.sre.role.HabiComponents.clearLeftoverRoundStateOnJoin(player);
            } catch (Exception e) {
                LOGGER.debug("role CCA leftover clear on JOIN skipped", e);
            }
            try {
                // 没有对局 → 大厅语音；有对局时观战/淘汰/休息加入 Train Spectators。
                if (!SREGameModeBase.isAnySreGameStartingOrRunning(server)) {
                    SREGameModeBase.queueLobbyGroupJoin(server, player.getUUID());
                } else if (shouldJoinMatchSpectatorVoice(player)) {
                    io.wifi.starrailexpress.compat.TrainVoicePlugin.addPlayer(player.getUUID());
                }
            } catch (Exception e) {
                LOGGER.error("[VoiceGroup] 处理语音群组加入失败", e);
            }
            // 始终下发 FullConfig；集成主机客户端自行跳过 import，避免同 JVM 竞态。
            CustomTaskBlockPayload.sendToPlayer(player);
            // FullConfigSync 已含 global + tasks + gameModes + minigames + shader，
            // 不再另发 TaskConfig / ShaderConfig（JOIN 包体积）。
            FullConfigSyncPayload.sendToPlayer(player);
            // 中途重连：仅在本维 SRE 对局 running 且任务维度匹配时重发 HUD，避免跨存档僵尸任务框
            try {
                var tm = com.habitrain.core.task.TaskManager.getInstance();
                tm.flushPendingReclaim(player);
                ServerLevel taskLevel = player.serverLevel();
                boolean sreRunning = false;
                if (taskLevel != null) {
                    try {
                        var gw = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(taskLevel);
                        sreRunning = gw != null && gw.isRunning();
                    } catch (Throwable t) {
                        sreRunning = false;
                    }
                }
                if (!sreRunning) {
                    tm.removeActiveTask(player.getUUID());
                    tm.removeFakeTask(player.getUUID());
                } else {
                    var active = tm.getActiveTask(player.getUUID());
                    if (active != null) {
                        var taskDim = active.getDimension();
                        if (taskDim == null || taskDim.equals(taskLevel.dimension())) {
                            com.habitrain.core.network.ActiveTaskPayload.sendToPlayer(
                                    player, active.getFullId(), false);
                            if (com.habitrain.core.game.blackout.BlackoutExclusiveTasks.isExclusive(active.getFullId())) {
                                com.habitrain.core.game.blackout.ExclusiveTaskHudSync.insert(player, active);
                            }
                        }
                    }
                    var fake = tm.getFakeTask(player.getUUID());
                    if (fake != null) {
                        var fakeDim = fake.getDimension();
                        if (fakeDim == null || fakeDim.equals(taskLevel.dimension())) {
                            com.habitrain.core.network.ActiveTaskPayload.sendToPlayer(
                                    player, fake.getFullId(), true);
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("ActiveTask resync on JOIN skipped", e);
            }
            // Mod 菜单访问门控同步：让客户端立即按授权状态决定是否锁定受门控页面
            MenuGatePayload.sendToPlayer(player);
            // 处理离线背包里遗留的刀耐久组件，确保全局开关对刚上线玩家同样生效。
            com.habitrain.core.game.sre.KnifeDurabilityToggleService.applyToPlayer(player);
            // 休息区/重连可能在主世界，对局在另一维度：按 UUID 解析 MATCH 模式。
            GameModeRegistry.resolveActiveForPlayer(player)
                    .ifPresent(mode -> mode.onPlayerJoin(player));
            // 角色扩展 manifest 握手：晚加入/中途重连的玩家立即获得当前服务端配置
            try {
                // Snapshot must precede the manifest: the client handshake report
                // includes the snapshot definition hash.
                com.habitrain.core.network.RoleSnapshotPayload.sendTo(player);
                com.habitrain.core.network.RoleManifestPayload.sendTo(player);
                // 角色状态 v2 全量同步（audit P0-2）：在 manifest/snapshot 之后推送
                // 该玩家有权接收的所有当前 slot（OWNER/OWNER_AND_TRACKING/ALL，
                // NONE/SERVER_ONLY 由 syncService 过滤），否则迟加入/重连玩家只能
                // 等到下一次状态变化才看到正确值。
                ((com.habitrain.core.role.state.RoleStateServiceImpl)
                        com.habitrain.core.api.role.v2.state.RoleStateApi.instance())
                        .sendCurrentStateTo(player.getUUID());
            } catch (Exception e) {
                LOGGER.debug("Role manifest/snapshot/state send on JOIN skipped", e);
            }
            // 同步最新地图介绍与地图档案（图片/中文描述/标签），确保进服立即可见并填充缓存
            try {
                var introPayload = com.habitrain.core.vote.MapFileMonitor.buildMapIntroPayload(server);
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, introPayload);

                ServerLevel overworld = server.overworld();
                if (overworld != null) {
                    var configMaps = ConfigManager.getInstance().getModeMapVoteSettings().maps;
                    var profiles = com.habitrain.core.vote.MapVoteProfileStore.loadProfiles(overworld, configMaps.keySet(), configMaps);
                    for (var fragment : com.habitrain.core.network.MapVoteProfilePayload.fragment(profiles)) {
                        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, fragment);
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Map intro/profile send on JOIN skipped", e);
            }

            // 同步进行中的 mode→map 投票 UI 给晚加入的玩家
            ModeMapVoteOrchestrator.onPlayerJoin(player);
        });
        // 维度切换改走 ServerEntityWorldChangeEvents，避免每 tick 扫全员。
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            if (player == null) {
                return;
            }
            try {
                java.util.UUID id = player.getUUID();
                net.minecraft.world.entity.Entity camera = player.getCamera();
                java.util.UUID cam = camera == null ? id : camera.getUUID();
                net.minecraft.resources.ResourceKey<Level> dim =
                        destination == null ? (player.level() == null ? null : player.level().dimension())
                                : destination.dimension();
                LAST_VIEW.put(id, new TrackedView(cam, dim));
                sendCurrentRoleState(player);
            } catch (Throwable t) {
                LOGGER.debug("role-state dimension resync skipped", t);
            }
        });
        // 角色状态 v2：观战镜头变化重同步。只检查旁观者（或 camera != self），
        // 维度变化已由 AFTER_PLAYER_CHANGE_WORLD 处理。PENDING_ROLE_STATE +
        // EntityTrackingEvents 仍负责 tracking 边沿。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                if (!PENDING_ROLE_STATE.isEmpty()) {
                    for (java.util.UUID pendingId : PENDING_ROLE_STATE) {
                        sendCurrentRoleStateUuid(pendingId);
                    }
                    PENDING_ROLE_STATE.clear();
                }
                for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (!p.isSpectator()) {
                        continue;
                    }
                    java.util.UUID id = p.getUUID();
                    net.minecraft.world.entity.Entity camera = p.getCamera();
                    java.util.UUID cam = camera == null ? id : camera.getUUID();
                    net.minecraft.resources.ResourceKey<Level> dim = p.level() == null ? null : p.level().dimension();
                    TrackedView prev = LAST_VIEW.get(id);
                    if (prev != null && prev.camera().equals(cam) && java.util.Objects.equals(prev.dimension(), dim)) {
                        continue;
                    }
                    LAST_VIEW.put(id, new TrackedView(cam, dim));
                    if (prev != null) {
                        sendCurrentRoleState(p);
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("role-state view resync tick skipped", t);
            }
        });
        // Fabric entity tracking is broader than spectator-camera following.
        // A full filtered snapshot on both edges makes OWNER_AND_TRACKING mirrors
        // appear immediately and removes them as soon as tracking stops.
        EntityTrackingEvents.START_TRACKING.register((trackedEntity, observer) -> {
            if (trackedEntity instanceof net.minecraft.world.entity.player.Player && observer != null) {
                PENDING_ROLE_STATE.add(observer.getUUID());
            }
        });
        EntityTrackingEvents.STOP_TRACKING.register((trackedEntity, observer) -> {
            if (trackedEntity instanceof net.minecraft.world.entity.player.Player && observer != null) {
                PENDING_ROLE_STATE.add(observer.getUUID());
            }
        });
        // 玩家断线：通知激活的 GameMode 处理。
        // 停电模式据此把断线玩家移出存活阵营，避免其继续被计为放逐候选人或卡住胜负判定（Q8）。
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player == null) return;
            try {
                // 清除汽笛确认窗口（断线后无需保留）
                com.habitrain.core.game.blackout.BlackoutHornVoteHandler.onPlayerRemoved(player.getUUID());
                // 立刻从语音 pending 队列移除（不必等下一 tick）
                SREGameModeBase.removePendingVoiceJoin(player.getUUID());
                // 清除效果归属追踪数据
                EffectOwnershipTracker.clearPlayer(player.getUUID());
                SlownessReapplyManager.unregisterAllLevels(player.getUUID());
                // 贪婪匿名交易：断线立即取消 session，避免对方卡 UI 等到超时
                GreedTradeManager.onPlayerDisconnect(server, player.getUUID());
                // 维修人员模式：断线自动解锁其锁定的地图并恢复参与状态/游戏模式
                com.habitrain.core.game.sre.RepairModeManager.onPlayerDisconnect(player.getUUID(), server);
                GameModeRegistry.resolveActiveForPlayer(player)
                        .ifPresent(mode -> mode.onPlayerLeave(player));
                // 断线保留本轮选票；当前维度无投票时改找 MATCH/其它维度。
                ServerLevel voteLevel = OptionVoteManager.resolveActiveVoteLevel(player);
                if (voteLevel != null) {
                    OptionVoteManager.onVoterDisconnected(voteLevel, player.getUUID());
                }
                // 角色动作 v2：断线清理该玩家的 sequence/rate/cooldown 窗口（fix-doc §12.2）
                ((com.habitrain.core.role.action.RoleActionServiceImpl)
                        com.habitrain.core.api.role.v2.action.RoleActionApi.instance())
                        .onPlayerDisconnect(player.getUUID());
                // 角色扩展握手（audit P1-4）：断线清除该玩家的上报，避免把上一连接的
                // manifest 带入下一次连接。
                com.habitrain.core.role.config.RoleHandshakeGate.INSTANCE
                        .clear(player.getUUID());
                // 角色状态 v2（复审 P2）：断线清除观战/维度基线，避免下次上线用旧基线
                // 误触发重同步。
                LAST_VIEW.remove(player.getUUID());
                PENDING_ROLE_STATE.remove(player.getUUID());
                com.habitrain.core.game.blackout.BlackoutPhoneSessionGate.clearPlayer(player);
                com.habitrain.core.network.C2SRateLimiter.clear(player.getUUID());
                com.habitrain.core.C2SReceiverRegistrar.clearConfigUpdateHistory(player.getUUID());
                com.habitrain.core.task.TaskManager.getInstance().unbindOwner(player.getUUID());
            } catch (Exception e) {
                LOGGER.error("[GameMode] 处理玩家断线失败", e);
            }
        });
    }

    /**
     * After a crash, SRE NBT can restore blackout ACTIVE/STARTING with no core round.
     * Do not reconstruct timers; stop SRE so votes are unblocked.
     */
    private static void stopStuckBlackoutSreRounds(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            try {
                stopStuckBlackoutSreRound(level);
            } catch (Throwable t) {
                LOGGER.error("[Lifecycle] stuck-blackout scan failed for {}",
                        level.dimension().location(), t);
            }
        }
    }

    private static void stopStuckBlackoutSreRound(ServerLevel level) {
        if (level == null) return;
        var gw = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(level);
        if (gw == null) return;
        var status = gw.getGameStatus();
        if (status != io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.ACTIVE
                && status != io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.STARTING) {
            return;
        }
        var sreMode = gw.gameMode;
        if (sreMode == null
                || !com.habitrain.core.game.blackout.sre.SREBlackoutGameMode.MODE_ID.equals(sreMode.identifier)) {
            return;
        }
        var active = GameModeRegistry.getActiveForLevel(level).orElse(null);
        if (active instanceof com.habitrain.core.game.blackout.BlackoutMode) {
            return;
        }
        LOGGER.warn("[Lifecycle] SRE blackout is {} in {} but core BlackoutMode round is missing; stopping SRE to unblock votes",
                status, level.dimension().location());
        try {
            io.wifi.starrailexpress.game.GameUtils.stopGame(level);
        } catch (Throwable t) {
            LOGGER.error("[Lifecycle] GameUtils.stopGame failed for stuck blackout in {}; forcing INACTIVE",
                    level.dimension().location(), t);
            gw.setGameStatus(io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.INACTIVE);
        }
    }

    /** Mid-round spectator / eliminated / rest reconnect joins Train Spectators. */
    private static boolean shouldJoinMatchSpectatorVoice(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (player.isSpectator()) {
            return true;
        }
        try {
            if (io.wifi.starrailexpress.game.GameUtils.isPlayerEliminated(player)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            return com.habitrain.core.game.sre.EliminatedRestAreaService.isResting(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void sendCurrentRoleState(ServerPlayer observer) {
        if (observer == null) {
            return;
        }
        sendCurrentRoleStateUuid(observer.getUUID());
    }

    private static void sendCurrentRoleStateUuid(java.util.UUID playerId) {
        if (playerId == null) {
            return;
        }
        try {
            ((com.habitrain.core.role.state.RoleStateServiceImpl)
                    com.habitrain.core.api.role.v2.state.RoleStateApi.instance())
                    .sendCurrentStateTo(playerId);
        } catch (Throwable t) {
            LOGGER.debug("role-state tracking resync skipped", t);
        }
    }
}
