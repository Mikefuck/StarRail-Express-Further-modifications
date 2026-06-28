package com.habitrain.taskapi;

import com.habitrain.taskapi.api.HabiTaskCategory;
import com.habitrain.taskapi.api.HabiTaskDefinition;
import com.habitrain.taskapi.api.HabiTaskInstance;
import com.habitrain.taskapi.api.HabiTaskRegistry;
import com.habitrain.taskapi.impl.HabiTaskManager;
import com.habitrain.taskapi.impl.config.HabiConfigManager;
import com.habitrain.taskapi.impl.network.ActiveCustomTaskPayload;
import com.habitrain.taskapi.impl.network.ConfigUpdateC2SPayload;
import com.habitrain.taskapi.impl.network.ShaderConfigSyncS2CPayload;
import com.habitrain.taskapi.impl.network.ShaderPackInfoC2SPayload;
import com.habitrain.taskapi.impl.network.TaskConfigSyncPayload;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.compat.TrainVoicePlugin;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 哈比列车任务API - 主类
 * 接管原版任务系统，提供完整的任务注册、配置和管理API
 */
public class HabiTrainTaskAPI implements ModInitializer {
    public static final String MOD_ID = "habitrain_taskapi";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // 等待加入语音群组的玩家 (UUID -> 剩余重试次数)
    private static final Map<UUID, Integer> pendingVoiceJoins = new HashMap<>();
    private static final int MAX_VOICE_JOIN_RETRIES = 200; // 最多重试200 tick (10秒)

    // 大厅语音群组（通过 Simple Voice Chat API 直接管理，不依赖 enhancedgroups）
    private static Group LOBBY_GROUP = null;
    private static final UUID LOBBY_GROUP_ID = UUID.randomUUID();

    // 游戏结束待处理的语音群组加入标记
    // finalizeGame() 内 OnGameEnd → resetPlayerAfterGame (清群组) → setGameStatus(INACTIVE)
    // 通过 END_SERVER_TICK 在下一 tick 处理，确保 SRE 所有清理都已完成
    private static boolean pendingGameEndGroupJoin = false;

    @Override
    public void onInitialize() {
        LOGGER.info("哈比列车任务API 初始化中...");

        // 初始化配置系统
        HabiConfigManager.getInstance().load();

        // 注册原版SRE任务到我们的配置系统中 (使其可在ModMenu中配置)
        registerOriginalTasksAsBuiltin();

        // 注册 /instantgroup 命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("instantgroup")
                    .requires(source -> source.hasPermission(2)) // 需要 OP 权限
                    .executes(context -> executeInstantGroup(context, 128))
                    .then(Commands.argument("range", IntegerArgumentType.integer(1, 512))
                            .executes(context -> executeInstantGroup(context,
                                    IntegerArgumentType.getInteger(context, "range")))
                    )
            );
        });

        // 注册网络包
        TaskConfigSyncPayload.register();
        ActiveCustomTaskPayload.register();
        ConfigUpdateC2SPayload.register();
        ShaderConfigSyncS2CPayload.register();
        ShaderPackInfoC2SPayload.register();

        // 注意: 不冻结注册表，允许DLC模组在onInitialize()中注册自定义任务
        // (冻结会阻止DLC模组按API文档方式注册任务)

        // 服务器启动后加载配置
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            HabiConfigManager.getInstance().load();
            LOGGER.info("任务API配置已加载，共 {} 个已注册任务", HabiTaskRegistry.size());
            // 群组现通过 SVC API 延迟创建（首个玩家加入时自动创建），无需在启动时预创建
        });

        // 服务器 tick 事件 - 处理待加入语音群组的玩家 + 游戏结束后的群组恢复
        // Simple Voice Chat 的连接在 JOIN 事件后才会建立，
        // 所以需要延迟重试直到语音连接就绪
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            processPendingVoiceJoins(server);
            processGameEndGroupJoin(server);
        });

        // 玩家加入时同步任务配置 + 活跃自定义任务 + 光影白名单 + 语音群组
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();

            // 玩家在大厅等待时（游戏未运行），自动加入语音群组
            // 游戏进行中（ACTIVE/STOPPING）不修改群组设置
            // ★ 注意：这段代码在单机/LAN模式下也要执行，不能放在 isSingleplayer 检查后面
            try {
                ServerLevel gameLevel = server.getLevel(Level.OVERWORLD);
                if (gameLevel != null) {
                    SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(gameLevel);
                    if (gameWorld != null && !gameWorld.isRunning()) {
                        // ★ 游戏外加入：玩家在游戏未运行时加入 → 加入语音群组
                        addPlayerToLobbyGroup(server, player.getUUID());
                        // 加入待处理队列（语音连接可能尚未就绪，后续tick重试）
                        pendingVoiceJoins.put(player.getUUID(), MAX_VOICE_JOIN_RETRIES);
                        LOGGER.info("[VoiceGroup] 游戏外加入 - 玩家 {} 将加入语音群组",
                                player.getName().getString());
                    } else {
                        // ★ 游戏内加入：游戏正在进行中 → 不做任何群组修改
                        LOGGER.info("[VoiceGroup] 游戏内加入 - 玩家 {} 已跳过语音群组修改",
                                player.getName().getString());
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[VoiceGroup] 添加大厅玩家到语音群组失败", e);
            }

            // ★ 单机模式（集成服务器）：跳过网络同步
            //    客户端和服务端共享同一个配置文件，无需通过数据包同步

            TaskConfigSyncPayload.sendToPlayer(player);

            // 同步活跃自定义任务（用于多人模式下客户端渲染透视）
            HabiTaskManager mgr = HabiTaskManager.getInstance();
            HabiTaskInstance activeTask = mgr.getActiveCustomTask(player.getUUID());
            if (activeTask != null) {
                ActiveCustomTaskPayload.sendToPlayer(player, activeTask.getFullId());
            } else {
                ActiveCustomTaskPayload.clearForPlayer(player);
            }

            // 同步 Iris 光影白名单配置到客户端（OP 在 ModMenu 中查看）
            ShaderConfigSyncS2CPayload.sendToPlayer(player);
        });

        // 注册 C2S 光影包信息接收器（客户端报告当前 Iris 光影包）
        ServerPlayNetworking.registerGlobalReceiver(ShaderPackInfoC2SPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;

                // 获取白名单配置
                HabiConfigManager cfg = HabiConfigManager.getInstance();

                // 白名单未启用 → 不检查
                if (!cfg.isShaderWhitelistEnabled()) return;

                String shaderPackName = payload.getShaderPackName();

                // 未使用光影包（默认/内部光影）→ 允许
                if (shaderPackName.isEmpty()) return;

                // 检查是否在白名单中
                boolean allowed = cfg.getShaderWhitelist().stream()
                        .anyMatch(name -> name.equalsIgnoreCase(shaderPackName));

                if (!allowed) {
                    LOGGER.warn("玩家 {} 使用了未授权的光影包 '{}'，将被踢出服务器",
                            player.getName().getString(), shaderPackName);
                    player.connection.disconnect(Component.literal(
                            "§c✖ 未授权的光影包\n\n" +
                            "§7你使用的光影包 §e" + shaderPackName + " §7不在服务器白名单中。\n" +
                            "§7请更换为允许的光影包后重新加入。\n\n" +
                            "§7如需帮助，请联系服务器管理员。"));
                } else {
                    LOGGER.info("玩家 {} 的光影包 '{}' 已通过白名单校验",
                            player.getName().getString(), shaderPackName);
                }
            });
        });

        // =========================================================
        //  注册 C2S 配置更新接收器（OP 通过 ModMenu 修改配置时）
        //  包含任务配置和光影白名单的更新
        // =========================================================
        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdateC2SPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;

                // ★ 仅 OP（权限等级 ≥ 4）可以修改服务端配置
                if (!player.hasPermissions(4)) {
                    LOGGER.warn("玩家 {} 尝试修改服务端配置但无 OP 权限，已拒绝",
                            player.getName().getString());
                    player.sendSystemMessage(
                            Component.literal("§c你没有权限修改服务端配置（需要 OP 权限）"));
                    return;
                }

                // 应用配置（包括光影白名单）
                HabiConfigManager.getInstance().loadFromJsonString(payload.getConfigJson());
                HabiConfigManager.getInstance().save();
                LOGGER.info("玩家 {} 通过 ModMenu 更新了服务端任务配置",
                        player.getName().getString());

                // ★ 单机模式（集成服务器）：跳过广播
                //    客户端和服务端运行在同一 JVM，共享同一个配置文件，
                //    无需额外同步，避免 ConcurrentModificationException
                if (context.server().isSingleplayer()) return;

                // 广播任务配置给所有在线玩家
                TaskConfigSyncPayload.broadcastToAll(context.server());

                // 广播光影白名单给所有在线玩家
                ShaderConfigSyncS2CPayload.broadcastToAll(context.server());
            });
        });

        // =========================================================
        //  自动录制回放 - 监听游戏开始/结束事件
        //  需要 ServerReplay 模组配合，在游戏开始/结束时自动执行录制命令
        // =========================================================

        // 游戏开始 → 清理待加入语音群组队列，并开始所有玩家录制
        OnGameStarted.EVENT.register(serverLevel -> {
            // 游戏已开始：清除所有待加入语音群组的队列
            // 防止在 STARTING 阶段加入的玩家在游戏 ACTIVE 后还被加入群组
            if (!pendingVoiceJoins.isEmpty()) {
                pendingVoiceJoins.clear();
                LOGGER.info("[VoiceGroup] 游戏开始，已清理待加入语音群组的队列");
            }

            if (HabiConfigManager.getInstance().isAutoReplayRecording()) {
                try {
                    serverLevel.getServer().getCommands().performPrefixedCommand(
                            serverLevel.getServer().createCommandSourceStack(),
                            "replay start players @a"
                    );
                    LOGGER.info("[AutoReplay] 游戏已开始 - 已自动录制所有玩家");
                } catch (Exception e) {
                    LOGGER.error("[AutoReplay] 开始录制失败（ServerReplay 模组可能未安装）", e);
                }
            }
        });

        // 游戏结束 → 将所有玩家拉入语音群组，并停止所有录制（静默执行，不显示具体信息）
        OnGameEnd.EVENT.register((serverLevel, gameWorldComponent) -> {
            // 游戏结束：标记需要将服务器上所有玩家加入语音群组。
            // ★ 不用 server.execute() 延迟，改用 END_SERVER_TICK 处理，确保：
            //   1. finalizeGame() 内的 resetPlayerAfterGame() → TrainVoicePlugin.resetPlayer() 已执行完
            //   2. gameComponent.setGameStatus(INACTIVE) 已设置
            //   3. 所有 SRE 清理完成后才执行群组操作
            // 标记在下一 tick 由 processGameEndGroupJoin() 处理
            pendingGameEndGroupJoin = true;
            LOGGER.info("[VoiceGroup] 游戏结束，已标记待处理（下一tick将添加 {} 名玩家到语音群组）",
                    serverLevel.getServer().getPlayerList().getPlayers().size());

            if (HabiConfigManager.getInstance().isAutoReplayRecording()) {
                try {
                    // 使用静默命令源执行，防止 ServerReplay 发送大量录制信息到聊天栏
                    CommandSourceStack original = serverLevel.getServer().createCommandSourceStack();
                    CommandSourceStack silentSource = new CommandSourceStack(
                            new CommandSource() {
                                @Override
                                public void sendSystemMessage(Component component) {
                                    // 静默 - 不发送任何消息
                                }

                                @Override
                                public boolean acceptsSuccess() {
                                    return false;
                                }

                                @Override
                                public boolean acceptsFailure() {
                                    return false;
                                }

                                @Override
                                public boolean shouldInformAdmins() {
                                    return false;
                                }
                            },
                            original.getPosition(),
                            original.getRotation(),
                            original.getLevel(),
                            4,
                            original.getTextName(),
                            original.getDisplayName(),
                            original.getServer(),
                            original.getEntity()
                    );

                    // ★ 通过反射临时关闭 ServerReplay 的 OP 通知，防止异步保存事件刷屏
                    //    ServerReplay 在异步线程（pool 线程）保存录制后触发事件，
                    //    RecorderNotifier 恢复发消息时检查 config.notifyAdminsOfStatus。
                    //    设为 false 后消息不会发送到聊天栏。
                    //    Mixin (@Pseudo + @Mixin(targets)) 可能因类加载器不可见而失效，
                    //    反射方案作为可靠的后备。
                    //
                    //    注意：录制保存是异步的，config 的修改会持续生效直到下次服务器重启。
                    //         这不会持久化到配置文件（反射不触发生序化），仅影响运行时的通知。
                    //         后续游戏开始/结束的消息也不再广播——这正是我们需要的静默行为。
                    boolean restored = false;
                    try {
                        Class<?> serverReplayClass = Class.forName("me.senseiwells.replay.ServerReplay");
                        java.lang.reflect.Field configField = serverReplayClass.getDeclaredField("config");
                        configField.setAccessible(true);
                        Object config = configField.get(null);

                        java.lang.reflect.Field notifyField = config.getClass().getDeclaredField("notifyAdminsOfStatus");
                        notifyField.setAccessible(true);

                        // 设为 false → ServerReplay 的 broadcastToOps 跳过 ops().broadcast()
                        notifyField.setBoolean(config, false);

                        // 执行停止命令（此时 async 事件检查 config，发现 false 就不会广播）
                        serverLevel.getServer().getCommands().performPrefixedCommand(silentSource, "replay stop all");

                        restored = true;
                    } catch (Exception e) {
                        LOGGER.warn("[AutoReplay] 无法通过反射静默 ServerReplay，回退到纯静默命令源");
                    }

                    if (!restored) {
                        serverLevel.getServer().getCommands().performPrefixedCommand(silentSource, "replay stop all");
                    }

                    // 发送简洁的完成提示
                    serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                            Component.literal("§a[AutoReplay] §7录制完成"),
                            false
                    );
                    LOGGER.info("[AutoReplay] 游戏已结束 - 已自动停止录制");
                } catch (Exception e) {
                    LOGGER.error("[AutoReplay] 停止录制失败（ServerReplay 模组可能未安装）", e);
                }
            }
        });

        LOGGER.info("哈比列车任务API 初始化完成！");
    }

    // ==================================================================
    //  /instantgroup 命令实现
    //  服主或OP可以通过指令，一次性将附近一定范围内的所有玩家拉入一个临时群组
    //  用法: /instantgroup [范围]  (默认 128 格)
    //  通过 Simple Voice Chat API 直接创建临时群组，无需 enhancedgroups 模组
    // ==================================================================

    /**
     * 执行 /instantgroup 命令
     * 将命令发送者附近的玩家加入一个临时语音群组
     */
    private static int executeInstantGroup(CommandContext<CommandSourceStack> context, int range) {
        CommandSourceStack source = context.getSource();
        ServerPlayer sender = source.getPlayer();
        if (sender == null) {
            source.sendFailure(Component.literal("§c此命令只能由玩家执行"));
            return 0;
        }

        // Simple Voice Chat 未安装 → 报错
        if (TrainVoicePlugin.isVoiceChatMissing()) {
            source.sendFailure(Component.literal("§c语音聊天系统未安装"));
            return 0;
        }
        if (TrainVoicePlugin.SERVER_API == null) {
            source.sendFailure(Component.literal("§c语音聊天系统尚未就绪，请稍后再试"));
            return 0;
        }
        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;

        // 检查发送者自身的语音连接是否就绪
        if (api.getConnectionOf(sender.getUUID()) == null) {
            source.sendFailure(Component.literal("§c你的语音连接尚未就绪，请检查 Simple Voice Chat 是否正确连接"));
            return 0;
        }

        // 查找附近的玩家
        MinecraftServer srv = source.getServer();
        Vec3 senderPos = sender.position();
        List<ServerPlayer> nearbyPlayers = new ArrayList<>();
        for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(sender.getUUID())) continue;
            if (player.distanceToSqr(sender) <= (double) range * range) {
                nearbyPlayers.add(player);
            }
        }

        if (nearbyPlayers.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§e附近 " + range + " 格内没有其他玩家"), true);
            return 0;
        }

        // 创建临时群组
        Group tempGroup;
        try {
            tempGroup = api.groupBuilder()
                    .setId(UUID.randomUUID())
                    .setName("临时群组")
                    .setPersistent(false)
                    .setType(Group.Type.OPEN)
                    .setHidden(false)
                    .build();
        } catch (Exception e) {
            LOGGER.error("[InstantGroup] 创建临时群组失败", e);
            source.sendFailure(Component.literal("§c创建语音群组失败"));
            return 0;
        }

        // 将发送者加入临时群组
        try {
            VoicechatConnection senderConn = api.getConnectionOf(sender.getUUID());
            if (senderConn != null) {
                senderConn.setGroup(tempGroup);
            }
        } catch (Exception e) {
            LOGGER.warn("[InstantGroup] 发送者 {} 加入临时群组失败", sender.getName().getString(), e);
        }

        // 将附近的玩家加入临时群组
        int count = 0;
        StringBuilder addedNames = new StringBuilder();
        for (ServerPlayer player : nearbyPlayers) {
            try {
                VoicechatConnection connection = api.getConnectionOf(player.getUUID());
                if (connection != null) {
                    connection.setGroup(tempGroup);
                    count++;
                    if (addedNames.length() > 0) addedNames.append("、");
                    addedNames.append(player.getName().getString());
                } else {
                    LOGGER.warn("[InstantGroup] 玩家 {} 语音连接未就绪，跳过", player.getName().getString());
                }
            } catch (Exception e) {
                LOGGER.warn("[InstantGroup] 玩家 {} 加入临时群组失败", player.getName().getString(), e);
            }
        }

        // 发送成功消息
        final int finalCount = count;
        source.sendSuccess(() -> Component.literal(
                "§a已将 §e" + finalCount + " §a名附近玩家加入临时语音群组（范围: " + range + " 格）"),
                true);

        // 向被加入的玩家发送提示
        Component notifyMsg = Component.literal("§7[语音] §a你已被加入临时语音群组");
        for (ServerPlayer player : nearbyPlayers) {
            try {
                if (api.getConnectionOf(player.getUUID()) != null) {
                    player.sendSystemMessage(notifyMsg);
                }
            } catch (Exception ignored) {
            }
        }

        LOGGER.info("[InstantGroup] {} 执行 /instantgroup {}，将 {} 名玩家加入临时群组: {}",
                sender.getName().getString(), range, count, addedNames);
        return count;
    }

    // ==================================================================
    //  大厅语音群组管理
    //  使用 Simple Voice Chat API 直接管理，不依赖 enhancedgroups 模组
    // ==================================================================

    /**
     * 每 tick 处理待加入语音群组的玩家。
     * 玩家 JOIN 时 Simple Voice Chat 连接可能尚未就绪，
     * 因此需要在后续 tick 中重试，直到语音连接可用。
     */
    private static void processPendingVoiceJoins(MinecraftServer server) {
        if (pendingVoiceJoins.isEmpty()) return;

        Iterator<Map.Entry<UUID, Integer>> it = pendingVoiceJoins.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            UUID playerId = entry.getKey();
            int retriesLeft = entry.getValue();

            // 玩家已离开服务器 → 移除
            if (server.getPlayerList().getPlayer(playerId) == null) {
                it.remove();
                continue;
            }

            // 超过最大重试次数 → 超时移除
            if (retriesLeft <= 0) {
                LOGGER.warn("[VoiceGroup] 玩家 {} 语音群组加入超时（{} tick内语音连接未就绪）",
                        playerId, MAX_VOICE_JOIN_RETRIES);
                it.remove();
                continue;
            }

            // 尝试加入语音群组（通过 SVC API）
            boolean success = addPlayerToLobbyGroup(server, playerId);
            entry.setValue(retriesLeft - 1);

            if (success) {
                // 语音连接已就绪且成功加入群组
                it.remove();
                LOGGER.info("[VoiceGroup] 玩家 {} 语音群组加入成功",
                        server.getPlayerList().getPlayer(playerId).getName().getString());
            }
        }
    }

    /**
     * 每 tick 处理游戏结束后待加入语音群组的玩家。
     * 在 finalizeGame() 完成后，于下一 END_SERVER_TICK 执行，
     * 确保 SRE 的 resetPlayerAfterGame() 和 setGameStatus(INACTIVE) 均已执行完毕。
     */
    private static void processGameEndGroupJoin(MinecraftServer server) {
        if (!pendingGameEndGroupJoin) return;
        pendingGameEndGroupJoin = false;

        try {
            java.util.List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;

            int success = 0;
            int failed = 0;
            for (ServerPlayer player : players) {
                boolean result = addPlayerToLobbyGroup(server, player.getUUID());
                if (result) {
                    success++;
                } else {
                    failed++;
                    LOGGER.warn("[VoiceGroup]   ↪ {} 加入语音群组失败（语音连接未就绪）",
                            player.getName().getString());
                }
            }
            LOGGER.info("[VoiceGroup] 游戏结束的语音群组结果: {}/{} 成功, {}/{} 失败",
                    success, players.size(), failed, players.size());
        } catch (Exception e) {
            LOGGER.error("[VoiceGroup] 游戏结束添加玩家到语音群组失败", e);
        }
    }

    /**
     * 通过 Simple Voice Chat API 将玩家加入 "LobbyChat" 大厅语音群组。
     * 使用 TrainVoicePlugin.SERVER_API 直接操作语音群组，不依赖 enhancedgroups 命令。
     * 群组在首次使用时延迟创建。
     *
     * @param server     MinecraftServer 实例
     * @param playerUUID 玩家UUID
     * @return true 如果语音连接就绪且成功加入群组，false 如果连接未就绪
     */
    private static boolean addPlayerToLobbyGroup(MinecraftServer server, UUID playerUUID) {
        // Simple Voice Chat 未安装 → 跳过
        if (TrainVoicePlugin.isVoiceChatMissing()) {
            return false;
        }

        // VoiceChatServerApi 未就绪 → 重试
        if (TrainVoicePlugin.SERVER_API == null) {
            return false;
        }
        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;

        // 玩家语音连接尚未就绪 → 重试
        VoicechatConnection connection = api.getConnectionOf(playerUUID);
        if (connection == null) {
            return false;
        }

        ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
        if (player == null) {
            return false;
        }

        try {
            // 延迟创建大厅群组（首次使用时创建）
            if (LOBBY_GROUP == null) {
                LOBBY_GROUP = api.groupBuilder()
                        .setId(LOBBY_GROUP_ID)
                        .setName("LobbyChat")
                        .setPersistent(true)
                        .setType(Group.Type.OPEN)
                        .setHidden(false)
                        .build();
                LOGGER.info("[VoiceGroup] 已通过 SVC API 创建大厅语音群组 'LobbyChat'");
            }

            // 使用 SVC API 直接设置玩家的语音群组
            connection.setGroup(LOBBY_GROUP);
            return true;
        } catch (Exception e) {
            LOGGER.error("[VoiceGroup] 添加玩家 {} 到语音群组时发生异常",
                    player.getName().getString(), e);
            return false;
        }
    }

    /**
     * 将原版SRE任务注册为内置任务，使其可在ModMenu中配置
     */
    private void registerOriginalTasksAsBuiltin() {
        // 谋杀模式任务
        registerBuiltin("sleep", "睡觉", HabiTaskCategory.MURDER, 1.0f, 4);
        registerBuiltin("eat", "进食", HabiTaskCategory.MURDER, 1.0f, 1);
        registerBuiltin("drink", "喝水", HabiTaskCategory.MURDER, 1.0f, 2);
        registerBuiltin("exercise", "锻炼", HabiTaskCategory.MURDER, 1.0f, 5);
        // 注意: 原版SRE的Task枚举中拼写为RAED_BOOK(拼写错误)，此处需保持一致
        registerBuiltin("raed_book", "阅读", HabiTaskCategory.MURDER, 1.0f, 6);
        registerBuiltin("bathe", "洗澡", HabiTaskCategory.MURDER, 1.0f, 3);
        registerBuiltin("toilet", "上厕所", HabiTaskCategory.MURDER, 1.0f, 8);
        registerBuiltin("chair", "坐椅子", HabiTaskCategory.MURDER, 1.0f, 9);
        registerBuiltin("note_block", "音符盒", HabiTaskCategory.MURDER, 1.0f, 10);
        registerBuiltin("meditate", "冥想", HabiTaskCategory.MURDER, 1.0f, -1);
        registerBuiltin("outside", "外出", HabiTaskCategory.MURDER, 1.0f, -1);
        registerBuiltin("breathe", "呼吸新鲜空气", HabiTaskCategory.MURDER, 1.0f, -1);

        // 修机模式任务
        registerBuiltin("repair_wire", "修复线路", HabiTaskCategory.REPAIR, 1.0f, -1);
        registerBuiltin("repair_panel", "修复面板", HabiTaskCategory.REPAIR, 1.0f, -1);

        // 共用任务
        registerBuiltin("vending_machine", "售货机", HabiTaskCategory.ALL, 0.5f, 11);

        LOGGER.info("已注册 {} 个内置任务", HabiTaskRegistry.size());
    }

    private void registerBuiltin(String id, String displayName, HabiTaskCategory category,
                                  float weight, int blockTypeId) {
        HabiTaskRegistry.register(new HabiTaskDefinition.Builder(MOD_ID, id)
                .displayName(displayName)
                .category(category)
                .weight(weight)
                .blockTypeId(blockTypeId)
                .build()
        );
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
