package com.habitrain.core.game.sre;

import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务枚举辅助类 - 动态获取枚举值以兼容不同SRE版本
 */
public class TaskEnumHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("TaskEnumHelper");
    private static SREPlayerTaskComponent.Task customTaskEnum = null;
    private static boolean initialized = false;

    public static SREPlayerTaskComponent.Task getCustom() {
        if (!initialized) {
            try {
                customTaskEnum = SREPlayerTaskComponent.Task.valueOf("CUSTOM");
                LOGGER.info("SRE Task.CUSTOM 枚举值已加载，API任务系统可用");
            } catch (IllegalArgumentException e) {
                customTaskEnum = null;
                LOGGER.warn("当前StarRailExpress版本不支持CUSTOM任务类型，API任务系统已降级。");
            }
            initialized = true;
        }
        return customTaskEnum;
    }

    public static boolean isCustomTaskSupported() {
        return getCustom() != null;
    }
}
