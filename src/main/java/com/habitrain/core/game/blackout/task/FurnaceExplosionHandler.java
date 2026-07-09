package com.habitrain.core.game.blackout.task;

import com.habitrain.core.HabiTrainCore;
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
 * <p>阶段0：右键红石火把方块 → 给缓慢III(6秒) + 发放 1 个红石火把 → 进入阶段1
 * <p>阶段1：手持红石火把右键 TNT → 消耗火把 + 给 2 秒缓慢 + 推进完成
 *           → 2 秒后点燃 TNT + 全图通报发电机被摧毁
 * <p>缓慢效果在 END_SERVER_TICK 中重新施加以对抗 betel-nut-mod 每 tick 清除。
 */
public class FurnaceExplosionHandler {

    private static final int SLOW_TICKS_LONG = 120; // 6 秒
    private static final int SLOW_TICKS_SHORT = 40; // 2 秒
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
                ServerLevel overworld = server.overworld();
                for (Iterator<Map.Entry<UUID, PendingExplosion>> it =
                     pendingExplosions.entrySet().iterator(); it.hasNext(); ) {
                    var entry = it.next();
                    PendingExplosion pe = entry.getValue();
                    if (tick >= pe.triggerTick) {
                        // 执行爆炸
                        BlockPos pos = pe.targetPos;
                        if (overworld.getBlockState(pos).is(Blocks.TNT)) {
                            overworld.destroyBlock(pos, false);
                        }
                        overworld.explode(null,
                                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                                4.0f, Level.ExplosionInteraction.BLOCK);
                        BlackoutMode.broadcast(overworld, "§c⚡ 发电机被摧毁！");
                        it.remove();
                    }
                }
            }
        });
    }

    public static void clearState(UUID uuid) {
        activeStates.remove(uuid);
        pendingExplosions.remove(uuid);
        SlownessReapplyManager.unregisterAllLevels(uuid);
    }

    public static void clearAll() {
        activeStates.clear();
        pendingExplosions.clear();
        SlownessReapplyManager.clearAll();
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                                 BlockHitResult hitResult) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null || !"habitrain_core:furnace_explosion".equals(task.getFullId())) {
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
                SubtitleNotifier.sendTop(serverPlayer,
                        Component.translatable("task.furnace_explosion"),
                        Component.literal("§7正在拔取红石火把，请稍候..."),
                        45);
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
                SubtitleNotifier.sendTop(serverPlayer,
                        Component.translatable("task.furnace_explosion"),
                        Component.literal("§c需要手持红石火把右键 TNT！"),
                        60);
                return InteractionResult.FAIL;
            }

            // 消耗 1 个红石火把
            mainHand.shrink(1);
            if (mainHand.isEmpty()) {
                serverPlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }

            // 给 2 秒缓慢
            giveSlow(serverPlayer, uuid, SLOW_TICKS_SHORT, true);

            // 推进任务完成 → 触发 onComplete（短暂停电 + 减供电时间 40 秒 + 奖励）
            task.setProgress(FurnaceExplosionTask.PROGRESS_DONE);

            // 调度延迟 2 秒点燃 TNT
            long triggerTick = serverPlayer.serverLevel().getServer().overworld().getGameTime() + FUSE_DELAY_TICKS;
            pendingExplosions.put(uuid, new PendingExplosion(pos, triggerTick));

            SubtitleNotifier.sendTop(serverPlayer,
                    Component.translatable("task.furnace_explosion"),
                    Component.literal("§e引信已点燃，2 秒后爆炸！"),
                    60);
            return InteractionResult.FAIL;
        }

        return InteractionResult.PASS;
    }

    private static void giveSlow(ServerPlayer sp, UUID uuid, int ticks, boolean phaseProgressed) {
        SlownessReapplyManager.register(sp.serverLevel().dimension(), uuid,
                new SlownessReapplyManager.EffectSpec(2, ticks + 10,
                        ResourceLocation.parse("habitrain_core:furnace_explosion")));
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

        PendingExplosion(BlockPos targetPos, long triggerTick) {
            this.targetPos = targetPos;
            this.triggerTick = triggerTick;
        }
    }
}