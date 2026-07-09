package com.habitrain.core.client.gui;

import com.habitrain.core.client.BlackoutKeyHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Blackout mode top HUD overlay.
 *
 * <p>色彩常量说明：
 * <ul>
 *   <li>{@link #COLOR_BG_PROGRESS_BAR_BORDER} — 进度条外框背景（边框阴影）</li>
 *   <li>{@link #COLOR_BG_PROGRESS_BAR} — 进度条内背景（未填充部分底色）</li>
 *   <li>{@link #COLOR_SEGMENT_ELAPSED} — 已过时间段的进度条填充色</li>
 *   <li>{@link #COLOR_SEGMENT_BLACKOUT} — 停电时段进度条色（金色）</li>
 *   <li>{@link #COLOR_SEGMENT_MAINTENANCE} — 维护时段进度条色（绿色）</li>
 *   <li>{@link #COLOR_MARKER_BLACKOUT_START} — 停电开始标记线色（红色）</li>
 *   <li>{@link #COLOR_MARKER_WARNING} — 警告标记线色（黄色）</li>
 * </ul>
 */
public class BlackoutHudOverlay {

    // ========================================================================
    //  命名色彩常量
    // ========================================================================
    private static final int COLOR_BG_PROGRESS_BAR_BORDER = 0x332A3642;
    private static final int COLOR_BG_PROGRESS_BAR = 0x88262E38;
    private static final int COLOR_SEGMENT_ELAPSED = 0xFF596573;
    private static final int COLOR_SEGMENT_BLACKOUT = 0xFFFFD84B;
    private static final int COLOR_SEGMENT_MAINTENANCE = 0xFF4AC06A;
    private static final int COLOR_MARKER_BLACKOUT_START = 0xFFFF6A6A;
    private static final int COLOR_MARKER_WARNING = 0xFFFFFF00;
    private static final int COLOR_TEXT_DEFAULT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_BLACKOUT = 0xFFD84B4B;
    private static final int COLOR_TEXT_MAINTENANCE = 0xFFFFD84B;

    // ========================================================================
    //  阶段常量
    // ========================================================================
    /** 阶段：未开始 / 初始 */
    private static final int PHASE_INITIAL = 0;
    /** 阶段：停电中 */
    private static final int PHASE_BLACKOUT = 1;
    /** 阶段：维护（供电已恢复） */
    private static final int PHASE_MAINTENANCE = 2;

    // ========================================================================
    //  HUD 布局常量
    // ========================================================================
    private static final int BAR_WIDTH = 220;
    private static final int BAR_Y = 11;
    private static final int BAR_HEIGHT = 2;

    // ========================================================================
    //  状态字段
    // ========================================================================
    private static int totalTimeRemaining = 300;
    private static boolean blackoutActive = false;
    private static boolean showHud = false;
    private static int currentPhase = 0;
    private static int totalDuration = 300;
    private static final int TIME_WARNING_SECONDS = 60;
    private static long cachedEndTimeTick = -1;

    public static void updateTime(int total, long endTimeTick, boolean active, int phase) {
        totalTimeRemaining = total;
        totalDuration = total;
        cachedEndTimeTick = endTimeTick;
        blackoutActive = active;
        currentPhase = phase;
        ClientBlackoutState.setBlackoutModeActive(true);
        showHud = true;
    }

    /** @deprecated Use {@link ClientBlackoutState#setBlackoutModeActive} directly. */
    @Deprecated
    public static void setBlackoutModeActive(boolean v) {
        ClientBlackoutState.setBlackoutModeActive(v);
    }

    /** @deprecated Use {@link ClientBlackoutState#isBlackoutModeActive} directly. */
    @Deprecated
    public static boolean isBlackoutModeActive() {
        return ClientBlackoutState.isBlackoutModeActive();
    }

    private static int getLocalCountdown() {
        var level = Minecraft.getInstance().level;
        if (level == null) return 0;
        long now = level.getGameTime();
        return (int) Math.max(0, cachedEndTimeTick - now);
    }

    public static void reset() {
        totalTimeRemaining = 300;
        blackoutActive = false;
        showHud = false;
        ClientBlackoutState.setBlackoutModeActive(false);
        currentPhase = 0;
        totalDuration = 300;
        cachedEndTimeTick = -1;
    }

    public static void render(GuiGraphics g) {
        if (!showHud) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        int width = mc.getWindow().getGuiScaledWidth();
        int barX = (width - BAR_WIDTH) / 2;

        // S11-001: Cache getLocalCountdown() once per frame
        long localCountdown = getLocalCountdown();

        g.fill(barX, BAR_Y - 1, barX + BAR_WIDTH, BAR_Y + BAR_HEIGHT + 1, COLOR_BG_PROGRESS_BAR_BORDER);
        g.fill(barX, BAR_Y, barX + BAR_WIDTH, BAR_Y + BAR_HEIGHT, COLOR_BG_PROGRESS_BAR);

        int elapsed = totalDuration - totalTimeRemaining;
        int filledW = totalDuration > 0 ? (int) ((float) elapsed / totalDuration * BAR_WIDTH) : 0;
        filledW = Math.max(0, Math.min(filledW, BAR_WIDTH));
        if (filledW > 0) {
            g.fill(barX, BAR_Y, barX + filledW, BAR_Y + BAR_HEIGHT, COLOR_SEGMENT_ELAPSED);
        }

        int remainingW = BAR_WIDTH - filledW;
        if (remainingW > 0) {
            int color = blackoutActive ? COLOR_SEGMENT_BLACKOUT : COLOR_SEGMENT_MAINTENANCE;
            g.fill(barX + filledW, BAR_Y, barX + BAR_WIDTH, BAR_Y + BAR_HEIGHT, color);
        }

        if (!blackoutActive && localCountdown >= 0) {
            int markerX = barX + (int) ((float) (totalDuration - localCountdown) / totalDuration * BAR_WIDTH);
            g.fill(markerX, BAR_Y - 2, markerX + 1, BAR_Y + BAR_HEIGHT + 2, COLOR_MARKER_BLACKOUT_START);
        }

        int warningX = barX + (int) ((float) (totalDuration - TIME_WARNING_SECONDS) / totalDuration * BAR_WIDTH);
        g.fill(warningX, BAR_Y - 1, warningX + 1, BAR_Y + BAR_HEIGHT + 1, COLOR_MARKER_WARNING);

        // 进度条右侧：游戏倒计时 + 停电/维护倒计时（仅按住 shift 时显示）
        Font font = mc.font;
        if (mc.player.isShiftKeyDown()) {
            int textX = barX + BAR_WIDTH + 6;
            int textY = BAR_Y - 4;

            String gameText = "§f对局剩余时间 " + formatTime(totalTimeRemaining);
            g.drawString(font, gameText, textX, textY, COLOR_TEXT_DEFAULT, false);

            if (blackoutActive) {
                String blackoutText = "§c停电中";
                g.drawString(font, blackoutText, textX, textY + 10, COLOR_TEXT_BLACKOUT, false);
            } else if (localCountdown >= 0) {
                String cdLabel = currentPhase == PHASE_MAINTENANCE ? "§e剩余供电时间" : "§e停电";
                String cdText = cdLabel + " " + formatTime((int) localCountdown);
                g.drawString(font, cdText, textX, textY + 10, COLOR_TEXT_MAINTENANCE, false);
            }
        }

        // 警长投票进行中时，在 HUD 下方显示带实际绑定按键的提示。
        if (BlackoutSheriffVoteState.isActive()) {
            KeyMapping voteKey = BlackoutKeyHandler.getOpenVoteKey();
            Component keyName = voteKey != null ? voteKey.getTranslatedKeyMessage() : Component.literal("V");
            Component hint = Component.literal("§e按 §f")
                    .append(keyName)
                    .append("§e 打开警长投票 §7(" + BlackoutSheriffVoteState.getRemainingSeconds() + "s)");
            int hintWidth = font.width(hint);
            int hintX = (width - hintWidth) / 2;
            int hintY = BAR_Y + 12;
            g.drawString(font, hint, hintX, hintY, COLOR_TEXT_DEFAULT, false);
        }
    }

    private static String formatTime(int seconds) {
        if (seconds < 0) seconds = 0;
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }

    private BlackoutHudOverlay() {
    }
}
