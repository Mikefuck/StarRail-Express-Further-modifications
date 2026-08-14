package com.habitrain.core;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.ModeMapVoteApi;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapPlayerCountSettings;
import com.habitrain.core.config.MenuGateService;
import com.habitrain.core.config.ModeMapVoteSettings;
import com.habitrain.core.game.sre.role.sins.trade.GreedTradeManager;
import com.habitrain.core.game.sre.RepairModeManager;
import com.habitrain.core.game.sre.SREModeStartAdapter;
import com.habitrain.core.network.MenuGatePayload;
import com.habitrain.core.role.config.RoleConfigApplyService;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.diag.RoleConfigCommands;
import com.habitrain.core.role.diag.RoleDiagnosticsCommands;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 命令注册器 — 负责注册所有 /instantgroup 和 /habi_api 命令。
 * <p>在 {@link HabiTrainCore#onInitialize()} 中调用 {@link #init()}。</p>
 */
public final class CommandRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|CommandRegistrar");

    private CommandRegistrar() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("instantgroup")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> VoiceGroupService.executeInstantGroup(ctx, 128))
                    .then(Commands.argument("range", IntegerArgumentType.integer(1, 512))
                            .executes(ctx -> VoiceGroupService.executeInstantGroup(ctx,
                                    IntegerArgumentType.getInteger(ctx, "range")))
                    )
            );
            // /habi_api 命令族（OP：blackout/list/vote；玩家：greed_trade 兼容回退）
            dispatcher.register(Commands.literal("habi_api")
                    .then(Commands.literal("blackout")
                            .requires(source -> source.hasPermission(2))
                            .executes(ctx -> {
                                ServerLevel level = ctx.getSource().getLevel();
                                try {
                                    GameModeRegistry.start("habitrain_core:habitrain:blackout", level);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§a✅ 停电模式已启动！"), true);
                                } catch (Exception e) {
                                    ctx.getSource().sendFailure(
                                            Component.literal("§c启动失败: " + e.getMessage()));
                                }
                                return 1;
                            })
                    )
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
                                if (snap == null || "IDLE".equals(snap.phase())) {
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
                                    .then(Commands.argument("player", StringArgumentType.string())
                                            .suggests(onlinePlayerNames())
                                            .requires(source -> source.hasPermission(2))
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "player");
                                                ServerPlayer target = findPlayer(ctx.getSource().getServer(), name);
                                                boolean ok;
                                                if (target != null) {
                                                    ok = RepairModeManager.exit(target);
                                                } else {
                                                    ok = false;
                                                }
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
                    // Mod 菜单访问门控（OP4 且非玩家：仅服务器后台控制台可维护允许列表）
                    .then(Commands.literal("menugate")
                            .requires(source -> source.hasPermission(4) && !source.isPlayer())
                            .then(Commands.literal("enable")
                                    .executes(ctx -> {
                                        MenuGateService.setEnabled(true);
                                        MenuGatePayload.broadcastToAll(ctx.getSource().getServer());
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("§a已启用 Mod 菜单访问门控：未授权玩家页面将被锁定"), true);
                                        return 1;
                                    }))
                            .then(Commands.literal("disable")
                                    .executes(ctx -> {
                                        MenuGateService.setEnabled(false);
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
                                                String uuid = "";
                                                ServerPlayer target = findPlayer(ctx.getSource().getServer(), name);
                                                if (target != null) {
                                                    name = target.getGameProfile().getName();
                                                    uuid = target.getUUID().toString();
                                                }
                                                final String resolvedName = name;
                                                final String resolvedUuid = uuid;
                                                boolean added = MenuGateService.add(resolvedName, resolvedUuid);
                                                MenuGatePayload.broadcastToAll(ctx.getSource().getServer());
                                                if (added) {
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "§a已将 " + resolvedName + " 加入允许列表"
                                                                    + (resolvedUuid.isEmpty() ? "（离线，按名字匹配）" : "")), true);
                                                } else {
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "§e" + resolvedName + " 已在允许列表中"), false);
                                                }
                                                return 1;
                                            })))
                            .then(Commands.literal("remove")
                                    .then(Commands.argument("player", StringArgumentType.string())
                                            .suggests(onlinePlayerNames())
                                            .executes(ctx -> {
                                                String name = StringArgumentType.getString(ctx, "player");
                                                boolean removed = MenuGateService.remove(name);
                                                MenuGatePayload.broadcastToAll(ctx.getSource().getServer());
                                                if (removed) {
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "§a已将 " + name + " 移出允许列表"), true);
                                                } else {
                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                            "§c未找到 " + name + "（允许列表中无此玩家）"), false);
                                                }
                                                return 1;
                                            })))
                    )
                    // 贪婪匿名交易双确认的命令兼容回退
                    .then(Commands.literal("greed_trade")
                            .then(Commands.literal("confirm")
                                    .then(Commands.argument("session", StringArgumentType.string())
                                            .executes(ctx -> {
                                                ServerPlayer player = ctx.getSource().getPlayerOrException();
                                                String sid = StringArgumentType.getString(ctx, "session");
                                                GreedTradeManager.confirm(player, sid);
                                                return 1;
                                            })))
                            .then(Commands.literal("cancel")
                                    .then(Commands.argument("session", StringArgumentType.string())
                                            .executes(ctx -> {
                                                ServerPlayer player = ctx.getSource().getPlayerOrException();
                                                String sid = StringArgumentType.getString(ctx, "session");
                                                GreedTradeManager.cancel(player, sid);
                                                return 1;
                                            })))
                    )
              );
            dispatcher.register(roleApiRootCommand());
          });
        LOGGER.info("命令已注册: /instantgroup, /habi_api blackout|list|vote|mappool|repair|greed_trade|menugate, /habitrain roleapi");
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

    /** 按名字解析在线玩家：先精确匹配，再忽略大小写匹配。 */
    private static int sendLines(CommandSourceStack source, List<String> lines) {
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

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

    /** Tab 补全：服务器当前可用地图（每次调用实时扫描 train_maps，非缓存）。 */
    private static SuggestionProvider<CommandSourceStack> availableMaps() {
        return (ctx, builder) -> {
            ServerLevel level = ctx.getSource().getLevel();
            for (String mapId : SREModeStartAdapter.getAvailableMaps(level)) {
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
                                        .executes(ctx -> sendLines(ctx.getSource(),
                                                RoleDiagnosticsCommands.state(
                                                        playerIdOrNull(ctx.getSource().getServer(),
                                                                StringArgumentType.getString(ctx, "player")),
                                                        null)))
                                        .then(Commands.argument("role", StringArgumentType.string())
                                                .executes(ctx -> sendLines(ctx.getSource(),
                                                        RoleDiagnosticsCommands.state(
                                                                playerIdOrNull(ctx.getSource().getServer(),
                                                                        StringArgumentType.getString(ctx, "player")),
                                                                StringArgumentType.getString(ctx, "role")))))))
                        // 角色扩展 v2 配置（§13.1/13.4）：只读 status OP2，修改 OP4
                        .then(Commands.literal("config")
                                .then(Commands.literal("status")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(ctx -> sendLines(ctx.getSource(),
                                                RoleConfigCommands.status())))
                                .then(Commands.literal("set")
                                        .requires(source -> source.hasPermission(4))
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
                                                                    RoleConfigApplyService.applyAndBroadcast(ctx.getSource().getServer());
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
                                                                    RoleConfigApplyService.applyAndBroadcast(ctx.getSource().getServer());
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
                                                            RoleConfigApplyService.applyAndBroadcast(ctx.getSource().getServer());
                                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                                    "§a已" + (on ? "开启" : "关闭") + " 全局 hook"), true);
                                                            return 1;
                                                        }))))
                                .then(Commands.literal("winner")
                                        .requires(source -> source.hasPermission(4))
                                        .then(Commands.argument("targetField", StringArgumentType.string())
                                                .then(Commands.argument("winnerEntry", StringArgumentType.string())
                                                        .executes(ctx -> {
                                                            String targetField = StringArgumentType.getString(ctx, "targetField");
                                                            String winner = StringArgumentType.getString(ctx, "winnerEntry");
                                                            String resolved = "none".equalsIgnoreCase(winner) ? null : winner;
                                                            RoleExtensionConfigService.INSTANCE.setConflictWinner(targetField, resolved);
                                                            RoleConfigApplyService.applyAndBroadcast(ctx.getSource().getServer());
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
}
