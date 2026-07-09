package com.habitrain.core.client;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.network.PayloadSenders;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Iris 光影包实时监测。
 * <p>
 * 职责：
 * <ul>
 *   <li>通过反射检测当前生效的 Iris 光影包</li>
 *   <li>在客户端 tick 轮询中每隔约 30 秒检查一次光影切换</li>
 *   <li>检测到切换后通过 {@link PayloadSenders} 上报服务端</li>
 * </ul>
 * <p>
 * 状态存储于 {@link ClientStateHolder}，生命周期由 {@link ClientLifecycleHandler} 驱动。
 */
@Environment(EnvType.CLIENT)
public class ShaderMonitor {

    public ShaderMonitor() {
        // 客户端 tick 轮询：检测光影包切换（~30秒检查一次，反射检测昂贵）
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!ClientStateHolder.monitoringShaderPack) return;
            if (!FabricLoader.getInstance().isModLoaded("iris")) return;

            ClientStateHolder.shaderMonitorTick++;
            if (ClientStateHolder.shaderMonitorTick % 600 != 0) return;

            String currentPack = detectCurrentShaderPack();
            if (!currentPack.equals(ClientStateHolder.lastSentShaderPack)) {
                ClientStateHolder.lastSentShaderPack = currentPack;
                HabiTrainCore.LOGGER.info("检测到光影包切换: {}", currentPack.isEmpty() ? "(无)" : currentPack);
                PayloadSenders.sendShaderInfo(currentPack);
            }
        });
    }

    /** 加入服务器时调用：检测当前光影包并上报，启动轮询监测 */
    public void start() {
        ClientStateHolder.lastSentShaderPack = detectCurrentShaderPack();
        if (!ClientStateHolder.lastSentShaderPack.isEmpty()) {
            HabiTrainCore.LOGGER.info("检测到当前光影包: {}", ClientStateHolder.lastSentShaderPack);
        }
        PayloadSenders.sendShaderInfo(ClientStateHolder.lastSentShaderPack);
        ClientStateHolder.monitoringShaderPack = true;
        ClientStateHolder.shaderMonitorTick = 0;
    }

    /** 断开连接时调用：停止轮询监测，清除上报记录 */
    public void stop() {
        ClientStateHolder.monitoringShaderPack = false;
        ClientStateHolder.lastSentShaderPack = "";
    }

    /**
     * 检测当前实际生效的 Iris 光影包名称。
     * 通过反射访问 Iris 内部配置，不强制依赖 Iris 模组。
     *
     * ★ 注意：Iris 关闭光影后，shaderPackName 字段仍保留上次选的光影包名，
     *   所以必须同时检查 areShadersEnabled()，关闭状态视为无光影包。
     *
     * @return 光影包名称（文件夹名或 zip 文件名），空字符串表示无光影包或 Iris 未安装
     */
    private static String detectCurrentShaderPack() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) {
            return "";
        }
        try {
            if (ClientStateHolder.cachedIrisClass == null) {
                ClientStateHolder.cachedIrisClass = Class.forName("net.irisshaders.iris.Iris");
            }
            Class<?> irisClass = ClientStateHolder.cachedIrisClass;

            // 缓存 Method 对象 (S9-012)，避免每 30 秒调用 getMethod
            if (ClientStateHolder.getIrisConfigMethod == null) {
                ClientStateHolder.getIrisConfigMethod = irisClass.getMethod("getIrisConfig");
            }
            Object irisConfig = ClientStateHolder.getIrisConfigMethod.invoke(null);

            Class<?> configClass = irisConfig.getClass();

            // ★ 关键：先检查光影是否实际启用（玩家可能关闭了光影）
            if (ClientStateHolder.areShadersEnabledMethod == null) {
                ClientStateHolder.areShadersEnabledMethod = configClass.getMethod("areShadersEnabled");
            }
            boolean shadersEnabled = (boolean) ClientStateHolder.areShadersEnabledMethod.invoke(irisConfig);
            if (!shadersEnabled) {
                return "";
            }

            // 获取光影包名称
            if (ClientStateHolder.getShaderPackNameMethod == null) {
                ClientStateHolder.getShaderPackNameMethod = configClass.getMethod("getShaderPackName");
            }
            Optional<String> packName = (Optional<String>) ClientStateHolder.getShaderPackNameMethod.invoke(irisConfig);
            return packName.orElse("");
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("无法通过反射检测 Iris 光影包", e);
            return "";
        }
    }
}
