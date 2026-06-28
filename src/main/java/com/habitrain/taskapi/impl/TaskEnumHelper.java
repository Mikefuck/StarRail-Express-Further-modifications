package com.habitrain.taskapi.impl;

import com.habitrain.taskapi.HabiTrainTaskAPI;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;

/**
 * 任务枚举辅助类 - 动态获取枚举值以兼容不同SRE版本
 *
 * 某些旧版SRE(如4.0.0)的Task枚举可能没有CUSTOM等字段，
 * 使用动态查找而非编译期常量引用来避免 NoSuchFieldError。
 */
public class TaskEnumHelper {

    private static SREPlayerTaskComponent.Task customTaskEnum = null;
    private static boolean initialized = false;

    /**
     * 动态获取 CUSTOM 枚举值
     * @return CUSTOM枚举值，若当前SRE版本不支持则返回null
     */
    public static SREPlayerTaskComponent.Task getCustom() {
        if (!initialized) {
            try {
                customTaskEnum = SREPlayerTaskComponent.Task.valueOf("CUSTOM");
                HabiTrainTaskAPI.LOGGER.info("SRE Task.CUSTOM 枚举值已加载，API任务系统可用");
            } catch (IllegalArgumentException e) {
                customTaskEnum = null;
                HabiTrainTaskAPI.LOGGER.warn("当前StarRailExpress版本不支持CUSTOM任务类型，API任务系统已降级。"
                        + "请更新StarRailExpress至最新版本以支持完整功能。");
            }
            initialized = true;
        }
        return customTaskEnum;
    }

    /**
     * 检查当前SRE版本是否支持CUSTOM任务类型
     */
    public static boolean isCustomTaskSupported() {
        return getCustom() != null;
    }
}
