package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 停电模式好人任务：添煤（两阶段右键交互）。
 * <p>阶段0：右键煤炭块 → 给缓慢III + 发放 1 个煤炭 → 进入阶段1
 * <p>阶段1：手持煤炭右键 {@code yuushya:generator} → 给缓慢III + 消耗煤炭 → 完成
 * <p>完成时：对局总时间减少 15 秒 + 发放金币 50 / 情绪 0.5 奖励（无阵营区分）。
 * <p>属于 {@link BlackoutMode#BLACKOUT_GOOD} 池。新增好人任务参考此文件，
 * 注册时用 {@code .category(BlackoutMode.BLACKOUT_GOOD)} 归属好人池。
 */
public class AddCoalTask {

    static final int COAL_PHASE = 0;
    static final int GENERATOR_PHASE = 1;
    static final int PROGRESS_DONE = 2;

    private static final int TIME_LIMIT_SECONDS = 120;
    private static final int TIME_REDUCTION = 15;

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
            .onAssign((player, task) -> {
                task.setMaxProgress(PROGRESS_DONE);
                if (player instanceof ServerPlayer serverPlayer) {
                    SubtitleNotifier.sendTop(
                            serverPlayer,
                            Component.translatable("task.add_coal"),
                            Component.literal("§6【任务】右键煤炭块取得煤炭，再手持煤炭右键发电机添煤。"),
                            80
                    );
                }
            })
            .onTick((player, task) -> {
                if (task.isFulfilled()) return;
                // 阶段1 检测：玩家背包中无煤炭 → 任务失败
                if (task.getProgress() == GENERATOR_PHASE && hasPlayerCoal(player) == false) {
                    task.markFailed();
                    cleanup(player);
                    if (player instanceof ServerPlayer serverPlayer) {
                        SubtitleNotifier.sendTop(
                                serverPlayer,
                                Component.translatable("task.add_coal"),
                                Component.literal("§c煤炭丢失，添煤任务失败！"),
                                60
                        );
                    }
                    return;
                }
            })
            .completionChecker((player, task) -> task.getProgress() >= PROGRESS_DONE)
            .onComplete((player, task) -> {
                if (!(player instanceof ServerPlayer serverPlayer)) return;

                cleanup(serverPlayer);

                BlackoutTimerSystem.reduceTime(serverPlayer.serverLevel(), TIME_REDUCTION);
                BlackoutTaskHelper.grantRewards(serverPlayer, "habitrain_core:add_coal");

                SubtitleNotifier.sendTop(
                        serverPlayer,
                        Component.translatable("task.add_coal"),
                        Component.literal("§a添煤完成！对局时间缩短 " + TIME_REDUCTION + " 秒，已发放奖励。"),
                        80
                );
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

    private static boolean hasPlayerCoal(Player player) {
        if (player == null) return false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.COAL)) {
                return true;
            }
        }
        return false;
    }
}