package com.habitrain.core;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskRegistry;
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
import com.habitrain.core.network.ShaderConfigPayload;
import com.habitrain.core.network.TaskConfigPayload;
import com.habitrain.core.task.BackpackQuestState;
import com.habitrain.core.task.BackpackSearchHandler;
import com.habitrain.core.task.SlownessReapplyManager;
import com.habitrain.core.vote.ModeMapVoteOrchestrator;
import com.habitrain.core.vote.OptionVoteManager;
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

    /** A player's observation identity: camera entity + dimension. */
    private record TrackedView(java.util.UUID camera, String dimension) {}

    private LifecycleEventsRegistrar() {}

    public static void init() {
        // 服务器启动后加载配置
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ConfigManager.getInstance().load();
            ConfigManager.getInstance().setServer(server);
            ConfigManager.getInstance().applyMinigameEnforcement(server);
            // 角色状态 v2：绑定实时 server 引用供 CCA store 与状态同步解析
            com.habitrain.core.role.state.RuntimeRoleServer.INSTANCE.bind(server);
            // 所有 entrypoint（含本 mod 与依赖 DLC）已在此前完成注册，
            // 现在冻结注册表，禁止运行期注册导致 CME 与状态不一致。
            TaskRegistry.freeze();
            GameModeRegistry.freeze();
            // 角色覆盖引擎在配置加载后重建，确保读取真实配置而非默认值。
            com.habitrain.core.role.override.RoleOverrideLifecycleHandler.rebuildAfterConfigLoad();
            LOGGER.info("配置已加载，共 {} 个已注册任务（注册表已冻结）", TaskRegistry.size());

            // Seed modeMapVote defaults so ModMenu maps list is usable before first vote.
            // Map discovery needs ServerLevel (train_maps under world path); client VoteTab
            // cannot call MapManager safely — server start is the client/server-safe hook.
            try {
                ServerLevel overworld = server.getLevel(Level.OVERWORLD);
                if (overworld != null) {
                    ConfigManager.getInstance().ensureModeMapVoteDefaults(
                            GameModeRegistry.getAllIds(),
                            SREModeStartAdapter.getAvailableMaps(overworld));
                }
            } catch (Throwable t) {
                LOGGER.debug("modeMapVote ensureDefaults on SERVER_STARTED skipped", t);
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
        });
        // 服务器关闭时清理停电模式各 manager 的 per-level 静态 Map 条目。
        // 单机模式下集成服务器停止后客户端 JVM 仍存活，static 字段不会重置，
        // 不清理会导致下一局残留状态（计时器/角色/商店/投票）误用。
        // 注：fabric-api 此版本无 ServerLevelEvents.UNLOAD，故在 SERVER_STOPPING 遍历所有 level 清理。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ConfigManager.getInstance().setServer(null);
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
            OptionVoteManager.resetAll();
            ModeMapVoteOrchestrator.resetAll();
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
            com.habitrain.core.role.extension.RoleRuntimeOverlayApplier.serverStop();
            // 角色状态 v2：清空 transient + round 会话状态，保留 WORLD/PERMANENT 持久槽
            //（真实世界组件随 world NBT 在下次启动恢复，fix-doc §20.2）。
            ((com.habitrain.core.role.state.RoleStateServiceImpl)
                    com.habitrain.core.api.role.v2.state.RoleStateApi.instance()).serverStop();
            // 角色状态 v2：解绑 server 引用，避免集成服务器同 JVM 重启后残留陈旧引用。
            com.habitrain.core.role.state.RuntimeRoleServer.INSTANCE.unbind();
        });
        // 玩家加入
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            try {
                // 如果当前没有 SRE 对局运行 → 入队等待加入大厅语音群组
                if (!SREGameModeBase.isAnySreGameStartingOrRunning(server)) {
                    SREGameModeBase.queueLobbyGroupJoin(server, player.getUUID());
                }
                // 如果有对局运行，不入队（避免把游戏中的玩家拉进大厅群组）
            } catch (Exception e) {
                LOGGER.error("[VoiceGroup] 处理语音群组加入失败", e);
            }
            // 同步配置。单机（集成服务器）跳过：客户端与服务端同 JVM 共享
            // ConfigManager，渲染线程 clear+putAll 会与服务端线程读取竞态
            // （review M17；与 C2S 广播路径的 isSingleplayer 守卫一致）。
            if (!server.isSingleplayer()) {
                TaskConfigPayload.sendToPlayer(player);
                CustomTaskBlockPayload.sendToPlayer(player);
                ShaderConfigPayload.sendToPlayer(player);
                // 完整配置同步（global + tasks + gameModes + minigames）：让客户端显示服务端真实值，
                // 避免 OP 联机保存时用本地过期全局项覆盖服务端。
                FullConfigSyncPayload.sendToPlayer(player);
            }
            // 中途重连：重发该玩家当前活跃/假 DLC 任务，否则客户端 ActiveTaskCache 为空 → 无自定义任务框
            try {
                var tm = com.habitrain.core.task.TaskManager.getInstance();
                var active = tm.getActiveTask(player.getUUID());
                if (active != null) {
                    com.habitrain.core.network.ActiveTaskPayload.sendToPlayer(
                            player, active.getFullId(), false);
                }
                var fake = tm.getFakeTask(player.getUUID());
                if (fake != null) {
                    com.habitrain.core.network.ActiveTaskPayload.sendToPlayer(
                            player, fake.getFullId(), true);
                }
            } catch (Exception e) {
                LOGGER.debug("ActiveTask resync on JOIN skipped", e);
            }
            // Mod 菜单访问门控同步：让客户端立即按授权状态决定是否锁定受门控页面
            MenuGatePayload.sendToPlayer(player);
            // 处理离线背包里遗留的刀耐久组件，确保全局开关对刚上线玩家同样生效。
            com.habitrain.core.game.sre.KnifeDurabilityToggleService.applyToPlayer(player);
            // 通知激活的 GameMode 玩家加入（用玩家所在维度，与 DISCONNECT 一致）
            ServerLevel joinLevel = player.serverLevel();
            if (joinLevel != null) {
                GameModeRegistry.getActiveForLevel(joinLevel)
                    .ifPresent(mode -> mode.onPlayerJoin(player));
            }
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
            // 同步进行中的 mode→map 投票 UI 给晚加入的玩家
            ModeMapVoteOrchestrator.onPlayerJoin(player);
        });
        // 角色状态 v2：观战/维度变化重同步（复审 P2）。观战者切换跟踪目标或玩家切换
        // 维度后，该玩家对 OWNER_AND_TRACKING / WORLD 槽位的接收权发生变化；客户端可能
        // 残留旧镜像（尤其槽位已被服务端删除时）。这里在变化发生的下一 tick 重新推送
        // 该玩家按权限过滤的全量快照（snapshot 语义会让客户端清空旧镜像后应用新全集）。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
                    java.util.UUID id = p.getUUID();
                    net.minecraft.world.entity.Entity camera = p.getCamera();
                    java.util.UUID cam = camera == null ? id : camera.getUUID();
                    String dim = (p.level() == null || p.level().dimension() == null)
                            ? "" : p.level().dimension().location().toString();
                    TrackedView prev = LAST_VIEW.put(id, new TrackedView(cam, dim));
                    if (prev == null) {
                        continue; // first observation: baseline only
                    }
                    if (!prev.camera().equals(cam) || !prev.dimension().equals(dim)) {
                        ((com.habitrain.core.role.state.RoleStateServiceImpl)
                                com.habitrain.core.api.role.v2.state.RoleStateApi.instance())
                                .sendCurrentStateTo(id);
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("role-state view resync tick skipped", t);
            }
        });
        // Fabric entity tracking is broader than spectator-camera following.
        // A full filtered snapshot on both edges makes OWNER_AND_TRACKING mirrors
        // appear immediately and removes them as soon as tracking stops.
        EntityTrackingEvents.START_TRACKING.register((trackedEntity, observer) ->
                sendCurrentRoleState(observer));
        EntityTrackingEvents.STOP_TRACKING.register((trackedEntity, observer) ->
                sendCurrentRoleState(observer));
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
                ServerLevel disconnectLevel = player.serverLevel();
                if (disconnectLevel != null) {
                    GameModeRegistry.getActiveForLevel(disconnectLevel)
                        .ifPresent(mode -> mode.onPlayerLeave(player));
                    // 从进行中的选项投票中移除断线玩家的票
                    OptionVoteManager.onVoterRemoved(disconnectLevel, player.getUUID());
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
            } catch (Exception e) {
                LOGGER.error("[GameMode] 处理玩家断线失败", e);
            }
        });
    }

    private static void sendCurrentRoleState(ServerPlayer observer) {
        if (observer == null) {
            return;
        }
        try {
            ((com.habitrain.core.role.state.RoleStateServiceImpl)
                    com.habitrain.core.api.role.v2.state.RoleStateApi.instance())
                    .sendCurrentStateTo(observer.getUUID());
        } catch (Throwable t) {
            LOGGER.debug("role-state tracking resync skipped", t);
        }
    }
}
