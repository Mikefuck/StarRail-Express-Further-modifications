package com.habitrain.core.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
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
 * 背包翻找交互处理器
 * 玩家右键背包方块后给予缓慢3效果，播放翻找音效，
 * 并在任务系统中标记为"正在翻找"状态，使任务进度每tick递增。
 * 谋杀模式 search_backpack 为 6 秒；停电模式 blackout_search_backpack 为 3 秒。
 */
public class BackpackSearchHandler {
    /** 活跃的翻找记录 (玩家UUID -> 翻找状态) */
    private static final Map<UUID, SearchState> activeSearches = new HashMap<>();
    private static final int SEARCH_TICKS_DEFAULT = 120; // 6秒 — 谋杀模式
    private static final int SEARCH_TICKS_BLACKOUT = 60; // 3秒 — 停电模式

    private static final String BACKPACK_BLOCK_ID = "decocraft:backpack_red";
    private static final String TASK_SEARCH_BACKPACK = "habitrain_core:search_backpack";
    private static final String TASK_BLACKOUT_SEARCH_BACKPACK = "habitrain_core:blackout_search_backpack";

    private static Block backpackBlock = null;
    private static boolean blockChecked = false;

    public static void register() {
        UseBlockCallback.EVENT.register(BackpackSearchHandler::onUseBlock);

        // 注册END_SERVER_TICK重新施加缓慢效果
        // betel-nut-mod 每tick会清除 MOVEMENT_SLOWDOWN，
        // 必须在 END_SERVER_TICK（实体同步前）重新施加，确保客户端能看到效果
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (activeSearches.isEmpty()) return;

            // 统一用主世界 gameTime，避免玩家在下界/末地右键背包时 startTick 与
            // 超时检查使用不同维度 gameTime 造成偏差。
            long tick = server.overworld().getGameTime();

            for (var it = activeSearches.entrySet().iterator(); it.hasNext();) {
                var entry = it.next();
                UUID uuid = entry.getKey();
                SearchState state = entry.getValue();

                // 超时清理：移除追踪并主动清除缓慢效果（不依赖外部 mod 每 tick 清除）
                if (tick - state.startTick() >= state.durationTicks()) {
                    ServerPlayer timedOut = server.getPlayerList().getPlayer(uuid);
                    if (timedOut != null) {
                        timedOut.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    }
                    SlownessReapplyManager.unregisterAllLevels(uuid);
                    it.remove();
                    // 同步清理任务实例：玩家可能因断线/切世界错过最后一 tick，
                    // 导致任务 progress 卡在 < max，本局再也无法获得新 DLC 任务。
                    // 这里主动标记失败并移除活跃任务，避免玩家被永久卡住。
                    TaskManager mgr = TaskManager.getInstance();
                    TaskInstance stuckTask = mgr.getActiveTask(uuid);
                    if (stuckTask != null
                            && (TASK_SEARCH_BACKPACK.equals(stuckTask.getFullId())
                                    || TASK_BLACKOUT_SEARCH_BACKPACK.equals(stuckTask.getFullId()))
                            && !stuckTask.isFulfilled()) {
                        // 任务超时前回收发放的道具（虽然翻背包通常 onComplete 才发放，
                        // 但若任务以某种方式提前发放了道具，这里回收保证安全）
                        ServerPlayer stuckPlayer = server.getPlayerList().getPlayer(uuid);
                        if (stuckPlayer != null) {
                            com.habitrain.core.api.ItemReclaimHelper.reclaimForTask(stuckPlayer, stuckTask);
                            // 走标准 onRemove（与 PerPlayerTaskTicker.handleMainTaskDone 失败分支一致），
                            // 让任务定义有机会做自身清理。
                            try {
                                stuckTask.getDefinition().onRemove(stuckPlayer, stuckTask);
                            } catch (Exception ex) {
                                HabiTrainCore.LOGGER.error("search_backpack 超时 onRemove 失败", ex);
                            }
                        }
                        stuckTask.markFailed();
                        mgr.removeActiveTask(uuid);
                        // 通知客户端清除活动任务 HUD/方块高亮，避免陈旧活动任务残留到下局
                        if (stuckPlayer != null) {
                            com.habitrain.core.network.ActiveTaskPayload.clearForPlayer(stuckPlayer);
                        }
                    }
                    continue;
                }

            }
        });
    }

    /**
     * 检查玩家是否正在翻找背包
     */
    public static boolean isSearching(UUID uuid) {
        return activeSearches.containsKey(uuid);
    }

    /**
     * 停止玩家的翻找状态（任务完成时调用）
     */
    public static void stopSearching(UUID uuid) {
        activeSearches.remove(uuid);
        SlownessReapplyManager.unregisterAllLevels(uuid);
    }

    /**
     * 清除所有翻找状态（游戏结束时调用）
     */
    public static void clearAllSearches() {
        activeSearches.clear();
        // 缓慢表由 GameLifecycleHandler 统一 clear
    }

    private static int getSearchTicks(String taskKey) {
        if (TASK_BLACKOUT_SEARCH_BACKPACK.equals(taskKey)) {
            return SEARCH_TICKS_BLACKOUT;
        }
        return SEARCH_TICKS_DEFAULT;
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                                BlockHitResult hitResult) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

        BlockPos pos = hitResult.getBlockPos();
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        // 检查是否是背包方块
        if (!isBackpackBlock(block)) return InteractionResult.PASS;

        UUID uuid = player.getUUID();

        // 检查玩家是否有翻找背包任务（支持谋杀模式与停电模式两个版本）
        TaskInstance task = TaskManager.getInstance().getActiveTask(uuid);
        if (task == null || (!TASK_SEARCH_BACKPACK.equals(task.getFullId())
                && !TASK_BLACKOUT_SEARCH_BACKPACK.equals(task.getFullId()))) {
            return InteractionResult.PASS;
        }
        if (task.isFulfilled() || task.getProgress() >= task.getMaxProgress()) {
            return InteractionResult.PASS;
        }
        String taskKey = task.getFullId();
        int searchTicks = getSearchTicks(taskKey);

        // 防止重复点击
        if (activeSearches.containsKey(uuid)) {
            return InteractionResult.FAIL;
        }

        // 给予缓慢3效果（时长按任务版本：谋杀6秒 / 停电3秒，+10 tick缓冲）；到期自动 unregister
        int slowDuration = searchTicks + 10;
        SlownessReapplyManager.register(serverPlayer.serverLevel().dimension(), serverPlayer.getUUID(),
                new SlownessReapplyManager.EffectSpec(2, slowDuration,
                        ResourceLocation.parse(taskKey)));

        // 记录翻找状态（onTick 会据此递增任务进度）
        // 用主世界 gameTime 与超时检查保持一致，避免跨维度偏差
        long startTick;
        if (world instanceof ServerLevel sl && sl.getServer() != null) {
            startTick = sl.getServer().overworld().getGameTime();
        } else {
            startTick = world.getGameTime();
        }
        activeSearches.put(uuid, new SearchState(startTick, searchTicks));

        serverPlayer.serverLevel().playSound(
                null,
                serverPlayer.blockPosition(),
                HabiTrainCore.BACKPACK_SEARCH_SOUND,
                net.minecraft.sounds.SoundSource.PLAYERS,
                1.0f,
                1.0f
        );

        return InteractionResult.FAIL;
    }

    private static boolean isBackpackBlock(Block block) {
        if (!blockChecked) {
            try {
                backpackBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(BACKPACK_BLOCK_ID));
                if (backpackBlock == null || backpackBlock == Blocks.AIR) {
                    HabiTrainCore.LOGGER.warn("背包方块未找到: {}", BACKPACK_BLOCK_ID);
                    backpackBlock = null;
                }
            } catch (Exception e) {
                HabiTrainCore.LOGGER.error("查找背包方块时出错: {}", BACKPACK_BLOCK_ID, e);
                backpackBlock = null;
            }
            blockChecked = true;
        }
        return backpackBlock != null && block == backpackBlock;
    }

    private record SearchState(long startTick, int durationTicks) {}
}
