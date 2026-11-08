package com.habitrain.core.task;


import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.MatchRestStateApi;
import com.habitrain.core.api.TaskInstance;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 背包翻找交互处理器
 * 玩家右键背包方块后给予缓慢3效果，播放翻找音效，
 * 并在任务系统中标记为"正在翻找"状态，使任务进度每tick递增。
 * 翻找背包任务需要 6 秒。
 */
public class BackpackSearchHandler {
    /** 活跃的翻找记录 (玩家UUID -> 翻找状态) */
    private static final Map<UUID, SearchState> activeSearches = new ConcurrentHashMap<>();
    private static final int SEARCH_TICKS_DEFAULT = 120; // 6秒 — 谋杀模式
    /** API-TV-15：超时宽限 tick，避免因 1~2 tick 调度抖动在读条即将满时将任务永久标为 FAILED */
    private static final int TIMEOUT_GRACE_TICKS = 40;
    private static final double MAX_INTERACT_DISTANCE_SQR = 6.0 * 6.0;
    private static final double MAX_HOLD_DISTANCE_SQR = 7.0 * 7.0;

    private static final String BACKPACK_BLOCK_ID = "decocraft:backpack_red";
    private static final String TASK_SEARCH_BACKPACK = "habitrain_core:search_backpack";

    private static volatile Block backpackBlock = null;
    private static volatile boolean blockChecked = false;
    private static boolean registered = false;

    public static void register() {
        // API-TV-15：幂等守卫，防止重复注册监听器
        if (registered) return;
        registered = true;
        ClearableHandlerRegistry.register(BackpackSearchHandler::clearAllSearches);
        UseBlockCallback.EVENT.register(BackpackSearchHandler::onUseBlock);

        // 注册END_SERVER_TICK重新施加缓慢效果并检查翻找状态
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (activeSearches.isEmpty()) return;

            // 统一用主世界 gameTime，避免玩家在下界/末地右键背包时 startTick 与
            // 超时检查使用不同维度 gameTime 造成偏差。
            long tick = server.overworld().getGameTime();

            for (var it = activeSearches.entrySet().iterator(); it.hasNext();) {
                var entry = it.next();
                UUID uuid = entry.getKey();
                SearchState state = entry.getValue();
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                TaskInstance assigned = TaskManager.getInstance().getActiveTask(uuid);
                if (assigned == null || !assigned.getInstanceId().equals(state.taskInstanceId())
                        || assigned.isFulfilled()) {
                    it.remove();
                    SlownessReapplyManager.unregisterAllLevels(uuid, player);
                    continue;
                }

                // 离线/死亡/旁观/休息区状态检查：玩家已失效则立即清理
                if (player == null || player.isRemoved() || player.isDeadOrDying()
                        || player.isSpectator() || MatchRestStateApi.isPlayerResting(player)) {
                    it.remove();
                    SlownessReapplyManager.unregisterAllLevels(uuid, player);

                    TaskManager mgr = TaskManager.getInstance();
                    TaskInstance stuckTask = mgr.getActiveTask(uuid);
                    if (stuckTask != null
                            && TASK_SEARCH_BACKPACK.equals(stuckTask.getFullId())
                            && !stuckTask.isFulfilled()) {
                        stuckTask.markFailed();
                        if (player != null) {
                            // API-TV-14：统一走 cancelTrackedTask（带实例比对删除 + stripSreWrapper + syncActiveTask）
                            mgr.cancelTrackedTask(player, stuckTask);
                        } else {
                            mgr.removeActiveTask(uuid, stuckTask);
                        }
                    }
                    continue;
                }

                // API-TV-15：若玩家被传送离开背包所在维度或超出距离，中断本次读条并归零进度，
                // 但不将任务永久标记为 FAILED，允许玩家回到背包旁重新翻找。
                if (!player.serverLevel().dimension().equals(state.dimension())
                        || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(state.pos())) > MAX_HOLD_DISTANCE_SQR
                        || !player.serverLevel().hasChunkAt(state.pos())
                        || !isBackpackBlock(player.serverLevel().getBlockState(state.pos()).getBlock())
                        || !canSeeBackpack(player, state.pos())) {
                    it.remove();
                    SlownessReapplyManager.unregisterAllLevels(uuid, player);
                    TaskInstance active = TaskManager.getInstance().getActiveTask(uuid);
                    if (active != null && TASK_SEARCH_BACKPACK.equals(active.getFullId()) && !active.isFulfilled()) {
                        active.setProgress(0);
                    }
                    continue;
                }

                // 超时兜底清理（含宽限期）：移除追踪并恢复进度，不锁死玩家任务槽
                if (tick - state.startTick() >= state.durationTicks() + TIMEOUT_GRACE_TICKS) {
                    it.remove();
                    SlownessReapplyManager.unregisterAllLevels(uuid, player);
                    TaskManager mgr = TaskManager.getInstance();
                    TaskInstance stuckTask = mgr.getActiveTask(uuid);
                    if (stuckTask != null
                            && TASK_SEARCH_BACKPACK.equals(stuckTask.getFullId())
                            && !stuckTask.isFulfilled()) {
                        stuckTask.setProgress(0);
                    }
                }
            }
        });
    }

    /**
     * 检查玩家是否正在翻找背包
     */
    public static boolean isSearching(UUID uuid) {
        return uuid != null && activeSearches.containsKey(uuid);
    }

    /**
     * 停止玩家的翻找状态（任务完成/移除时调用）
     */
    public static void stopSearching(UUID uuid) {
        if (uuid == null) return;
        activeSearches.remove(uuid);
        SlownessReapplyManager.unregisterAllLevels(uuid);
    }

    /**
     * 停止玩家的翻找状态并按所有权安全移除缓慢效果
     */
    public static void stopSearching(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        activeSearches.remove(uuid);
        SlownessReapplyManager.unregisterAllLevels(uuid, player);
    }

    /**
     * 清除所有翻找状态（游戏结束时调用）
     */
    public static void clearAllSearches() {
        for (UUID uuid : activeSearches.keySet()) {
            SlownessReapplyManager.unregisterAllLevels(uuid);
        }
        activeSearches.clear();
    }

    private static int getSearchTicks(String taskKey) {
        return SEARCH_TICKS_DEFAULT;
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                                BlockHitResult hitResult) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        // API-TV-15：排除旁观者、死亡玩家与淘汰休息区玩家，防止隐身旁观者触发全场翻找音效
        if (!serverPlayer.isAlive() || serverPlayer.isSpectator() || MatchRestStateApi.isPlayerResting(serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (hitResult == null) return InteractionResult.PASS;

        BlockPos pos = hitResult.getBlockPos();
        if (serverPlayer.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > MAX_INTERACT_DISTANCE_SQR) {
            return InteractionResult.PASS;
        }
        if (!world.hasChunkAt(pos)) return InteractionResult.PASS;
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        // 检查是否是背包方块
        if (!isBackpackBlock(block)) return InteractionResult.PASS;
        if (!canSeeBackpack(serverPlayer, pos)) return InteractionResult.PASS;

        UUID uuid = player.getUUID();

        // 检查玩家是否有翻找背包任务
        TaskInstance task = TaskManager.getInstance().getActiveTask(uuid);
        if (task == null || !TASK_SEARCH_BACKPACK.equals(task.getFullId())) {
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

        // 登记效果所有权并给予缓慢3效果（6秒，+10 tick缓冲）；到期自动 unregister
        int slowDuration = searchTicks + 10;
        SlownessReapplyManager.register(serverPlayer.serverLevel(), serverPlayer.getUUID(),
                2, slowDuration, ResourceLocation.parse(taskKey));

        // 记录翻找状态（onTick 会据此递增任务进度）
        // 用主世界 gameTime 与超时检查保持一致，避免跨维度偏差
        long startTick;
        if (world instanceof ServerLevel sl && sl.getServer() != null) {
            startTick = sl.getServer().overworld().getGameTime();
        } else {
            startTick = world.getGameTime();
        }
        activeSearches.put(uuid, new SearchState(startTick, searchTicks, serverPlayer.serverLevel().dimension(),
                pos.immutable(), task.getInstanceId()));

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

    public static boolean isBackpackBlockRegistered() {
        ensureBackpackBlockChecked();
        return backpackBlock != null;
    }

    private static boolean canSeeBackpack(ServerPlayer player, BlockPos pos) {
        BlockHitResult hit = player.level().clip(new ClipContext(player.getEyePosition(),
                Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    private static boolean isBackpackBlock(Block block) {
        ensureBackpackBlockChecked();
        return backpackBlock != null && block == backpackBlock;
    }

    private static void ensureBackpackBlockChecked() {
        if (!blockChecked) {
            synchronized (BackpackSearchHandler.class) {
                if (!blockChecked) {
                    try {
                        ResourceLocation id = ResourceLocation.parse(BACKPACK_BLOCK_ID);
                        if (BuiltInRegistries.BLOCK.containsKey(id)) {
                            Block resolved = BuiltInRegistries.BLOCK.get(id);
                            if (resolved != null && resolved != Blocks.AIR) {
                                backpackBlock = resolved;
                            } else {
                                HabiTrainCore.LOGGER.warn("背包方块未找到: {}", BACKPACK_BLOCK_ID);
                                backpackBlock = null;
                            }
                        } else {
                            HabiTrainCore.LOGGER.warn("背包方块未注册: {}", BACKPACK_BLOCK_ID);
                            backpackBlock = null;
                        }
                    } catch (Exception e) {
                        HabiTrainCore.LOGGER.error("查找背包方块时出错: {}", BACKPACK_BLOCK_ID, e);
                        backpackBlock = null;
                    }
                    blockChecked = true;
                }
            }
        }
    }

    private record SearchState(long startTick, int durationTicks, ResourceKey<Level> dimension, BlockPos pos,
                               UUID taskInstanceId) {}
}
