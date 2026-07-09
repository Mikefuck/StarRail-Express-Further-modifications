package com.habitrain.core.task;

import betel.nut.component.BetelNutAddictionComponent;
import betel.nut.component.BetelNutEntityComponents;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.betel.BetelQuestState;
import com.habitrain.core.betel.BetelLeafHandler;
import com.habitrain.core.misc.EffectOwnershipTracker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;

import java.util.UUID;


/**
 * 游戏生命周期处理器
 * 使用ServerTick检测游戏结束并进行清理
 */
public class GameLifecycleHandler {
    /** 记录上一tick是否处于游戏中（全局，不受多世界影响） */
    private static boolean wasGameActive = false;

    /**
     * 每tick检测游戏状态，当游戏从活跃变为非活跃时清理效果
     * 注意：此方法在遍历所有世界后调用一次，而非每世界调用。
     * 使用全局 anyGameActive 避免多世界下 wasGameActive 状态被破坏。
     *
     * @param anyGameActive 任意世界是否有游戏运行
     * @param server MinecraftServer 实例（用于 handleGameEnd 遍历玩家）
     */
    public static void tickGameEndCheck(boolean anyGameActive, MinecraftServer server) {
        // 检测下降沿：上一tick游戏活跃 → 当前tick游戏非活跃
        if (wasGameActive && !anyGameActive) {
            handleGameEnd(server);
        }
        wasGameActive = anyGameActive;
    }

    public static void resetGameState() {
        wasGameActive = false;
    }

    private static void handleGameEnd(MinecraftServer server) {
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                UUID pUuid = player.getUUID();
                // per-player try-catch：单个玩家清理失败不应跳过后续玩家
                // 和全局清理（BetelQuestState.resetAll 等），避免跨局状态泄漏。
                try {
                    // 使用归属追踪器释放游戏中的槟榔效果
                    // 只移除本模组的"betel_quest"来源效果，不影响其他模组
                    if (EffectOwnershipTracker.release(pUuid, MobEffects.MOVEMENT_SPEED, "betel_quest")) {
                        player.removeEffect(MobEffects.MOVEMENT_SPEED);
                    }
                    if (EffectOwnershipTracker.release(pUuid, MobEffects.DARKNESS, "betel_quest")) {
                        player.removeEffect(MobEffects.DARKNESS);
                    }
                    if (EffectOwnershipTracker.release(pUuid, MobEffects.GLOWING, "betel_quest")) {
                        player.removeEffect(MobEffects.GLOWING);
                    }
                    if (EffectOwnershipTracker.release(pUuid, MobEffects.MOVEMENT_SLOWDOWN, "betel_quest")) {
                        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                    }

                    try {
                        BuiltInRegistries.MOB_EFFECT.getHolder(
                                        ResourceLocation.fromNamespaceAndPath("noellesroles", "noellesroles"))
                                .ifPresent(player::removeEffect);
                    } catch (Exception ignored) {}

                    // 清除槟榔成瘾组件数据
                    try {
                        BetelNutAddictionComponent addiction = BetelNutEntityComponents.ADDICTION.get(player);
                        addiction.clearAddiction(player);
                    } catch (Exception ignored) {}
                } catch (Exception pe) {
                    HabiTrainCore.LOGGER.error("清理玩家 {} 的效果时出错，继续处理其他玩家",
                            player.getName().getString(), pe);
                }
            }
        } finally {
            // 全局清理必须执行，即使某玩家清理抛异常也不应跳过
            ClearableHandlerRegistry.clearAll();
            SlownessReapplyManager.clearAll();
            BetelQuestState.resetGameState();
            BetelLeafHandler.clearAllHarvests();

            // 清除任务池缓存，确保下一局任务重新计算
            TaskPoolBuilder.invalidateAll();

            // 重置背包翻找任务状态（下一局可以再次刷新）
            BackpackQuestState.getInstance().resetAll();
            // 清除所有背包翻找动作（防止残留状态影响下一局）
            BackpackSearchHandler.clearAllSearches();

            HabiTrainCore.LOGGER.info("游戏结束，已清除所有槟榔效果");
        }
    }
}
