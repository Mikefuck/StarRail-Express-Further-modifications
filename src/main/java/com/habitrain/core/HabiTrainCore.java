package com.habitrain.core;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutPhoneHandler;
import com.habitrain.core.game.blackout.BlackoutPoliceHireService;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutSheriffVoteManager;
import com.habitrain.core.game.blackout.BlackoutShopService;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.game.blackout.sre.SREBlackoutGameMode;
import com.habitrain.core.game.sre.SRERepairMode;
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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
import java.util.List;
import java.util.UUID;

import com.habitrain.core.betel.BetelFoodRestriction;
import com.habitrain.core.betel.BetelLeafHandler;
import com.habitrain.core.betel.BetelQuestDefinition;
import com.habitrain.core.betel.BetelQuestState;
import com.habitrain.core.task.BackpackQuestState;
import com.habitrain.core.task.BackpackSearchHandler;
import com.habitrain.core.task.GameLifecycleHandler;
import com.habitrain.core.task.SlownessReapplyManager;
import betel.nut.BetelNutConfig;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

/**
 * 哈比列车核心 — 主入口类。
 * 职责: 配置初始化、GameMode注册、网络包注册、生命周期事件转发。
 * 注意: 原 {@code HabiTrainTaskAPI} 中的自动录制回放逻辑已完全移除。
 */
public class HabiTrainCore implements ModInitializer {
    public static final String MOD_ID = "habitrain_core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ===== 音效事件常量 =====
    public static final ResourceLocation BETEL_NUT_EAT_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "betel_nut_eat");
    public static final SoundEvent BETEL_NUT_EAT_SOUND = SoundEvent.createVariableRangeEvent(BETEL_NUT_EAT_ID);
    public static final ResourceLocation BETEL_NUT_GET_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "betel_nut_get");
    public static final SoundEvent BETEL_NUT_GET_SOUND = SoundEvent.createVariableRangeEvent(BETEL_NUT_GET_ID);
    public static final ResourceLocation LOOK_MY_EYES_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "look_my_eyes");
    public static final SoundEvent LOOK_MY_EYES_SOUND = SoundEvent.createVariableRangeEvent(LOOK_MY_EYES_ID);
    // look_my_eyes.ogg now bundled in assets

    @Override
    public void onInitialize() {
        LOGGER.info("哈比列车核心 (HabiTrain Core) 初始化中...");
        // 1. 配置系统
        ConfigManager.getInstance().load();
        // 2. 注册内置 GameMode（SRE 模式 + 停电模式）
        //    构造 SRE 模式时会通过 SREGameModeBase 的静态初始化注册原版任务
        GameModeRegistry.register(MOD_ID, "sre:murder", new SREMurderMode());
        GameModeRegistry.register(MOD_ID, "sre:repair", new SRERepairMode());
        GameModeRegistry.register(MOD_ID, "habitrains:blackout", new BlackoutMode());
        // 注册停电模式专用的 SRE GameMode（复用 SRE 原版角色分配流程）。
        SREBlackoutGameMode.register();
        // 按角色能力填充警长/杀手商店目录（canUseKiller=杀手商店, isVigilanteTeam=警长商店）
        com.habitrain.core.game.blackout.BlackoutShopService.bootstrapDefaults();
        // 3. 注册网络包
        TaskConfigPayload.register();
        ActiveTaskPayload.register();
        ConfigUpdatePayload.register();
        ShaderConfigPayload.register();
        ShaderInfoPayload.register();
        BlackoutTimerPayload.register();
        BlackoutAnnouncePayload.register();
        BlackoutSheriffVotePayload.register();       // S2C: 投票状态同步
        BlackoutSheriffVoteCastPayload.register();   // C2S: 玩家投票
        BlackoutPhoneOpenPayload.register();
        BlackoutHirePolicePayload.register();
        BlackoutVotePayload.register();
        BlackoutVoteCastPayload.register();
        CustomTaskBlockPayload.register();
        FullConfigSyncPayload.register();
        // 注：字幕报幕包 starrailexpress:subtitle 由 SRE 4.3.0 原生注册（SREPayloadRegister），
        //     本模组不再重复注册；客户端接收、HUD tick/render 也由 SRE 接管。
        // 4. 注册命令
        registerCommands();
        // 5. 注册生命周期事件
        registerLifecycleEvents();
        // 6. 注册集中式缓慢重施管理器
        SlownessReapplyManager.registerTickHandler();
        // 7. 注册内置任务
        BuiltinTaskRegistrar.register();
        ModTickHandler.register();
        // 停电模式任务注册
        com.habitrain.core.game.blackout.task.AddCoalTask.register();
        com.habitrain.core.game.blackout.task.AddCoalHandler.register();
        com.habitrain.core.game.blackout.task.RepairWiringTask.register();
        com.habitrain.core.game.blackout.task.RepairWiringHandler.register();
        com.habitrain.core.game.blackout.task.SabotageWiringTask.register();
        com.habitrain.core.game.blackout.task.SabotageWiringHandler.register();
        com.habitrain.core.game.blackout.task.FurnaceExplosionTask.register();
        com.habitrain.core.game.blackout.task.FurnaceExplosionHandler.register();
        com.habitrain.core.game.blackout.task.MaintainPowerTask.register();
        com.habitrain.core.game.blackout.task.MaintainPowerHandler.register();
        com.habitrain.core.game.blackout.task.RestorePowerTask.register();
        com.habitrain.core.game.blackout.task.RestorePowerHandler.register();

        // 停电模式日常任务（7个，加入 BLACKOUT_GOOD 池，也自动成为坏人假任务池）
        com.habitrain.core.game.blackout.task.BlackoutEatTask.register();
        com.habitrain.core.game.blackout.task.BlackoutEatHandler.register();
        com.habitrain.core.game.blackout.task.BlackoutDrinkTask.register();
        com.habitrain.core.game.blackout.task.BlackoutDrinkHandler.register();
        com.habitrain.core.game.blackout.task.BlackoutSearchBackpackTask.register();
        com.habitrain.core.game.blackout.task.BlackoutBetelQuestTask.register();
        com.habitrain.core.game.blackout.task.BlackoutPetCatTask.register();
        com.habitrain.core.game.blackout.task.BlackoutBeAloneTask.register();
        com.habitrain.core.game.blackout.task.BlackoutLookMyEyesTask.register();
        BlackoutPhoneHandler.register();
        registerMoreSounds();
        initBetelSystem();
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
      }
    private void registerLifecycleEvents() {
        // 服务器启动后加载配置
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ConfigManager.getInstance().load();
            ConfigManager.getInstance().applyMinigameEnforcement(server);
            // 所有 entrypoint（含本 mod 与依赖 DLC）已在此前完成注册，
            // 现在冻结注册表，禁止运行期注册导致 CME 与状态不一致。
            TaskRegistry.freeze();
            GameModeRegistry.freeze();
            LOGGER.info("配置已加载，共 {} 个已注册任务（注册表已冻结）", TaskRegistry.size());
        });
        // 服务器关闭时清理停电模式各 manager 的 per-level 静态 Map 条目。
        // 单机模式下集成服务器停止后客户端 JVM 仍存活，static 字段不会重置，
        // 不清理会导致下一局残留状态（计时器/角色/商店/投票）误用。
        // 注：fabric-api 此版本无 ServerLevelEvents.UNLOAD，故在 SERVER_STOPPING 遍历所有 level 清理。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (ServerLevel level : server.getAllLevels()) {
                if (GameModeRegistry.isActiveInLevel(level)) {
                    GameModeRegistry.stop(level);
                }
                BlackoutRoleManager.clear(level);
                BlackoutTimerSystem.reset(level);
                BlackoutSheriffVoteManager.reset(level);
                BlackoutShopService.resetRound(level);
            }
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
            CustomTaskBlockPayload.sendToPlayer(player);
            ShaderConfigPayload.sendToPlayer(player);
            // 完整配置同步（global + tasks + gameModes + minigames）：让客户端显示服务端真实值，
            // 避免 OP 联机保存时用本地过期全局项覆盖服务端。
            FullConfigSyncPayload.sendToPlayer(player);
            // 通知激活的 GameMode 玩家加入
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level != null) {
                GameModeRegistry.getActiveForLevel(level)
                    .ifPresent(mode -> mode.onPlayerJoin(player));
            }
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
                ConfigManager.getInstance().applyMinigameEnforcement(context.server());
                LOGGER.info("玩家 {} 通过 ModMenu 更新了服务端配置", player.getName().getString());
                if (context.server().isSingleplayer()) return;
                TaskConfigPayload.broadcastToAll(context.server());
                ShaderConfigPayload.broadcastToAll(context.server());
                // 广播完整配置，让所有客户端的全局项同步到服务端最新值。
                FullConfigSyncPayload.broadcastToAll(context.server());
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
        // C2S 警长投票接收器：客户端投票 → 服务端记票
        ServerPlayNetworking.registerGlobalReceiver(BlackoutSheriffVoteCastPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer voter = context.player();
                if (voter == null) return;
                ServerLevel level = voter.serverLevel();
                if (level == null) return;
                BlackoutSheriffVoteManager.castVote(level, voter.getUUID(), payload.targetPlayerId(), payload.slotIndex());
            });
        });
        // C2S 电话聘请警察接收器
        ServerPlayNetworking.registerGlobalReceiver(BlackoutHirePolicePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                ServerLevel level = player.serverLevel();
                if (level == null) return;
                Component error = BlackoutPoliceHireService.tryHire(level, player);
                if (error != null) {
                    player.sendSystemMessage(error);
                } else {
                    // 聘请成功后服务端状态已更新，客户端下次右键电话会看到 hasHiredThisGame=true
                    player.sendSystemMessage(Component.literal("§a已成功聘请警察！"));
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

    private void registerMoreSounds() {
        Registry.register(BuiltInRegistries.SOUND_EVENT, BETEL_NUT_EAT_ID, BETEL_NUT_EAT_SOUND);
        Registry.register(BuiltInRegistries.SOUND_EVENT, BETEL_NUT_GET_ID, BETEL_NUT_GET_SOUND);
        Registry.register(BuiltInRegistries.SOUND_EVENT, LOOK_MY_EYES_ID, LOOK_MY_EYES_SOUND);
        LOGGER.info("已注册自定义音效: betel_nut_eat, betel_nut_get, look_my_eyes");
    }

    private void initBetelSystem() {
        var betelConfig = BetelNutConfig.get();
        if (!betelConfig.enableAddictionSystem) {
            betelConfig.enableAddictionSystem = true;
            LOGGER.info("已强制开启槟榔mod的成瘾系统（覆盖配置文件设置）");
        } else {
            LOGGER.info("槟榔mod的成瘾系统已开启");
        }
        BetelQuestState.init();
        BackpackQuestState.init();
        BetelQuestDefinition.register();
        BetelLeafHandler.register();
        BackpackSearchHandler.register();
        BetelFoodRestriction.register();
        GameLifecycleHandler.register();
    }

}
