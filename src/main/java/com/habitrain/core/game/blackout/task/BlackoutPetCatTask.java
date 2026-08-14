package com.habitrain.core.game.blackout.task;

import com.habitrain.core.BuiltinTaskRegistrar;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * 停电模式好人任务：摸猫猫（注视猫方块5秒）。
 * <p>玩家盯着 yuushya 模组的猫方块看 5 秒（100 tick）后完成。
 * 漏看会回退进度。
 * <p>完成时增加供电时间 15 秒 + 发放金币 50 / 情绪 0.5 奖励。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。
 */
public class BlackoutPetCatTask {

    public static void register() {
        TaskRegistry.register("habitrain_core", "blackout_pet_cat", builder -> builder
            .displayName("摸猫猫")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(37)
            .instinctColor(255, 182, 193, 200)
            .scanBlockIds(BuiltinTaskRegistrar.CAT_BLOCK_IDS)
            .timeImpact(TaskDefinition.TimeImpact.TimeAxis.MAINTENANCE_OR_COUNTDOWN, 15)
            .onAssign((player, task) -> {
                task.setMaxProgress(100);
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;
                // 两 tick 采样一次，命中时按两 tick 记进度，保持 5 秒完成语义。
                if (player.level().getGameTime() % 2L != 0L) return;

                Set<Block> currentCatBlocks = BuiltinTaskRegistrar.resolveCatBlocks();
                if (currentCatBlocks.isEmpty()) return;

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
                        task.setProgress(Math.min(task.getProgress() + 2, task.getMaxProgress()));
                        return;
                    }
                }

                if (task.getProgress() > 0) {
                    task.setProgress(Math.max(0, task.getProgress() - 4));
                }
            })
            .completionChecker((player, task) ->
                task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    BlackoutTaskHelper.applyTimeImpact(serverPlayer.serverLevel(),
                            "habitrain_core:blackout_pet_cat");
                    BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:blackout_pet_cat");
                }
            })
        );
    }
}
