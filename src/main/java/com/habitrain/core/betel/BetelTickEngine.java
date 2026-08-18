package com.habitrain.core.betel;

import betel.nut.BetelNutConfig;
import betel.nut.component.BetelNutAddictionComponent;
import betel.nut.component.BetelNutEntityComponents;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.misc.EffectOwnershipTracker;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class BetelTickEngine {
    private static final int SRE_ROLE_CIVILIAN = 1;
    private static final int SRE_ROLE_NEUTRAL_1 = 2;
    private static final int SRE_ROLE_NEUTRAL_2 = 3;
    private static final int SRE_ROLE_KILLER = 4;
    private static final int SRE_ROLE_SHERIFF = 5;

    private static final int ADDICTION_STAGE_THRESHOLD_1 = 80;
    private static final int ADDICTION_STAGE_THRESHOLD_2 = 60;
    private static final int ADDICTION_STAGE_THRESHOLD_3 = 40;
    private static final int ADDICTION_STAGE_THRESHOLD_4 = 20;
    private static final int WITHDRAWAL_TICK_THRESHOLD = 600;
    private static final int MIN_WITHDRAWAL_VALUE = 1;
    private static final int MAX_WITHDRAWAL_VALUE = 25;
    private static final int DARKNESS_DURATION_TICKS = 600;

    // Lazily cached registry lookups (review L42): a one-shot static block
    // caches a miss forever; retrying keeps the lookups working when mods load
    // or register after the first query.
    private static final ResourceLocation NOELLES_EFFECT_ID = ResourceLocation.fromNamespaceAndPath("noellesroles", "noellesroles");
    private static final ResourceKey<MobEffect> NOELLES_EFFECT_KEY = ResourceKey.create(Registries.MOB_EFFECT, NOELLES_EFFECT_ID);
    private static volatile Holder<MobEffect> noellesEffectHolderCache;
    private static volatile Item betelNutResidueItemCache;

    /** Noelles 效果 holder；未注册时返回 null 并在下次重试（不缓存失败）。 */
    private static Holder<MobEffect> noellesEffectHolder() {
        Holder<MobEffect> holder = noellesEffectHolderCache;
        if (holder == null) {
            try {
                var holderOpt = BuiltInRegistries.MOB_EFFECT.getHolder(NOELLES_EFFECT_KEY);
                if (holderOpt.isPresent()) {
                    holder = holderOpt.get();
                    noellesEffectHolderCache = holder;
                }
            } catch (Exception ignored) {}
        }
        return holder;
    }

    /** 槟榔渣物品；未注册时返回 AIR 并在下次重试（不缓存失败）。 */
    private static Item betelNutResidueItem() {
        Item item = betelNutResidueItemCache;
        if (item == null) {
            try {
                item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("betel-nut-mod", "betel_nut_residue"));
                if (item != Items.AIR) {
                    betelNutResidueItemCache = item;
                }
            } catch (Exception ignored) {}
        }
        return item == null ? Items.AIR : item;
    }

    public static void tickPlayer(ServerPlayer player) {
        BetelQuestState state = BetelQuestState.getInstance();
        BetelQuestState.PlayerBetelData data = BetelQuestState.getPlayerData(player.getUUID());

        if (!data.hasBeenProcessed) {
            data.hasBeenProcessed = true;
            clearAddictionForPlayer(player);
            return;
        }

        SREGameWorldComponent gameWorld;
        try {
            gameWorld = SREGameWorldComponent.KEY.get(player.level());
        } catch (Exception e) {
            clearAddictionForPlayer(player);
            return;
        }

        if (!gameWorld.isRunning()) {
            if (!data.wasGameNotRunning) {
                data.wasGameNotRunning = true;
                clearAddictionForPlayer(player);
            }
            return;
        }
        data.wasGameNotRunning = false;

        if (player.isSpectator()) {
            if (!data.wasSpectating) {
                data.wasSpectating = true;
                clearAddictionForPlayer(player);
            }
            return;
        }
        data.wasSpectating = false;

        if (!player.isAlive()) return;

        BetelNutAddictionComponent addiction;
        try {
            addiction = BetelNutEntityComponents.ADDICTION.get(player);
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("无法获取槟榔成瘾组件", e);
            return;
        }
        BetelNutConfig config = BetelNutConfig.get();

        boolean detectedEating = false;
        long currentLastEatTime = addiction.getLastEatTime();

        if (currentLastEatTime > 0 && currentLastEatTime != data.lastDetectedEatTime) {
            data.hasEatenBetelNut = true;
            detectedEating = true;
            data.lastDetectedEatTime = currentLastEatTime;
        }

        long currentGameTime = player.level().getGameTime();

        if (detectedEating) {
            BetelWithdrawal.enterWithdrawalRelief(player, data, 100);
            data.betelNutsEatenThisGame++;
            data.ownLastEatGameTime = currentGameTime;

            BetelWithdrawal.removeHeavyAddictionEffects(player);

            applyBetelNutEffects(player);
            giveBetelNutResidue(player);

            player.displayClientMessage(Component.literal("§7你嚼碎了槟榔，只剩下一些渣滓"), true);

            HabiTrainCore.LOGGER.debug("玩家 {} 检测到吃槟榔，已给予槟榔渣", player.getName().getString());
        }

        boolean shouldReveal = false;
        int currentStage = 0;

        if (config.enableAddictionSystem) {
            currentStage = addiction.getAddictionStage();
            int bsev = addiction.getWithdrawalSeverity();
            if (currentStage >= 3 && bsev > 0 && !state.isRevealUsed()) {
                HabiTrainCore.LOGGER.info("槟榔揭晓触发(官方): 玩家 {} stage={}, severity={}",
                        player.getName().getString(), currentStage, bsev);
                shouldReveal = true;
            }
        } else {
            int ownValue = data.betelNutsEatenThisGame * 5;
            int ownStage = 0;
            if (ownValue >= ADDICTION_STAGE_THRESHOLD_1)      ownStage = 5;
            else if (ownValue >= ADDICTION_STAGE_THRESHOLD_2) ownStage = 4;
            else if (ownValue >= ADDICTION_STAGE_THRESHOLD_3) ownStage = 3;
            else if (ownValue >= ADDICTION_STAGE_THRESHOLD_4) ownStage = 2;
            else if (ownValue > 0)   ownStage = 1;
            currentStage = ownStage;

            boolean inWithdrawal = false;
            if (ownStage >= 3 && data.ownLastEatGameTime > 0) {
                long ticksSinceLastEat = currentGameTime - data.ownLastEatGameTime;
                inWithdrawal = ticksSinceLastEat >= WITHDRAWAL_TICK_THRESHOLD;
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
            executeReveal(player, gameWorld);
            state.setRevealUsed(true);
        }

        if (currentStage >= 3 && !data.hasFoodRestriction) {
            data.hasFoodRestriction = true;
            player.displayClientMessage(Component.literal("§c你的身体没办法接受正常的食物.."), true);
            HabiTrainCore.LOGGER.info("玩家 {} 达到Stage 3，食物限制已激活", player.getName().getString());
        } else if (currentStage < 3 && data.hasFoodRestriction) {
            data.hasFoodRestriction = false;
            HabiTrainCore.LOGGER.info("玩家 {} 低于 Stage 3，食物限制已解除", player.getName().getString());
        }

        if (!config.enableAddictionSystem) {
            if (data.addictionStage != BetelQuestState.AddictionStage.NONE
                    && data.effectState == BetelQuestState.EffectState.WITHDRAWAL_ACTIVE) {
                data.addictionStage = BetelQuestState.AddictionStage.NONE;
                data.effectState = BetelQuestState.EffectState.NONE;
            }
            return;
        }

        int betelAddictionStage = addiction.getAddictionStage();
        int betelWithdrawalSeverity = addiction.getWithdrawalSeverity();
        int betelWithdrawalValue = addiction.getWithdrawalValue();
        int visibleThreshold = Math.max(MIN_WITHDRAWAL_VALUE, Math.min(MAX_WITHDRAWAL_VALUE, config.maxWithdrawalValue));

        if (betelWithdrawalValue >= visibleThreshold && betelWithdrawalSeverity > 0) {
            if (data.effectState != BetelQuestState.EffectState.DARKNESS_APPLIED) {
                player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, DARKNESS_DURATION_TICKS, 0, false, true, true));
                EffectOwnershipTracker.claim(player.getUUID(), MobEffects.DARKNESS, "betel_quest");
                data.effectState = BetelQuestState.EffectState.DARKNESS_APPLIED;
                HabiTrainCore.LOGGER.debug("玩家 {} 成瘾发作，给予黑暗效果", player.getName().getString());
            }
        } else {
            if (data.effectState == BetelQuestState.EffectState.DARKNESS_APPLIED) {
                data.effectState = BetelQuestState.EffectState.NONE;
            }
        }

        // 戒断缓解窗口到期：重置 effectState 为 NONE，使后续 applyHeavyAddictionEffects 重新生效，
        // 避免 stage>=3 玩家在缓解窗口后既无 slowness 也无 darkness 的空窗（P2-9）。
        if (data.effectState == BetelQuestState.EffectState.WITHDRAWAL_ACTIVE
                && player.serverLevel().getGameTime() >= data.withdrawalReliefUntilTick) {
            data.effectState = BetelQuestState.EffectState.NONE;
        }

        if (betelAddictionStage >= 3) {
            data.addictionStage = BetelQuestState.AddictionStage.SEVERE;
            BetelWithdrawal.applyHeavyAddictionEffects(player, data);
        } else {
            // 位于 >=3 的 else 分支，betelAddictionStage < 3 恒真（review L40）。
            if (data.addictionStage != BetelQuestState.AddictionStage.NONE) {
                data.addictionStage = BetelQuestState.AddictionStage.NONE;
                data.effectState = BetelQuestState.EffectState.NONE;
            }
        }
    }

    public static boolean isGameActive(ServerLevel world) {
        try {
            Object gameComponent = SREGameWorldComponent.KEY.get(world);
            if (gameComponent instanceof SREGameWorldComponent gc) {
                return gc.isRunning();
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("isGameActive check failed: {}", e.getMessage());
        }
        return false;
    }

    private static void clearAddictionForPlayer(ServerPlayer player) {
        UUID pUuid = player.getUUID();

        if (EffectOwnershipTracker.release(pUuid, MobEffects.DARKNESS, "betel_quest")) {
            player.removeEffect(MobEffects.DARKNESS);
        }
        if (EffectOwnershipTracker.release(pUuid, MobEffects.MOVEMENT_SLOWDOWN, "betel_quest")) {
            player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
        if (EffectOwnershipTracker.release(pUuid, MobEffects.MOVEMENT_SPEED, "betel_quest")) {
            player.removeEffect(MobEffects.MOVEMENT_SPEED);
        }
        if (EffectOwnershipTracker.release(pUuid, MobEffects.GLOWING, "betel_quest")) {
            player.removeEffect(MobEffects.GLOWING);
        }

        try {
            Holder<MobEffect> noellesHolder = noellesEffectHolder();
            if (noellesHolder != null) {
                player.removeEffect(noellesHolder);
            }
        } catch (Exception ignored) {}

        try {
            BetelNutAddictionComponent addiction = BetelNutEntityComponents.ADDICTION.get(player);
            addiction.clearAddiction(player);
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("clearAddictionForPlayer failed for player {}: {}", player.getUUID(), e.getMessage());
        }

        HabiTrainCore.LOGGER.debug("已清除玩家 {} 的槟榔成瘾效果", player.getName().getString());
    }

    private static void applyBetelNutEffects(ServerPlayer player) {
        UUID pUuid = player.getUUID();

        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0, false, true, true));
        EffectOwnershipTracker.claim(pUuid, MobEffects.MOVEMENT_SPEED, "betel_quest");

        applyNoellesrolesEffect(player);
    }

    private static void applyNoellesrolesEffect(ServerPlayer player) {
        Holder<MobEffect> noellesHolder = noellesEffectHolder();
        if (noellesHolder != null) {
            player.addEffect(new MobEffectInstance(noellesHolder, 200, 0, false, true, true));
        }
    }

    private static void giveBetelNutResidue(ServerPlayer player) {
        try {
            Item residueItem = betelNutResidueItem();
            if (residueItem == Items.AIR) {
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

    private static void executeReveal(ServerPlayer revealer, SREGameWorldComponent gameWorld) {
        try {
            List<ServerPlayer> allPlayers = new ArrayList<>();
            for (ServerPlayer p : revealer.getServer().getPlayerList().getPlayers()) {
                // 揭晓每局仅一次：排除揭示者自己与旁观者，否则随机命中自己即浪费（review M16）。
                if (p == revealer || p.isSpectator()) continue;
                if (p.level().dimension() == revealer.level().dimension()) {
                    allPlayers.add(p);
                }
            }

            if (allPlayers.isEmpty()) return;

            ServerPlayer target = allPlayers.get(ThreadLocalRandom.current().nextInt(allPlayers.size()));

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

    private static String getRoleDisplayName(ServerPlayer player, SREGameWorldComponent gameWorld) {
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
            return switch (roleType) {
                case SRE_ROLE_CIVILIAN -> "平民";
                case SRE_ROLE_NEUTRAL_1, SRE_ROLE_NEUTRAL_2 -> "中立";
                case SRE_ROLE_KILLER -> "杀手";
                case SRE_ROLE_SHERIFF -> "警长";
                default -> "未知";
            };
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("获取角色显示名时出错", e);
            return "未知";
        }
    }
}
