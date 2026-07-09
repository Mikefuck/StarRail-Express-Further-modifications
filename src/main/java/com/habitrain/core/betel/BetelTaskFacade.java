package com.habitrain.core.betel;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 对外（blackout 包）暴露的槟榔任务门面接口。
 * <p>blackout 包通过此门面操作 {@link BetelQuestState}，避免直接依赖其内部结构。</p>
 */
public final class BetelTaskFacade {

    private BetelTaskFacade() {}

    /**
     * 标记玩家槟榔任务已在本局刷新。
     *
     * @param player 目标玩家
     */
    public static void markQuestAssigned(Player player) {
        BetelQuestState.markQuestAssigned(player.getUUID());
    }

    /**
     * 重置玩家吃槟榔状态（用于每局任务分配时清除上一局残留）。
     *
     * @param player 目标玩家
     */
    public static void resetEatenStatus(ServerPlayer player) {
        BetelQuestState.resetEatenStatus(player);
    }

    /**
     * 检查玩家本局是否已吃过槟榔。
     *
     * @param player 目标玩家
     * @return 是否已吃过槟榔
     */
    public static boolean hasPlayerEatenBetelNut(Player player) {
        return BetelQuestState.hasPlayerEatenBetelNut(player.getUUID());
    }
}
