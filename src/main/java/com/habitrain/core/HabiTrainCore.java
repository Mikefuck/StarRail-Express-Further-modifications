package com.habitrain.core;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.blackout.BlackoutMode;
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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.betel.BetelLeafHandler;
import com.habitrain.core.betel.BetelQuestDefinition;
import com.habitrain.core.betel.BetelQuestState;
import com.habitrain.core.task.BackpackQuestState;
import com.habitrain.core.task.BackpackSearchHandler;
import com.habitrain.core.task.GameLifecycleHandler;
import com.habitrain.core.util.SubtitleNotifier;
import betel.nut.BetelNutConfig;
import io.wifi.starrailexpress.cca.ExtraSlotComponent;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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

    // ===== 方块类型ID常量 =====
    private static final int GRASS_BLOCK_TYPE_ID = 12;
    private static final int CAT_BLOCK_TYPE_ID = 13;
    private static final int BACKPACK_TYPE_ID = 15;
    private static final int NO_BLOCK_TYPE_ID = -1;

    public static final String[] CAT_BLOCK_IDS = {
        "yuushya:british_shorthair", "yuushya:white_cat", "yuushya:black_cat",
        "yuushya:ragdoll", "yuushya:calico", "yuushya:siamese", "yuushya:tabby"
    };

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
        CustomTaskBlockPayload.register();
        // 注：字幕报幕包 starrailexpress:subtitle 由 SRE 4.3.0 原生注册（SREPayloadRegister），
        //     本模组不再重复注册；客户端接收、HUD tick/render 也由 SRE 接管。
        // 4. 注册命令
        registerCommands();
        // 5. 注册生命周期事件
        registerLifecycleEvents();
        // 6. 注册更多模组的任务和系统（合并自 HabiTrainMoreTasks）
        registerMoreTasks();
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
        // 每 tick 处理待加入语音群组的玩家 + 游戏结束后的群组恢复 + 激活的 GameMode tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SREGameModeBase.processPendingVoiceJoins(server);
            SREGameModeBase.processGameEndGroupJoin(server);
            // tick active game modes
            GameModeRegistry.tickAll(server);
            // 更多模组 tick 处理器（槟榔、成瘾、游戏检测）
            tickMoreMods(server);
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

    // ===== 以下方法合并自 HabiTrainMoreTasks =====

    private void registerMoreTasks() {
        // 任务: test_grass（已有的测试任务）
        TaskRegistry.register(MOD_ID, "test_grass", builder -> builder
            .displayName("test_grass")
            .category(TaskCategory.MURDER)
            .weight(1.0f)
            .blockTypeId(GRASS_BLOCK_TYPE_ID)
            .instinctColor(0, 200, 0, 180)
            .scanBlocks(Blocks.GRASS_BLOCK)
            .onAssign((player, task) -> {
                task.setMaxProgress(80);
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;

                Vec3 eyePos = player.getEyePosition();
                Vec3 lookVec = player.getLookAngle();
                double reach = 5.0;
                Vec3 targetPos = eyePos.add(
                    lookVec.x * reach,
                    lookVec.y * reach,
                    lookVec.z * reach
                );

                BlockHitResult hitResult = player.level().clip(
                    new ClipContext(
                        eyePos, targetPos,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        player
                    )
                );

                if (hitResult.getType() == HitResult.Type.BLOCK
                    && player.level().getBlockState(hitResult.getBlockPos()).is(Blocks.GRASS_BLOCK)) {
                    task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
                } else {
                    if (task.getProgress() > 0) {
                        task.setProgress(Math.max(0, task.getProgress() - 2));
                    }
                }
            })
            .completionChecker((player, task) ->
                task.getProgress() >= task.getMaxProgress())
        );

        // 任务: pet_cat（摸猫猫）
        TaskRegistry.register(MOD_ID, "pet_cat", builder -> builder
            .displayName("摸猫猫")
            .category(TaskCategory.MURDER)
            .weight(1.0f)
            .blockTypeId(CAT_BLOCK_TYPE_ID)
            .instinctColor(255, 182, 193, 200)
            .scanBlockIds(CAT_BLOCK_IDS)
            .onAssign((player, task) -> {
                task.setMaxProgress(100);
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;

                Set<Block> currentCatBlocks = resolveCatBlocks();

                Vec3 eyePos = player.getEyePosition();
                Vec3 lookVec = player.getLookAngle();
                double reach = 5.0;
                Vec3 targetPos = eyePos.add(
                    lookVec.x * reach,
                    lookVec.y * reach,
                    lookVec.z * reach
                );

                BlockHitResult hitResult = player.level().clip(
                    new ClipContext(
                        eyePos, targetPos,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        player
                    )
                );

                if (hitResult.getType() == HitResult.Type.BLOCK) {
                    Block lookedBlock = player.level().getBlockState(hitResult.getBlockPos()).getBlock();
                    if (currentCatBlocks.contains(lookedBlock)) {
                        task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
                        return;
                    }
                }

                if (task.getProgress() > 0) {
                    task.setProgress(Math.max(0, task.getProgress() - 2));
                }
            })
            .completionChecker((player, task) ->
                task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    SubtitleNotifier.sendTop(serverPlayer, Component.translatable("task.pet_cat"), Component.literal("§a✔ 摸猫猫任务完成！猫猫真可爱！"));
                }
            })
        );

        // 任务: search_backpack（翻找背包）
        TaskRegistry.register(MOD_ID, "search_backpack", builder -> builder
            .displayName("翻找一下自己的背包...")
            .category(TaskCategory.MURDER)
            .weight(1.0f)
            .blockTypeId(BACKPACK_TYPE_ID)
            .instinctColor(139, 90, 43, 200)
            .scanBlockIds("decocraft:backpack_red")
            .canAssign((player, task) ->
                !BackpackQuestState.hasCompleted(player.getUUID()))
            .onAssign((player, task) -> {
                task.setMaxProgress(120);
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;

                if (BackpackSearchHandler.isSearching(player.getUUID())) {
                    task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
                }
            })
            .completionChecker((player, task) ->
                task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (!(player instanceof ServerPlayer serverPlayer)) return;

                BackpackQuestState.markCompleted(serverPlayer.getUUID());
                BackpackSearchHandler.stopSearching(serverPlayer.getUUID());
                serverPlayer.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                ItemStack granted = giveRandomBackpackItem(serverPlayer);
                // 记录发放的道具，供任务取消时回收
                if (granted != null) {
                    com.habitrain.core.api.ItemReclaimHelper.tagGrantedItem(granted, "habitrain_core:search_backpack");
                    task.addGrantedItem(granted);
                }
                SubtitleNotifier.sendTop(
                    serverPlayer,
                    Component.translatable("task.search_backpack"),
                    Component.literal("§a✔ 翻找背包完成！你找到了一些有用的东西！"));
            })
            .onReclaim((player, task) -> com.habitrain.core.api.ItemReclaimHelper.reclaim(player, "habitrain_core:search_backpack"))
        );

        // 任务: look_my_eyes（LOOK MY EYES）
        TaskRegistry.register(MOD_ID, "look_my_eyes", builder -> builder
            .displayName("LOOK MY EYES")
            .category(TaskCategory.MURDER)
            .weight(1.0f)
            .blockTypeId(NO_BLOCK_TYPE_ID)
            .instinctColor(255, 105, 180, 200)
            .onAssign((player, task) -> {
                task.setMaxProgress(60);
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;
                if (!(player instanceof ServerPlayer serverPlayer)) return;

                Vec3 eyePos = serverPlayer.getEyePosition();
                AABB searchBox = new AABB(eyePos.x - 3.0, eyePos.y - 3.0, eyePos.z - 3.0,
                                           eyePos.x + 3.0, eyePos.y + 3.0, eyePos.z + 3.0);
                List<ServerPlayer> nearby = serverPlayer.serverLevel()
                        .getEntitiesOfClass(ServerPlayer.class, searchBox,
                                p -> p != serverPlayer && p.isAlive());

                Vec3 lookVec = serverPlayer.getLookAngle();
                boolean eyeContact = false;

                for (ServerPlayer otherPlayer : nearby) {
                    Vec3 toOther = otherPlayer.getEyePosition().subtract(eyePos);
                    double distance = toOther.length();
                    if (distance > 3.0) continue;

                    Vec3 dirToOther = toOther.normalize();
                    Vec3 otherLookVec = otherPlayer.getLookAngle();
                    Vec3 dirToThis = eyePos.subtract(otherPlayer.getEyePosition()).normalize();

                    double dotThis = lookVec.dot(dirToOther);
                    double dotOther = otherLookVec.dot(dirToThis);

                    if (dotThis > 0.8 && dotOther > 0.8) {
                        eyeContact = true;
                        break;
                    }
                }

                if (eyeContact) {
                    task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
                } else {
                    if (task.getProgress() > 0) {
                        task.setProgress(0);
                    }
                }
            })
            .completionChecker((player, task) ->
                task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.serverLevel().playSound(
                        null,
                        serverPlayer.blockPosition(),
                        LOOK_MY_EYES_SOUND,
                        SoundSource.PLAYERS,
                        1.0f,
                        1.0f
                    );
                }
                if (player instanceof ServerPlayer serverPlayer) {
                    SubtitleNotifier.sendTop(serverPlayer, Component.translatable("task.look_my_eyes"), Component.literal("§a✔ LOOK MY EYES 完成！你们对视了3秒！"));
                }
            })
        );

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

        LOGGER.info("已注册更多任务: test_grass, 摸猫猫, 翻找背包, LOOK MY EYES, 停电模式x5, 停电日常x7");
    }

    /**
     * 翻找背包完成时，根据玩家阵营发放随机道具
     * 平民/中立: 合成世界槟榔、表情头盔、防御药水、毒药瓶、回形针、左轮手枪、螺丝刀
     * 警长:      撬锁器、钥匙
     * 杀手:      撬棍、双截棍、假左轮、消防斧、硫酸桶、飞刀
     *
     * @return 发放的 ItemStack（已加入玩家背包或掉落），如发放失败返回 null。
     *         调用方可存入 TaskInstance.grantedItems 以便任务取消时回收。
     */
    public static ItemStack giveRandomBackpackItem(ServerPlayer player) {
        try {
            var gameWorld = SREGameWorldComponent.KEY.get(player.level());
            var roles = gameWorld.getRoles();
            var role = roles.get(player.getUUID());
            if (role == null) {
                LOGGER.warn("玩家没有角色数据，无法发放背包奖励");
                return null;
            }

            int roleType = role.getRoleType();
            List<String> itemPool;

            if (roleType == 4) { // 杀手
                itemPool = List.of(
                    "trainmurdermystery:crowbar",
                    "trainmurdermystery:nunchuck",
                    "noellesroles:fake_revolver",
                    "noellesroles:fire_axe",
                    "noellesroles:bucket_of_h2so4",
                    "noellesroles:throwing_knife",
                    "noellesroles:boxing_glove",
                    "noellesroles:pan",
                    "noellesroles:handcuffs",
                    "noellesroles:rope",
                    "noellesroles:signed_paper",
                    "noellesroles:delivery_box",
                    "exposure_polaroid:instant_camera",
                    "noellesroles:extinguisher"
                );
            } else if (roleType == 5) { // 警长
                itemPool = List.of(
                    "trainmurdermystery:lockpick",
                    "trainmurdermystery:firecracker",
                    "trainmurdermystery:iron_door_key",
                    "noellesroles:handcuffs"
                );
            } else { // 平民(1) / 中立(2, 3)
                itemPool = List.of(
                    "betel-nut-mod:synthetic_world_betel",
                    "trainmurdermystery:emoji_helmet",
                    "trainmurdermystery:defense_vial",
                    "trainmurdermystery:poison_vial",
                    "noellesroles:noell_paperclip",
                    "noellesroles:screwdriver"
                );
            }

            int idx = player.getRandom().nextInt(itemPool.size());
            String itemId = itemPool.get(idx);

            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
            if (item != Items.AIR) {
                ItemStack stack = new ItemStack(item, 1);
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }

                // 双节棍特殊处理：根据角色设置初始冷却时间
                if ("trainmurdermystery:nunchuck".equals(itemId)) {
                    int initialCooldown = (roleType == 4) ? 1000 : 200;
                    player.getCooldowns().addCooldown(item, initialCooldown);
                    LOGGER.debug("双节棍初始冷却: {} ticks ({}秒, roleType={})",
                            initialCooldown, initialCooldown / 20, roleType);
                }

                player.displayClientMessage(
                    Component.literal("§e你从背包中翻找到了: ").append(stack.getHoverName()), true);
                LOGGER.info("玩家 {} 翻找背包获得: {} (阵营类型: {})",
                    player.getName().getString(), itemId, roleType);
                return stack;
            } else {
                LOGGER.warn("找不到背包奖励物品: {}", itemId);
            }
        } catch (Exception e) {
            LOGGER.error("发放背包奖励时出错", e);
        }
        return null;
    }

    private static Set<Block> cachedCatBlocks = null;
    public static Set<Block> resolveCatBlocks() {
        // yuushya 模组是否安装在运行期不变，首次解析后缓存，避免每 tick 重新查注册表 + 新建 Set
        if (cachedCatBlocks != null) {
            return cachedCatBlocks;
        }
        Set<Block> blocks = Arrays.stream(CAT_BLOCK_IDS)
            .map(id -> BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)))
            .filter(block -> block != Blocks.AIR)
            .collect(Collectors.toSet());
        if (blocks.isEmpty()) {
            LOGGER.warn("yuushya mod not installed, cat task will have no scan blocks");
        }
        cachedCatBlocks = blocks;
        return blocks;
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
        BetelQuestState.registerFoodRestriction();
        GameLifecycleHandler.register();
    }

    private void tickMoreMods(MinecraftServer server) {
        boolean anyGameActive = false;
        boolean hasActiveGame = false;
        for (ServerLevel world : server.getAllLevels()) {
            BetelLeafHandler.tickHarvests(world);
            if (BetelQuestState.isGameActive(world)) {
                anyGameActive = true;
                hasActiveGame = true;
            }
        }
        GameLifecycleHandler.tickGameEndCheck(anyGameActive, server);

        // 没有游戏进行中时，跳过逐玩家 tick，节省 CPU
        if (!hasActiveGame) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            BetelQuestState.tickPlayer(player);
            ExtraSlotComponent.KEY.get(player).serverTick();
        }
    }
}
