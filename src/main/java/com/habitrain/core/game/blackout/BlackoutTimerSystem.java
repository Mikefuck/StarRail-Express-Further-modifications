package com.habitrain.core.game.blackout;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 停电模式 — 双计时器系统。
 * 管理总对局时间 (5分钟) + 停电倒计时 (2分钟循环)。
 * 所有方法均为静态，供 BlackoutMode (tick) 和任务层 (reduceTime/addTime 等) 调用。
 */
public class BlackoutTimerSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutTimer");

    private static int totalTimeRemaining = 300;       // 秒
    private static int blackoutCountdown = 120;         // 秒
    private static boolean blackoutActive = false;
    private static int blackoutElapsedTicks = 0;        // 停电已持续 tick 数
    private static boolean warningSent = false;         // 60s 预警是否已发

    private static ServerLevel currentLevel = null;
    private static Runnable onBlackoutStart = null;     // 回调: 调用 SRE 停电 API
    private static Runnable onBlackoutEnd = null;       // 回调: 调用 SRE 恢复供电 API
    private static Runnable onTimeWarning = null;       // 回调: 剩余60秒通知

    // ====== 初始化 ======

    public static void init(ServerLevel level, Runnable blackoutStartCb, Runnable blackoutEndCb, Runnable timeWarningCb) {
        totalTimeRemaining = 300;
        blackoutCountdown = 120;
        blackoutActive = false;
        blackoutElapsedTicks = 0;
        warningSent = false;
        currentLevel = level;
        onBlackoutStart = blackoutStartCb;
        onBlackoutEnd = blackoutEndCb;
        onTimeWarning = timeWarningCb;
        LOGGER.info("BlackoutTimerSystem initialized: 300s total, 120s blackout CD");
    }

    public static void reset() {
        currentLevel = null;
        onBlackoutStart = null;
        onBlackoutEnd = null;
        onTimeWarning = null;
    }

    // ====== 每秒更新 (由 BlackoutMode.onTick 调用, 每秒仅执行一次) ======

    public static void tickSecond() {
        if (currentLevel == null) return;

        // 停电中：计时
        if (blackoutActive) {
            blackoutElapsedTicks++;
            if (blackoutElapsedTicks >= 140) { // 7秒 × 20 tick
                // 恢复供电
                if (onBlackoutEnd != null) onBlackoutEnd.run();
                blackoutActive = false;
                blackoutCountdown = 120;
                blackoutElapsedTicks = 0;
                LOGGER.info("Blackout ended, reset CD to 120s");
            }
            return; // 停电期间不更新主计时器
        }

        // 正常状态：更新主计时器
        totalTimeRemaining--;
        if (totalTimeRemaining <= 60 && !warningSent) {
            warningSent = true;
            if (onTimeWarning != null) onTimeWarning.run();
        }

        // 更新停电倒计时
        blackoutCountdown--;
        if (blackoutCountdown <= 0) {
            // 触发停电
            if (onBlackoutStart != null) onBlackoutStart.run();
            blackoutActive = true;
            blackoutElapsedTicks = 0;
            LOGGER.info("Blackout triggered by countdown");
        }
    }

    // ====== 任务交互 API ======

    /** 好人任务: 减少总时间 (添加煤炭 → -30s) */
    public static void reduceTime(int seconds) {
        totalTimeRemaining = Math.max(0, totalTimeRemaining - seconds);
        LOGGER.info("Total time reduced by {}s, remaining: {}s", seconds, totalTimeRemaining);
    }

    /** 坏人任务: 增加总时间 (熔炉爆炸 → +15s) */
    public static void addTime(int seconds) {
        totalTimeRemaining += seconds;
        LOGGER.info("Total time increased by {}s, remaining: {}s", seconds, totalTimeRemaining);
    }

    /** 好人任务: 推迟停电倒计时 (维修线路 → +15s) */
    public static void delayBlackout(int seconds) {
        blackoutCountdown = Math.min(blackoutCountdown + seconds, 300); // 上限5分钟
        LOGGER.info("Blackout delayed by {}s, CD now: {}s", seconds, blackoutCountdown);
    }

    /** 坏人任务: 立即触发停电 (破坏线路 → 停电7s) */
    public static void triggerBlackout() {
        if (blackoutActive || currentLevel == null) return;
        if (onBlackoutStart != null) onBlackoutStart.run();
        blackoutActive = true;
        blackoutElapsedTicks = 0;
        LOGGER.info("Blackout triggered manually by task");
    }

    // ====== 读取器 ======

    public static int getTotalTimeRemaining() { return totalTimeRemaining; }
    public static int getBlackoutCountdown() { return blackoutActive ? 0 : blackoutCountdown; }
    public static boolean isBlackoutActive() { return blackoutActive; }
    public static boolean isTimeUp() { return totalTimeRemaining <= 0; }
}
