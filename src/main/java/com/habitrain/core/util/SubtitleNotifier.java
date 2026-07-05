package com.habitrain.core.util;

import net.exmo.sre.subtitle.SubtitleCommand;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Small wrapper around the DLC subtitle GUI so API code can send prompts
 * without depending on chat messages.
 */
public final class SubtitleNotifier {
    private static final int DEFAULT_DURATION = 60;

    private SubtitleNotifier() {
    }

    public static void sendTop(ServerPlayer player, Component mainText) {
        sendTop(player, mainText, Component.empty(), DEFAULT_DURATION);
    }

    public static void sendTop(ServerPlayer player, Component mainText, Component subText) {
        sendTop(player, mainText, subText, DEFAULT_DURATION);
    }

    public static void sendTop(ServerPlayer player, Component mainText, Component subText, int durationTicks) {
        if (player == null) {
            return;
        }
        SubtitleCommand.sendToPlayerTop(player, mainText, subText, durationTicks);
    }
}
