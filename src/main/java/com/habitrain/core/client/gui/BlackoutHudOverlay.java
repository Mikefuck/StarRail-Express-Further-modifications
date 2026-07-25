package com.habitrain.core.client.gui;

import com.habitrain.core.client.BlackoutKeyHandler;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.client.SREClient;
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
 *   <li>{@link #COLOR_SEGMENT_ELAPSED_GOLD} — 好人黄条已过段（半透明深黄）</li>
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
    /** 好人黄条：已过段半透明深黄，保持全黄系 */
    private static final int COLOR_SEGMENT_ELAPSED_GOLD = 0x88FFD84B;
    private static final int COLOR_MARKER_BLACKOUT_START = 0xFFFF6A6A;
    private static final int COLOR_MARKER_WARNING = 0xFFFFFF00;
    private static final int COLOR_TEXT_DEFAULT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_BLACKOUT = 0xFFD84B4B;
    private static final int COLOR_TEXT_MAINTENANCE = 0xFFFFD84B;

    // ========================================================================
    //  阶段常量（与 BlackoutTimerSystem.Phase.ordinal 对齐）
    // ========================================================================
    private static final int PHASE_NORMAL = 0;
    private static final int PHASE_FIRST_BLACKOUT = 1;
    private static final int PHASE_MAINTENANCE = 2;
    private static final int PHASE_SECOND_BLACKOUT = 3;

    // 阶段默认 CD 上限（秒），用于好人黄条进度分母
    private static final int DEFAULT_BLACKOUT_CD = 240;
    private static final int DEFAULT_MAINTENANCE_CD = 60;

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
    /** Full match length for progress bar denominator — never overwrite with remaining each packet. */
    private static int totalDuration = 600;
    private static final int TIME_WARNING_SECONDS = 60;
    private static final int DEFAULT_MATCH_DURATION = 600;
    private static long cachedEndTimeTick = -1;
    /** 当前阶段倒计时分母（好人黄条用）；随 phase 切换重置。 */
    private static int countdownDuration = DEFAULT_BLACKOUT_CD;

    public static void updateTime(int total, long endTimeTick, boolean active, int phase) {
        totalTimeRemaining = total;
        // Capture full duration once (or grow if server extends time); never shrink to remaining.
        if (totalDuration <= 0) {
            totalDuration = Math.max(total, DEFAULT_MATCH_DURATION);
        } else if (total > totalDuration) {
            totalDuration = total;
        }
        cachedEndTimeTick = endTimeTick;
        blackoutActive = active;

        if (phase != currentPhase) {
            currentPhase = phase;
            countdownDuration = defaultCountdownForPhase(phase);
        }

        // 用包内剩余秒数抬高分母（延长供电时进度条不倒退）
        int remainingFromPacket = endTimeTick > 0
                ? Math.max(0, (int) ((endTimeTick - (Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.getGameTime() : endTimeTick)) / 20L))
                : 0;
        if (remainingFromPacket > countdownDuration) {
            countdownDuration = remainingFromPacket;
        }

        ClientBlackoutState.setBlackoutModeActive(true);
        showHud = true;
    }

    private static int defaultCountdownForPhase(int phase) {
        return switch (phase) {
            case PHASE_MAINTENANCE -> DEFAULT_MAINTENANCE_CD;
            case PHASE_NORMAL -> DEFAULT_BLACKOUT_CD;
            default -> DEFAULT_BLACKOUT_CD; // permanent blackout: full bar
        };
    }

    
    private static int getLocalCountdown() {
        var level = Minecraft.getInstance().level;
        if (level == null) return 0;
        if (cachedEndTimeTick <= 0) return 0;
        long now = level.getGameTime();
        return (int) Math.max(0, cachedEndTimeTick - now);
    }

    /**
     * BAD only if true killer; sheriff/vigilante 在停电里算好人。
     * 对齐 {@code FixTaskRendererMixin} 的 vigilante 纠正。
     */
    private static boolean isBlackoutBadClient() {
        if (!SREClient.isKiller()) return false;
        var gc = SREClient.gameComponent;
        var self = Minecraft.getInstance().player;
        if (gc == null || self == null) return true;
        SRERole role = gc.getRole(self);
        if (role != null && role.isVigilanteTeam()) return false;
        return true;
    }

    public static void reset() {
        totalTimeRemaining = 300;
        blackoutActive = false;
        showHud = false;
        ClientBlackoutState.setBlackoutModeActive(false);
        currentPhase = 0;
        totalDuration = DEFAULT_MATCH_DURATION;
        cachedEndTimeTick = -1;
        countdownDuration = DEFAULT_BLACKOUT_CD;
    }

    public static void render(GuiGraphics g) {
        if (!showHud) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        int width = mc.getWindow().getGuiScaledWidth();
        int barX = (width - BAR_WIDTH) / 2;

        long localCountdown = getLocalCountdown();
        int localCountdownSeconds = (int) Math.max(0L, localCountdown / 20L);
        boolean bad = isBlackoutBadClient();

        g.fill(barX, BAR_Y - 1, barX + BAR_WIDTH, BAR_Y + BAR_HEIGHT + 1, COLOR_BG_PROGRESS_BAR_BORDER);
        g.fill(barX, BAR_Y, barX + BAR_WIDTH, BAR_Y + BAR_HEIGHT, COLOR_BG_PROGRESS_BAR);

        if (!bad) {
            renderGoodBar(g, barX, localCountdownSeconds);
        } else {
            renderBadBar(g, barX, localCountdownSeconds);
        }

        Font font = mc.font;
        if (mc.player.isShiftKeyDown()) {
            int textX = barX + BAR_WIDTH + 6;
            int textY = BAR_Y - 4;
            if (!bad) {
                renderGoodShiftText(g, font, textX, textY, localCountdownSeconds);
            } else {
                renderBadShiftText(g, font, textX, textY, localCountdownSeconds);
            }
        }

        // 投票提示：通用选项投票优先于放逐。
        if (OptionVoteState.isActive()) {
            drawVoteHint(g, font, width, OptionVoteTexts.openActionLabel(), OptionVoteState.getRemainingSeconds());
        } else if (BlackoutVoteState.isActive()) {
            drawVoteHint(g, font, width,
                    "打开放逐投票",
                    BlackoutVoteState.getRemainingSeconds());
        }
    }

    /** 好人：全黄系进度条，按停电/维护倒计时；永久停电满黄。 */
    private static void renderGoodBar(GuiGraphics g, int barX, int localCountdownSeconds) {
        if (blackoutActive || cachedEndTimeTick <= 0
                || currentPhase == PHASE_FIRST_BLACKOUT
                || currentPhase == PHASE_SECOND_BLACKOUT) {
            // 永久停电：整条满黄
            g.fill(barX, BAR_Y, barX + BAR_WIDTH, BAR_Y + BAR_HEIGHT, COLOR_SEGMENT_BLACKOUT);
            return;
        }

        int denom = Math.max(1, countdownDuration);
        int remaining = Math.max(0, Math.min(localCountdownSeconds, denom));
        int remainingW = (int) ((float) remaining / denom * BAR_WIDTH);
        remainingW = Math.max(0, Math.min(remainingW, BAR_WIDTH));
        int elapsedW = BAR_WIDTH - remainingW;

        if (elapsedW > 0) {
            g.fill(barX, BAR_Y, barX + elapsedW, BAR_Y + BAR_HEIGHT, COLOR_SEGMENT_ELAPSED_GOLD);
        }
        if (remainingW > 0) {
            g.fill(barX + elapsedW, BAR_Y, barX + BAR_WIDTH, BAR_Y + BAR_HEIGHT, COLOR_SEGMENT_BLACKOUT);
        }
    }

    /** 坏人：保持对局总进度条 + 标记线。 */
    private static void renderBadBar(GuiGraphics g, int barX, int localCountdownSeconds) {
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

        if (!blackoutActive && localCountdownSeconds >= 0 && totalDuration > 0) {
            int markerX = barX + (int) ((float) (totalDuration - localCountdownSeconds) / totalDuration * BAR_WIDTH);
            g.fill(markerX, BAR_Y - 2, markerX + 1, BAR_Y + BAR_HEIGHT + 2, COLOR_MARKER_BLACKOUT_START);
        }

        if (totalDuration > 0) {
            int warningX = barX + (int) ((float) (totalDuration - TIME_WARNING_SECONDS) / totalDuration * BAR_WIDTH);
            g.fill(warningX, BAR_Y - 1, warningX + 1, BAR_Y + BAR_HEIGHT + 1, COLOR_MARKER_WARNING);
        }
    }

    private static void renderGoodShiftText(GuiGraphics g, Font font, int textX, int textY,
                                             int localCountdownSeconds) {
        if (blackoutActive || currentPhase == PHASE_FIRST_BLACKOUT
                || currentPhase == PHASE_SECOND_BLACKOUT || cachedEndTimeTick <= 0) {
            g.drawString(font, "§c停电中", textX, textY, COLOR_TEXT_BLACKOUT, false);
        } else if (currentPhase == PHASE_MAINTENANCE) {
            String cdText = "§e剩余供电时间 " + formatTime(localCountdownSeconds);
            g.drawString(font, cdText, textX, textY, COLOR_TEXT_MAINTENANCE, false);
        } else {
            String cdText = "§e停电 " + formatTime(localCountdownSeconds);
            g.drawString(font, cdText, textX, textY, COLOR_TEXT_MAINTENANCE, false);
        }
    }

    private static void renderBadShiftText(GuiGraphics g, Font font, int textX, int textY,
                                            int localCountdownSeconds) {
        String gameText = "§f对局剩余时间 " + formatTime(totalTimeRemaining);
        g.drawString(font, gameText, textX, textY, COLOR_TEXT_DEFAULT, false);

        if (blackoutActive) {
            g.drawString(font, "§c停电中", textX, textY + 10, COLOR_TEXT_BLACKOUT, false);
        } else if (localCountdownSeconds >= 0) {
            String cdLabel = currentPhase == PHASE_MAINTENANCE ? "§e剩余供电时间" : "§e停电";
            String cdText = cdLabel + " " + formatTime(localCountdownSeconds);
            g.drawString(font, cdText, textX, textY + 10, COLOR_TEXT_MAINTENANCE, false);
        }
    }

    private static void drawVoteHint(GuiGraphics g, Font font, int width, String actionLabel, int remainingSeconds) {
        Component keyName = BlackoutKeyHandler.getBoundKeyDisplay();
        Component hint = Component.literal("§e按 §f")
                .append(keyName)
                .append("§e " + actionLabel + " §7(" + remainingSeconds + "s)");
        int hintWidth = font.width(hint);
        int hintX = (width - hintWidth) / 2;
        int hintY = BAR_Y + 12;
        g.drawString(font, hint, hintX, hintY, COLOR_TEXT_DEFAULT, false);
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
