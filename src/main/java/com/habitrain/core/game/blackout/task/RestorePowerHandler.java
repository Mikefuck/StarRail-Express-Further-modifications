package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.util.SubtitleNotifier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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

public class RestorePowerHandler {

    private static final int SLOW_TICKS = 120;
    private static final Map<UUID, RestoreState> activeStates = new HashMap<>();
    private static boolean restoreCompleted = false;

    public static void register() {
        UseBlockCallback.EVENT.register(RestorePowerHandler::onUseBlock);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (activeStates.isEmpty()) return;
            long tick = server.overworld().getGameTime();
            for (var it = activeStates.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                UUID uuid = entry.getKey();
                RestoreState state = entry.getValue();
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);

                TaskInstance task = TaskManager.getInstance().getActiveTask(uuid);
                if (task == null || !"habitrain_core:restore_power".equals(task.getFullId())) {
                    if (sp != null) sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    it.remove();
                    continue;
                }

                if (task.isFulfilled() || task.getProgress() >= task.getMaxProgress()) {
                    if (state.slowUntilTick <= tick) {
                        if (sp != null) sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                        it.remove();
                    }
                    continue;
                }

                if (restoreCompleted) {
                    if (sp != null) sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    it.remove();
                    continue;
                }

                if (sp != null && state.slowUntilTick > tick) {
                    int remaining = (int) (state.slowUntilTick - tick + 10);
                    sp.addEffect(new MobEffectInstance(
                            MobEffects.MOVEMENT_SLOWDOWN, remaining, 2, false, true, true));
                }

                if (state.slowUntilTick <= tick) {
                    if (sp != null) {
                        sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    }
                    if (!restoreCompleted) {
                        task.setProgress(task.getMaxProgress());
                    }
                    it.remove();
                }
            }
        });
    }

    public static void clearState(UUID uuid) {
        activeStates.remove(uuid);
    }

    public static void resetCompleted() {
        restoreCompleted = false;
        activeStates.clear();
    }

    public static boolean isRestoreCompleted() {
        return restoreCompleted;
    }

    public static void markRestoreCompleted() {
        restoreCompleted = true;
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                                  BlockHitResult hitResult) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null || !"habitrain_core:restore_power".equals(task.getFullId())) {
            return InteractionResult.PASS;
        }
        if (task.isFulfilled() || task.getProgress() >= task.getMaxProgress()) {
            return InteractionResult.PASS;
        }
        if (restoreCompleted) return InteractionResult.PASS;

        BlockState state = world.getBlockState(hitResult.getBlockPos());
        if (state.getBlock() != Blocks.LEVER) {
            return InteractionResult.PASS;
        }

        UUID uuid = serverPlayer.getUUID();
        if (activeStates.containsKey(uuid)) {
            return InteractionResult.FAIL;
        }

        long tick = serverPlayer.serverLevel().getServer().overworld().getGameTime();
        RestoreState rs = new RestoreState();
        rs.slowUntilTick = tick + SLOW_TICKS;
        activeStates.put(uuid, rs);

        serverPlayer.addEffect(new MobEffectInstance(
                MobEffects.MOVEMENT_SLOWDOWN, SLOW_TICKS + 10, 2, false, true, true));

        return InteractionResult.FAIL;
    }

    private static final class RestoreState {
        long slowUntilTick;
    }
}