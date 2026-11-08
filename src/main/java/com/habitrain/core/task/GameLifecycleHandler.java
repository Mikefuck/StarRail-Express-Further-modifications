package com.habitrain.core.task;


import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiComponents;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;



/**
 * 游戏生命周期处理器
 * 使用ServerTick检测游戏结束并进行清理
 */
public class GameLifecycleHandler {
    /** 记录上一tick是否处于游戏中（全局，不受多世界影响） */
    private static volatile boolean wasGameActive = false;
    private static volatile MinecraftServer lastServer;

    /**
     * 每tick检测游戏状态，当游戏从活跃变为非活跃时清理效果
     * 注意：此方法在遍历所有世界后调用一次，而非每世界调用。
     * 使用全局 anyGameActive 避免多世界下 wasGameActive 状态被破坏。
     *
     * @param anyGameActive 任意世界是否有游戏运行
     * @param server MinecraftServer 实例（用于 handleGameEnd 遍历玩家）
     */
    public static void tickGameEndCheck(boolean anyGameActive, MinecraftServer server) {
        lastServer = server;
        // 检测下降沿：上一tick游戏活跃 → 当前tick游戏非活跃
        if (wasGameActive && !anyGameActive) {
            handleGameEnd(server);
        }
        wasGameActive = anyGameActive;
    }

    /** 供按维度清任务时找回在线玩家（休息区可能不在对局维）。 */
    public static MinecraftServer peekServer() {
        return lastServer;
    }

    public static void resetGameState() {
        wasGameActive = false;
    }

    /**
     * CORE-07：服务器停止时彻底重置静态生命周期状态并释放 MinecraftServer 引用，
     * 防止 IntegratedServer 内存泄漏及进入新存档首 tick 误触发 handleGameEnd。
     */
    public static void resetLifecycleState() {
        wasGameActive = false;
        lastServer = null;
    }

    private static void handleGameEnd(MinecraftServer server) {
        try {
            if (server != null) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    // per-player try-catch：单个玩家清理失败不应跳过后续玩家
                    // 和全局清理，避免跨局状态泄漏。
                    try {
                        TaskManager.getInstance().cancelAllTrackedTasks(player);
                        try {
                            BuiltInRegistries.MOB_EFFECT.getHolder(
                                            ResourceLocation.fromNamespaceAndPath("noellesroles", "noellesroles"))
                                    .ifPresent(player::removeEffect);
                        } catch (Exception ignored) {}

                        // 七宗罪 + 既有角色 CCA 局终清空（仅在线玩家；离线残留由 JOIN 再清）。
                        try {
                            HabiComponents.clearAll(player);
                        } catch (Throwable ignored) {}
                    } catch (Exception pe) {
                        HabiTrainCore.LOGGER.error("清理玩家 {} 的效果时出错，继续处理其他玩家",
                                player.getName().getString(), pe);
                    }
                }
            }
        } finally {
            // 全局清理必须执行，即使某玩家清理抛异常也不应跳过
            ClearableHandlerRegistry.clearAll();
            SlownessReapplyManager.clearAll();
            MimeKillerComponent.clearHiddenBodies();
            try {
                com.habitrain.core.game.sre.modifier.virtue.TemperanceVirtue.clearAll();
            } catch (Throwable ignored) {}
            // 清除任务池缓存，确保下一局任务重新计算
            TaskPoolBuilder.invalidateAll();
            // API-TV-13：将仍残留的离线玩家活跃任务转入 pendingReclaim 队列后再清空活动表，
            // 确保中途掉线的玩家在重连进服时仍会回收上局发放的任务道具。
            TaskManager.getInstance().endAllActiveTasks(server);

            // 重置背包翻找任务状态（下一局可以再次刷新）
            BackpackQuestState.getInstance().resetAll();
            // 清除所有背包翻找动作（防止残留状态影响下一局）
            BackpackSearchHandler.clearAllSearches();

            // 清除跨局静态状态
            com.habitrain.core.game.sre.match.MvpScoreTracker.resetAll();
            com.habitrain.core.game.sre.role.component.FlowerGirlComponent.clearMeleeImmune();

            HabiTrainCore.LOGGER.info("游戏结束，已清理任务与角色状态");
        }
    }
}
