package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.task.ClearableHandlerRegistry;
import com.habitrain.core.task.SlownessReapplyManager;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.util.SubtitleNotifier;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.UUID;

/**
 * 破坏线路任务交互处理器。
 * <p>玩家右键红石块 → 给缓慢III(3秒) + 推进任务完成。
 * <p>煤炭块归添煤任务，不接受为破坏线路目标。
 * <p>缓慢效果在 END_SERVER_TICK 中重新施加以对抗 betel-nut-mod 每 tick 清除。
 */
public class SabotageWiringHandler {

    /** 缓慢持续 tick（3 秒）。 */
    private static final int SLOW_TICKS = 60;

    public static void register() {
        UseBlockCallback.EVENT.register(SabotageWiringHandler::onUseBlock);
        ClearableHandlerRegistry.register(SabotageWiringHandler::clearAll);
    }

    public static void clearState(UUID uuid) {
        SlownessReapplyManager.unregisterAllLevels(uuid);
    }

    public static void clearAll() {
        // 无 per-player map；缓慢表由 GameLifecycleHandler 统一 clear
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                                 BlockHitResult hitResult) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null || !com.habitrain.core.game.blackout.BlackoutExclusiveTasks.TASK_SABOTAGE_WIRING.equals(task.getFullId())) {
            return InteractionResult.PASS;
        }
        if (task.isFulfilled() || task.getProgress() >= task.getMaxProgress()) {
            return InteractionResult.PASS;
        }

        BlockState state = world.getBlockState(hitResult.getBlockPos());
        if (!state.is(Blocks.REDSTONE_BLOCK)) {
            return InteractionResult.PASS;
        }

        // 给缓慢 III (3 秒)，到期后由 SlownessReapplyManager 自动 unregister
        SlownessReapplyManager.register(serverPlayer.serverLevel(), serverPlayer.getUUID(),
                2, SLOW_TICKS + 10,
                ResourceLocation.parse(com.habitrain.core.game.blackout.BlackoutExclusiveTasks.TASK_SABOTAGE_WIRING));

        // 推进任务完成
        task.setProgress(task.getMaxProgress());

        SubtitleNotifier.sendTop(serverPlayer,
                Component.translatable("task.sabotage_wiring"),
                Component.literal("§a正在破坏线路..."),
                60);

        return InteractionResult.FAIL;
    }
}