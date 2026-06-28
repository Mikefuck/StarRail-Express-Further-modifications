package com.habitrain.core.client;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.network.ConfigUpdatePayload;
import com.habitrain.core.network.ShaderConfigPayload;
import com.habitrain.core.network.ShaderInfoPayload;
import com.habitrain.core.network.TaskConfigPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.util.Optional;

/**
 * 哈比列车任务API - 客户端初始化
 */
@Environment(EnvType.CLIENT)
public class HabiTrainCoreClient implements ClientModInitializer {

    // ====== Iris 光影包实时监测状态 ======
    /** 上次已上报的光影包名称 */
    private static String lastSentShaderPack = "";
    /** 是否正在监测（加入服务器后启用，断开后停止） */
    private static boolean monitoringShaderPack = false;
    /** tick 计数器，每 20 tick 检查一次（≈1秒） */
    private static int shaderMonitorTick = 0;

    @Override
    public void onInitializeClient() {
        HabiTrainCore.LOGGER.info("哈比列车任务API 客户端初始化完成");

        // =========================================================
        //  注册 S2C 网络接收器
        // =========================================================

        // 1) 接收服务端任务配置同步
        ClientPlayNetworking.registerGlobalReceiver(TaskConfigPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                HabiTrainCore.LOGGER.info("收到服务端任务配置同步 ({} 个任务配置)", payload.getConfigs().size());

                // 使用 applySyncData 应用（抑制 save 回调，防止回环）
                ConfigManager.getInstance().applySyncData(
                        payload.getConfigs(),
                        ConfigManager.getInstance().getDlcProbabilityTarget()
                );
            });
        });

        // 2) 接收服务端活跃自定义任务同步（用于多人模式下透视渲染）
        ClientPlayNetworking.registerGlobalReceiver(ActiveTaskPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                if (payload.isClear()) {
                    HabiTrainCore.LOGGER.info("收到活跃自定义任务清空信号");
                    ActiveTaskCache.clear();
                } else {
                    HabiTrainCore.LOGGER.info("收到活跃自定义任务同步: {}",
                            payload.getTaskFullId());
                    ActiveTaskCache.setActiveTask(payload.getTaskFullId());
                }
            });
        });

        // 3) 接收服务端光影白名单同步（仅更新内存，不触发 save 回调）
        ClientPlayNetworking.registerGlobalReceiver(ShaderConfigPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                HabiTrainCore.LOGGER.info("收到服务端光影白名单同步: {}",
                        payload.isEnabled() ? "启用, " + payload.getWhitelist().size() + "个光影" : "禁用");
                ConfigManager cfg = ConfigManager.getInstance();
                cfg.applyShaderWhitelistSync(payload.isEnabled(), payload.getWhitelist());
            });
        });

        // =========================================================
        //  Iris 光影包实时监测（加入时报告 + 游戏中轮询检测切换）
        // =========================================================

        // 玩家加入服务器 → 报告当前光影包 + 启动监测
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            client.execute(() -> {
                lastSentShaderPack = detectCurrentShaderPack();
                if (!lastSentShaderPack.isEmpty()) {
                    HabiTrainCore.LOGGER.info("检测到当前光影包: {}", lastSentShaderPack);
                }
                // 发送到服务端
                ShaderInfoPayload.sendToServer(lastSentShaderPack);
                // 启动实时监测
                monitoringShaderPack = true;
                shaderMonitorTick = 0;
            });
        });

        // 玩家断开连接 → 停止监测
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            monitoringShaderPack = false;
            lastSentShaderPack = "";
        });

        // 客户端 tick 轮询：检测光影包切换（每秒检查一次）
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!monitoringShaderPack) return;
            if (!FabricLoader.getInstance().isModLoaded("iris")) return;

            shaderMonitorTick++;
            if (shaderMonitorTick % 20 != 0) return; // ~1秒检查一次

            String currentPack = detectCurrentShaderPack();
            if (!currentPack.equals(lastSentShaderPack)) {
                lastSentShaderPack = currentPack;
                HabiTrainCore.LOGGER.info("检测到光影包切换: {}", currentPack.isEmpty() ? "(无)" : currentPack);
                ShaderInfoPayload.sendToServer(currentPack);
            }
        });

        // =========================================================
        //  设置保存回调：配置修改后自动同步到服务端（仅 OP 会生效）
        // =========================================================
        ConfigManager.setOnSaveCallback(() -> {
            // 仅在连接到多人服务器时发送
            var client = Minecraft.getInstance();
            if (client.getConnection() == null) return;

            // ★ 单机模式（集成服务器）：配置已保存在本地，无需同步
            //    client.getSingleplayerServer() != null 表示当前正在运行本地集成服务器
            if (client.getSingleplayerServer() != null) return;

            // 发送当前完整配置到服务端
            // 服务端会校验 OP 权限，非 OP 的请求会被拒绝
            String configJson = ConfigManager.getInstance().toJsonString();
            ConfigUpdatePayload.sendToServer(configJson);
        });
    }

    /**
     * 检测当前实际生效的 Iris 光影包名称
     * 通过反射访问 Iris 内部配置，不强制依赖 Iris 模组
     *
     * ★ 注意：Iris关闭光影后，shaderPackName 字段仍保留上次选的光影包名，
     *   所以必须同时检查 areShadersEnabled()，关闭状态视为无光影包。
     *
     * @return 光影包名称（文件夹名或zip文件名），空字符串表示无光影包或 Iris 未安装
     */
    private static String detectCurrentShaderPack() {
        // 检查 Iris 模组是否已加载
        if (!FabricLoader.getInstance().isModLoaded("iris")) {
            return "";
        }

        try {
            // 使用反射避免对 Iris 的编译期依赖
            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            Object irisConfig = irisClass.getMethod("getIrisConfig").invoke(null);

            // ★ 关键：先检查光影是否实际启用（玩家可能关闭了光影）
            boolean shadersEnabled = (boolean) irisConfig.getClass()
                    .getMethod("areShadersEnabled").invoke(irisConfig);
            if (!shadersEnabled) {
                return ""; // 光影已关闭，视为无光影包
            }

            // 获取光影包名称
            Optional<String> packName = (Optional<String>) irisConfig.getClass()
                    .getMethod("getShaderPackName").invoke(irisConfig);
            return packName.orElse("");
        } catch (Exception e) {
            HabiTrainCore.LOGGER.warn("无法通过反射检测 Iris 光影包", e);
            return "";
        }
    }
}
