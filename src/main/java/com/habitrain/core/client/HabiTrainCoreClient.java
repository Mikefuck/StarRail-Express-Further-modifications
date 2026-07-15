package com.habitrain.core.client;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 哈比列车任务API - 客户端初始化（组装类）。
 * <p>
 * 实例化并注册所有客户端 module，自身不承载业务逻辑：
 * <ul>
 *   <li>{@link NetworkReceiverRegistrar} - S2C 网络接收器注册</li>
 *   <li>{@link ShaderMonitor} - Iris 光影包实时监测</li>
 *   <li>{@link HudRegistrar} - HUD 叠加层与快捷键注册</li>
 *   <li>{@link ClientLifecycleHandler} - JOIN/DISCONNECT/游戏结束/配置保存生命周期</li>
 * </ul>
 * <p>
 * 静态可变状态存放于 {@link ClientStateHolder}（光影监测）与
 * {@link com.habitrain.core.client.gui.ClientBlackoutState}（停电模式）。
 */
@Environment(EnvType.CLIENT)
public class HabiTrainCoreClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        HabiTrainCore.LOGGER.info("哈比列车任务API 客户端初始化完成");

        // S2C 网络接收器注册
        new NetworkReceiverRegistrar();

        // Iris 光影包实时监测（轮询检测切换）
        ShaderMonitor shaderMonitor = new ShaderMonitor();

        // HUD 叠加层 + 快捷键注册
        new HudRegistrar();

        // 投稿职业客户端钩子（替罪羊本能伪装等）
        HabiRoleClientHooks.init();

        // 生命周期事件处理（JOIN / DISCONNECT / 游戏结束 / 配置保存回调）
        new ClientLifecycleHandler(shaderMonitor);

        // 填充警长/杀手商店目录（按角色能力绑定，否则 ROLE_SHOPS 为空，商店无商品可买）
        com.habitrain.core.game.blackout.BlackoutShopService.bootstrapDefaults();

        // 注：字幕报幕客户端接收由 SRE 4.3.0 原生注册（SREClient），
        //     SubtitleHUDPrefixFixMixin 仍拦截 enqueueFromPacket 做任务标题归一化。
    }
}
