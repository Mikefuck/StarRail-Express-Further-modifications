package com.habitrain.core.client;

import com.habitrain.core.client.gui.BlackoutRoleIntroduceScreen;
import com.habitrain.core.client.gui.VoteScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * 停电模式 — 快捷键注册
 * P = 打开投票 GUI
 * U = 打开角色介绍 GUI
 */
public class BlackoutKeyHandler {

    private static final KeyMapping VOTE_KEY = new KeyMapping(
            "key.habitrain.blackout.vote",
            GLFW.GLFW_KEY_P,
            "category.habitrain.blackout"
    );

    private static final KeyMapping ROLE_INTRO_KEY = new KeyMapping(
            "key.habitrain.blackout.role_intro",
            GLFW.GLFW_KEY_U,
            "category.habitrain.blackout"
    );

    private static boolean registered = false;

    public static void register() {
        if (registered) return;
        registered = true;

        KeyBindingHelper.registerKeyBinding(VOTE_KEY);
        KeyBindingHelper.registerKeyBinding(ROLE_INTRO_KEY);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // P 键：投票 GUI
            while (VOTE_KEY.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    client.setScreen(new VoteScreen());
                }
            }

            // U 键：角色介绍 GUI（仅在 Blackout 模式中）
            while (ROLE_INTRO_KEY.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    client.setScreen(new BlackoutRoleIntroduceScreen());
                }
            }
        });
    }
}
