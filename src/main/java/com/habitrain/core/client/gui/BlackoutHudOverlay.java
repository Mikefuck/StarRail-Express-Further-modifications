package com.habitrain.core.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 停电模式 — 顶部 HUD 覆盖层。
 * 显示: 模式名称、总时间、停电倒计时、进度条
 * <p>
 * <strong>注意：</strong>此类使用全局 static 变量，不支持多世界。
 * 当前设计假设同一时刻只有一个停电模式游戏运行，
 * 若需多 world/多实例支持，需改为实例化设计。
 *
 * 由客户端网络接收器通过 updateTime() 更新数据,
 * 在 HudRenderCallback 中调用 render()。
 */
public class BlackoutHudOverlay {

    private static int totalTimeRemaining = 300;
    private static int blackoutCountdown = 120;
    private static boolean blackoutActive = false;
    private static boolean showHud = false;
    private static boolean blackoutModeActive = false;
    private static boolean votingOpen = false;
    private static int currentPhase = 0;  // 0=NORMAL, 1=FIRST_BLACKOUT, 2=MAINTENANCE, 3=SECOND_BLACKOUT

    public static void updateTime(int total, int cd, boolean active, int phase) {
        totalTimeRemaining = total;
        blackoutCountdown = cd;
        blackoutActive = active;
        currentPhase = phase;
        blackoutModeActive = true;
        showHud = true;
    }

    public static void setBlackoutModeActive(boolean v) { blackoutModeActive = v; }
    public static void setVotingOpen(boolean v) { votingOpen = v; }
    public static boolean isBlackoutModeActive() { return blackoutModeActive; }
    public static boolean isVotingOpen() { return votingOpen; }

    public static void setVisible(boolean visible) { showHud = visible; }

    /**
     * 重置所有静态状态为默认值。
     * 在断开连接或游戏结束时调用，确保 HUD 不会残留。
     */
    public static void reset() {
        totalTimeRemaining = 300;
        blackoutCountdown = 120;
        blackoutActive = false;
        showHud = false;
        blackoutModeActive = false;
        votingOpen = false;
        currentPhase = 0;
    }

    /**
     * 在 HUD 渲染时调用 (注册到 HudRenderCallback)
     */
    public static void render(GuiGraphics g) {
        if (!showHud) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Font font = mc.font;
        int width = mc.getWindow().getGuiScaledWidth();
        int barW = 200;
        int barX = (width - barW) / 2;
        int barY = 10;
        int barH = 6;

        // 顶部信息行
        String timeStr = formatTime(totalTimeRemaining);
        String cdStr;
        if (blackoutActive) {
            cdStr = "§c⚡ 停电中";
        } else if (currentPhase == 2) {  // MAINTENANCE
            cdStr = "§b维护: §e" + formatTime(blackoutCountdown);
        } else if (currentPhase == 0) {  // NORMAL
            cdStr = "§7停电: §e" + formatTime(blackoutCountdown);
        } else {
            cdStr = "§7停电: §e" + formatTime(blackoutCountdown);
        }
        String title = "§6⚡ 停电模式 §f剩余: §l" + timeStr + "§r  " + cdStr;

        g.drawString(font, Component.literal(title),
                (width - font.width(title)) / 2, 0, 0, false);

        // 进度条背景
        g.fill(barX, barY, barX + barW, barY + barH, 0x88333333);
        // 已过时间 (灰色)
        int elapsed = 300 - totalTimeRemaining;
        int filledW = (int) ((float) elapsed / 300 * barW);
        if (filledW > 0) {
            g.fill(barX, barY, barX + Math.min(filledW, barW), barY + barH, 0xFF555555);
        }
        // 剩余时间 (绿色，停电中红色)
        int remainingW = barW - filledW;
        if (remainingW > 0) {
            int color = blackoutActive ? 0xFFFF4444 : 0xFF44AA44;
            g.fill(barX + filledW, barY, barX + barW, barY + barH, color);
        }
        // 停电倒计时标记 (红色竖线)
        if (!blackoutActive && blackoutCountdown > 0) {
            int markerX = barX + (int) ((float) (300 - blackoutCountdown) / 300 * barW);
            g.fill(markerX, barY - 2, markerX + 2, barY + barH + 2, 0xFFFF4444);
        }
        // 60s 预警线 (黄色)
        int warningX = barX + (int) ((float) (300 - 60) / 300 * barW);
        g.fill(warningX, barY - 1, warningX + 1, barY + barH + 1, 0xFFFFFF00);
    }

    private static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }
}
