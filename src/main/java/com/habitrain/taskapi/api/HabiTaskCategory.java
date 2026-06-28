package com.habitrain.taskapi.api;

/**
 * 任务分类 - 用于区分不同游戏模式下的任务
 * 哈比列车原版支持谋杀模式(TMM)和修机模式(Repair)等多种模式
 */
public enum HabiTaskCategory {
    /**
     * 谋杀模式任务 - 仅经典列车谋杀案模式下可用
     */
    MURDER,

    /**
     * 修机模式任务 - 仅修复逃脱模式下可用
     */
    REPAIR,

    /**
     * 所有模式共用任务 - 所有游戏模式均可使用
     */
    ALL,

    /**
     * 自定义 - 由DLC模组自行控制可用性
     */
    CUSTOM
}
