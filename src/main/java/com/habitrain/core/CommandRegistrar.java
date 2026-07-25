package com.habitrain.core;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.ModeMapVoteApi;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapPoolEntry;
import com.habitrain.core.config.MapPoolRotationSettings;
import com.habitrain.core.config.ModeMapVoteSettings;
import com.habitrain.core.game.sre.role.sins.trade.GreedTradeManager;
import com.habitrain.core.vote.MapPoolRotationService;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
                                        MapPoolRotationSettings rot = s.rotationOrDefault();
                                        MapPoolEntry p = rot.poolAt(rot.activePoolIndex);
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "§e地图池: " + (rot.enabled ? "§a启用" : "§c关闭")
                                                        + " §7共" + rot.poolCount() + "池"
                                                        + " §7当前池" + (rot.activePoolIndex + 1)
                                                        + " §f" + p.displayName
                                                        + " §8(" + (p.mapIds != null ? p.mapIds.size() : 0) + "图)"
                                                        + " §7模式=" + rot.applyMode
                                                        + " §7自动重分=" + rot.autoRepartition
                                                        + " §7日期=" + rot.lastRotationDate
                                        ), false);
                                        return 1;
                                    }))
                            .then(Commands.literal("skip")
                                    .requires(source -> source.hasPermission(4))
                                    .executes(ctx -> {
                                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                                        boolean ok = MapPoolRotationService.skip(player);
                                        return ok ? 1 : 0;
                                    }))
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
          });
        LOGGER.info("命令已注册: /instantgroup, /habi_api blackout|list|vote|mappool|greed_trade");
    }
}
