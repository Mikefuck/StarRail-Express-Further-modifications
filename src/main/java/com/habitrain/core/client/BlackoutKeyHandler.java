package com.habitrain.core.client;

import com.habitrain.core.client.gui.BlackoutHudOverlay;

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
            // P 键 - only when blackout mode active
            while (VOTE_KEY.consumeClick()) {
                if (client.player != null && client.screen == null
                        && BlackoutHudOverlay.isBlackoutModeActive()
                        && BlackoutHudOverlay.isVotingOpen()) {
                    client.setScreen(new VoteScreen(true));
                } else if (client.player != null && client.screen == null
                        && BlackoutHudOverlay.isBlackoutModeActive()) {
                    client.setScreen(new VoteScreen(false));  // show "不在投票时间"
                }
            }

            // U 键 - only when blackout mode active, opens SRE RoleIntroduceScreen
            while (ROLE_INTRO_KEY.consumeClick()) {
                if (client.player != null && client.screen == null
                        && BlackoutHudOverlay.isBlackoutModeActive()) {
                    try {
                        Class<?> screenClass = Class.forName("org.agmas.noellesroles.client.screen.RoleIntroduceScreen");
                        net.minecraft.client.gui.screens.Screen screen =
                            (net.minecraft.client.gui.screens.Screen) screenClass.getConstructor().newInstance();
                        client.setScreen(screen);
                    } catch (Exception e) {
                        System.out.println("Failed to open SRE RoleIntroduceScreen: " + e.getMessage());
                    }
                }
            }
        });
    }
}
