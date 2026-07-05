package com.habitrain.core.game.blackout.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import io.wifi.starrailexpress.cca.SREPlayerMoodComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

final class BlackoutTaskHelper {
    private static final double DEFAULT_REACH = 5.0;

    static final int DEFAULT_GOLD_REWARD = 50;
    static final float DEFAULT_EMOTION_REWARD = 0.5f;

    private BlackoutTaskHelper() {
    }

    static void grantRewards(ServerPlayer player, String taskFullId, int defaultGold, float defaultEmotion) {
        TaskConfigEntry config = ConfigManager.getInstance().getTaskConfig(taskFullId);
        int gold = (config != null && config.goldReward >= 0) ? config.goldReward : defaultGold;
        float emotion = (config != null && config.emotionReward >= 0f)
                ? config.emotionReward : defaultEmotion;
        try {
            var shop = SREPlayerShopComponent.KEY.get(player);
            if (shop != null) {
                shop.addToBalance(gold);
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("任务 {} 发放金币奖励失败", taskFullId, t);
        }
        try {
            var mood = SREPlayerMoodComponent.KEY.get(player);
            if (mood != null) {
                mood.addMood(emotion);
            }
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("任务 {} 发放情绪奖励失败", taskFullId, t);
        }
    }

    static void grantRewards(ServerPlayer player, String taskFullId) {
        grantRewards(player, taskFullId, DEFAULT_GOLD_REWARD, DEFAULT_EMOTION_REWARD);
    }

    static boolean advanceOnLook(Player player, TaskInstance task) {
        if (player == null || task == null || task.isFulfilled()) {
            return false;
        }

        Set<Block> targets = resolveTargets(task.getDefinition());
        if (targets.isEmpty() || task.getProgress() >= task.getMaxProgress()) {
            return false;
        }

        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        Vec3 targetPos = eyePos.add(
                lookVec.x * DEFAULT_REACH,
                lookVec.y * DEFAULT_REACH,
                lookVec.z * DEFAULT_REACH
        );

        BlockHitResult hitResult = player.level().clip(new ClipContext(
                eyePos,
                targetPos,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        Block lookedBlock = player.level().getBlockState(hitResult.getBlockPos()).getBlock();
        if (!targets.contains(lookedBlock)) {
            return false;
        }

        task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
        return true;
    }

    private static Set<Block> resolveTargets(TaskDefinition def) {
        Set<Block> targets = new HashSet<>();
        if (def == null) {
            return targets;
        }

        if (def.getScanBlocks() != null) {
            targets.addAll(def.getScanBlocks());
        }
        if (def.getScanBlockIds() != null) {
            for (String blockId : def.getScanBlockIds()) {
                if (blockId == null || blockId.isBlank()) {
                    continue;
                }

                Block resolved = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
                if (resolved != Blocks.AIR) {
                    targets.add(resolved);
                }
            }
        }
        return targets;
    }
}
