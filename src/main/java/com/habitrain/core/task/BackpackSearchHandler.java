package com.habitrain.core.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
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
 * 背包翻找交互处理器
 * 玩家右键背包方块后给予缓慢3效果（6秒），播放翻找音效，
 * 并在任务系统中标记为"正在翻找"状态，使任务进度每tick递增。
 * 6秒后任务自动完成并发放奖励。
 */
public class BackpackSearchHandler {
    /** 活跃的翻找记录 (玩家UUID -> 翻找开始时的世界tick) */
    private static final Map<UUID, Long> activeSearches = new HashMap<>();
    private static final int SEARCH_TICKS = 120; // 6秒 (20 ticks/秒 × 6)

    private static final String BACKPACK_BLOCK_ID = "decocraft:backpack_red";

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
                long startTick = entry.getValue();

                // 超时清理：移除追踪并主动清除缓慢效果（不依赖外部 mod 每 tick 清除）
                if (tick - startTick >= SEARCH_TICKS) {
                    ServerPlayer timedOut = server.getPlayerList().getPlayer(uuid);
                    if (timedOut != null) {
                        timedOut.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    }
                    it.remove();
                    // 同步清理任务实例：玩家可能因断线/切世界错过最后一 tick，
                    // 导致任务 progress 卡在 < max，本局再也无法获得新 DLC 任务。
                    // 这里主动标记失败并移除活跃任务，避免玩家被永久卡住。
                    TaskManager mgr = TaskManager.getInstance();
                    TaskInstance stuckTask = mgr.getActiveTask(uuid);
                    if (stuckTask != null
                            && ("habitrain_core:search_backpack".equals(stuckTask.getFullId())
                                    || "habitrain_core:blackout_search_backpack".equals(stuckTask.getFullId()))
                            && !stuckTask.isFulfilled()) {
                        stuckTask.markFailed();
                        mgr.removeActiveTask(uuid);
                    }
                    continue;
                }

                // 在实体同步前重新施加缓慢，对抗betel-nut-mod的清除
                // 直接用 PlayerList.getPlayer(uuid) 查找，避免遍历所有世界（原 O(N*M)）
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    int remaining = (int) (SEARCH_TICKS - (tick - startTick) + 10);
                    sp.addEffect(new MobEffectInstance(
                            MobEffects.MOVEMENT_SLOWDOWN, remaining, 2, false, true, true));
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
    }

    /**
     * 清除所有翻找状态（游戏结束时调用）
     */
    public static void clearAllSearches() {
        activeSearches.clear();
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
        if (task == null || (!"habitrain_core:search_backpack".equals(task.getFullId())
                && !"habitrain_core:blackout_search_backpack".equals(task.getFullId()))) {
            return InteractionResult.PASS;
        }
        if (task.isFulfilled() || task.getProgress() >= task.getMaxProgress()) {
            return InteractionResult.PASS;
        }
        String taskKey = task.getFullId();

        // 防止重复点击
        if (activeSearches.containsKey(uuid)) {
            SubtitleNotifier.sendTop(
                    serverPlayer,
                    Component.translatable(taskKey),
                    Component.literal("正在翻找背包中，请稍候..."),
                    45
            );
            return InteractionResult.FAIL;
        }

        // 给予缓慢3效果（6秒 = 120 ticks，+10 tick缓冲）
        serverPlayer.addEffect(new MobEffectInstance(
                MobEffects.MOVEMENT_SLOWDOWN, SEARCH_TICKS + 10, 2, false, true, true));

        SubtitleNotifier.sendTop(
                serverPlayer,
                Component.translatable(taskKey),
                Component.literal("右键背包来翻找！"),
                60
        );

        // 记录翻找状态（onTick 会据此递增任务进度）
        // 用主世界 gameTime 与超时检查保持一致，避免跨维度偏差
        if (world instanceof ServerLevel sl && sl.getServer() != null) {
            activeSearches.put(uuid, sl.getServer().overworld().getGameTime());
        } else {
            activeSearches.put(uuid, world.getGameTime());
        }

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
}
