package com.habitrain.core.client.util;

import net.exmo.sre.subtitle.SubtitleS2CPayload;
import net.exmo.sre.subtitle.client.SubtitleHUD;
import net.minecraft.network.chat.Component;

/**
 * 客户端侧字幕提示辅助：直接调用 SRE 的 {@link SubtitleHUD} 入队，
 * 用于客户端自发提示（无法走服务端 {@code SubtitleNotifier} 的场景，例如按键反馈）。
 *
 * 与服务端 {@code com.habitrain.core.util.SubtitleNotifier} 对应，避免依赖被屏蔽的聊天栏。
 */
public final class ClientSubtitleNotifier {
    private static final int DEFAULT_DURATION = 60;
    private static final int DEFAULT_COLOR = 0xFFFFFFFF;

    private ClientSubtitleNotifier() {
    }

    public static void sendTop(Component mainText, Component subText) {
        sendTop(mainText, subText, DEFAULT_DURATION);
    }

    public static void sendTop(Component mainText, Component subText, int durationTicks) {
        sendTop(mainText, subText, durationTicks, DEFAULT_COLOR);
    }

    public static void sendTop(Component mainText, Component subText, int durationTicks, int color) {
        SubtitleHUD.INSTANCE.enqueueFromPacket(
                mainText == null ? Component.empty() : mainText,
                subText == null ? Component.empty() : subText,
                durationTicks,
                color,
                false,
                SubtitleS2CPayload.POS_TOP
        );
    }
}