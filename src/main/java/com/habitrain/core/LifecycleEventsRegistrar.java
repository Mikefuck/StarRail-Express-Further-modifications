package com.habitrain.core;

import com.habitrain.core.game.sre.environment.SREWeatherController;
import com.habitrain.core.game.sre.map.MapVoteLoadCoordinator;
import com.habitrain.core.game.sre.match.GameEndTransitionCoordinator;
import com.habitrain.core.game.sre.match.MvpScoreTracker;
import com.habitrain.core.game.sre.mode.RepairModeManager;
import com.habitrain.core.game.sre.config.SREIntegration;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.internal.CoreLifecycleScope;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MenuGateService;
import com.habitrain.core.game.sre.environment.EnvironmentController;
import com.habitrain.core.game.sre.mode.SREGameModeBase;
import com.habitrain.core.game.sre.voice.VoiceChatPresence;
import com.habitrain.core.misc.EffectOwnershipTracker;
import com.habitrain.core.network.CustomTaskBlockPayload;
import com.habitrain.core.network.FullConfigSyncPayload;
import com.habitrain.core.network.MenuGatePayload;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.snapshot.RoleSnapshotArchive;
import com.habitrain.core.role.snapshot.RoleSnapshotManager;
import com.habitrain.core.task.BackpackQuestState;
import com.habitrain.core.task.BackpackSearchHandler;
import com.habitrain.core.task.ClearableHandlerRegistry;
import com.habitrain.core.task.GameLifecycleHandler;
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
            com.habitrain.core.internal.ServerFileTasks.start(server);
            com.habitrain.core.role.capability.ServerVoicePermissions.start();
        com.habitrain.core.game.sre.match.ServerMatchContext.GATE.reset();
            com.habitrain.core.scene.server.SceneTransferService.getInstance().start();
            com.habitrain.core.scene.server.SceneCaptureService.getInstance().start();
        com.habitrain.core.scene.server.SceneStagingService.getInstance().start();
            ConfigManager.getInstance().load();
            // CORE-08：单机集成服切换存档或开服前手动改盘时，重新加载门控与角色扩展 v2 配置
            MenuGateService.load();
            RoleExtensionConfigService.INSTANCE.load();
            ConfigManager.getInstance().setServer(server);
            ConfigManager.getInstance().applyMinigameEnforcement(server);
            // 角色状态 v2：绑定实时 server 引用供 CCA store 与状态同步解析
            com.habitrain.core.role.state.RuntimeRoleServer.INSTANCE.bind(server);
            // 所有 entrypoint（含本 mod 与依赖 DLC）已在此前完成注册，
            // 现在冻结注册表，禁止运行期注册导致 CME 与状态不一致。
            // freeze() 仅在 CoreLifecycleScope 内生效，防止 DLC 误调提前冻住注册表。
            CoreLifecycleScope.run(() -> {
                TaskRegistry.freeze();
                GameModeRegistry.freeze();
            });
            // 角色扩展在配置加载后编译，确保读取真实配置而非默认值。
            com.habitrain.core.role.extension.RoleExtensionLifecycleHandler.rebuildAfterConfigLoad();
            LOGGER.info("配置已加载，共 {} 个已注册任务（注册表已冻结）", TaskRegistry.size());

            // Seed modeMapVote defaults so ModMenu maps list is usable before first vote.
            // Map discovery reads both ServerMapConfig (train_vote_maps.json) and MapManager
            // (train_maps/ folder), populating display names, player counts, and pruning deleted maps.
            try {
                ServerLevel overworld = server.getLevel(Level.OVERWORLD);
                if (overworld != null) {
                    var discoveredMaps = SREIntegration.discoverServerMaps(overworld);
                    ConfigManager.getInstance().syncAndPruneMaps(
                            GameModeRegistry.getAllIds(),
                            discoveredMaps);
                }
            } catch (Throwable t) {
                com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
                LOGGER.debug("modeMapVote syncAndPruneMaps on SERVER_STARTED skipped", t);
            }
            // CORE-08：合并为单次 save()，避免连续两次 save() 将刚生成的配置覆盖进 .bak 备份
            ConfigManager.getInstance().save();

            com.habitrain.core.scene.server.SceneAssetStore.getInstance().bindWorld(
                    server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile());
            // Load the small delta index during world initialization, not the first player's download request.
            com.habitrain.core.scene.server.SceneDeltaStore.getInstance().getDeltaDir();

            // 服务端启动后应用大厅环境（时间/天气/雪雾等）
            try {
                ServerLevel overworld = server.getLevel(Level.OVERWORLD);
                if (overworld != null) {
                    EnvironmentController.applyLobby(overworld);
                }
            } catch (Throwable t) {
                com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
                LOGGER.debug("initial lobby env apply skipped", t);
            }

            // Crash/restart: SRE may persist ACTIVE/STARTING blackout while core
            // BlackoutMode rounds are empty. Stop the stuck SRE game so votes work.
            try {
                stopStuckBlackoutSreRounds(server);
            } catch (Throwable t) {
                com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
                LOGGER.error("[Lifecycle] stuck-blackout scan failed", t);
            }
        });
        // 服务器关闭时清理各服务的 per-level 静态状态。
        // 单机模式下集成服务器停止后客户端 JVM 仍存活，static 字段不会重置，
        // 不清理会导致下一局残留状态（计时器/角色/商店/投票）误用。
        // 注：fabric-api 此版本无 ServerLevelEvents.UNLOAD，故在 SERVER_STOPPING 遍历所有 level 清理。
        //
        // 审核 S-02：本处理器<b>必须</b>自身吞掉异常。Fabric 按注册顺序分发监听器，
        // 异常穿出会跳过其后的监听器——在停服阶段那意味着另一个模组的存档 / 退款
        // 整体被跳过（丢档）。因此外层再包一层 try/catch。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            try {
                onServerStopping(server);
            } catch (Throwable t) {
                com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
                LOGGER.error("停服清理失败；其它模组的 SERVER_STOPPING 收尾不受影响", t);
            }
        });
        // 玩家加入：任务/角色/场景/投票的全量同步与重连恢复。
        // 提取为独立方法后必须在此注册，否则整个 JOIN 同步链路静默失效。
        registerJoinHandler();
    }

    /** 审核 S-02 / CORE-02 / CORE-07：{@code SERVER_STOPPING} 的实际清理逻辑（注册处已包 try/catch）。 */
    private static void onServerStopping(net.minecraft.server.MinecraftServer server) {
        LifecycleCleanup cleanup = new LifecycleCleanup("server stopping");
        cleanup.run("file tasks", com.habitrain.core.internal.ServerFileTasks::shutdown);
        cleanup.run("map suggestions", CommandRegistrar::clearMapSuggestions);
        cleanup.run("scene capture", () -> com.habitrain.core.scene.server.SceneCaptureService.getInstance().shutdown());
        cleanup.run("scene staging", () -> com.habitrain.core.scene.server.SceneStagingService.getInstance().shutdown());
        cleanup.run("scene transfer", () -> com.habitrain.core.scene.server.SceneTransferService.getInstance().shutdown());
        cleanup.run("scene capture drain", () -> com.habitrain.core.scene.server.SceneCaptureService.getInstance().awaitStopped());
        cleanup.run("scene staging drain", () -> com.habitrain.core.scene.server.SceneStagingService.getInstance().awaitStopped());
        cleanup.run("scene transfer drain", () -> com.habitrain.core.scene.server.SceneTransferService.getInstance().awaitStopped());
        cleanup.run("file task drain", com.habitrain.core.internal.ServerFileTasks::awaitStopped);
        cleanup.run("match context", com.habitrain.core.game.sre.match.ServerMatchContext.GATE::reset);
        cleanup.run("voice snapshot", com.habitrain.core.role.capability.ServerVoicePermissions::stop);
        cleanup.run("save main config", () -> ConfigManager.getInstance().save());
        cleanup.run("save role config", () -> {
            var roleCfg = RoleExtensionConfigService.INSTANCE;
            if (roleCfg.lastSaveError() != null) roleCfg.save();
        });
        cleanup.run("config server reference", () -> ConfigManager.getInstance().setServer(null));
        cleanup.run("starting game flag", () -> io.wifi.starrailexpress.game.GameUtils.isStartingGame = false);
        cleanup.run("world cleanup enumeration", () -> {
            for (ServerLevel level : server.getAllLevels()) {
                cleanup.run("mode " + level.dimension().location(), () -> {
                    if (GameModeRegistry.isActiveInLevel(level)) GameModeRegistry.stop(level);
                });
                cleanup.run("vote " + level.dimension().location(), () -> OptionVoteManager.reset(level));
                cleanup.run("mode vote " + level.dimension().location(), () -> ModeMapVoteOrchestrator.reset(level));
                cleanup.run("map load " + level.dimension().location(), () -> MapVoteLoadCoordinator.reset(level));
            }
        });
        cleanup.run("map loads", MapVoteLoadCoordinator::resetAll);
        cleanup.run("option votes", OptionVoteManager::resetAll);
        cleanup.run("mode votes", ModeMapVoteOrchestrator::resetAll);
        cleanup.run("map file monitor", com.habitrain.core.vote.MapFileMonitor::reset);
        cleanup.run("map profile cache", com.habitrain.core.vote.MapVoteProfileStore::clearCache);
        cleanup.run("end transitions", GameEndTransitionCoordinator::resetAll);
        cleanup.run("settlement bridge", com.habitrain.core.game.sre.match.MatchEventBridge::resetAll);
        cleanup.run("MVP", MvpScoreTracker::resetAll);
        cleanup.run("repair mode", () -> RepairModeManager.resetAll(server));
        cleanup.run("slowness", SlownessReapplyManager::clearAll);
        cleanup.run("backpack searches", BackpackSearchHandler::clearAllSearches);
        cleanup.run("effect ownership", EffectOwnershipTracker::clearAll);
        cleanup.run("backpack quests", () -> BackpackQuestState.getInstance().resetAll());
        cleanup.run("environment", EnvironmentController::clearRuntimeState);
        cleanup.run("weather", SREWeatherController::resetAll);
        cleanup.run("sre base state", SREGameModeBase::resetServerState);
        cleanup.run("dlc task tracker", com.habitrain.core.game.sre.task.DlcTaskTracker::clearAll);
        cleanup.run("clearable handlers", ClearableHandlerRegistry::clearAll);
        cleanup.run("game lifecycle state", GameLifecycleHandler::resetLifecycleState);
        cleanup.run("active modes", GameModeRegistry::clearActiveModes);
        cleanup.run("task registry lifecycle", () -> CoreLifecycleScope.run(TaskRegistry::resetLifecycle));
        cleanup.run("mode registry lifecycle", () -> CoreLifecycleScope.run(GameModeRegistry::resetLifecycle));
        // CORE-02：清理 RoleSnapshotManager（含 round/lobby/pending/settlement）与 RoleSnapshotArchive，
        // 避免单机切档或异常停服后 round 非空导致新存档重建角色配置时误入 NEXT_ROUND 队列！
        cleanup.run("role snapshots", RoleSnapshotManager.INSTANCE::clear);
        cleanup.run("role snapshot archive", RoleSnapshotArchive.INSTANCE::clear);
        cleanup.run("role runtime overlays", com.habitrain.core.role.extension.RoleRuntimeOverlayApplier::serverStop);
        cleanup.run("role state", () -> ((com.habitrain.core.role.state.RoleStateServiceImpl)
                com.habitrain.core.api.role.v2.state.RoleStateApi.instance()).serverStop());
        cleanup.run("role action windows", () -> {
            if (com.habitrain.core.api.role.v2.action.RoleActionApi.instance()
                    instanceof com.habitrain.core.role.action.RoleActionServiceImpl actionSvc && server != null) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    actionSvc.onPlayerDisconnect(p.getUUID());
                }
            }
        });
        cleanup.run("role handshake gate", () -> {
            if (server != null) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    com.habitrain.core.role.config.RoleHandshakeGate.INSTANCE.clear(p.getUUID());
                }
            }
            com.habitrain.core.role.config.RoleHandshakeGate.INSTANCE.publishServerManifest(null);
        });
        cleanup.run("role server reference", com.habitrain.core.role.state.RuntimeRoleServer.INSTANCE::unbind);
        cleanup.run("task owners", () -> com.habitrain.core.task.TaskManager.getInstance().clearAll());
        cleanup.run("role views", () -> { LAST_VIEW.clear(); PENDING_ROLE_STATE.clear(); });
        cleanup.run("c2s rate limiter", com.habitrain.core.network.C2SRateLimiter::clearAll);
        cleanup.run("config requests", com.habitrain.core.network.ConfigSubmissionService::clear);
        cleanup.run("shader reports", com.habitrain.core.config.ShaderRuleRevalidation::clear);
        cleanup.run("scene preload", () -> com.habitrain.core.scene.server.ScenePreloadCoordinator.getInstance().resetAll());
        cleanup.run("scene instances", () -> com.habitrain.core.scene.server.SceneInstanceService.getInstance().shutdown());
        cleanup.run("scene runtime", () -> com.habitrain.core.scene.server.SceneRuntimeCoordinator.getInstance().resetAll());
        cleanup.run("scene selection", () -> com.habitrain.core.scene.server.SceneSelectionSessionManager.getInstance().clearAll());
        cleanup.run("scene world reference", () -> com.habitrain.core.scene.server.SceneAssetStore.getInstance().bindWorld(null));
        cleanup.report(LOGGER);
    }

    private static void registerJoinHandler() {
        // 玩家加入
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            com.habitrain.core.config.ShaderRuleRevalidation.onPlayerJoin(player);
            if (player == null) {
                return;
            }
            // CORE-09：玩家进服时立即初始化观察视角基线，确保首次切换旁观目标即可触发状态重同步
            try {
                net.minecraft.world.entity.Entity joinCam = player.getCamera();
                LAST_VIEW.put(player.getUUID(), new TrackedView(
                        joinCam == null ? player.getUUID() : joinCam.getUUID(),
                        player.level() == null ? null : player.level().dimension()));
            } catch (Throwable ignored) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(ignored);
                LOGGER.debug("Unable to capture initial observer view for {}", player.getUUID(), ignored);
            }
            try {
                // G5-F017：局终只清在线玩家；离线/崩溃残留的局内 CCA 在进服时无条件清掉，
                // 再按本局角色 init（卖花女进行中则保留已持久的 stillTicks/rewarded）。
                com.habitrain.core.game.sre.role.HabiComponents.clearLeftoverRoundStateOnJoin(player);
            } catch (Exception e) {
                LOGGER.debug("role CCA leftover clear on JOIN skipped", e);
            }
            try {
                // CORE-06：调用 TrainVoicePlugin 前先检查 VoiceChatPresence.isLoaded() 并捕获 Throwable，
                // 防止未安装 voicechat 时因 NoClassDefFoundError/LinkageError 中断后续全部 JOIN 同步链路。
                if (!SREGameModeBase.isAnySreGameStartingOrRunning(server)) {
                    SREGameModeBase.queueLobbyGroupJoin(server, player.getUUID());
                } else if (VoiceChatPresence.isLoaded() && shouldJoinMatchSpectatorVoice(player)) {
                    io.wifi.starrailexpress.compat.TrainVoicePlugin.addPlayer(player.getUUID());
                }
            } catch (Throwable t) {
                com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
                LOGGER.error("[VoiceGroup] 处理语音群组加入失败", t);
            }
            // 始终下发 FullConfig；集成主机客户端自行跳过 import，避免同 JVM 竞态。
            CustomTaskBlockPayload.sendToPlayer(player);
            // FullConfigSync 已含 global + tasks + gameModes + minigames + shader，
            // 不再另发 TaskConfig / ShaderConfig（JOIN 包体积）。
            FullConfigSyncPayload.sendToPlayer(player);
            // 中途重连：仅在本维 SRE 对局 running 且任务维度匹配时重发 HUD，避免跨存档僵尸任务框
            resyncActiveTaskOnJoin(player);
            // Mod 菜单访问门控同步：让客户端立即按授权状态决定是否锁定受门控页面
            MenuGatePayload.sendToPlayer(player);
            // 处理离线背包里遗留的刀耐久组件，确保全局开关对刚上线玩家同样生效。
            com.habitrain.core.game.sre.mode.KnifeDurabilityToggleService.applyToPlayer(player);
            // 休息区/重连可能在主世界，对局在另一维度：按 UUID 解析 MATCH 模式。
            GameModeRegistry.resolveActiveForPlayer(player)
                    .ifPresent(mode -> mode.onPlayerJoin(player));
            // 角色扩展 manifest 握手：晚加入/中途重连的玩家立即获得当前服务端配置
            try {
                // Snapshot must precede the manifest: the client handshake report
                // includes the snapshot definition hash.
                com.habitrain.core.network.RoleSnapshotPayload.sendTo(player);
                // ROLE-CMP-08：若当前对局进行中且存在 NEXT_ROUND 待激活快照，向新进玩家单发 manifest，
                // 不调用 publishServerManifest 覆盖全服缓存，避免局内已握手玩家因 pending 哈希被误拒动作。
                if (RoleSnapshotManager.INSTANCE.round() != null
                        && RoleSnapshotManager.INSTANCE.pending() != null) {
                    var manifest = com.habitrain.core.role.config.RoleManifestService.build();
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                            player, com.habitrain.core.network.RoleManifestPayload.fromManifest(manifest));
                } else {
                    com.habitrain.core.network.RoleManifestPayload.sendTo(player);
                }
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
            com.habitrain.core.game.sre.map.MapVoteLoadCoordinator.onPlayerJoin(player);
            // 移动场景：在全部既有 JOIN 同步之后追加当前地图资产与运行状态。
            com.habitrain.core.scene.server.SceneRuntimeCoordinator.getInstance().onPlayerJoin(player);
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
                if (dim != null) {
                    var sceneSessions = com.habitrain.core.scene.server.SceneSelectionSessionManager.getInstance();
                    sceneSessions.onPlayerChangeDimension(id, dim.location().toString());
                    String mapKey = sceneSessions.getEditorMapKey(id);
                    if (mapKey == null || mapKey.isBlank()) {
                        mapKey = com.habitrain.core.game.sre.scene.SreSceneContextResolver.INSTANCE
                                .resolve(player.serverLevel()).mapKey();
                    }
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            com.habitrain.core.scene.network.SceneSelectionStateS2C.cleared(
                                    dim.location().toString(), mapKey));
                }
                com.habitrain.core.scene.server.SceneRuntimeCoordinator.getInstance()
                        .onPlayerChangeDimension(player);
                // SCN-SRV-11/12：仅在跨维度切换时重置该玩家的场景预取会话
                if (origin == null || destination == null || !origin.dimension().equals(destination.dimension())) {
                    com.habitrain.core.scene.server.ScenePreloadCoordinator.getInstance()
                            .onPlayerDisconnect(player.getUUID());
                    com.habitrain.core.scene.server.ScenePreloadCoordinator.getInstance()
                            .onPlayerJoin(player);
                }
            } catch (Throwable t) {
                com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
                LOGGER.debug("role-state dimension resync skipped", t);
            }
        });
        // 角色状态 v2：观战镜头变化重同步。
        // 维度变化已由 AFTER_PLAYER_CHANGE_WORLD 处理。PENDING_ROLE_STATE +
        // EntityTrackingEvents 负责旁观者 tracking 边沿。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                com.habitrain.core.scene.server.SceneCaptureService.getInstance().tick(server);
                com.habitrain.core.scene.server.SceneStagingService.getInstance().tick(server);
                // CORE-09：先快照再移除，避免 clear() 丢弃并发写入的待同步 UUID
                if (!PENDING_ROLE_STATE.isEmpty()) {
                    java.util.List<java.util.UUID> batch = new java.util.ArrayList<>(PENDING_ROLE_STATE);
                    PENDING_ROLE_STATE.removeAll(batch);
                    for (java.util.UUID pendingId : batch) {
                        sendCurrentRoleStateUuid(pendingId);
                    }
                }
                for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
                    java.util.UUID id = p.getUUID();
                    net.minecraft.resources.ResourceKey<Level> dim = p.level() == null ? null : p.level().dimension();
                    if (!p.isSpectator()) {
                        // CORE-09 / ROLE-RT-13：玩家脱离旁观模式（如复活或回合结束切回生存/冒险）时，
                        // 若此前曾附身观察其他玩家，必须重置 LAST_VIEW 并重推状态，使客户端清理残留的镜像状态。
                        TrackedView prev = LAST_VIEW.get(id);
                        if (prev != null && !prev.camera().equals(id)) {
                            LAST_VIEW.put(id, new TrackedView(id, dim));
                            sendCurrentRoleState(p);
                        }
                        continue;
                    }
                    net.minecraft.world.entity.Entity camera = p.getCamera();
                    java.util.UUID cam = camera == null ? id : camera.getUUID();
                    TrackedView prev = LAST_VIEW.get(id);
                    if (prev != null && prev.camera().equals(cam) && java.util.Objects.equals(prev.dimension(), dim)) {
                        continue;
                    }
                    LAST_VIEW.put(id, new TrackedView(cam, dim));
                    sendCurrentRoleState(p);
                }
            } catch (Throwable t) {
                com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
                LOGGER.debug("role-state view resync tick skipped", t);
            }
        });
        // CORE-09 / ROLE-RT-13：仅当观察者处于旁观模式时才需要在实体追踪边沿触发角色状态重同步，
        // 避免存活玩家在列车内走动跨越 64m 追踪边界时产生无意义的 O(N) 全量角色状态重算。
        EntityTrackingEvents.START_TRACKING.register((trackedEntity, observer) -> {
            if (trackedEntity instanceof net.minecraft.world.entity.player.Player
                    && observer != null && observer.isSpectator()) {
                PENDING_ROLE_STATE.add(observer.getUUID());
            }
        });
        EntityTrackingEvents.STOP_TRACKING.register((trackedEntity, observer) -> {
            if (trackedEntity instanceof net.minecraft.world.entity.player.Player
                    && observer != null && observer.isSpectator()) {
                PENDING_ROLE_STATE.add(observer.getUUID());
            }
        });
        // 玩家断线：通知激活的 GameMode 处理。
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            com.habitrain.core.config.ShaderRuleRevalidation.onPlayerJoin(player);
            if (player == null) return;
            LifecycleCleanup cleanup = new LifecycleCleanup("player disconnect " + player.getUUID());
            cleanup.run("mode callback", () -> GameModeRegistry.resolveActiveForPlayer(player)
                    .ifPresent(mode -> mode.onPlayerLeave(player)));
            cleanup.run("voice pending", () -> SREGameModeBase.removePendingVoiceJoin(player.getUUID()));
            cleanup.run("effects", () -> EffectOwnershipTracker.clearPlayer(player.getUUID()));
            cleanup.run("slowness", () -> SlownessReapplyManager.unregisterAllLevels(player.getUUID()));
            cleanup.run("repair mode", () -> RepairModeManager.onPlayerDisconnect(player.getUUID(), server));
            cleanup.run("vote", () -> {
                ServerLevel voteLevel = OptionVoteManager.resolveActiveVoteLevel(player);
                if (voteLevel != null) OptionVoteManager.onVoterDisconnected(voteLevel, player.getUUID());
            });
            cleanup.run("role force queue", () -> com.habitrain.core.api.role.v2.RoleForceApi.instance().clear(player.getUUID()));
            cleanup.run("role actions", () -> ((com.habitrain.core.role.action.RoleActionServiceImpl)
                    com.habitrain.core.api.role.v2.action.RoleActionApi.instance()).onPlayerDisconnect(player.getUUID()));
            cleanup.run("role capabilities", () -> {
                com.habitrain.core.role.capability.ServerVoicePermissions.remove(player.getUUID());
                if (com.habitrain.core.api.role.v2.capability.RoleCapabilityApi.instance()
                        instanceof com.habitrain.core.role.capability.RoleCapabilityServiceImpl caps) {
                    caps.onPlayerDisconnect(player.getUUID());
                }
            });
            cleanup.run("handshake", () -> com.habitrain.core.role.config.RoleHandshakeGate.INSTANCE.clear(player.getUUID()));
            cleanup.run("role view", () -> { LAST_VIEW.remove(player.getUUID()); PENDING_ROLE_STATE.remove(player.getUUID()); });
            cleanup.run("rate windows", () -> com.habitrain.core.network.C2SRateLimiter.clear(player.getUUID()));
            cleanup.run("config requests", () -> com.habitrain.core.C2SReceiverRegistrar.clearConfigUpdateHistory(player.getUUID()));
            cleanup.run("shader report", () -> com.habitrain.core.config.ShaderRuleRevalidation.clearPlayer(player.getUUID()));
            cleanup.run("task owner", () -> com.habitrain.core.task.TaskManager.getInstance().unbindOwner(player.getUUID()));
            cleanup.run("scene instances", () -> com.habitrain.core.scene.server.SceneInstanceService.getInstance().onPlayerDisconnect(player.getUUID()));
            cleanup.run("scene selection", () -> com.habitrain.core.scene.server.SceneSelectionSessionManager.getInstance().onPlayerDisconnect(player.getUUID()));
            cleanup.run("scene capture", () -> com.habitrain.core.scene.server.SceneCaptureService.getInstance().onPlayerDisconnect(player.getUUID()));
            cleanup.run("scene staging", () -> com.habitrain.core.scene.server.SceneStagingService.getInstance().onPlayerDisconnect(player.getUUID()));
            cleanup.run("scene preload", () -> com.habitrain.core.scene.server.ScenePreloadCoordinator.getInstance().onPlayerDisconnect(player.getUUID()));
            cleanup.run("scene transfer", () -> com.habitrain.core.scene.server.SceneTransferService.getInstance().onPlayerDisconnect(player.getUUID()));
            cleanup.report(LOGGER);
        });
    }

    private static void resyncActiveTaskOnJoin(ServerPlayer player) {
        try {
            var tm = com.habitrain.core.task.TaskManager.getInstance();
            com.habitrain.core.game.sre.task.DlcTaskTracker.onPlayerJoin(player);
            tm.flushPendingReclaim(player);
            ServerLevel taskLevel = player.serverLevel();
            boolean sreRunning = false;
            if (taskLevel != null) {
                // A failed read must leave the active task intact; the outer boundary
                // skips this resync while allowing the remaining JOIN sync to proceed.
                var gw = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(taskLevel);
                sreRunning = gw != null && gw.isRunning();
            }
            if (!sreRunning) {
                tm.removeActiveTask(player.getUUID());
                // 客户端 JOIN 不清 ActiveTaskCache（避免与服务端快照竞态），
                // 因此"服务端已经没有任务"必须显式下清空包，否则集成服/重连会
                // 把上一局记录的 DLC 任务 id 留在客户端，旧任务点持续透视。
                com.habitrain.core.network.ActiveTaskPayload.clearForPlayer(player);
            } else {
                var active = tm.getActiveTask(player.getUUID());
                if (active != null) {
                    var taskDim = active.getDimension();
                    if (taskDim == null || taskDim.equals(taskLevel.dimension())) {
                        com.habitrain.core.network.ActiveTaskPayload.sendToPlayer(
                                player, active.getFullId());
                    } else {
                        com.habitrain.core.network.ActiveTaskPayload.clearForPlayer(player);
                    }
                } else {
                    com.habitrain.core.network.ActiveTaskPayload.clearForPlayer(player);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("ActiveTask resync on JOIN failed for {}", player.getUUID(), e);
        }
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
                com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
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
                || !net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("sre", "blackout").equals(sreMode.identifier)) {
            return;
        }

        LOGGER.warn("[Lifecycle] Retired SRE blackout mode is {} in {}; stopping it to unblock votes",
                status, level.dimension().location());
        try {
            io.wifi.starrailexpress.game.GameUtils.stopGame(level);
        } catch (Throwable t) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
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
        com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(ignored);
            LOGGER.warn("Unable to resolve eliminated status for voice group: {}", player.getUUID(), ignored);
        }
        try {
            return com.habitrain.core.game.sre.rest.EliminatedRestAreaService.isResting(player);
        } catch (Throwable ignored) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(ignored);
            LOGGER.warn("Unable to resolve rest status for voice group: {}", player.getUUID(), ignored);
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
            var server = com.habitrain.core.role.state.RuntimeRoleServer.INSTANCE.server();
            var observer = server == null ? null : server.getPlayerList().getPlayer(playerId);
            if (observer != null) org.agmas.harpymodloader.component.WorldModifierComponent.KEY.syncWith(observer, (org.ladysnake.cca.api.v3.component.ComponentProvider) (Object) observer.level());
            ((com.habitrain.core.role.state.RoleStateServiceImpl)
                    com.habitrain.core.api.role.v2.state.RoleStateApi.instance())
                    .sendCurrentStateTo(playerId);
        } catch (Throwable t) {
            com.habitrain.core.internal.FailurePolicy.rethrowIfFatal(t);
            LOGGER.debug("role-state tracking resync skipped", t);
        }
    }
}
