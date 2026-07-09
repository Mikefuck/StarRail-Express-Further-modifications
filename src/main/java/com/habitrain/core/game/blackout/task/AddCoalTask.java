package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

/**
 * 停电模式好人任务：添煤（两阶段右键交互）。
 * <p>阶段0：右键煤炭块 → 给缓慢III + 发放 1 个煤炭 → 进入阶段1
 * <p>阶段1：手持煤炭右键 {@code yuushya:generator} → 给缓慢III + 消耗煤炭 → 完成
 * <p>完成时：对局总时间减少 30 秒 + 发放金币 50 / 情绪 0.5 奖励（无阵营区分）。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。新增好人任务参考此文件，
 * 注册时用 {@code .category(BlackoutMode.BLACKOUT_GOOD)} 归属好人池。
 */
public class AddCoalTask {

    static final int COAL_PHASE = 0;
    static final int GENERATOR_PHASE = 1;
    static final int PROGRESS_DONE = 2;

    private static final int TIME_LIMIT_SECONDS = 120;
    private static final int TIME_REDUCTION = 30;

    public static void register() {
        TaskRegistry.register("habitrain_core", "add_coal", builder -> builder
            .displayName("添煤")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(3.0f)
            .blockTypeId(20)
            .instinctColor(50, 50, 50, 200)
            .scanBlocks(Blocks.COAL_BLOCK)
            .scanBlockIds("yuushya:generator")
            .timeLimit(TIME_LIMIT_SECONDS)
            // 声明时间影响：完成后对局总时间减少 30 秒（TOTAL_TIME 轴，负值=减少）。
            // 注意：add_coal 减的是对局总时间，不是停电倒计时，所以不在供电池自适应概率曲线内。
            .timeImpact(TaskDefinition.TimeImpact.TimeAxis.TOTAL_TIME, -TIME_REDUCTION)
            .onAssign((player, task) -> {
                task.setMaxProgress(PROGRESS_DONE);
            })
            .onTick((player, task) -> {
                // S8-003: Per-tick full inventory scan removed.
                // Coal validation happens exclusively in AddCoalHandler
                // at generator interaction time, so no per-tick scan is needed.
            })
            .completionChecker((player, task) -> task.getProgress() >= PROGRESS_DONE)
            .onComplete((player, task) -> {
                if (!(player instanceof ServerPlayer serverPlayer)) return;

                cleanup(serverPlayer);

                // 通过 applyTimeImpact 统一调用（替代硬编码 reduceTime(level, 30)）
                BlackoutTaskHelper.applyTimeImpact(serverPlayer.serverLevel(), "habitrain_core:add_coal");
                BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:add_coal");

                // 同步完成其它正在做 add_coal 的 GOOD 玩家
                SupplyTaskSyncHelper.syncCompletion(
                        serverPlayer.serverLevel(), serverPlayer.getUUID(), "habitrain_core:add_coal");
            })
            .onFail((player, task) -> cleanup(player))
            .onRemove((player, task) -> cleanup(player))
        );
    }

    private static void cleanup(Player player) {
        if (player == null) return;
        AddCoalHandler.clearState(player.getUUID());
        if (player instanceof ServerPlayer sp) {
            sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
    }
}