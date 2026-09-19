package com.habitrain.core.scene.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * 场景子系统的客户端 tick 驱动。
 *
 * <p>场景渲染完全由 {@code WorldRenderEvents} 驱动，本身没有 tick 钩子；而分片请求的
 * 超时判定与退避重试需要一个与帧率无关的固定节奏，因此单独注册一个
 * {@code END_CLIENT_TICK} 回调。注册是幂等的，重复调用 {@link #init()} 不会叠加回调。</p>
 */
@Environment(EnvType.CLIENT)
public final class SceneClientTicker {
    private static boolean registered;

    private SceneClientTicker() {}

    public static synchronized void init() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null) return;
            // 兜底推进网格构建：主驱动是每帧一次的渲染钩子，但世界长时间不渲染时构建不能就此
            // 停住。配额是共享令牌桶，多一个调用点不会让总预算变多。
            // 刻意放在关卡判空之前：关卡已经卸载时渲染钩子不再触发，若此时还有构建在队列里，
            // 它的 future 就永远不会完成——构建步骤自己会发现关卡变了并主动放弃。
            SceneBuildScheduler.getInstance().pump();
            if (client.level == null) return;
            SceneAssetCache.getInstance().tick();
            // API 场景实例的车外音/微震按 tick 对齐（几何是每帧确定性计算的，不需要 tick）。
            SceneRenderRuntime.getInstance().tickDynamicInstances();
        });
    }
}
