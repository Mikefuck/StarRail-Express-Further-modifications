package com.habitrain.core.api;

/**
 * 生命周期事件枚举，用于框架内部调度。
 * DLC 模组通常只需实现 GameMode 接口中的 default 方法。
 */
public enum GameModeLifecycle {
    PRE_START,
    START,
    TICK,
    PLAYER_JOIN,
    PLAYER_LEAVE,
    TASK_COMPLETE,
    CHECK_WIN,
    END,
    CLEANUP
}
