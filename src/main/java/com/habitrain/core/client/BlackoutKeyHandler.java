package com.habitrain.core.client;

import com.habitrain.core.client.gui.BlackoutHudOverlay;
import com.habitrain.core.client.gui.BlackoutRoleIntroduceScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class BlackoutKeyHandler {

    private static final KeyMapping ROLE_INTRO_KEY = new KeyMapping(
            "key.habitrain.blackout.role_intro",
            GLFW.GLFW_KEY_U,
            "category.habitrain.blackout"
    );

    private static boolean registered = false;

    public static void register() {
        if (registered) return;
        registered = true;

        KeyBindingHelper.registerKeyBinding(ROLE_INTRO_KEY);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // U 键 — 打开角色介绍（仅黑灯模式激活时）
            while (ROLE_INTRO_KEY.consumeClick()) {
                if (client.player != null && client.screen == null
                        && BlackoutHudOverlay.isBlackoutModeActive()) {
                    client.setScreen(new BlackoutRoleIntroduceScreen());
                }
            }
        });
    }
}
