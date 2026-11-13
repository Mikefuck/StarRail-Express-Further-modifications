package com.habitrain.core;

import com.habitrain.core.game.sre.voice.VoiceGroupService;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.ModeMapVoteApi;
import com.habitrain.core.api.menu.MenuGateApi;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapPlayerCountSettings;
import com.habitrain.core.config.MenuGateService;
import com.habitrain.core.config.ModeMapVoteSettings;
import com.habitrain.core.game.sre.mode.RepairModeManager;
import com.habitrain.core.game.sre.mode.SREModeStartAdapter;
import com.habitrain.core.network.MenuGatePayload;
import com.habitrain.core.role.config.RoleConfigApplyService;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.diag.RoleConfigCommands;
import com.habitrain.core.role.diag.RoleDiagnosticsCommands;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 命令注册器 — 负责注册所有 /instantgroup 和 /habi_api 命令。
 * <p>在 {@link HabiTrainCore#onInitialize()} 中调用 {@link #init()}。</p>
 */
public final class CommandRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|CommandRegistrar");

    private static volatile long cachedMapsTimestampMs = 0L;
    private static volatile List<String> cachedAvailableMaps = List.of();
    private static final long MAP_SUGGEST_CACHE_TTL_MS = 2000L;

    private CommandRegistrar() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            if (FabricLoader.getInstance().isModLoaded("voicechat")) {
                dispatcher.register(Commands.literal("instantgroup")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> VoiceGroupService.executeInstantGroup(ctx, 128))
                        .then(Commands.argument("range", IntegerArgumentType.integer(1, 512))
                                .executes(ctx -> VoiceGroupService.executeInstantGroup(ctx,
                                        IntegerArgumentType.getInteger(ctx, "range")))
                        )
                );
            }
            // /habi_api 命令族（OP：list/vote/mappool/repair/menugate/scene）
            dispatcher.register(Commands.literal("habi_api")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.literal("list")
                            .requires(source -> source.hasPermission(2))
                            .executes(ctx -> {
                                String modes = GameModeRegistry.getAll().stream()
                                        .map(GameMode::getId)
                                        .collect(java.util.stream.Collectors.joining("§7, §e"));
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("§e已注册模式: §e" + modes), true);
                                return 1;
                            })
                    )
                    .then(Commands.literal("vote")
                            .requires(source -> source.hasPermission(2))
                            .then(Commands.literal("start").executes(ctx -> {
                                ServerLevel level = ctx.getSource().getLevel();
                                boolean ok = ModeMapVoteApi.start(level);
                                if (ok) {
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§a已启动模式→地图投票"), true);
                                    return 1;
                                }
                                ctx.getSource().sendFailure(
                                        Component.literal("§c无法启动投票（已禁用/进行中/对局已运行/无候选）"));
                                return 0;
                            }))
                            .then(Commands.literal("cancel").executes(ctx -> {
                                ModeMapVoteApi.cancel(ctx.getSource().getLevel());
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("§e已取消投票"), true);
                                return 1;
                            }))
                            .then(Commands.literal("status").executes(ctx -> {
                                var snap = ModeMapVoteApi.getSnapshot(ctx.getSource().getLevel()).orElse(null);
                                if (snap == null || snap.phase() == null || snap.phase().isIdle()) {
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§7当前无投票"), false);
                                } else {
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "§e阶段: " + snap.phase()
                                            + " §7剩余: " + snap.remainingSeconds()
                                            + "s §e模式: " + snap.selectedModeId()
                                            + " §e地图: " + snap.selectedMapId()), false);
                                }
                                return 1;
                            }))
                    )
                    .then(Commands.literal("mappool")
                            .requires(source -> source.hasPermission(2))
                            .then(Commands.literal("status")
                                    .requires(source -> source.hasPermission(2))
                                    .executes(ctx -> {
                                        ModeMapVoteSettings s = ConfigManager.getInstance().getModeMapVoteSettings();
                                        MapPlayerCountSettings pc = s.playerCountOrDefault();
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "§e按人数抽图: " + (pc.enabled ? "§a启用" : "§c关闭")
                                                        + " §7抽取数量=" + pc.drawCount
                                        ), false);
                                        return 1;
                                    }))
                    )
                    // 维修人员模式（OP2）：进入维修模式并锁定地图，被锁地图不进投票池
                    .then(Commands.literal("repair")
                            .requires(source -> source.hasPermission(2))
                            // 进入维修模式并锁定一张地图
                            .then(Commands.argument("map", StringArgumentType.string())
                                    .suggests(availableMaps())
                                    .requires(source -> source.hasPermission(2))
                                    .executes(ctx -> {
                                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                                        String mapId = StringArgumentType.getString(ctx, "map");
                                        if (!isValidMap(ctx.getSource().getLevel(), mapId)) {
                                            ctx.getSource().sendFailure(
                                                    Component.literal("§c无效地图: " + mapId + " §7（按 Tab 可查看服务器当前可用地图）"));
                                            return 0;
                                        }
                                        boolean shared = RepairModeManager.isMapLocked(mapId);
                                        boolean ok = RepairModeManager.enter(player, mapId);
                                        if (!ok) {
                                            ctx.getSource().sendFailure(
                                                    Component.literal("§c进入维修模式失败（可能已在维修模式中）"));
                                            return 0;
                                        }
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "§a已进入维修模式（创造模式），锁定地图 §e" + mapId
                                                        + (shared ? " §7（该地图已被他人锁定，多人同时维修）" : "")), true);
                                        return 1;
                                    }))
                            // 取消自己的维修模式
                            .then(Commands.literal("cancel")
                                    .requires(source -> source.hasPermission(2))
                                    .executes(ctx -> {
                                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                                        boolean ok = RepairModeManager.exit(player);
                                        if (!ok) {
                                            ctx.getSource().sendFailure(
                                                    Component.literal("§c你当前不在维修模式中"));
                                            return 0;
                                        }
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "§a已退出维修模式，恢复原参与状态与游戏模式"), true);
                                        return 1;
                                    }))
                            // 列出当前维修人员与锁定的地图
                            .then(Commands.literal("list")
                                    .requires(source -> source.hasPermission(2))
                                    .executes(ctx -> {
                                        var list = RepairModeManager.list();
                                        if (list.isEmpty()) {
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "§7当前无维修人员，无地图被锁定"), false);
                                            return 1;
                                        }
                                        StringBuilder sb = new StringBuilder("§e当前维修人员(" + list.size() + "):");
                                        for (RepairModeManager.RepairEntryView v : list) {
                                            sb.append("\n §f").append(v.playerName())
                                                    .append(" §7→ 锁定地图 §e").append(v.mapId());
                                        }
                                        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                                        return 1;
                                    }))
                            // 强制解锁一张地图（移除所有负责玩家）
                            .then(Commands.literal("unlock")
                                    .requires(source -> source.hasPermission(2))
                                    .then(Commands.argument("map", StringArgumentType.string())
                                            .suggests(lockedMaps())
                                            .requires(source -> source.hasPermission(2))
                                            .executes(ctx -> {
                                                String mapId = StringArgumentType.getString(ctx, "map");
                                                int removed = RepairModeManager.unlockMap(mapId, ctx.getSource().getServer());
                                                if (removed <= 0) {
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "§e地图 " + mapId + " 当前未被锁定"), false);
                                                } else {
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "§a已强制解锁地图 §e" + mapId + " §7（移除 " + removed + " 名维修员）"), true);
                                                }
                                                return 1;
                                            })))
                            // 强制指定某玩家进入维修模式并锁定一张地图
                            .then(Commands.literal("add")
                                    .requires(source -> source.hasPermission(2))
                                    .then(Commands.argument("player", StringArgumentType.string())
                                            .suggests(onlinePlayerNames())
                                            .then(Commands.argument("map", StringArgumentType.string())
                                                    .suggests(availableMaps())
                                                    .requires(source -> source.hasPermission(2))
                                                    .executes(ctx -> {
                                                        String name = StringArgumentType.getString(ctx, "player");
                                                        String mapId = StringArgumentType.getString(ctx, "map");
                                                        ServerPlayer target = findPlayer(ctx.getSource().getServer(), name);
                                                        if (target == null) {
                                                            ctx.getSource().sendFailure(
                                                                    Component.literal("§c未找到在线玩家 " + name));
                                                            return 0;
                                                        }
                                                        if (!isValidMap(ctx.getSource().getLevel(), mapId)) {
                                                            ctx.getSource().sendFailure(
                                                                    Component.literal("§c无效地图: " + mapId));
                                                            return 0;
                                                        }
                                                        boolean ok = RepairModeManager.enter(target, mapId);
                                                        if (!ok) {
                                                            ctx.getSource().sendFailure(
                                                                    Component.literal("§c" + name + " 已在维修模式中"));
                                                            return 0;
                                                        }
                                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                                "§a已将 §e" + name + " §a设为维修模式，锁定地图 §e" + mapId), true);
                                                        return 1;
                                                    }))))
                            // 强制某玩家退出维修模式
                            .then(Commands.literal("remove")
                                    .requires(source -> source.hasPermission(2))
                                    .then(Commands.argument("player", StringArgumentType.string())
                                            .suggests(onlinePlayerNames())
                                            .requires(source -> source.hasPermission(2))
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "player");
                                                ServerPlayer target = findPlayer(ctx.getSource().getServer(), name);
                                                boolean ok = target != null && RepairModeManager.exit(target);
                                                if (!ok) {
                                                    ctx.getSource().sendFailure(
                                                            Component.literal("§c" + name + " 不在维修模式中（或不在线）"));
                                                    return 0;
                                                }
                                                ctx.getSource().sendSuccess(() -> Component.literal(
                                                        "§a已强制移除 §e" + name + " §a的维修模式"), true);
                                                return 1;
                                            })))
                    )
                    // CORE-01：Mod 菜单访问门控仅允许真实服务器后台控制台（拒绝玩家、/execute as 非玩家实体、命令方块）
                    .then(Commands.literal("menugate")
                            .requires(CommandRegistrar::isServerConsole)
                            .then(Commands.literal("enable")
                                    .executes(ctx -> {
                                        if (!MenuGateService.setEnabled(true)) {
                                            ctx.getSource().sendFailure(Component.literal("§c保存 Mod 菜单门控失败"));
                                            return 0;
                                        }
                                        MenuGatePayload.broadcastToAll(ctx.getSource().getServer());
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("§a已启用 Mod 菜单访问门控：未授权玩家页面将被锁定"), true);
                                        return 1;
                                    }))
                            .then(Commands.literal("disable")
                                    .executes(ctx -> {
                                        if (!MenuGateService.setEnabled(false)) {
                                            ctx.getSource().sendFailure(Component.literal("§c保存 Mod 菜单门控失败"));
                                            return 0;
                                        }
                                        MenuGatePayload.broadcastToAll(ctx.getSource().getServer());
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("§e已关闭 Mod 菜单访问门控：所有玩家可访问"), true);
                                        return 1;
                                    }))
                            .then(Commands.literal("status")
                                    .executes(ctx -> {
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "§eMod 菜单门控: " + (MenuGateService.isEnabled() ? "§a启用" : "§c关闭")
                                                        + " §7已允许 " + MenuGateService.getAllowed().size() + " 人"), false);
                                        return 1;
                                    }))
                            .then(Commands.literal("list")
                                    .executes(ctx -> {
                                        var list = MenuGateService.getAllowed();
                                        if (list.isEmpty()) {
                                            ctx.getSource().sendSuccess(() -> Component.literal("§7允许访问列表为空"), false);
                                            return 1;
                                        }
                                        StringBuilder sb = new StringBuilder("§e允许访问的玩家:");
                                        for (MenuGateService.AllowedPlayer ap : list) {
                                            String mark = isOnline(ctx.getSource().getServer(), ap) ? "§a●" : "§7○";
                                            sb.append("\n").append(mark).append(" §f").append(ap.getName());
                                            if (!ap.getUuid().isEmpty()) {
                                                sb.append(" §7(").append(ap.getUuid()).append(")");
                                            }
                                        }
                                        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                                        return 1;
                                    }))
                            .then(Commands.literal("add")
                                    .then(Commands.argument("player", StringArgumentType.string())
                                            .suggests(onlinePlayerNames())
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "player");
                                                MinecraftServer server = ctx.getSource().getServer();
                                                ServerPlayer target = findPlayer(server, name);
                                                String uuid;
                                                if (target != null) {
                                                    name = target.getGameProfile().getName();
                                                    uuid = target.getUUID().toString();
                                                } else {
                                                    java.util.UUID lookedUp = lookupOfflineUuid(server, name);
                                                    if (lookedUp == null) {
                                                        ctx.getSource().sendFailure(Component.literal(
                                                                "§c离线添加必须能解析 UUID，无法将 " + name + " 加入允许列表"));
                                                        return 0;
                                                    }
                                                    uuid = lookedUp.toString();
                                                    String cachedName = lookupOfflineName(server, lookedUp);
                                                    if (cachedName != null && !cachedName.isBlank()) {
                                                        name = cachedName;
                                                    }
                                                }
                                                final String resolvedName = name;
                                                boolean added = MenuGateService.add(resolvedName, uuid);
                                                if (added) {
                                                    MenuGatePayload.broadcastToAll(server);
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "§a已将 " + resolvedName + " 加入允许列表"), true);
                                                    return 1;
                                                }
                                                boolean inList = MenuGateService.isAllowed(
                                                        java.util.UUID.fromString(uuid), resolvedName);
                                                if (inList) {
                                                    MenuGatePayload.broadcastToAll(server);
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "§e" + resolvedName + " 已在允许列表中"), false);
                                                    return 1;
                                                }
                                                ctx.getSource().sendFailure(Component.literal(
                                                        "§c无法将 " + resolvedName + " 加入允许列表（保存失败）"));
                                                return 0;
                                            })))
                            .then(Commands.literal("remove")
                                    .then(Commands.argument("player", StringArgumentType.string())
                                            .suggests(onlinePlayerNames())
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "player");
                                                boolean existed = menugateContains(name);
                                                boolean removed = MenuGateService.remove(name);
                                                if (removed) {
                                                    MenuGatePayload.broadcastToAll(ctx.getSource().getServer());
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "§a已将 " + name + " 移出允许列表"), true);
                                                    return 1;
                                                }
                                                if (existed) {
                                                    ctx.getSource().sendFailure(Component.literal(
                                                            "§c保存 Mod 菜单门控失败，未移除 " + name));
                                                    return 0;
                                                }
                                                // CORE-13：目标不存在时应走 sendFailure 并返回 0
                                                ctx.getSource().sendFailure(Component.literal(
                                                        "§c未找到 " + name + "（允许列表中无此玩家）"));
                                                return 0;
                                            })))
                    )
                    // 移动场景系统调试与管理命令（CORE-17 / X-CUT-18：同时校验 MenuGate）
                    .then(Commands.literal("scene")
                            .requires(CommandRegistrar::canUseSceneAdminCommands)
                            .then(Commands.literal("tool").executes(ctx -> {
                                ServerPlayer player = ctx.getSource().getPlayerOrException();
                                player.getInventory().add(new net.minecraft.world.item.ItemStack(
                                        com.habitrain.core.scene.item.HabiAdminItems.SCENE_CONFIGURATOR));
                                ctx.getSource().sendSuccess(() -> Component.literal("§a已给予场景配置器道具"), false);
                                return 1;
                            }))
                            .then(Commands.literal("status").executes(ctx -> {
                                ServerLevel level = ctx.getSource().getLevel();
                                var state = com.habitrain.core.scene.server.SceneRuntimeCoordinator.getInstance().getRuntimeState(level);
                                var ctxRes = com.habitrain.core.game.sre.scene.SreSceneContextResolver.INSTANCE.resolve(level);
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "§6=== 移动场景状态 ===\n"
                                        + "§7当前地图: §b" + ctxRes.mapKey() + "\n"
                                        + "§7对局进行中: §e" + ctxRes.matchActive() + "\n"
                                        + "§7场景激活: " + (state != null && state.isActive() ? "§a是" : "§c否") + "\n"
                                        + "§7资产哈希: §d" + (state != null ? state.getAssetHash() : "none") + "\n"
                                        + "§7速度: §f" + (state != null ? state.getProfile().getSpeedBlocksPerSecond() : 0) + " m/s"
                                ), false);
                                return 1;
                            }))
                            .then(Commands.literal("start")
                                    .executes(ctx -> executeSceneStart(ctx.getSource(), null))
                                    .then(Commands.argument("map", StringArgumentType.string())
                                            .executes(ctx -> executeSceneStart(
                                                    ctx.getSource(), StringArgumentType.getString(ctx, "map"))))
                            )
                            .then(Commands.literal("stop").executes(ctx -> {
                                ServerLevel level = ctx.getSource().getLevel();
                                com.habitrain.core.api.scene.SceneMotionApi.instance().stopScene(level);
                                ctx.getSource().sendSuccess(() -> Component.literal("§e已停止当前维度的场景运动"), true);
                                return 1;
                            }))
                            // CORE-12：导出当前资产供整合包分发，提示目录与 SceneSeedImporter.SEED_DIR_NAME 一致，且不泄露宿主机绝对路径
                            .then(Commands.literal("export").executes(ctx -> {
                                var source = ctx.getSource();
                                var server = source.getServer();
                                var snapshot = com.habitrain.core.scene.server.SceneExportService.snapshot();
                                boolean queued = com.habitrain.core.internal.ServerFileTasks.submit(server,
                                        () -> com.habitrain.core.scene.server.SceneExportService.export(snapshot,
                                                () -> com.habitrain.core.internal.ServerFileTasks.isCurrent(server)),
                                        (result, failure) -> {
                                if (failure != null || !result.ok()) {
                                    source.sendFailure(Component.literal("§c导出失败，请查看服务器日志"));
                                    return;
                                }
                                LOGGER.info("已导出 {} 个场景资产到 {}", result.assets(), result.directory());
                                source.sendSuccess(() -> Component.literal(
                                        "§a已导出 §e" + result.assets() + " §a个场景资产（§f"
                                                + com.habitrain.core.api.scene.asset.SceneAssetSizeReport.humanBytes(result.bytes())
                                                + "§a）\n§7导出目录: §f<world>/" + com.habitrain.core.scene.server.SceneExportService.DIR_NAME + "/"
                                                + "\n§7把该目录里的 .hscene 放进客户端 §fhabitrain_scene_seed/§7 即可免下载"
                                                + (result.skipped() > 0 ? "\n§7跳过（无文件/无效）: §e" + result.skipped() : "")), false);
                                        });
                                if (!queued) { source.sendFailure(Component.literal("§c文件处理队列繁忙，请稍后再试")); return 0; }
                                source.sendSuccess(() -> Component.literal("§e正在后台导出场景资产，完成后会通知"), false);
                                return 1;
                            }))
                            .then(Commands.literal("build")
                                    .executes(ctx -> executeSceneBuild(ctx.getSource(), null))
                                    .then(Commands.argument("map", StringArgumentType.string())
                                            .executes(ctx -> executeSceneBuild(
                                                    ctx.getSource(), StringArgumentType.getString(ctx, "map"))))
                            )
                            // API 场景实例（外部 Mod 注册，数量无上限）诊断
                            .then(Commands.literal("api")
                                    .executes(ctx -> {
                                        var api = com.habitrain.core.api.scene.SceneApi.instance();
                                        var lines = api.describeInstances();
                                        StringBuilder sb = new StringBuilder("§6=== API 场景实例 ===\n")
                                                .append("§7总数: §b").append(api.instanceCount())
                                                .append(" §7全局开关: ").append(api.isGlobalEnabled() ? "§a开" : "§c关");
                                        if (lines.isEmpty()) {
                                            sb.append("\n§7（暂无实例；外部 Mod 可通过 SceneApi 注册）");
                                        } else {
                                            for (String line : lines) {
                                                sb.append('\n').append(line);
                                            }
                                        }
                                        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                                        return 1;
                                    })
                                    .then(Commands.literal("clear").executes(ctx -> {
                                        int removed = com.habitrain.core.api.scene.SceneApi.instance().despawnAll();
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "§e已回收 " + removed + " 个 API 场景实例"), true);
                                        return 1;
                                    }))
                                    .then(Commands.literal("resync").executes(ctx -> {
                                        com.habitrain.core.api.scene.SceneApi.instance().resyncAll();
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "§a已向所有在线玩家重新下发 API 场景实例"), true);
                                        return 1;
                                    }))
                            )
                    )
              );
            dispatcher.register(roleApiRootCommand());
          });
        LOGGER.info("命令已注册: /instantgroup, /habi_api list|vote|mappool|repair|menugate, /habitrain roleapi");
    }

    private static int executeSceneStart(CommandSourceStack source, @Nullable String mapArg) {
        ServerLevel level = source.getLevel();
        String map = (mapArg != null && !mapArg.isBlank())
                ? mapArg
                : com.habitrain.core.game.sre.scene.SreSceneContextResolver.INSTANCE.resolve(level).mapKey();
        boolean ok = com.habitrain.core.api.scene.SceneMotionApi.instance().startScene(level, map);
        if (ok) {
            source.sendSuccess(() -> Component.literal("§a已启动场景运动: " + map), true);
            return 1;
        }
        source.sendFailure(Component.literal("§c启动场景失败（配置禁用或无有效资产）"));
        return 0;
    }

    private static int executeSceneBuild(CommandSourceStack source, @Nullable String mapArg)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = source.getLevel();
        ServerPlayer player = source.getPlayerOrException();
        String map = (mapArg != null && !mapArg.isBlank())
                ? mapArg
                : com.habitrain.core.game.sre.scene.SreSceneContextResolver.INSTANCE.resolve(level).mapKey();
        var settings = ConfigManager.getInstance().getSceneMotionSettings();
        if (!settings.isConfiguredEditorMap(map)) {
            source.sendFailure(Component.literal("§c请先添加该地图的独立场景配置；如要生成默认回退资产，请显式指定 __default__"));
            return 0;
        }
        String assetKey = settings.effectiveMapKey(map);
        var profile = settings.getProfile(assetKey);
        if (profile.getSourceBounds().isEmpty()) {
            source.sendFailure(Component.literal("§c该地图未配置源选区 sourceBounds: " + map));
            return 0;
        }
        if (!com.habitrain.core.scene.server.SceneCaptureService.getInstance()
                .requestCapture(level, assetKey, profile.getSourceBounds(), player)) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§e已发起场景资产生成任务: " + assetKey), false);
        return 1;
    }

    private static java.util.UUID playerIdOrNull(MinecraftServer server, String name) {
        ServerPlayer player = findPlayer(server, name);
        return player == null ? null : player.getUUID();
    }

    /** Parses an on/off word into a Boolean, or {@code null} for anything else. */
    private static Boolean parseBool(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "on", "true", "1" -> Boolean.TRUE;
            case "off", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Tab 补全：on/off。 */
    private static SuggestionProvider<CommandSourceStack> onOff() {
        return (ctx, builder) -> {
            builder.suggest("on");
            builder.suggest("off");
            return builder.buildFuture();
        };
    }

    private static boolean menugateContains(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) {
            return false;
        }
        String s = nameOrUuid.trim();
        java.util.UUID parsed = MenuGateService.parseUuid(s);
        for (MenuGateService.AllowedPlayer ap : MenuGateService.getAllowed()) {
            if (parsed != null && ap.getUuid().equalsIgnoreCase(parsed.toString())) {
                return true;
            }
            if (ap.getName().equalsIgnoreCase(s) || ap.getUuid().equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 离线 menugate add 必须解析到正式 UUID：在线玩家、UUID 字面量、或 profile cache。
     * 禁止回退 {@code OfflinePlayer:} 名字哈希。
     */
    private static java.util.UUID lookupOfflineUuid(MinecraftServer server, String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.isBlank()) {
            return null;
        }
        String s = nameOrUuid.trim();
        java.util.UUID parsed = MenuGateService.parseUuid(s);
        if (parsed != null) {
            return parsed;
        }
        if (server == null) {
            return null;
        }
        try {
            var cache = server.getProfileCache();
            if (cache == null) {
                return null;
            }
            var opt = cache.get(s);
            if (opt == null || opt.isEmpty()) {
                return null;
            }
            return extractProfileUuid(opt.get());
        } catch (Exception e) {
            LOGGER.debug("menugate offline uuid lookup failed for {}", s, e);
            return null;
        }
    }

    private static String lookupOfflineName(MinecraftServer server, java.util.UUID id) {
        if (server == null || id == null) {
            return null;
        }
        try {
            var cache = server.getProfileCache();
            if (cache == null) {
                return null;
            }
            var opt = cache.get(id);
            if (opt == null || opt.isEmpty()) {
                return null;
            }
            GameProfile gp = opt.get();
            String name = gp != null ? gp.getName() : null;
            return (name != null && !name.isBlank()) ? name : null;
        } catch (Exception e) {
            LOGGER.debug("menugate offline name lookup failed for {}", id, e);
            return null;
        }
    }

    private static java.util.UUID extractProfileUuid(GameProfile profile) {
        return profile != null ? profile.getId() : null;
    }

    private static int sendLines(CommandSourceStack source, List<String> lines) {
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** 按名字解析在线玩家：先精确匹配，再忽略大小写匹配。 */
    private static ServerPlayer findPlayer(MinecraftServer server, String name) {
        if (server == null || name == null) return null;
        ServerPlayer exact = server.getPlayerList().getPlayerByName(name);
        if (exact != null) return exact;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp.getGameProfile().getName().equalsIgnoreCase(name)) return sp;
        }
        return null;
    }

    /** Tab 补全：服务器当前在线玩家名（实时）。 */
    private static SuggestionProvider<CommandSourceStack> onlinePlayerNames() {
        return (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            if (server != null) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    builder.suggest(p.getGameProfile().getName());
                }
            }
            return builder.buildFuture();
        };
    }

    private static MinecraftServer mapsCacheOwner;
    private static boolean mapsRefreshPending;

    public static void clearMapSuggestions() {
        mapsCacheOwner = null;
        mapsRefreshPending = false;
        cachedAvailableMaps = List.of();
        cachedMapsTimestampMs = 0L;
    }

    /** Suggestions use a snapshot; a cache miss never walks the disk on the server thread. */
    private static SuggestionProvider<CommandSourceStack> availableMaps() {
        return (ctx, builder) -> {
            ServerLevel level = ctx.getSource().getLevel();
            MinecraftServer server = level.getServer();
            if (mapsCacheOwner != server) {
                mapsCacheOwner = server;
                cachedAvailableMaps = List.of();
                cachedMapsTimestampMs = 0L;
                mapsRefreshPending = false;
            }
            long now = System.currentTimeMillis();
            List<String> maps = cachedAvailableMaps;
            if (!mapsRefreshPending && (now - cachedMapsTimestampMs > MAP_SUGGEST_CACHE_TTL_MS)) {
                java.nio.file.Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("train_maps");
                mapsRefreshPending = true;
                if (!com.habitrain.core.internal.ServerFileTasks.submit(server, () -> {
                    if (!java.nio.file.Files.isDirectory(root)) return List.<String>of();
                    try (var files = java.nio.file.Files.walk(root)) {
                        return files.filter(java.nio.file.Files::isRegularFile)
                                .map(root::relativize).map(Object::toString)
                                .filter(path -> path.toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
                                .map(path -> path.substring(0, path.length() - 5).replace('\\', '/'))
                                .filter(path -> !com.habitrain.core.vote.MapVoteProfileStore.isReservedMapId(path))
                                .sorted().toList();
                    } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }, (refreshed, failure) -> {
                    if (mapsCacheOwner == server) {
                        mapsRefreshPending = false;
                        cachedMapsTimestampMs = System.currentTimeMillis();
                        if (failure == null) cachedAvailableMaps = refreshed;
                    }
                })) mapsRefreshPending = false;
            }
            for (String mapId : maps) {
                builder.suggest(mapId);
            }
            return builder.buildFuture();
        };
    }

    /** Tab 补全：当前被维修员锁定的地图（供 unlock 使用）。 */
    private static SuggestionProvider<CommandSourceStack> lockedMaps() {
        return (ctx, builder) -> {
            for (String mapId : RepairModeManager.getLockedMapIds()) {
                builder.suggest(mapId);
            }
            return builder.buildFuture();
        };
    }

    /** 校验地图名是否在当前服务器可用地图列表中。 */
    private static boolean isValidMap(ServerLevel level, String mapId) {
        if (level == null || mapId == null || mapId.isBlank()) return false;
        try {
            return SREModeStartAdapter.getAvailableMaps(level).contains(mapId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 允许列表条目当前是否在线（有 UUID 按 UUID 查，无 UUID 按名字查）。 */
    private static boolean isOnline(MinecraftServer server, MenuGateService.AllowedPlayer ap) {
        if (server == null) return false;
        if (ap.getUuid() != null && !ap.getUuid().isEmpty()) {
            try {
                return server.getPlayerList().getPlayer(java.util.UUID.fromString(ap.getUuid())) != null;
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return findPlayer(server, ap.getName()) != null;
    }

    /**
     * /habitrain roleapi 命令树：只读诊断（OP2）+ 角色扩展 v2 配置编辑（OP4）
     * + manifest 摘要（OP2）。
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> roleApiRootCommand() {
        return Commands.literal("habitrain")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("roleapi")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("providers").executes(ctx ->
                                sendLines(ctx.getSource(), RoleDiagnosticsCommands.providers())))
                        .then(Commands.literal("list")
                                .executes(ctx -> sendLines(ctx.getSource(),
                                        RoleDiagnosticsCommands.list("effective")))
                                .then(Commands.argument("filter", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            b.suggest("effective");
                                            b.suggest("disabled");
                                            b.suggest("conflict");
                                            b.suggest("invalid");
                                            b.suggest("legacy");
                                            b.suggest("broken");
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> sendLines(ctx.getSource(),
                                                RoleDiagnosticsCommands.list(
                                                        StringArgumentType.getString(ctx, "filter"))))))
                        .then(Commands.literal("inspect")
                                .then(Commands.argument("role", StringArgumentType.string())
                                        .executes(ctx -> sendLines(ctx.getSource(),
                                                RoleDiagnosticsCommands.inspect(
                                                        StringArgumentType.getString(ctx, "role"))))))
                        .then(Commands.literal("trace")
                                .then(Commands.argument("role", StringArgumentType.string())
                                        .then(Commands.argument("field", StringArgumentType.word())
                                                .executes(ctx -> sendLines(ctx.getSource(),
                                                        RoleDiagnosticsCommands.trace(
                                                                StringArgumentType.getString(ctx, "role"),
                                                                StringArgumentType.getString(ctx, "field")))))))
                        .then(Commands.literal("aliases")
                                .executes(ctx -> sendLines(ctx.getSource(),
                                        RoleDiagnosticsCommands.aliases(null)))
                                .then(Commands.argument("role", StringArgumentType.string())
                                        .executes(ctx -> sendLines(ctx.getSource(),
                                                RoleDiagnosticsCommands.aliases(
                                                        StringArgumentType.getString(ctx, "role"))))))
                        .then(Commands.literal("snapshot").executes(ctx ->
                                sendLines(ctx.getSource(), RoleDiagnosticsCommands.snapshot())))
                        .then(Commands.literal("hooks")
                                .then(Commands.argument("role", StringArgumentType.string())
                                        .executes(ctx -> sendLines(ctx.getSource(),
                                                RoleDiagnosticsCommands.hooks(
                                                        StringArgumentType.getString(ctx, "role"))))))
                        .then(Commands.literal("actions")
                                .executes(ctx -> sendLines(ctx.getSource(),
                                        RoleDiagnosticsCommands.actions(null)))
                                .then(Commands.argument("role", StringArgumentType.string())
                                        .executes(ctx -> sendLines(ctx.getSource(),
                                                RoleDiagnosticsCommands.actions(
                                                        StringArgumentType.getString(ctx, "role"))))))
                        .then(Commands.literal("capabilities").executes(ctx ->
                                sendLines(ctx.getSource(), RoleDiagnosticsCommands.capabilities())))
                        .then(Commands.literal("perf").executes(ctx ->
                                sendLines(ctx.getSource(), RoleDiagnosticsCommands.perf())))
                        .then(Commands.literal("archive").executes(ctx ->
                                sendLines(ctx.getSource(), RoleDiagnosticsCommands.archive())))
                        .then(Commands.literal("legacy").executes(ctx ->
                                sendLines(ctx.getSource(), RoleDiagnosticsCommands.legacy())))
                        .then(Commands.literal("state")
                                .executes(ctx -> sendLines(ctx.getSource(),
                                        RoleDiagnosticsCommands.state(null, null)))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(onlinePlayerNames())
                                        .executes(ctx -> {
                                            String playerName = StringArgumentType.getString(ctx, "player");
                                            java.util.UUID targetId = playerIdOrNull(ctx.getSource().getServer(), playerName);
                                            if (targetId == null) {
                                                ctx.getSource().sendFailure(Component.literal("§c未找到在线玩家: " + playerName));
                                                return 0;
                                            }
                                            return sendLines(ctx.getSource(), RoleDiagnosticsCommands.state(targetId, null));
                                        })
                                        .then(Commands.argument("role", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    String playerName = StringArgumentType.getString(ctx, "player");
                                                    java.util.UUID targetId = playerIdOrNull(ctx.getSource().getServer(), playerName);
                                                    if (targetId == null) {
                                                        ctx.getSource().sendFailure(Component.literal("§c未找到在线玩家: " + playerName));
                                                        return 0;
                                                    }
                                                    return sendLines(ctx.getSource(),
                                                            RoleDiagnosticsCommands.state(targetId,
                                                                    StringArgumentType.getString(ctx, "role")));
                                                }))))
                        // 角色扩展 v2 配置（§13.1/13.4）：只读 status OP2，修改 OP4
                        .then(Commands.literal("config")
                                .requires(CommandRegistrar::canUseSceneAdminCommands)
                                .then(Commands.literal("status")
                                        .requires(CommandRegistrar::canUseSceneAdminCommands)
                                        .executes(ctx -> sendLines(ctx.getSource(),
                                                RoleConfigCommands.status())))
                                .then(Commands.literal("set")
                                        .requires(CommandRegistrar::canWriteRoleApiConfig)
                                        .then(Commands.literal("provider")
                                                .then(Commands.argument("id", StringArgumentType.word())
                                                        .then(Commands.argument("on", StringArgumentType.word())
                                                                .suggests(onOff())
                                                                .executes(ctx -> {
                                                                    String id = StringArgumentType.getString(ctx, "id");
                                                                    Boolean on = parseBool(StringArgumentType.getString(ctx, "on"));
                                                                    if (on == null) {
                                                                        ctx.getSource().sendFailure(Component.literal("§con/off 参数必须是 on 或 off"));
                                                                        return 0;
                                                                    }
                                                                    RoleExtensionConfigService.INSTANCE.setProviderEnabled(id, on);
                                                                    String saveErr = RoleExtensionConfigService.INSTANCE.lastSaveError();
                                                                    RoleConfigApplyService.applyAndBroadcast(ctx.getSource().getServer());
                                                                    if (saveErr != null) {
                                                                        ctx.getSource().sendFailure(Component.literal(
                                                                                "§c配置已内存生效但落盘失败: " + saveErr));
                                                                        return 0;
                                                                    }
                                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                                            "§a已" + (on ? "启用" : "禁用") + " provider §e" + id), true);
                                                                    return 1;
                                                                }))))
                                        .then(Commands.literal("entry")
                                                .then(Commands.argument("entryId", StringArgumentType.string())
                                                        .then(Commands.argument("on", StringArgumentType.word())
                                                                .suggests(onOff())
                                                                .executes(ctx -> {
                                                                    String entryId = StringArgumentType.getString(ctx, "entryId");
                                                                    Boolean on = parseBool(StringArgumentType.getString(ctx, "on"));
                                                                    if (on == null) {
                                                                        ctx.getSource().sendFailure(Component.literal("§con/off 参数必须是 on 或 off"));
                                                                        return 0;
                                                                    }
                                                                    RoleExtensionConfigService.INSTANCE.setEntryEnabled(entryId, on);
                                                                    String saveErr = RoleExtensionConfigService.INSTANCE.lastSaveError();
                                                                    RoleConfigApplyService.applyAndBroadcast(ctx.getSource().getServer());
                                                                    if (saveErr != null) {
                                                                        ctx.getSource().sendFailure(Component.literal(
                                                                                "§c配置已内存生效但落盘失败: " + saveErr));
                                                                        return 0;
                                                                    }
                                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                                            "§a已" + (on ? "启用" : "禁用") + " entry §e" + entryId), true);
                                                                    return 1;
                                                                }))))
                                        .then(Commands.literal("allowGlobalHooks")
                                                .then(Commands.argument("on", StringArgumentType.word())
                                                        .suggests(onOff())
                                                        .executes(ctx -> {
                                                            Boolean on = parseBool(StringArgumentType.getString(ctx, "on"));
                                                            if (on == null) {
                                                                ctx.getSource().sendFailure(Component.literal("§con/off 参数必须是 on 或 off"));
                                                                return 0;
                                                            }
                                                            RoleExtensionConfigService.INSTANCE.setAllowGlobalHooks(on);
                                                            String saveErr = RoleExtensionConfigService.INSTANCE.lastSaveError();
                                                            RoleConfigApplyService.applyAndBroadcast(ctx.getSource().getServer());
                                                            if (saveErr != null) {
                                                                ctx.getSource().sendFailure(Component.literal(
                                                                        "§c配置已内存生效但落盘失败: " + saveErr));
                                                                return 0;
                                                            }
                                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                                    "§a已" + (on ? "开启" : "关闭") + " 全局 hook"), true);
                                                            return 1;
                                                        }))))
                                .then(Commands.literal("winner")
                                        .requires(CommandRegistrar::canWriteRoleApiConfig)
                                        .then(Commands.argument("targetField", StringArgumentType.string())
                                                .then(Commands.argument("winnerEntry", StringArgumentType.string())
                                                        .executes(ctx -> {
                                                            String targetField = StringArgumentType.getString(ctx, "targetField");
                                                            String winner = StringArgumentType.getString(ctx, "winnerEntry");
                                                            String resolved = "none".equalsIgnoreCase(winner) ? null : winner;
                                                            RoleExtensionConfigService.INSTANCE.setConflictWinner(targetField, resolved);
                                                            String saveErr = RoleExtensionConfigService.INSTANCE.lastSaveError();
                                                            RoleConfigApplyService.applyAndBroadcast(ctx.getSource().getServer());
                                                            if (saveErr != null) {
                                                                ctx.getSource().sendFailure(Component.literal(
                                                                        "§c配置已内存生效但落盘失败: " + saveErr));
                                                                return 0;
                                                            }
                                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                                    resolved == null
                                                                            ? "§e已清除 " + targetField + " 的冲突裁决"
                                                                            : "§a" + targetField + " -> §e" + resolved), true);
                                                            return 1;
                                                        }))))
                        .then(Commands.literal("manifest")
                                .requires(source -> source.hasPermission(2))
                                .executes(ctx -> sendLines(ctx.getSource(), RoleConfigCommands.manifest())))
                ));
    }

    /**
     * CORE-01：严格校验命令来源是否为服务器后台控制台（或 RCON）。
     * 必须同时满足 {@code source.getServer() != null}、{@code source.getEntity() == null}（排除 {@code /execute as @e}）
     * 并核验原始 CommandSource 为服务器实例或 RCON，不信任可变显示名。
     */
    private static boolean isServerConsole(CommandSourceStack source) {
        if (source == null || !source.hasPermission(4) || source.getServer() == null) return false;
        Object origin = ((com.habitrain.core.game.sre.mixin.CommandSourceStackAccessor) (Object) source)
                .habitrain$getOriginalSource();
        return origin == source.getServer() || origin instanceof net.minecraft.server.rcon.RconConsoleSource;
    }

    private static boolean canUseSceneAdminCommands(CommandSourceStack source) {
        if (source == null || !source.hasPermission(2)) {
            return false;
        }
        if (isServerConsole(source)) {
            return true;
        }
        if (((com.habitrain.core.game.sre.mixin.CommandSourceStackAccessor) (Object) source)
                .habitrain$getOriginalSource() instanceof ServerPlayer player) {
            return !MenuGateApi.isBlocked(player, source.getServer());
        }
        return false;
    }

    /**
     * CORE-01：{@code /habitrain roleapi config set|winner} 需要 OP4。
     * 仅允许服务器后台控制台，或通过 MenuGate 校验的在线 OP4 玩家；
     * 严禁通过 {@code /execute as @e[type=!player]} 或命令方块绕过。
     */
    private static boolean canWriteRoleApiConfig(CommandSourceStack source) {
        if (source == null || !source.hasPermission(4)) {
            return false;
        }
        if (isServerConsole(source)) {
            return true;
        }
        if (((com.habitrain.core.game.sre.mixin.CommandSourceStackAccessor) (Object) source)
                .habitrain$getOriginalSource() instanceof ServerPlayer player) {
            MinecraftServer server = source.getServer();
            if (server == null || !server.isDedicatedServer() || !MenuGateService.isEnabled()) {
                return true;
            }
            return MenuGateService.isAllowed(player);
        }
        return false;
    }
}
