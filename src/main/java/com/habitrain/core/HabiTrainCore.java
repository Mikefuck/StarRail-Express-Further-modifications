package com.habitrain.core;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.sre.SERepairMode;
import com.habitrain.core.game.sre.SREGameModeBase;
import com.habitrain.core.game.sre.SREMurderMode;
import com.habitrain.core.network.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.compat.TrainVoicePlugin;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 哈比列车核心 — 主入口类。
 * 职责: 配置初始化、GameMode注册、网络包注册、生命周期事件转发。
 * 注意: 原 {@code HabiTrainTaskAPI} 中的自动录制回放逻辑已完全移除。
 */
public class HabiTrainCore implements ModInitializer {
    public static final String MOD_ID = "habitrain_core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("哈比列车核心 (HabiTrain Core) 初始化中...");

        // 1. 配置系统
        ConfigManager.getInstance().load();

        // 2. 注册内置 GameMode（SRE 模式）
        //    构造 SRE 模式时会通过 SREGameModeBase 的静态初始化注册原版任务
        GameModeRegistry.register(MOD_ID, "sre:murder", new SREMurderMode());
        GameModeRegistry.register(MOD_ID, "sre:repair", new SERepairMode());

        // 3. 注册网络包
        TaskConfigPayload.register();
        ActiveTaskPayload.register();
        ConfigUpdatePayload.register();
        ShaderConfigPayload.register();
        ShaderInfoPayload.register();

        // 4. 注册命令
        registerCommands();

        // 5. 注册生命周期事件
        registerLifecycleEvents();

        LOGGER.info("哈比列车核心 初始化完成！已注册 {} 个 GameMode, {} 个任务",
                GameModeRegistry.size(), TaskRegistry.size());
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("instantgroup")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> executeInstantGroup(ctx, 128))
                    .then(Commands.argument("range", IntegerArgumentType.integer(1, 512))
                            .executes(ctx -> executeInstantGroup(ctx,
                                    IntegerArgumentType.getInteger(ctx, "range")))
                    )
            );
        });
    }

    private void registerLifecycleEvents() {
        // 服务器启动后加载配置
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ConfigManager.getInstance().load();
            LOGGER.info("配置已加载，共 {} 个已注册任务", TaskRegistry.size());
        });

        // 每 tick 处理待加入语音群组的玩家 + 游戏结束后的群组恢复
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SREGameModeBase.processPendingVoiceJoins(server);
            SREGameModeBase.processGameEndGroupJoin(server);
        });

        // 玩家加入
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();

            try {
                var gameLevel = server.getLevel(Level.OVERWORLD);
                if (gameLevel != null) {
                    SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(gameLevel);
                    if (gameWorld != null && !gameWorld.isRunning()) {
                        SREGameModeBase.addPlayerToLobbyGroup(server, player.getUUID());
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[VoiceGroup] 添加大厅玩家到语音群组失败", e);
            }

            // 同步配置
            TaskConfigPayload.sendToPlayer(player);
            ShaderConfigPayload.sendToPlayer(player);
        });

        // C2S 配置更新接收器
        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                if (!player.hasPermissions(4)) {
                    player.sendSystemMessage(Component.literal("§c你没有权限修改服务端配置（需要 OP 权限）"));
                    return;
                }
                ConfigManager.getInstance().loadFromJsonString(payload.getConfigJson());
                ConfigManager.getInstance().save();
                LOGGER.info("玩家 {} 通过 ModMenu 更新了服务端配置", player.getName().getString());

                if (context.server().isSingleplayer()) return;
                TaskConfigPayload.broadcastToAll(context.server());
                ShaderConfigPayload.broadcastToAll(context.server());
            });
        });

        // C2S 光影包信息接收器
        ServerPlayNetworking.registerGlobalReceiver(ShaderInfoPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                ConfigManager cfg = ConfigManager.getInstance();
                if (!cfg.isShaderWhitelistEnabled()) return;
                String shaderPackName = payload.getShaderPackName();
                if (shaderPackName.isEmpty()) return;
                boolean allowed = cfg.getShaderWhitelist().stream()
                        .anyMatch(name -> name.equalsIgnoreCase(shaderPackName));
                if (!allowed) {
                    player.connection.disconnect(Component.literal(
                            "§c✖ 未授权的光影包\n\n" +
                            "§7你使用的光影包 §e" + shaderPackName + " §7不在服务器白名单中。\n" +
                            "§7请更换为允许的光影包后重新加入。\n\n" +
                            "§7如需帮助，请联系服务器管理员。"));
                }
            });
        });
    }

    // ========== /instantgroup 命令 ==========

    private static int executeInstantGroup(CommandContext<CommandSourceStack> context, int range) {
        CommandSourceStack source = context.getSource();
        ServerPlayer sender = source.getPlayer();
        if (sender == null) { source.sendFailure(Component.literal("§c此命令只能由玩家执行")); return 0; }

        if (TrainVoicePlugin.isVoiceChatMissing() || TrainVoicePlugin.SERVER_API == null) {
            source.sendFailure(Component.literal("§c语音聊天系统未就绪")); return 0;
        }
        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
        if (api.getConnectionOf(sender.getUUID()) == null) {
            source.sendFailure(Component.literal("§c你的语音连接尚未就绪")); return 0;
        }

        MinecraftServer srv = source.getServer();
        Vec3 senderPos = sender.position();
        List<ServerPlayer> nearby = new ArrayList<>();
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            if (p.getUUID().equals(sender.getUUID())) continue;
            if (p.distanceToSqr(sender) <= (double) range * range) nearby.add(p);
        }
        if (nearby.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§e附近 " + range + " 格内没有其他玩家"), true);
            return 0;
        }

        Group tempGroup;
        try {
            tempGroup = api.groupBuilder()
                    .setId(UUID.randomUUID()).setName("临时群组")
                    .setPersistent(false).setType(Group.Type.OPEN).setHidden(false).build();
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c创建语音群组失败")); return 0;
        }

        try {
            VoicechatConnection conn = api.getConnectionOf(sender.getUUID());
            if (conn != null) conn.setGroup(tempGroup);
        } catch (Exception ignored) {}

        int count = 0;
        for (ServerPlayer p : nearby) {
            try {
                VoicechatConnection conn = api.getConnectionOf(p.getUUID());
                if (conn != null) { conn.setGroup(tempGroup); count++; }
            } catch (Exception ignored) {}
        }
        final int finalCount = count;
        source.sendSuccess(() -> Component.literal("§a已将 §e" + finalCount + " §a名附近玩家加入临时语音群组（范围: " + range + " 格）"), true);
        Component notify = Component.literal("§7[语音] §a你已被加入临时语音群组");
        for (ServerPlayer p : nearby) {
            if (api.getConnectionOf(p.getUUID()) != null) p.sendSystemMessage(notify);
        }
        LOGGER.info("[InstantGroup] {} 执行 /instantgroup {}，{} 名玩家", sender.getName().getString(), range, count);
        return count;
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
