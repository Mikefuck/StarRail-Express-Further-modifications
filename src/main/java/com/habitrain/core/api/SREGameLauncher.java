package com.habitrain.core.api;

import net.minecraft.server.level.ServerLevel;

/**
 * SRE 游戏启动抽象。
 * 屏蔽 BlackoutMode 对 SREGameModes、GameUtils 的直接依赖。
 */
@FunctionalInterface
public interface SREGameLauncher {

    /**
     * 在当前世界启动 SRE 停电游戏。
     *
     * @param level 目标世界
     */
    void startBlackoutGame(ServerLevel level);
}
