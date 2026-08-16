package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.task.ClearableHandlerRegistry;
import com.habitrain.core.task.SlownessReapplyManager;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.util.SubtitleNotifier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 炸毁熔炉任务交互处理器（两阶段右键流程）。
 * <p>阶段0：右键红石火把方块 → 给缓慢III(3秒) + 发放 1 个红石火把 → 进入阶段1
 * <p>阶段1：手持红石火把右键 TNT → 消耗火把 + 推进完成
 *           → 2 秒后点燃 TNT + 全图通报发电机被摧毁
 * <p>缓慢效果在 END_SERVER_TICK 中重新施加以对抗 betel-nut-mod 每 tick 清除。
 */
public class FurnaceExplosionHandler {

    private static final int SLOW_TICKS_LONG = 60; // 3 秒
    private static final int FUSE_DELAY_TICKS = 40; // 2 秒延迟点燃 TNT

    private static final Map<UUID, TorchState> activeStates = new HashMap<>();
    private static final Map<UUID, PendingExplosion> pendingExplosions = new HashMap<>();

    public static void register() {
        UseBlockCallback.EVENT.register(FurnaceExplosionHandler::onUseBlock);
        ClearableHandlerRegistry.register(FurnaceExplosionHandler::clearAll);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long tick = server.overworld().getGameTime();

            if (!activeStates.isEmpty()) {
                for (var it = activeStates.entrySet().iterator(); it.hasNext(); ) {
                    var entry = it.next();
                    UUID uuid = entry.getKey();
                    TorchState state = entry.getValue();

                    // 缓慢结束且阶段推进完成，清理
                    if (state.slowUntilTick <= tick && state.phaseProgressed) {
                        ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                        if (sp != null) {
                            sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                        }
                        SlownessReapplyManager.unregisterAllLevels(uuid);
                        it.remove();
                    }
                }
            }

            // 检查延迟点燃 TNT 队列
            if (!pendingExplosions.isEmpty()) {
                for (Iterator<Map.Entry<UUID, PendingExplosion>> it =
                     pendingExplosions.entrySet().iterator(); it.hasNext(); ) {
                    var entry = it.next();
                    PendingExplosion pe = entry.getValue();
                    if (tick >= pe.triggerTick) {
                        // 按玩家点燃 TNT 时所在维度执行爆炸，避免炸错世界（P0-2）
                        ServerLevel level = server.getLevel(pe.dimension);
                        if (level == null) {
                            // 维度已卸载：放弃本次爆炸，清理条目
                            it.remove();
                            continue;
                        }
                        BlockPos pos = pe.targetPos;
                        if (level.getBlockState(pos).is(Blocks.TNT)) {
                            level.destroyBlock(pos, false);
                        }
                        level.explode(null,
                                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                                4.0f, Level.ExplosionInteraction.BLOCK);
                        BlackoutMode.broadcast(level, "§c⚡ 发电机被摧毁！");
                        it.remove();
                    }
                }
            }
        });
    }

    public static void clearState(UUID uuid) {
        activeStates.remove(uuid);
        // 不在此移除 pendingExplosions：TNT 已点燃、任务已判完成后，玩家掉线/淘汰
        // 不应取消 2 秒后必然发生的爆炸。clearAll() 仍会在整局清理时清空队列。
        SlownessReapplyManager.unregisterAllLevels(uuid);
    }

    public static void clearAll() {
        activeStates.clear();
        pendingExplosions.clear();
        // 缓慢表由 GameLifecycleHandler 统一 clear
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                                 BlockHitResult hitResult) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null || !com.habitrain.core.game.blackout.BlackoutExclusiveTasks.TASK_FURNACE_EXPLOSION.equals(task.getFullId())) {
            return InteractionResult.PASS;
        }
        if (task.isFulfilled() || task.getProgress() >= FurnaceExplosionTask.PROGRESS_DONE) {
            return InteractionResult.PASS;
        }

        BlockPos pos = hitResult.getBlockPos();
        BlockState state = world.getBlockState(pos);
        int progress = task.getProgress();
        UUID uuid = serverPlayer.getUUID();

        if (progress == FurnaceExplosionTask.TORCH_PHASE && state.is(Blocks.REDSTONE_TORCH)) {
            // 阶段0：右键红石火把方块
            TorchState existing = activeStates.get(uuid);
            if (existing != null && !existing.phaseProgressed) {
                return InteractionResult.FAIL;
            }

            giveSlow(serverPlayer, uuid, SLOW_TICKS_LONG, true);
            // 发放 1 个红石火把
            boolean added = serverPlayer.getInventory().add(new ItemStack(Items.REDSTONE_TORCH, 1));
            if (!added) {
                serverPlayer.drop(new ItemStack(Items.REDSTONE_TORCH, 1), false);
            }
            // 推进任务进度到阶段1
            task.setProgress(FurnaceExplosionTask.TNT_PHASE);

            TorchState s = activeStates.get(uuid);
            if (s != null) {
                s.phaseProgressed = true;
            } else {
                s = new TorchState();
                s.phaseProgressed = true;
                s.slowUntilTick = serverPlayer.serverLevel().getServer().overworld().getGameTime() + SLOW_TICKS_LONG;
                activeStates.put(uuid, s);
            }

            serverPlayer.serverLevel().playSound(null, serverPlayer.blockPosition(),
                    SoundEvents.WOOD_BREAK, SoundSource.PLAYERS, 1.0f, 1.0f);
            SubtitleNotifier.sendTop(serverPlayer,
                    Component.translatable("task.furnace_explosion"),
                    Component.literal("§a已取得红石火把！手持火把右键 §cTNT §a引爆。"),
                    80);
            return InteractionResult.FAIL;
        }

        if (progress == FurnaceExplosionTask.TNT_PHASE && state.is(Blocks.TNT)) {
            // 阶段1：手持红石火把右键 TNT
            ItemStack mainHand = serverPlayer.getMainHandItem();
            if (!mainHand.is(Items.REDSTONE_TORCH)) {
                return InteractionResult.FAIL;
            }

            // 消耗 1 个红石火把
            mainHand.shrink(1);
            if (mainHand.isEmpty()) {
                serverPlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }

            // 阶段1：取消手持火把右键 TNT 时的缓慢（仅给短动画/声音提示，不再施加强制缓慢）
            // 推进任务完成 → 触发 onComplete（立刻永久停电 + 派发拉闸 + 奖励）
            task.setProgress(FurnaceExplosionTask.PROGRESS_DONE);

            // 调度延迟 2 秒点燃 TNT（不提示倒计时文案）
            long triggerTick = serverPlayer.serverLevel().getServer().overworld().getGameTime() + FUSE_DELAY_TICKS;
            pendingExplosions.put(uuid, new PendingExplosion(pos, triggerTick, serverPlayer.serverLevel().dimension()));
            return InteractionResult.FAIL;
        }

        return InteractionResult.PASS;
    }

    private static void giveSlow(ServerPlayer sp, UUID uuid, int ticks, boolean phaseProgressed) {
        SlownessReapplyManager.register(sp.serverLevel(), uuid, 2, ticks + 10,
                ResourceLocation.parse(com.habitrain.core.game.blackout.BlackoutExclusiveTasks.TASK_FURNACE_EXPLOSION));
        long tick = sp.serverLevel().getServer().overworld().getGameTime();
        TorchState s = new TorchState();
        s.slowUntilTick = tick + ticks;
        s.phaseProgressed = phaseProgressed;
        activeStates.put(uuid, s);
    }

    private static final class TorchState {
        long slowUntilTick;
        boolean phaseProgressed;
    }

    private static final class PendingExplosion {
        final BlockPos targetPos;
        final long triggerTick;
        final ResourceKey<Level> dimension;

        PendingExplosion(BlockPos targetPos, long triggerTick, ResourceKey<Level> dimension) {
            this.targetPos = targetPos;
            this.triggerTick = triggerTick;
            this.dimension = dimension;
        }
    }
}
