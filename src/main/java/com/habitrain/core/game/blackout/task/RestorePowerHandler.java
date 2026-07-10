package com.habitrain.core.game.blackout.task;

import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.task.ClearableHandlerRegistry;
import com.habitrain.core.task.SlownessReapplyManager;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.util.SubtitleNotifier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RestorePowerHandler {

    private static final int SLOW_TICKS = 60;
    private static final Map<UUID, RestoreState> activeStates = new HashMap<>();
    /** per-level: true 表示该维度下 restore_power 已完成，停电已恢复 */
    private static final Map<ResourceKey<Level>, Boolean> restoreCompleted = new HashMap<>();

    public static void register() {
        UseBlockCallback.EVENT.register(RestorePowerHandler::onUseBlock);
        ClearableHandlerRegistry.register(RestorePowerHandler::clearAll);
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

                // sp 可能为 null（玩家在 onUseBlock 注册 slow 后、本 tick 前离线）；
                // restoreCompleted 按 level 维度记录，玩家离线时无法取其维度 → 直接清理本条目。
                if (sp == null) {
                    it.remove();
                    continue;
                }
                if (restoreCompleted.getOrDefault(sp.serverLevel().dimension(), false)) {
                    sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    it.remove();
                    continue;
                }

                if (state.slowUntilTick <= tick) {
                    if (sp != null) {
                        sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    }
                    if (sp != null && !restoreCompleted.containsKey(sp.serverLevel().dimension())) {
                        task.setProgress(task.getMaxProgress());
                    }
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
        restoreCompleted.clear();
        SlownessReapplyManager.clearAll();
    }

    public static void resetCompleted(ServerLevel level) {
        restoreCompleted.remove(level.dimension());
        activeStates.clear();
        SlownessReapplyManager.clearAll();
    }

    public static boolean isRestoreCompleted(ServerLevel level) {
        return restoreCompleted.getOrDefault(level.dimension(), false);
    }

    public static void markRestoreCompleted(ServerLevel level) {
        restoreCompleted.put(level.dimension(), true);
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
        if (restoreCompleted.containsKey(serverPlayer.serverLevel().dimension())) return InteractionResult.PASS;

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

        SlownessReapplyManager.register(serverPlayer.serverLevel(), serverPlayer.getUUID(),
                2, SLOW_TICKS + 10,
                ResourceLocation.parse("habitrain_core:restore_power"));

        return InteractionResult.FAIL;
    }

    private static final class RestoreState {
        long slowUntilTick;
    }
}