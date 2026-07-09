package com.habitrain.core.api;

import net.minecraft.server.level.ServerLevel;

/**
 * 核心持有的 GameStateProvider 接口。
 * 屏蔽 TaskManager 对 SRE 具体类的直接依赖。
 * DLC 或 SRE 绑定层提供实现。
 */
@FunctionalInterface
public interface GameStateProvider {

    /**
     * 向 SRE 发送自定义获胜通知。
     *
     * @param level          当前世界
     * @param customWinnerId 自定义获胜者 ID（格式 {@code modId_taskId_win}）
     * @param winnerPlayerId 获胜玩家 UUID
     */
    void triggerCustomWin(ServerLevel level, String customWinnerId, java.util.UUID winnerPlayerId);
}
