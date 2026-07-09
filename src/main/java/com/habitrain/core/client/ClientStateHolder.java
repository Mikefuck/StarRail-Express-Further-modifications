package com.habitrain.core.client;

import java.lang.reflect.Method;

/**
 * 客户端光影包监测相关静态可变状态持有者。
 * <p>
 * 与 {@link com.habitrain.core.client.gui.ClientBlackoutState} 互补，
 * 后者管理停电模式相关状态。本类仅持有包级可访问的 static 字段，
 * 供同一包内的 {@link ShaderMonitor} 读写。
 */
public final class ClientStateHolder {

    /** 上次已上报的光影包名称 */
    static String lastSentShaderPack = "";
    /** 是否正在监测（加入服务器后启用，断开后停止） */
    static boolean monitoringShaderPack = false;
    /** tick 计数器 */
    static int shaderMonitorTick = 0;
    /** 缓存的 Iris 反射 Class 对象 */
    static Class<?> cachedIrisClass;
    /** 缓存的 Iris 反射 Method 对象 (S9-012)，避免每 30 秒调用 getMethod */
    static Method getIrisConfigMethod;
    static Method areShadersEnabledMethod;
    static Method getShaderPackNameMethod;

    private ClientStateHolder() {
    }
}
