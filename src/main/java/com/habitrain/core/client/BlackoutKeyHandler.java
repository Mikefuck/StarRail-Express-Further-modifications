package com.habitrain.core.client;

import com.habitrain.core.client.gui.BlackoutVoteScreen;
import com.habitrain.core.client.gui.BlackoutVoteState;
import com.habitrain.core.client.gui.OptionVoteScreen;
import com.habitrain.core.client.gui.OptionVoteState;
import com.habitrain.core.client.gui.VoteLaunchSession;
import com.habitrain.core.client.gui.VoteLaunchTransitionScreen;
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
     * 供投票/加载 Screen 在 GUI 自己的按键回调里匹配「打开/隐藏投票」键。
     *
     * <p>不能只依赖 END_CLIENT_TICK 的 {@link KeyMapping#consumeClick()}：Screen 打开时，
     * 键盘事件会先交给 Screen，而开局加载 Screen 还会主动吞掉输入。若 Screen 不直接
     * 用 {@link KeyMapping#matches(int, int)} 匹配，界面提示的默认 V 键就可能完全不触发
     * hide，继而让判定点 A 误走「未隐藏」加载分支。</p>
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
                if (EliminatedRestAreaKeyHandler.handleVoteKeyPress(client)) {
                    continue;
                }
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

        // 开局加载期：投票已结束，但 Session 仍 active。V 键 hide / 重开加载页。
        // 重开只改变可见性，不得 clear sticky 隐藏意图——否则判定点 A 会误走可见路径
        // 继续显示「开局加载中」，而不是左→右「对局开始」补盖。
        if (VoteLaunchSession.isActive()) {
            if (client.screen instanceof VoteLaunchTransitionScreen open) {
                if (VoteLaunchSession.canHide()) {
                    open.hideByUser();
                }
                return;
            }
            if (VoteLaunchSession.canReopenLoading()) {
                client.setScreen(VoteLaunchTransitionScreen.reopenLoading(client.screen));
                return;
            }
            // 已锁定 hide（判定点 A 后）且屏未开：不操作，等待服务端强制补盖/交还
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
