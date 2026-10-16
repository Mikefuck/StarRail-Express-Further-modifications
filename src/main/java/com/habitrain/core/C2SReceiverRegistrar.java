package com.habitrain.core;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MenuGateService;
import com.habitrain.core.network.*;
import com.habitrain.core.vote.OptionVoteManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * C2S 网络包接收器注册器 — 负责注册所有客户端→服务端的数据包接收与路由逻辑。
 * <p>在 {@link HabiTrainCore#onInitialize()} 中调用 {@link #init()}。</p>
 */
public final class C2SReceiverRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|C2SReceiverRegistrar");


    private C2SReceiverRegistrar() {}

    public static void clearConfigUpdateHistory(UUID playerId) {
        if (playerId != null) {
            ConfigSubmissionService.clearPlayer(playerId);
        }
    }

    public static void init() {
        com.habitrain.core.config.ShaderRuleRevalidation.init();
        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.TYPE, (payload, context) ->
                context.server().execute(() -> ConfigSubmissionService.handle(context.server(), context.player(), payload.getConfigJson())));
        // C2S 地图介绍预览图：属于背包地图投票权限边界，只要求 OP2，不受完整菜单门控影响。
        ServerPlayNetworking.registerGlobalReceiver(MapVotePreviewUploadPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                var configMaps = ConfigManager.getInstance().getModeMapVoteSettings().maps;
                ConfigUpdateAccessPolicy.MapPreviewUploadAccess access =
                        ConfigUpdateAccessPolicy.mapPreviewUploadAccess(
                                player.hasPermissions(2), payload.mapId(), configMaps.keySet());
                if (access == ConfigUpdateAccessPolicy.MapPreviewUploadAccess.OP2_REQUIRED) {
                    player.sendSystemMessage(Component.literal("§c预览图上传失败：需要 OP2 权限"));
                    return;
                }
                if (access == ConfigUpdateAccessPolicy.MapPreviewUploadAccess.MAP_NOT_CONFIGURED) {
                    player.sendSystemMessage(Component.literal("§c预览图上传失败：地图不在服务器配置列表中"));
                    return;
                }
                if (!C2SRateLimiter.tryAcquire(player.getUUID(), "preview_upload", 2000)) {
                    player.sendSystemMessage(Component.literal("§c预览图上传过快，请稍后再试"));
                    return;
                }
                com.habitrain.core.vote.MapVoteProfileStore.UploadResult result =
                        com.habitrain.core.vote.MapVoteProfileStore.saveUploadedPreview(
                                player.serverLevel(), payload.mapId(),
                                payload.previousPreviewPath(), payload.pngBytes());
                if (result.success()) {
                    player.sendSystemMessage(Component.literal(
                            "§a地图 “" + payload.mapId() + "” 的预览图已上传并替换旧图"));
                    LOGGER.info("玩家 {} 上传地图预览图 map={} bytes={}",
                            player.getName().getString(), payload.mapId(), payload.pngBytes().length);
                    // 广播最新的 MapVoteProfilePayload 给所有在线玩家（包含上传者），使客户端即时刷新预览
                    try {
                        ServerLevel overworld = context.server().overworld();
                        if (overworld != null) {
                            var profiles = com.habitrain.core.vote.MapVoteProfileStore.loadProfiles(
                                    overworld, configMaps.keySet(), configMaps);
                            if (!profiles.isEmpty()) {
                                var profilePayloads = MapVoteProfilePayload.fragment(profiles);
                                for (ServerPlayer sp : context.server().getPlayerList().getPlayers()) {
                                    for (var profilePayload : profilePayloads) {
                                        ServerPlayNetworking.send(sp, profilePayload);
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        LOGGER.debug("MapVoteProfile broadcast on preview upload skipped", t);
                    }
                } else {
                    player.sendSystemMessage(Component.literal("§c预览图上传失败：" + result.message()));
                    LOGGER.warn("玩家 {} 上传地图预览图失败 map={} reason={}",
                            player.getName().getString(), payload.mapId(), result.message());
                }
            });
        });
        // C2S 光影包信息接收器
        ServerPlayNetworking.registerGlobalReceiver(ShaderInfoPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                if (!com.habitrain.core.config.ShaderRuleRevalidation.awaitingReport(player.getUUID())
                        && !C2SRateLimiter.tryAcquire(player.getUUID(), "shader_info", 1000)) {
                    return;
                }
                com.habitrain.core.config.ShaderRuleRevalidation.report(player, payload.getShaderPackName(), payload.ruleRevision());
            });
        });



        // C2S 通用选项投票接收器（模式/地图等）；voteId 不匹配时 manager 内 no-op
        ServerPlayNetworking.registerGlobalReceiver(OptionVoteCastPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer voter = context.player();
                if (voter == null) return;
                ServerLevel level = voter.serverLevel();
                if (level == null) return;
                OptionVoteManager.cast(level, voter.getUUID(), payload.voteId(), payload.optionId());
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SlothSleepRosterRequestPayload.TYPE, (payload, context) ->
                context.server().execute(() -> sendSlothSleepRoster(context.player())));
        ServerPlayNetworking.registerGlobalReceiver(LustActionPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (!C2SRateLimiter.tryAcquire(player.getUUID(), "lust_action", 50)) return;
                    if (payload.action() == LustActionPayload.SELECT) {
                        com.habitrain.core.game.sre.role.sins.component.LustComponent.select(player, payload.id());
                    } else {
                        com.habitrain.core.game.sre.role.sins.component.LustComponent.answerRevival(
                                player, payload.id(), payload.action() == LustActionPayload.REVIVE);
                    }
                }));
        ServerPlayNetworking.registerGlobalReceiver(SlothSleepTargetPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer sloth = context.player();
                    if (sloth != null) {
                        com.habitrain.core.game.sre.role.sins.component.SlothComponent
                                .tryInduceSleep(sloth, payload.targetId());
                        sendSlothSleepRoster(sloth);
                    }
                }));
        ServerPlayNetworking.registerGlobalReceiver(RoleActionC2SPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayer player = context.player();
                    if (player == null || payload == null || payload.actionId() == null) {
                        return;
                    }
                    // Cheap packet-level ceiling before handshake/hash/spec work.
                    // Individual action specs retain their own stricter rate/cooldown gates.
                    if (!C2SRateLimiter.tryAcquire(player.getUUID(), "role_action", 50)) {
                        return;
                    }
                    // The service runs the §12.4 validation order and echoes the
                    // result (with the request sequence) through its bound ResultSender.
                    com.habitrain.core.api.role.v2.action.RoleActionApi.instance()
                            .receiveC2S(player, payload.actionId(), payload.payload(), payload.sequence());
                }));
        // C2S 角色扩展 v2 配置更新：完整 Mod Menu 的 OP2 + 门控授权后服务端权威应用。
        ServerPlayNetworking.registerGlobalReceiver(RoleConfigUpdatePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null || payload == null) {
                    return;
                }
                if (!ConfigUpdateAccessPolicy.isAllowed(ConfigUpdateScope.FULL_MOD_MENU,
                        player.hasPermissions(2), context.server().isDedicatedServer(),
                        MenuGateService.isEnabled(), MenuGateService.isAllowed(player))) {
                    player.sendSystemMessage(Component.literal("§c完整 Mod 菜单需要 OP2 和服务器后台单独授权"));
                    return;
                }
                if (!C2SRateLimiter.tryAcquire(player.getUUID(), "role_config_update", 2000)) {
                    player.sendSystemMessage(Component.literal("§c配置保存过快，请稍后再试"));
                    return;
                }
                if (!com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE
                        .applyFromJson(payload.configJson())) {
                    player.sendSystemMessage(Component.literal(
                            "§c角色扩展配置合并失败：JSON 无效或字段类型错误，配置未改动"));
                    LOGGER.warn("玩家 {} 的角色扩展配置更新被拒绝（解析失败）",
                            player.getName().getString());
                    return;
                }
                com.habitrain.core.role.config.RoleExtensionConfigService.INSTANCE.save();
                com.habitrain.core.role.config.RoleConfigApplyService.applyAndBroadcast(context.server());
                player.sendSystemMessage(Component.literal(
                        "§a角色扩展配置已应用；若在对局中，将下一局生效。"));
                LOGGER.info("玩家 {} 更新了角色扩展 v2 配置", player.getName().getString());
            });
        });
        // C2S 角色扩展握手本地 manifest 上报（audit P1-4）：服务端据此权威计算
        // §14.2 握手结果并门控角色动作；断线时在 LifecycleEventsRegistrar 清除。
        ServerPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.network.RoleHandshakeReportPayload.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            ServerPlayer player = context.player();
                            if (player == null || payload == null) {
                                return;
                            }
                            // Always keep the newest report. Manifest and snapshot are sent
                            // back-to-back, so dropping the second report could preserve an
                            // earlier null definition hash and leave actions blocked.
                            com.habitrain.core.role.config.RoleHandshakeGate.INSTANCE
                                    .record(player.getUUID(), payload.toClientManifest());
                            LOGGER.debug("玩家 {} 上报角色扩展握手 manifest",
                                    player.getName().getString());
                        }));

        // C2S 场景资产分片请求：具体 Manifest 授权、offset 与速率校验由传输服务负责。
        // 与其他 C2S 一致在服务端线程执行；真正的文件读取仍提交到传输服务的 IO 池。
        ServerPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.scene.network.SceneAssetChunkRequestC2S.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            ServerPlayer player = context.player();
                            if (player != null && payload != null) {
                                com.habitrain.core.scene.server.SceneTransferService.getInstance()
                                        .handleChunkRequest(player, payload);
                            }
                        }));

        // C2S 增量补丁探测：客户端手里有旧版本时问一句"这版有没有补丁"。
        // 只查内存索引与文件存在性，不做磁盘读，因此在服务端线程执行是安全的。
        ServerPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.scene.network.SceneAssetDeltaProbeC2S.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            ServerPlayer player = context.player();
                            if (player != null && payload != null) {
                                com.habitrain.core.scene.server.SceneTransferService.getInstance()
                                        .handleDeltaProbe(player, payload.mapKey(), payload.targetSha256());
                            }
                        }));

        // 客户端只有在场景文件校验、解码与 GPU 网格预编译全部成功后才会上报 ready。
        ServerPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.scene.network.SceneAssetReadyC2S.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            ServerPlayer player = context.player();
                            if (player == null || payload == null) return;
                            com.habitrain.core.scene.server.ScenePreloadCoordinator.getInstance()
                                    .handleReady(player, payload.sessionId(), payload.sha256(), payload.success());
                        }));

        // C2S 场景资产生成请求。绝不信任客户端 mapKey；捕获服务会解析服务端当前地图。
        ServerPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.scene.network.SceneAssetBuildRequestC2S.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            ServerPlayer player = context.player();
                            if (player == null || payload == null) {
                                return;
                            }
                            if (!player.hasPermissions(2)) {
                                player.sendSystemMessage(Component.literal("§c需要 OP2 权限才能生成场景资产"));
                                return;
                            }
                            if (!C2SRateLimiter.tryAcquire(player.getUUID(), "scene_build", 2000)) {
                                player.sendSystemMessage(Component.literal("§c操作过于频繁，请稍候再试"));
                                return;
                            }
                            com.habitrain.core.scene.server.SceneCaptureService.getInstance()
                                    .startCapture(player, payload.mapKey());
                        }));

        // 管理员客户端只报告私有暂存资产的烘焙结果；报告本身绝不触发发布。
        ServerPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.scene.network.SceneStagingReportC2S.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            ServerPlayer player = context.player();
                            if (player == null || payload == null) return;
                            if (!C2SRateLimiter.tryAcquire(player.getUUID(), "scene_staging_report", 250)) return;
                            com.habitrain.core.scene.server.SceneStagingService.getInstance()
                                    .handleReport(player, payload);
                        }));

        // 只有第二个独立的管理员动作才能把已通过诊断的暂存资产提升为正式资产。
        ServerPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.scene.network.SceneStagingDecisionC2S.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            ServerPlayer player = context.player();
                            if (player == null || payload == null) return;
                            if (!C2SRateLimiter.tryAcquire(player.getUUID(), "scene_staging_decision", 250)) return;
                            com.habitrain.core.scene.server.SceneStagingService.getInstance()
                                    .handleDecision(context.server(), player, payload);
                        }));

        // 地图箭头切换是场景工具会话状态，必须立即同步；不能依赖“保存配置”的回调，
        // 因为集成服务器会跳过配置 C2S，导致 HUD 显示 map2 而下一次右键仍打开默认配置。
        ServerPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.scene.network.SceneToolMapSelectC2S.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            ServerPlayer player = context.player();
                            if (player == null || payload == null || !player.hasPermissions(2)) return;

                            String currentMapKey = com.habitrain.core.game.sre.scene.SreSceneContextResolver.INSTANCE
                                    .resolve(player.serverLevel()).mapKey();
                            java.util.LinkedHashSet<String> allowedMapKeys = new java.util.LinkedHashSet<>(
                                    ConfigManager.getInstance().getModeMapVoteSettings().maps.keySet());
                            allowedMapKeys.addAll(ConfigManager.getInstance().getSceneMotionSettings().profiles.keySet());
                            try {
                                String selectedMapKey = ConfigUpdateAccessPolicy.resolveAdminSceneTargetMap(
                                        payload.mapKey(), currentMapKey, allowedMapKeys);
                                String backgroundId = payload.backgroundId() != null && !payload.backgroundId().isBlank()
                                        ? com.habitrain.core.api.scene.model.SceneBackgroundKey.normalizeBackgroundId(payload.backgroundId())
                                        : com.habitrain.core.api.scene.model.SceneBackgroundKey.DEFAULT_ID;
                                syncSceneToolTarget(player, selectedMapKey, backgroundId);
                            } catch (RuntimeException ignored) {
                                player.sendSystemMessage(Component.literal("§c场景地图切换被拒绝：地图不存在或未配置"));
                            }
                        }));

        // C2S API 场景实例重同步请求：客户端清空过本地状态（例如对局结束）但没换会话时，
        // 服务端并不知道，需要客户端主动要一次全量。服务端侧带 1 秒冷却。
        ServerPlayNetworking.registerGlobalReceiver(
                com.habitrain.core.scene.network.SceneInstanceResyncC2S.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            ServerPlayer player = context.player();
                            if (player == null) return;
                            com.habitrain.core.scene.server.SceneInstanceService.getInstance()
                                    .handleResyncRequest(player);
                        }));
    }

    private static void sendSlothSleepRoster(ServerPlayer sloth) {
        if (sloth == null) return;
        var entries = com.habitrain.core.game.sre.role.sins.component.SlothComponent
                .eligibleTargets(sloth).stream()
                .map(player -> new SlothSleepRosterPayload.Entry(
                        player.getUUID(), player.getGameProfile().getName()))
                .toList();
        ServerPlayNetworking.send(sloth, new SlothSleepRosterPayload(entries));
    }

    public static void syncSceneToolTarget(ServerPlayer player, String mapKey) {
        var manager = com.habitrain.core.scene.server.SceneSelectionSessionManager.getInstance();
        syncSceneToolTarget(player, mapKey, manager.getEditorBackgroundId(player.getUUID(), mapKey));
    }

    private static void syncSceneToolTarget(ServerPlayer player, String mapKey, String backgroundId) {
        String dimension = player.serverLevel().dimension().location().toString();
        var manager = com.habitrain.core.scene.server.SceneSelectionSessionManager.getInstance();
        manager.selectEditorMap(player.getUUID(), mapKey);
        manager.selectEditorBackground(player.getUUID(), mapKey, backgroundId);
        ServerPlayNetworking.send(player,
                new com.habitrain.core.scene.network.SceneToolTargetStateS2C(mapKey, backgroundId));
        var session = manager.getSession(player.getUUID());
        boolean selectionInDimension = session != null && dimension.equals(session.getDimension());
        String selectionMapKey = selectionInDimension ? session.getMapKey() : mapKey;
        com.habitrain.core.api.scene.model.SceneBounds bounds = selectionInDimension
                ? session.toBounds() : com.habitrain.core.api.scene.model.SceneBounds.EMPTY;
        ServerPlayNetworking.send(player,
                com.habitrain.core.scene.network.SceneSelectionStateS2C.fromBounds(
                        dimension, selectionMapKey, bounds, bounds.isEmpty() ? 0L : bounds.volume()));
    }

}
