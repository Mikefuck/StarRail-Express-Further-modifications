package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.util.SubtitleNotifier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 破坏线路任务交互处理器。
 * <p>玩家右键红石块 → 给缓慢III(6秒) + 推进任务完成。
 * <p>缓慢效果在 END_SERVER_TICK 中重新施加以对抗 betel-nut-mod 每 tick 清除。
 */
public class SabotageWiringHandler {

    private static final int SLOW_TICKS = 120; // 6 秒

    private static final Map<UUID, Long> slowUntilTickMap = new HashMap<>();

    public static void register() {
        UseBlockCallback.EVENT.register(SabotageWiringHandler::onUseBlock);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (slowUntilTickMap.isEmpty()) return;
            long tick = server.overworld().getGameTime();
            for (var it = slowUntilTickMap.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                UUID uuid = entry.getKey();
                long slowUntil = entry.getValue();
                if (slowUntil > tick) {
                    ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                    if (sp != null) {
                        int remaining = (int) (slowUntil - tick + 10);
                        sp.addEffect(new MobEffectInstance(
                                MobEffects.MOVEMENT_SLOWDOWN, remaining, 2, false, true, true));
                    }
                } else {
                    ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                    if (sp != null) {
                        sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    }
                    it.remove();
                }
            }
        });
    }

    public static void clearState(UUID uuid) {
        slowUntilTickMap.remove(uuid);
    }

    public static void clearAll() {
        slowUntilTickMap.clear();
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                                 BlockHitResult hitResult) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null || !"habitrain_core:sabotage_wiring".equals(task.getFullId())) {
            return InteractionResult.PASS;
        }
        if (task.isFulfilled() || task.getProgress() >= task.getMaxProgress()) {
            return InteractionResult.PASS;
        }

        BlockState state = world.getBlockState(hitResult.getBlockPos());
        if (!state.is(Blocks.REDSTONE_BLOCK)) {
            return InteractionResult.PASS;
        }

        // 给缓慢 III (6 秒)
        serverPlayer.addEffect(new MobEffectInstance(
                MobEffects.MOVEMENT_SLOWDOWN, SLOW_TICKS + 10, 2, false, true, true));
        slowUntilTickMap.put(serverPlayer.getUUID(),
                serverPlayer.serverLevel().getServer().overworld().getGameTime() + SLOW_TICKS);

        // 推进任务完成
        task.setProgress(task.getMaxProgress());

        SubtitleNotifier.sendTop(serverPlayer,
                Component.translatable("task.sabotage_wiring"),
                Component.literal("§a正在破坏线路..."),
                60);

        return InteractionResult.FAIL;
    }
}