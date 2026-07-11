package com.habitrain.core.client;

import com.habitrain.core.client.gui.BlackoutSheriffVoteScreen;
import com.habitrain.core.client.gui.BlackoutSheriffVoteState;
import com.habitrain.core.client.gui.BlackoutVoteScreen;
import com.habitrain.core.client.gui.BlackoutVoteState;
import com.habitrain.core.client.gui.OptionVoteScreen;
import com.habitrain.core.client.gui.OptionVoteState;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class BlackoutKeyHandler {
    private static boolean registered = false;
    private static KeyMapping openVoteKey;

    public static KeyMapping getOpenVoteKey() {
        return openVoteKey;
    }

    /**
     * Client-bound key display for HUD tips. Never hardcodes "V" — if unregistered, "?".
     */
    public static Component getBoundKeyDisplay() {
        KeyMapping key = openVoteKey;
        if (key == null) {
            return Component.literal("?");
        }
        return key.getTranslatedKeyMessage();
    }

    public static void register() {
        if (registered) return;
        registered = true;

        openVoteKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.habitrain_core.open_vote",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "key.categories.habitrain_core"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openVoteKey.consumeClick()) {
                openVote(client);
            }
        });
    }

    private static void openVote(Minecraft client) {
        if (client.player == null) return;

        // Highest priority: generic option vote (mode/map lobby vote)
        if (OptionVoteState.isActive()) {
            if (client.screen instanceof OptionVoteScreen) return;
            client.setScreen(new OptionVoteScreen(client.screen));
            return;
        }

        // Sheriff vote takes priority over exile while active.
        if (BlackoutSheriffVoteState.isActive()) {
            if (client.screen instanceof BlackoutSheriffVoteScreen) return;
            client.setScreen(new BlackoutSheriffVoteScreen(client.screen));
            return;
        }

        if (!BlackoutVoteState.isActive()) {
            com.habitrain.core.client.util.ClientSubtitleNotifier.sendTop(
                    Component.literal("§e投票"),
                    Component.literal("§e当前没有进行中的投票。"),
                    60);
            return;
        }

        if (client.screen instanceof BlackoutVoteScreen) return;
        client.setScreen(new BlackoutVoteScreen(client.screen));
    }
}
