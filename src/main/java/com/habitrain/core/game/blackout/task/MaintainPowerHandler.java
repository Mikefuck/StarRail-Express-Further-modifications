package com.habitrain.core.game.blackout.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.util.SubtitleNotifier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
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
 * 维持供电任务交互处理器（持续右键发电机）。
 * <p>玩家右键发电机后进入维护状态，每 5 秒（100 tick）需再右键一次。
 * 漏右键则进度重置。累计 15 秒（3 次 5 秒）后任务完成。
 * <p>每次右键给 2 秒缓慢，缓慢在 END_SERVER_TICK 中重新施加以对抗 betel-nut-mod。
 */
public class MaintainPowerHandler {

    private static final String GENERATOR_BLOCK_ID = "yuushya:generator";
    private static final int RIGHT_CLICK_INTERVAL_TICKS = 100; // 5 秒
    private static final int REQUIRED_SECONDS = 15;
    private static final int SLOW_TICKS = 40; // 2 秒

    private static final Map<UUID, MaintainState> activeStates = new HashMap<>();

    private static Block generatorBlock = null;
    private static boolean blockChecked = false;

    public static void register() {
        UseBlockCallback.EVENT.register(MaintainPowerHandler::onUseBlock);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (activeStates.isEmpty()) return;
            long tick = server.overworld().getGameTime();
            for (var it = activeStates.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                UUID uuid = entry.getKey();
                MaintainState state = entry.getValue();
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);

                // 重施缓慢对抗 betel-nut-mod
                if (sp != null && state.slowUntilTick > tick) {
                    int remaining = (int) (state.slowUntilTick - tick + 10);
                    sp.addEffect(new MobEffectInstance(
                            MobEffects.MOVEMENT_SLOWDOWN, remaining, 2, false, true, true));
                }

                // 检查右键超时（漏右键则进度重置）
                TaskInstance task = TaskManager.getInstance().getActiveTask(uuid);
                if (task == null || !"habitrain_core:maintain_power".equals(task.getFullId())) {
                    it.remove();
                    continue;
                }
                if (!task.isFulfilled() && state.lastRightClickTick > 0
                        && (tick - state.lastRightClickTick) > RIGHT_CLICK_INTERVAL_TICKS + 20) {
                    // 超时：进度重置
                    state.accumulatedSeconds = 0;
                    state.lastRightClickTick = 0;
                    task.setProgress(0);
                    if (sp != null) {
                        SubtitleNotifier.sendTop(sp,
                                Component.translatable("task.maintain_power"),
                                Component.literal("§c节奏断了，请重新开始！"),
                                60);
                    }
                }

                // 缓慢到期清理
                if (state.slowUntilTick <= tick && state.lastRightClickTick == 0) {
                    if (sp != null) {
                        sp.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    }
                    // 已完成或任务不存在则清理
                    if (task.isFulfilled() || task.getProgress() >= task.getMaxProgress()) {
                        it.remove();
                    }
                }
            }
        });
    }

    public static void clearState(UUID uuid) {
        activeStates.remove(uuid);
    }

    public static void clearAll() {
        activeStates.clear();
    }

    /** Task 的 onTick 调用：同步 progress 到累计秒数。 */
    public static void tickCheck(Player player, TaskInstance task) {
        if (player == null || task == null || task.isFulfilled()) return;
        MaintainState state = activeStates.get(player.getUUID());
        if (state == null) return;
        // 同步 progress 到 accumulatedSeconds
        int newProgress = Math.min(state.accumulatedSeconds, task.getMaxProgress());
        if (task.getProgress() != newProgress) {
            task.setProgress(newProgress);
        }
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                                BlockHitResult hitResult) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
        if (task == null || !"habitrain_core:maintain_power".equals(task.getFullId())) {
            return InteractionResult.PASS;
        }
        if (task.isFulfilled() || task.getProgress() >= task.getMaxProgress()) {
            return InteractionResult.PASS;
        }

        BlockState state = world.getBlockState(hitResult.getBlockPos());
        if (!isGeneratorBlock(state.getBlock())) {
            return InteractionResult.PASS;
        }

        long tick = serverPlayer.serverLevel().getServer().overworld().getGameTime();
        UUID uuid = serverPlayer.getUUID();
        MaintainState ms = activeStates.get(uuid);

        if (ms == null || ms.lastRightClickTick == 0) {
            // 首次右键
            ms = new MaintainState();
            ms.lastRightClickTick = tick;
            ms.accumulatedSeconds = 0;
            activeStates.put(uuid, ms);
        } else {
            long interval = tick - ms.lastRightClickTick;
            if (interval > RIGHT_CLICK_INTERVAL_TICKS + 20) {
                // 超时重置
                ms.accumulatedSeconds = 0;
                SubtitleNotifier.sendTop(serverPlayer,
                        Component.translatable("task.maintain_power"),
                        Component.literal("§c节奏断了，请重新开始！"),
                        60);
            } else if (interval >= RIGHT_CLICK_INTERVAL_TICKS - 10) {
                // 成功 5 秒间隔
                ms.accumulatedSeconds = Math.min(ms.accumulatedSeconds + 5, REQUIRED_SECONDS);
            } else {
                // 太快点击：忽略（不算入进度）
                SubtitleNotifier.sendTop(serverPlayer,
                        Component.translatable("task.maintain_power"),
                        Component.literal("§7请等待 5 秒再右键..."),
                        40);
                return InteractionResult.FAIL;
            }
            ms.lastRightClickTick = tick;
        }

        // 给 2 秒缓慢
        serverPlayer.addEffect(new MobEffectInstance(
                MobEffects.MOVEMENT_SLOWDOWN, SLOW_TICKS + 10, 2, false, true, true));
        ms.slowUntilTick = tick + SLOW_TICKS;

        // 同步进度
        task.setProgress(Math.min(ms.accumulatedSeconds, task.getMaxProgress()));

        if (ms.accumulatedSeconds >= REQUIRED_SECONDS) {
            SubtitleNotifier.sendTop(serverPlayer,
                    Component.translatable("task.maintain_power"),
                    Component.literal("§a供电维持完成！"),
                    60);
        } else {
            SubtitleNotifier.sendTop(serverPlayer,
                    Component.translatable("task.maintain_power"),
                    Component.literal("§a已维持 " + ms.accumulatedSeconds + "/" + REQUIRED_SECONDS + " 秒"),
                    40);
        }

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
        long lastRightClickTick;
        int accumulatedSeconds;
        long slowUntilTick;
    }
}