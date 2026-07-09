package com.habitrain.core;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
            // /habi_api 命令族 (OP 命令: blackout/list; 玩家命令: buy_gun/buy_ammo)
            dispatcher.register(Commands.literal("habi_api")
                    .then(Commands.literal("blackout")
                            .requires(source -> source.hasPermission(2))
                            .executes(ctx -> {
                                ServerLevel level = ctx.getSource().getLevel();
                                try {
                                    GameModeRegistry.start("habitrain_core:habitrains:blackout", level);
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
              );
          });
        LOGGER.info("命令已注册: /instantgroup, /habi_api blackout, /habi_api list");
    }
}
