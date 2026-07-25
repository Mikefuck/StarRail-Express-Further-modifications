package com.habitrain.core.game.blackout.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.task.ClearableHandlerRegistry;
import com.habitrain.core.task.SlownessReapplyManager;
import com.habitrain.core.task.TaskManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 维持供电任务交互处理器（右键发电机 → 3秒缓慢 → 缓慢结束后完成）。
 * <p>玩家右键发电机后获得 3 秒缓慢III，缓慢期间每 tick 重新施加以对抗 betel-nut-mod。
 * 缓慢到期后任务进度设为完成，触发 onComplete（奖励 + 断电倒计时 +60 秒）。
 */
public class MaintainPowerHandler {

    private static final String GENERATOR_BLOCK_ID = "yuushya:generator";
    private static final int SLOW_TICKS = 60; // 3 秒

    private static final Map<UUID, MaintainState> activeStates = new HashMap<>();

    private static Block generatorBlock = null;
    private static boolean blockChecked = false;

    public static void register() {
        UseBlockCallback.EVENT.register(MaintainPowerHandler::onUseBlock);
        ClearableHandlerRegistry.register(MaintainPowerHandler::clearAll);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (activeStates.isEmpty()) return;
            long tick = server.overworld().getGameTime();
            for (var it = activeStates.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                UUID uuid = entry.getKey();
                MaintainState state = entry.getValue();
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);

                TaskInstance task = TaskManager.getInstance().getActiveTask(uuid);
                if (task == null || !HabiTrainCore.TASK_MAINTAIN_POWER.equals(task.getFullId())) {
                    if (sp != null) {
                        sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    }
                    it.remove();
                    continue;
                }

                if (task.isFulfilled() || task.getProgress() >= task.getMaxProgress()) {
                    if (state.slowUntilTick <= tick && sp != null) {
                        sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    }
                    if (state.slowUntilTick <= tick) {
                        it.remove();
                    }
                    continue;
                }

                if (state.slowUntilTick <= tick) {
                    if (sp != null) {
                        sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    }
                    task.setProgress(task.getMaxProgress());
                    SlownessReapplyManager.unregisterAllLevels(uuid);
                    it.remove();
                }
            }
        });
    }

    public static void clearState(UUID uuid) {
        activeStates.remove(uuid);
        SlownessReapplyManager.unregisterAllLevels(uuid);
    }

    public static void clearAll() {
        activeStates.clear();
        // 缓慢表由 GameLifecycleHandler 统一 clear
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                                 BlockHitResult hitResult) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null || !com.habitrain.core.HabiTrainCore.TASK_MAINTAIN_POWER.equals(task.getFullId())) {
            return InteractionResult.PASS;
        }
        if (task.isFulfilled() || task.getProgress() >= task.getMaxProgress()) {
            return InteractionResult.PASS;
        }

        BlockState state = world.getBlockState(hitResult.getBlockPos());
        if (!isGeneratorBlock(state.getBlock())) {
            return InteractionResult.PASS;
        }

        UUID uuid = serverPlayer.getUUID();
        MaintainState existing = activeStates.get(uuid);
        if (existing != null && existing.slowUntilTick > serverPlayer.serverLevel().getServer().overworld().getGameTime()) {
            return InteractionResult.FAIL;
        }

        long tick = serverPlayer.serverLevel().getServer().overworld().getGameTime();
        MaintainState ms = new MaintainState();
        ms.slowUntilTick = tick + SLOW_TICKS;
        activeStates.put(uuid, ms);

        SlownessReapplyManager.register(serverPlayer.serverLevel(), serverPlayer.getUUID(),
                2, SLOW_TICKS + 10,
                ResourceLocation.parse(com.habitrain.core.HabiTrainCore.TASK_MAINTAIN_POWER));

        return InteractionResult.FAIL;
    }

    private static boolean isGeneratorBlock(Block block) {
        if (!blockChecked) {
            try {
                generatorBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(GENERATOR_BLOCK_ID));
                if (generatorBlock == null || generatorBlock == Blocks.AIR) {
                    HabiTrainCore.LOGGER.warn("发电机方块未找到: {}", GENERATOR_BLOCK_ID);
                    generatorBlock = null;
                }
            } catch (Exception e) {
                HabiTrainCore.LOGGER.error("查找发电机方块时出错: {}", GENERATOR_BLOCK_ID, e);
                generatorBlock = null;
            }
            blockChecked = true;
        }
        return generatorBlock != null && block == generatorBlock;
    }

    private static final class MaintainState {
        long slowUntilTick;
    }
}