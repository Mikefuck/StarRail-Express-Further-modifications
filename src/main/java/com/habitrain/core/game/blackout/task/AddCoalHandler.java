package com.habitrain.core.game.blackout.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.task.ClearableHandlerRegistry;
import com.habitrain.core.task.SlownessReapplyManager;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.util.SubtitleNotifier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 添煤任务交互处理器（两阶段右键流程）。
 * <p>阶段0：右键煤炭块 → 给缓慢III(3秒) → 进入阶段1
 * <p>阶段1：手持煤炭右键 yuushya:generator → 给缓慢III(3秒) + 消耗煤炭 → 任务完成
 * <p>煤炭的发放不在右键时立即执行，而是在 END_SERVER_TICK 中缓慢效果结束后发放，
 * 确保缓慢效果先施加、玩家先被减速，防止快速连续操作跳过慢速阶段。
 * <p>缓慢效果会在 END_SERVER_TICK 中重新施加以对抗 betel-nut-mod 的每 tick 清除。
 */
public class AddCoalHandler {

    private static final String GENERATOR_BLOCK_ID = "yuushya:generator";
    private static final int SLOW_TICKS = 60; // 3 秒
    private static final int COAL_PHASE = 0;
    private static final int GENERATOR_PHASE = 1;
    private static final int PROGRESS_DONE = 2;

    private static final Map<UUID, CoalState> activeStates = new HashMap<>();

    private static Block generatorBlock = null;
    private static boolean blockChecked = false;

    public static void register() {
        UseBlockCallback.EVENT.register(AddCoalHandler::onUseBlock);
        ClearableHandlerRegistry.register(AddCoalHandler::clearAll);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (activeStates.isEmpty()) return;
            long tick = server.overworld().getGameTime();
            for (var it = activeStates.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                UUID uuid = entry.getKey();
                CoalState state = entry.getValue();

                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                TaskInstance task = sp != null ? TaskManager.getInstance().getActiveTask(uuid) : null;
                boolean isAddCoalTask = task != null && "habitrain_core:add_coal".equals(task.getFullId());

                if (state.slowUntilTick <= tick) {
                    if (sp != null) {
                        sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    }
                    if (!state.phaseProgressed && isAddCoalTask && task.getProgress() == COAL_PHASE) {
                        boolean added = sp.getInventory().add(new ItemStack(Items.COAL, 1));
                        if (!added) {
                            sp.drop(new ItemStack(Items.COAL, 1), false);
                        }
                        task.setProgress(GENERATOR_PHASE);
                        state.phaseProgressed = true;
                        sp.serverLevel().playSound(null, sp.blockPosition(),
                                SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0f, 1.0f);
                        SubtitleNotifier.sendTop(sp,
                                Component.translatable("task.add_coal"),
                                Component.literal("§a已取得煤炭！手持煤炭右键 §e发电机 §a添煤。"),
                                80);
                    }
                    SlownessReapplyManager.unregisterAllLevels(uuid);
                    it.remove();
                }
            }
        });
    }

    /** 清理某玩家的添煤状态（任务完成/失败/移除时调用）。 */
    public static void clearState(UUID uuid) {
        activeStates.remove(uuid);
        SlownessReapplyManager.unregisterAllLevels(uuid);
    }

    /** 清空全部状态（游戏结束时调用）。 */
    public static void clearAll() {
        activeStates.clear();
        SlownessReapplyManager.clearAll();
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                                BlockHitResult hitResult) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null || !"habitrain_core:add_coal".equals(task.getFullId())) {
            return InteractionResult.PASS;
        }
        if (task.isFulfilled() || task.getProgress() >= PROGRESS_DONE) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hitResult.getBlockPos();
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        int progress = task.getProgress();
        UUID uuid = serverPlayer.getUUID();

        if (progress == COAL_PHASE && block == Blocks.COAL_BLOCK) {
            CoalState existing = activeStates.get(uuid);
            if (existing != null && !existing.phaseProgressed) {
                return InteractionResult.FAIL;
            }

            giveSlow(serverPlayer, uuid, false);
            CoalState s = activeStates.get(uuid);
            if (s == null) {
                s = new CoalState();
                s.slowUntilTick = serverPlayer.serverLevel().getServer().overworld().getGameTime() + SLOW_TICKS;
                s.phaseProgressed = false;
                activeStates.put(uuid, s);
            }
            serverPlayer.serverLevel().playSound(null, serverPlayer.blockPosition(),
                    SoundEvents.STONE_BREAK, SoundSource.PLAYERS, 1.0f, 1.0f);
            return InteractionResult.FAIL;
        }

        if (progress == GENERATOR_PHASE && isGeneratorBlock(block)) {
            ItemStack mainHand = serverPlayer.getMainHandItem();
            if (!mainHand.is(Items.COAL)) {
                return InteractionResult.FAIL;
            }

            CoalState existing = activeStates.get(uuid);
            if (existing != null && !existing.phaseProgressed) {
                return InteractionResult.FAIL;
            }

            giveSlow(serverPlayer, uuid, true);
            mainHand.shrink(1);
            if (mainHand.isEmpty()) {
                serverPlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            task.setProgress(PROGRESS_DONE);
            CoalState s = activeStates.get(uuid);
            if (s != null) {
                s.phaseProgressed = true;
            }
            serverPlayer.serverLevel().playSound(null, serverPlayer.blockPosition(),
                    SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, 1.0f, 1.0f);
            return InteractionResult.FAIL;
        }

        return InteractionResult.PASS;
    }

    private static void giveSlow(ServerPlayer sp, UUID uuid, boolean phaseProgressed) {
        SlownessReapplyManager.register(sp.serverLevel().dimension(), uuid,
                new SlownessReapplyManager.EffectSpec(2, SLOW_TICKS + 10,
                        ResourceLocation.parse("habitrain_core:add_coal")));
        long tick = sp.serverLevel().getServer().overworld().getGameTime();
        CoalState s = new CoalState();
        s.slowUntilTick = tick + SLOW_TICKS;
        s.phaseProgressed = phaseProgressed;
        activeStates.put(uuid, s);
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

    private static final class CoalState {
        long slowUntilTick;
        boolean phaseProgressed;
    }
}