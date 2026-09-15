package com.habitrain.core.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

/**
 * 客户端 HUD 与快捷键注册。
 * <p>
 * 将快捷键绑定注册与 HUD 叠加层渲染注册收拢到此类。
 */
@Environment(EnvType.CLIENT)
public class HudRegistrar {

    public HudRegistrar() {
        // 投票快捷键
        VoteKeyHandler.register();

        // HUD 渲染
        HudRenderCallback.EVENT.register((g, tickDelta) -> {
            com.habitrain.core.scene.client.SceneViewDistanceWarningHud.render(g);
        });
    }
}
