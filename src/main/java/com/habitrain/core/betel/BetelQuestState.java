package com.habitrain.core.betel;

import betel.nut.BetelNutConfig;
import betel.nut.component.BetelNutAddictionComponent;
import betel.nut.component.BetelNutEntityComponents;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.misc.EffectOwnershipTracker;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * 槟榔任务状态管理器
 * 使用tick轮询检测吃槟榔和成瘾事件（代替Mixin）
 * 同时处理游戏结束、玩家死亡/旁观、新人加入时的成瘾清除
 *
 * 【修复说明】
 * - 不再在clearAddictionForPlayer中移除SLOWNESS(缓慢)，避免与槟榔叶采集的缓慢冲突
 * - 吃槟榔检测现在对lastEatTime做两层检测 (lastKnownLastEatTime + lastDetectedEatTime)
 * - 修复ateBetelNutToRelieve状态重置逻辑，确保重度依赖的缓慢/黑暗能正确解除
 * - 当enableAddictionSystem关闭时自动跳过依赖config的代码，但仍能检测吃槟榔事件
 */
public class BetelQuestState {
    // ===== SRE 角色类型常量 =====
    private static final int SRE_ROLE_CIVILIAN = 1;
    private static final int SRE_ROLE_NEUTRAL_1 = 2;
    private static final int SRE_ROLE_NEUTRAL_2 = 3;
    private static final int SRE_ROLE_KILLER = 4;
    private static final int SRE_ROLE_SHERIFF = 5;

    private static BetelQuestState instance;

    /** 每局游戏是否已使用揭晓机制 */
    private boolean revealUsedThisRound = false;

    /** 记录每个玩家ID -> 玩家状态 */
    private final Map<UUID, PlayerBetelData> playerData = new HashMap<>();

    private BetelQuestState() {}

    public static void init() {
        instance = new BetelQuestState();
    }

    public static BetelQuestState getInstance() {
        if (instance == null) {
            instance = new BetelQuestState();
        }
        return instance;
    }

    private PlayerBetelData computePlayerData(UUID uuid) {
        return playerData.computeIfAbsent(uuid, k -> new PlayerBetelData());
    }

    /** 获取玩家数据（静态快捷方式） */
    public static PlayerBetelData getPlayerData(UUID uuid) {
        return getInstance().computePlayerData(uuid);
    }

    // ===== 食物限制系统（来自UseItemCallback） =====
    private static final TagKey<net.minecraft.world.item.Item> BETEL_NUTS_TAG =
            TagKey.create(Registries.ITEM,
                    ResourceLocation.fromNamespaceAndPath("betel-nut-mod", "betel_nuts"));

    /**
     * 注册食物拦截事件 - 当玩家处于Stage 3+食物限制时，
     * 禁止食用除槟榔标签(betel-nut-mod:betel_nuts)以外的任何食物
     */
    public static void registerFoodRestriction() {
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(
            (net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level world, net.minecraft.world.InteractionHand hand) -> {
                if (world.isClientSide()) return net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand));
                if (!(player instanceof ServerPlayer serverPlayer)) return net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand));

                var stack = serverPlayer.getItemInHand(hand);
                if (stack.isEmpty()) return net.minecraft.world.InteractionResultHolder.pass(stack);

                // 检查是否有食物组件（即这是否是可食用的物品）
                var foodComponent = stack.get(DataComponents.FOOD);
                if (foodComponent == null) return net.minecraft.world.InteractionResultHolder.pass(stack);

                UUID uuid = serverPlayer.getUUID();
                if (!hasFoodRestriction(uuid)) return net.minecraft.world.InteractionResultHolder.pass(stack);

                // 检查是否是槟榔（槟榔标签内的物品允许食用）
                if (stack.is(BETEL_NUTS_TAG)) {
                    return net.minecraft.world.InteractionResultHolder.pass(stack); // 槟榔可以吃
                }

                // 禁止吃其他食物
                serverPlayer.displayClientMessage(Component.literal("§c你的身体没办法接受正常的食物.."), true);
                return net.minecraft.world.InteractionResultHolder.fail(stack);
            }
        );
        HabiTrainCore.LOGGER.info("已注册槟榔食物限制系统");
    }

    // ===== 槟榔任务刷新状态管理 =====

    /**
     * 标记玩家的槟榔任务已在本局刷新过
     * 之后玩家可以随时采集槟榔（不再需要任务激活）
     */
    public static void markQuestAssigned(UUID uuid) {
        getPlayerData(uuid).hasBetelQuestBeenAssigned = true;
        HabiTrainCore.LOGGER.debug("玩家 {} 的槟榔任务已标记为本局已刷新", getPlayerName(uuid));
    }

    /**
     * 检查玩家的槟榔任务是否已在本局刷新过
     */
    public static boolean hasQuestBeenAssigned(UUID uuid) {
        return getPlayerData(uuid).hasBetelQuestBeenAssigned;
    }

    /**
     * 检查玩家是否处于食物限制状态（Stage 3+，只能吃槟榔）
     */
    public static boolean hasFoodRestriction(UUID uuid) {
        return getPlayerData(uuid).hasFoodRestriction;
    }

    /**
     * 设置玩家的食物限制状态
     */
    public static void setFoodRestriction(UUID uuid, boolean restricted) {
        getPlayerData(uuid).hasFoodRestriction = restricted;
    }

    /**
     * 重置玩家在本局的吃槟榔状态（使用玩家对象直接操作）
     * 由 onAssign 回调调用，确保新任务需要新的吃槟榔动作
     *
     * ★ 通过玩家对象直接获取 addiction 组件，避免在集成服务器中服务端查找失败。
     *
     * 策略：同步当前 addiction.getLastEatTime() 到 lastDetectedEatTime，
     * 使检测系统跳过已记录的旧时间戳，同时不干扰新吃槟榔事件的检测。
     *
     * - 若 addiction.getLastEatTime() 有残留值（如上一局吃槟榔的时间戳）
     *   → lastDetectedEatTime = 该值，tickPlayer 中 current == lastDetected → 不触发 ✓
     * - 若 addiction.getLastEatTime() = 0（已被游戏结束/死亡清理）
     *   → lastDetectedEatTime = 0，玩家吃槟榔后 lastEatTime 变化 → 正常检测 ✓
     * - 两种路径都不会吞掉新吃槟榔事件
     */
    public static void resetEatenStatus(Player player) {
        if (player == null) return;
        PlayerBetelData data = getPlayerData(player.getUUID());
        data.hasEatenBetelNut = false;

        try {
            var addiction = BetelNutEntityComponents.ADDICTION.get(player);
            long currentEatTime = addiction.getLastEatTime();
            data.lastDetectedEatTime = currentEatTime > 0 ? currentEatTime : 0;
        } catch (Exception e) {
            data.lastDetectedEatTime = 0;
        }

        HabiTrainCore.LOGGER.debug("玩家 {} 的吃槟榔状态已重置 (lastDetectedEatTime={})",
                player.getName().getString(), data.lastDetectedEatTime);
    }

    /** 获取当前服务器实例 */
    private static MinecraftServer getCurrentServer() {
        try {
            var instance = net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance();
            if (instance instanceof MinecraftServer server) {
                return server;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 获取玩家名称（日志用） */
    private static String getPlayerName(UUID uuid) {
        var server = getCurrentServer();
        if (server != null) {
            var player = server.getPlayerList().getPlayer(uuid);
            if (player != null) return player.getName().getString();
        }
        return uuid.toString();
    }

    /** 重置所有状态 (游戏结束时) */
    public void resetAll() {
        revealUsedThisRound = false;
        playerData.clear();
    }

    public boolean isRevealUsed() {
        return revealUsedThisRound;
    }

    public void setRevealUsed(boolean used) {
        this.revealUsedThisRound = used;
    }

    /**
     * 检测当前世界是否处于活跃游戏状态
     */
    public static boolean isGameActive(ServerLevel world) {
        try {
            Object gameComponent = SREGameWorldComponent.KEY.get(world);
            if (gameComponent instanceof SREGameWorldComponent gc) {
                return gc.isRunning();
            }
        } catch (Exception e) {
            // 忽略
        }
        return false;
    }

    /**
     * 清除合成天下槟榔的所有计数器和相关视觉效果
     * 直接调用槟榔mod的 clearHechengTianxiaData 方法，不再使用反射
     */
    public static void clearHechengTianxiaData(ServerPlayer player) {
        try {
            BetelNutAddictionComponent addiction = BetelNutEntityComponents.ADDICTION.get(player);
            addiction.clearHechengTianxiaData(player);
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("清除合成天下槟榔数据失败", e);
        }
    }

    /**
     * 清理玩家身上的所有成瘾效果
     * 包括：移除状态效果 + 重置槟榔成瘾组件数据 + 清除合成天下槟榔计数器
     *
     * ★ 现在使用 EffectOwnershipTracker 追踪来源，只移除槟榔任务系统添加的效果，
     *    不干扰其他模组的效果（如关灯DARKNESS、手铐缓慢等）。
     *
     * ★ 现在同时清除合成天下槟榔的 hechengTianxia*Ticks 计数器，
     *    解决"身体承受不住第二颗"限制在死亡/观战/游戏结束后仍不解除的问题。
     *
     * 注意：不主动移除SLOWNESS(缓慢)，因为槟榔叶采集任务也使用该效果，
     * 调用此方法时玩家可能正在进行采集。
     * 槟榔叶的缓慢会在采集完成/中断时自行移除。
     */
    private static void clearAddictionForPlayer(ServerPlayer player) {
        UUID pUuid = player.getUUID();

        // 使用归属追踪器释放槟榔任务的效果（引用计数方式）
        // 只有没有其他来源引用时才真正移除
        if (EffectOwnershipTracker.release(pUuid, MobEffects.DARKNESS, "betel_quest")) {
            player.removeEffect(MobEffects.DARKNESS);
        }
        if (EffectOwnershipTracker.release(pUuid, MobEffects.MOVEMENT_SPEED, "betel_quest")) {
            player.removeEffect(MobEffects.MOVEMENT_SPEED);
        }
        if (EffectOwnershipTracker.release(pUuid, MobEffects.GLOWING, "betel_quest")) {
            player.removeEffect(MobEffects.GLOWING);
        }

        // 移除 noellesroles 效果（如果存在）
        try {
            ResourceLocation noellesId = ResourceLocation.fromNamespaceAndPath("noellesroles", "noellesroles");
            BuiltInRegistries.MOB_EFFECT.getHolder(
                    ResourceKey.create(Registries.MOB_EFFECT, noellesId)
            ).ifPresent(player::removeEffect);
        } catch (Exception ignored) {}

        // 清除槟榔mod中的成瘾组件数据
        try {
            BetelNutAddictionComponent addiction = BetelNutEntityComponents.ADDICTION.get(player);
            addiction.clearAddiction(player);
        } catch (Exception e) {
            // 跨映射兼容，静默处理
        }

        // ★ 清除合成天下槟榔计数器（死亡/观战/游戏结束重置）
        // 注：addiction.clearAddiction() 内部已调用 clearHechengTianxiaData，此处不再重复调用
        // clearHechengTianxiaData(player);

        HabiTrainCore.LOGGER.debug("已清除玩家 {} 的槟榔成瘾效果", player.getName().getString());
    }

    /**
     * 每个玩家的tick逻辑
     * 检测：新玩家加入清除成瘾、旁观/死亡清除成瘾、游戏结束清除成瘾
     * 以及吃槟榔事件、成瘾发作、重度依赖、身份揭晓
     */
    public static void tickPlayer(ServerPlayer player) {
        BetelQuestState state = getInstance();
        PlayerBetelData data = state.computePlayerData(player.getUUID());

        // ===== 新玩家加入（第一次tick）- 清除残留的成瘾效果 =====
        if (!data.hasBeenProcessed) {
            data.hasBeenProcessed = true;
            clearAddictionForPlayer(player);
            return;
        }

        // 获取游戏组件
        SREGameWorldComponent gameWorld;
        try {
            gameWorld = SREGameWorldComponent.KEY.get(player.level());
        } catch (Exception e) {
            clearAddictionForPlayer(player);
            return;
        }

        // ===== 游戏未运行 - 清除成瘾效果（下降沿检测，仅切换时执行一次） =====
        if (!gameWorld.isRunning()) {
            if (!data.wasGameNotRunning) {
                data.wasGameNotRunning = true;
                clearAddictionForPlayer(player);
            }
            return;
        }
        data.wasGameNotRunning = false;

        // ===== 玩家死亡/旁观模式 - 清除成瘾效果（仅切换时执行一次） =====
        if (player.isSpectator()) {
            if (!data.wasSpectating) {
                data.wasSpectating = true;
                clearAddictionForPlayer(player);
            }
            return;
        }
        data.wasSpectating = false;

        if (!player.isAlive()) return;

        // ===== 以下为正常游戏中的槟榔任务逻辑 =====

        // 获取槟榔成瘾组件
        BetelNutAddictionComponent addiction;
        try {
            addiction = BetelNutEntityComponents.ADDICTION.get(player);
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("无法获取槟榔成瘾组件", e);
            return;
        }
        BetelNutConfig config = BetelNutConfig.get();

        // ===== 吃槟榔检测 =====
        boolean detectedEating = false;
        long currentLastEatTime = addiction.getLastEatTime();

        // ★ 直接对比检测：lastEatTime 有更新 → 玩家吃了槟榔
        //    (删除了旧的基线同步逻辑：它在 quest 被分配后，
        //     把首次食用的 lastEatTime 变化当作"遗留数据"跳过了，
        //     导致第一个槟榔永远不被算作任务完成)
        if (currentLastEatTime > 0 && currentLastEatTime != data.lastDetectedEatTime) {
            // lastEatTime有更新 - 玩家吃了槟榔（成瘾系统开启时此值才会变化）
            if (!data.hasEatenBetelNut) {
                data.hasEatenBetelNut = true;
                detectedEating = true;
            }
            data.lastDetectedEatTime = currentLastEatTime;
        }

        // ===== 应用吃槟榔效果 =====
        if (detectedEating) {
            data.ateBetelNutToRelieve = true;
            // 更新独立成瘾追踪数据
            data.betelNutsEatenThisGame++;
            data.ownLastEatGameTime = player.level().getGameTime();

            // 通过归属追踪器释放槟榔任务的效果
            // （释放后如果其他来源仍然引用，效果会保留）
            UUID pUuid = player.getUUID();
            if (EffectOwnershipTracker.release(pUuid, MobEffects.MOVEMENT_SLOWDOWN, "betel_quest")) {
                player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            }
            if (EffectOwnershipTracker.release(pUuid, MobEffects.DARKNESS, "betel_quest")) {
                player.removeEffect(MobEffects.DARKNESS);
            }

            applyBetelNutEffects(player);

            // 给予槟榔渣（额外安全保证，因为 betel-nut-mod 在 finishUsingItem 中已处理）
            giveBetelNutResidue(player);

            // 播放吃槟榔音效（玩家位置，周围玩家可听到）
            player.serverLevel().playSound(
                    null, // null = 所有在范围内的玩家都能听到
                    player.blockPosition(),
                    HabiTrainCore.BETEL_NUT_EAT_SOUND,
                    SoundSource.PLAYERS,
                    1.0f,  // 音量
                    1.0f   // 音调
            );
            player.displayClientMessage(Component.literal("§7你嚼碎了槟榔，只剩下一些渣滓"), true);

            HabiTrainCore.LOGGER.debug("玩家 {} 检测到吃槟榔，已给予槟榔渣并播放音效", player.getName().getString());
        }

        // ===== 读取成瘾数值（用于身份揭晓判断 + 食物限制） =====
        boolean shouldReveal = false;
        int currentStage = 0; // 用于食物限制判断

        if (config.enableAddictionSystem) {
            // 槟榔mod成瘾系统开启：使用其官方数值
            currentStage = addiction.getAddictionStage();
            int bsev = addiction.getWithdrawalSeverity();
            if (currentStage >= 3 && bsev > 0 && !state.isRevealUsed()) {
                HabiTrainCore.LOGGER.info("槟榔揭晓触发(官方): 玩家 {} stage={}, severity={}",
                        player.getName().getString(), currentStage, bsev);
                shouldReveal = true;
            }
        } else {
            // 成瘾系统关闭：使用本mod独立追踪
            int ownValue = data.betelNutsEatenThisGame * 5;
            int ownStage = 0;
            if (ownValue >= 80)      ownStage = 5;
            else if (ownValue >= 60) ownStage = 4;
            else if (ownValue >= 40) ownStage = 3;
            else if (ownValue >= 20) ownStage = 2;
            else if (ownValue > 0)   ownStage = 1;
            currentStage = ownStage;

            // 戒断检测
            boolean inWithdrawal = false;
            if (ownStage >= 3 && data.ownLastEatGameTime > 0) {
                long ticksSinceLastEat = player.level().getGameTime() - data.ownLastEatGameTime;
                inWithdrawal = ticksSinceLastEat >= 600;
            }
            if (ownStage != data.lastDiagnosticStage) {
                data.lastDiagnosticStage = ownStage;
                HabiTrainCore.LOGGER.info("槟榔揭晓诊断: 玩家 {} 当前Stage={} (本局吃槟榔次数={})",
                        player.getName().getString(), ownStage, data.betelNutsEatenThisGame);
            }
            if (ownStage >= 3 && inWithdrawal && !state.isRevealUsed()) {
                HabiTrainCore.LOGGER.info("槟榔揭晓触发(独立追踪): 玩家 {} stage={}, 戒断中",
                        player.getName().getString(), ownStage);
                shouldReveal = true;
            }
        }

        if (shouldReveal) {
            state.executeReveal(player, gameWorld);
            state.setRevealUsed(true);
        }

        // ===== Stage 3+食物限制：无法进食除槟榔以外的食物 =====
        if (currentStage >= 3 && !data.hasFoodRestriction) {
            data.hasFoodRestriction = true;
            player.displayClientMessage(Component.literal("§c你的身体没办法接受正常的食物.."), true);
            HabiTrainCore.LOGGER.info("玩家 {} 达到Stage 3，食物限制已激活", player.getName().getString());
        }

        // ===== 以下为成瘾相关视觉效果，仅在成瘾系统开启时执行 =====
        if (!config.enableAddictionSystem) {
            if (data.ateBetelNutToRelieve && data.hasHeavyAddiction) {
                data.hasHeavyAddiction = false;
                data.ateBetelNutToRelieve = false;
            }
            return;
        }

        // ===== 同步lastKnownLastEatTime用于存档兼容 =====
        if (currentLastEatTime > 0 && currentLastEatTime != data.lastKnownLastEatTime) {
            data.lastKnownLastEatTime = currentLastEatTime;
        }

        // 此部分仅在成瘾系统开启时执行，直接读取槟榔mod的原始数值
        int betelAddictionStage = addiction.getAddictionStage();
        int betelWithdrawalSeverity = addiction.getWithdrawalSeverity();
        int betelWithdrawalValue = addiction.getWithdrawalValue();
        int visibleThreshold = Math.max(1, Math.min(25, config.maxWithdrawalValue));

        // ===== 检测成瘾触发 - 黑暗效果30秒 =====
        if (betelWithdrawalValue >= visibleThreshold && betelWithdrawalSeverity > 0) {
            if (!data.darknessAppliedThisTrigger) {
                player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 600, 0, false, true, true));
                EffectOwnershipTracker.claim(player.getUUID(), MobEffects.DARKNESS, "betel_quest");
                data.darknessAppliedThisTrigger = true;
                HabiTrainCore.LOGGER.debug("玩家 {} 成瘾发作，给予黑暗效果", player.getName().getString());
            }
        } else {
            data.darknessAppliedThisTrigger = false;
        }

        // ===== 重度依赖(Stage 3+)：永久缓慢2 + 黑暗，直到重新吃槟榔 =====
        if (betelAddictionStage >= 3) {
            data.hasHeavyAddiction = true;
            if (!data.ateBetelNutToRelieve) {
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 1, false, true, true));
                EffectOwnershipTracker.claim(player.getUUID(), MobEffects.MOVEMENT_SLOWDOWN, "betel_quest");
                player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0, false, true, true));
                EffectOwnershipTracker.claim(player.getUUID(), MobEffects.DARKNESS, "betel_quest");
            }
        } else {
            if (data.hasHeavyAddiction && betelAddictionStage < 3) {
                data.hasHeavyAddiction = false;
                data.ateBetelNutToRelieve = false;
            }
        }
    }

    /**
     * 应用吃槟榔后的效果
     */
    private static void applyBetelNutEffects(ServerPlayer player) {
        UUID pUuid = player.getUUID();

        // 速度1 (10秒 = 200 ticks)
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0, false, true, true));
        EffectOwnershipTracker.claim(pUuid, MobEffects.MOVEMENT_SPEED, "betel_quest");

        // 尝试应用 noellesroles:noellesroles 效果
        applyNoellesrolesEffect(player);
    }

    private static void applyNoellesrolesEffect(ServerPlayer player) {
        try {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("noellesroles", "noellesroles");
            BuiltInRegistries.MOB_EFFECT.getHolder(
                    ResourceKey.create(Registries.MOB_EFFECT, id)
            ).ifPresent(holder ->
                player.addEffect(new MobEffectInstance(holder, 200, 0, false, true, true))
            );
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("找不到 noellesroles:noellesroles 效果，跳过");
        }
    }

    /**
     * 给予玩家槟榔渣
     * betel-nut-mod 在 finishUsingItem 中已处理此逻辑，此方法作为额外安全保证
     */
    private static void giveBetelNutResidue(ServerPlayer player) {
        try {
            var residueItem = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("betel-nut-mod", "betel_nut_residue"));
            if (residueItem == null || residueItem == Items.AIR) {
                HabiTrainCore.LOGGER.warn("槟榔渣物品未找到");
                return;
            }
            ItemStack residue = new ItemStack(residueItem, 1);
            if (!player.getInventory().add(residue)) {
                player.drop(residue, false);
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("给予槟榔渣时出错", e);
        }
    }

    /**
     * 执行身份揭晓
     */
    private void executeReveal(ServerPlayer revealer, SREGameWorldComponent gameWorld) {
        try {
            List<ServerPlayer> allPlayers = new ArrayList<>();
            for (ServerPlayer p : revealer.getServer().getPlayerList().getPlayers()) {
                if (p.level().dimension() == revealer.level().dimension()) {
                    allPlayers.add(p);
                }
            }

            if (allPlayers.isEmpty()) return;

            Random random = new Random();
            ServerPlayer target = allPlayers.get(random.nextInt(allPlayers.size()));

            String roleName = getRoleDisplayName(target, gameWorld);

            String message = String.format("§7[在痛苦中，你察觉 %s 的身份是 %s]",
                    target.getName().getString(), roleName);
            revealer.displayClientMessage(Component.literal(message), true);

            HabiTrainCore.LOGGER.info("[槟榔揭晓] {} 察觉了 {} 的身份: {}",
                    revealer.getName().getString(), target.getName().getString(), roleName);

        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("执行槟榔揭晓时出错", e);
        }
    }

    /**
     * 通过Roles Map获取玩家阵营
     */
    private String getRoleDisplayName(ServerPlayer player, SREGameWorldComponent gameWorld) {
        try {
            var roles = gameWorld.getRoles();
            if (roles == null) {
                HabiTrainCore.LOGGER.warn("获取角色失败: gameWorld.getRoles() 返回 null");
                return "未知";
            }
            SRERole role = roles.get(player.getUUID());
            if (role == null) {
                HabiTrainCore.LOGGER.warn("玩家 {} 没有角色数据", player.getName().getString());
                return "未知";
            }

            int roleType = role.getRoleType();
            String name = switch (roleType) {
                case SRE_ROLE_CIVILIAN -> "平民";
                case SRE_ROLE_NEUTRAL_1, SRE_ROLE_NEUTRAL_2 -> "中立";
                case SRE_ROLE_KILLER -> "杀手";
                case SRE_ROLE_SHERIFF -> "警长";
                default -> "未知";
            };
            HabiTrainCore.LOGGER.debug("获取玩家 {} 阵营: type={}, name={}", player.getName().getString(), roleType, name);
            return name;
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("获取角色显示名时出错", e);
            return "未知";
        }
    }

    /**
     * 检查玩家是否已经吃过槟榔（用于任务完成检测）
     */
    public static boolean hasPlayerEatenBetelNut(UUID uuid) {
        return getPlayerData(uuid).hasEatenBetelNut;
    }

    public static class PlayerBetelData {
        /** 本局槟榔任务是否已刷新给该玩家 */
        boolean hasBetelQuestBeenAssigned = false;

        /** 上次记录的诊断成瘾阶段（用于变化检测，避免重复日志） */
        int lastDiagnosticStage = 0;

        /** 是否已处理过首次tick（用于检测新玩家加入） */
        boolean hasBeenProcessed = false;

        /** 上一tick游戏是否未运行（用于检测游戏结束/开始的状态切换） */
        boolean wasGameNotRunning = false;

        /** 上一tick玩家是否处于旁观模式（用于检测死亡/旁观的状态切换） */
        boolean wasSpectating = false;

        /** 上次记录的 lastEatTime (用于检测吃槟榔事件) */
        long lastKnownLastEatTime = 0;

        /** 更可靠的lastEatTime检测 - 只要lastEatTime变化就触发，防止clearAddiction导致的遗漏 */
        long lastDetectedEatTime = 0;

        /** 本局是否吃过槟榔 */
        boolean hasEatenBetelNut = false;

        /** 本局吃槟榔次数（用于独立成瘾追踪） */
        int betelNutsEatenThisGame = 0;

        /** 本局上次吃槟榔的游戏时间（用于独立戒断追踪） */
        long ownLastEatGameTime = 0;

        /** 本局是否已触发黑暗效果 */
        boolean darknessAppliedThisTrigger = false;

        /** 是否达到重度依赖 */
        boolean hasHeavyAddiction = false;

        /** 是否通过吃槟榔解除了永久效果 */
        boolean ateBetelNutToRelieve = false;

        /** Stage 3+食物限制是否激活（不能吃非槟榔食物） */
        boolean hasFoodRestriction = false;
    }
}
