package com.habitrain.core.client.gui;

import com.habitrain.core.client.BlackoutKeyHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Blackout mode top HUD overlay.
 */
public class BlackoutHudOverlay {

    private static int totalTimeRemaining = 300;
    private static int blackoutCountdown = 120;
    private static boolean blackoutActive = false;
    private static boolean showHud = false;
    private static boolean blackoutModeActive = false;
    private static int currentPhase = 0;  // 0=NORMAL, 1=FIRST_BLACKOUT, 2=MAINTENANCE, 3=SECOND_BLACKOUT
    // 当前对局的有效总时长（进度条分母）。服务端 BlackoutTimerSystem.TOTAL_TIME 固定为 300，
    // 但 addTime 可使剩余时间超过 300，因此动态追踪已知的最大值，避免进度条比例失真。
    private static int totalDuration = 300;
    private static final int TIME_WARNING_SECONDS = 60;

    public static void updateTime(int total, int cd, boolean active, int phase) {
        totalTimeRemaining = total;
        if (total > totalDuration) {
            totalDuration = total;
        }
        blackoutCountdown = cd;
        blackoutActive = active;
        currentPhase = phase;
        blackoutModeActive = true;
        showHud = true;
    }

    public static void setBlackoutModeActive(boolean v) { blackoutModeActive = v; }
    public static boolean isBlackoutModeActive() { return blackoutModeActive; }

    public static void setVisible(boolean visible) { showHud = visible; }

    public static void reset() {
        totalTimeRemaining = 300;
        blackoutCountdown = 120;
        blackoutActive = false;
        showHud = false;
        blackoutModeActive = false;
        currentPhase = 0;
        totalDuration = 300;
    }

    public static void render(GuiGraphics g) {
        if (!showHud) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        int width = mc.getWindow().getGuiScaledWidth();
        int barW = 220;
        int barX = (width - barW) / 2;
        int barY = 11;
        int barH = 2;

        g.fill(barX, barY - 1, barX + barW, barY + barH + 1, 0x332A3642);
        g.fill(barX, barY, barX + barW, barY + barH, 0x88262E38);

        int elapsed = totalDuration - totalTimeRemaining;
        int filledW = totalDuration > 0 ? (int) ((float) elapsed / totalDuration * barW) : 0;
        filledW = Math.max(0, Math.min(filledW, barW));
        if (filledW > 0) {
            g.fill(barX, barY, barX + filledW, barY + barH, 0xFF596573);
        }

        int remainingW = barW - filledW;
        if (remainingW > 0) {
            int color = blackoutActive ? 0xFFFFD84B : 0xFF4AC06A;
            g.fill(barX + filledW, barY, barX + barW, barY + barH, color);
        }

        if (!blackoutActive && blackoutCountdown > 0) {
            int markerX = barX + (int) ((float) (totalDuration - blackoutCountdown) / totalDuration * barW);
            g.fill(markerX, barY - 2, markerX + 1, barY + barH + 2, 0xFFFF6A6A);
        }

        int warningX = barX + (int) ((float) (totalDuration - TIME_WARNING_SECONDS) / totalDuration * barW);
        g.fill(warningX, barY - 1, warningX + 1, barY + barH + 1, 0xFFFFFF00);

        // 进度条右侧：游戏倒计时 + 停电/维护倒计时（仅按住 shift 时显示）
        Font font = mc.font;
        if (mc.player.isShiftKeyDown()) {
            int textX = barX + barW + 6;
            int textY = barY - 4;

            String gameText = "§f对局剩余时间 " + formatTime(totalTimeRemaining);
            g.drawString(font, gameText, textX, textY, 0xFFFFFFFF, false);

            if (blackoutActive) {
                String blackoutText = "§c停电中";
                g.drawString(font, blackoutText, textX, textY + 10, 0xFFD84B4B, false);
            } else if (blackoutCountdown > 0) {
                String cdLabel = currentPhase == 2 ? "§e剩余供电时间" : "§e停电";
                String cdText = cdLabel + " " + formatTime(blackoutCountdown);
                g.drawString(font, cdText, textX, textY + 10, 0xFFFFD84B, false);
            }
        }

        // 警长投票进行中时，在 HUD 下方显示带实际绑定按键的提示。
        // 按键名取自客户端 KeyMapping，玩家改键后提示自动跟随。
        if (BlackoutSheriffVoteState.isActive()) {
            KeyMapping voteKey = BlackoutKeyHandler.getOpenVoteKey();
            Component keyName = voteKey != null ? voteKey.getTranslatedKeyMessage() : Component.literal("V");
            Component hint = Component.literal("§e按 §f")
                    .append(keyName)
                    .append("§e 打开警长投票 §7(" + BlackoutSheriffVoteState.getRemainingSeconds() + "s)");
            int hintWidth = font.width(hint);
            int hintX = (width - hintWidth) / 2;
            int hintY = barY + 12;
            g.drawString(font, hint, hintX, hintY, 0xFFFFFFFF, false);
        }
    }

    private static String formatTime(int seconds) {
        if (seconds < 0) seconds = 0;
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }
}
