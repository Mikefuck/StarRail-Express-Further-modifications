package com.habitrain.core.client;

import com.habitrain.core.client.gui.OptionVoteScreen;
import com.habitrain.core.client.gui.OptionVoteState;
import com.habitrain.core.client.gui.VoteLaunchSession;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class VoteKeyHandler {
    private static boolean registered = false;
    private static KeyMapping openVoteKey;

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

    /**
     * 供投票 Screen 在 GUI 自己的按键回调里匹配「打开/隐藏投票」键。
     *
     * <p>不能只依赖 END_CLIENT_TICK 的 {@link KeyMapping#consumeClick()}：Screen 打开时，
     * 键盘事件会先交给 Screen，若 Screen 不直接用 {@link KeyMapping#matches(int, int)} 匹配，
     * 界面提示的默认 V 键就可能完全不触发隐藏。</p>
     */
    public static boolean matchesOpenVoteKey(int keyCode, int scanCode) {
        KeyMapping key = openVoteKey;
        return key != null && key.matches(keyCode, scanCode);
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

        // Highest priority: generic option vote (mode/map lobby vote).
        // 复用已注册的 open_vote 键：已打开则隐藏（本轮不再自动弹），已隐藏则重开。
        if (OptionVoteState.isActive()) {
            if (client.screen instanceof OptionVoteScreen open) {
                open.hideByUser();
                return;
            }
            OptionVoteState.clearUiHiddenByUser();
            client.setScreen(new OptionVoteScreen(client.screen));
            return;
        }

        // 投票已结束、正在开局（顶部进度牌或全屏转场）：没有可打开的投票，也不弹「没有投票」提示。
        if (VoteLaunchSession.isActive()) {
            return;
        }

        com.habitrain.core.client.util.ClientSubtitleNotifier.sendTop(
                Component.literal("§e投票"),
                Component.literal("§e当前没有进行中的投票。"), 60);

    }
}
