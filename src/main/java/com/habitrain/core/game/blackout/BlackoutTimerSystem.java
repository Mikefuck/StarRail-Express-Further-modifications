package com.habitrain.core.game.blackout;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 停电模式 — 三态计时器系统。
 *
 * 状态机：
 *   NORMAL (灯亮) ──停电倒计时归零──→ FIRST_BLACKOUT (永久停电，可恢复)
 *                                         │
 *                                    好人"维修线路" ──→ MAINTENANCE (60s维护期)
 *                                                           │
 *                                                      维护期归零 → SECOND_BLACKOUT (永久停电，不可逆)
 */
public class BlackoutTimerSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutTimer");

    // ====== 常量 ======
    private static final int TOTAL_TIME = 300;          // 对局总时长 300s
    private static final int FIRST_BLACKOUT_CD = 120;   // 第一次停电倒计时 120s
    private static final int MAINTENANCE_DURATION = 60; // 维护期时长 60s
    private static final int TRANSIENT_TICKS = 140;     // 短暂停电 7s × 20 tick

    // ====== 三态枚举 ======
    public enum Phase {
        NORMAL,           // 灯亮，停电倒计时递减
        FIRST_BLACKOUT,   // 第一次永久停电 (可恢复)
        MAINTENANCE,      // 恢复供电维护期
        SECOND_BLACKOUT   // 第二次永久停电 (不可逆)
    }

    private static Phase phase = Phase.NORMAL;

    // ====== 计时器 ======
    private static int totalTimeRemaining = TOTAL_TIME;   // 对局总倒计时
    private static int blackoutCountdown = FIRST_BLACKOUT_CD;  // 停电倒计时
    private static int maintenanceTime = 0;                // 维护期倒计时
    private static boolean warningSent = false;            // 60s 预警

    // 短暂停电 (杀手破坏线路)
    private static boolean transientBlackoutActive = false;
    private static int transientBlackoutTicks = 0;

    private static ServerLevel currentLevel = null;
    private static Runnable onPermanentStart = null;  // 永久停电回调（调用 SRE API）
    private static Runnable onPermanentEnd = null;     // 永久停电恢复（调用 SRE API 恢复灯光）
    private static Runnable onTimeWarning = null;      // 60s 预警

    // ====== 初始化 ======

    public static void init(ServerLevel level, Runnable permanentStartCb, Runnable permanentEndCb, Runnable timeWarningCb) {
        phase = Phase.NORMAL;
        totalTimeRemaining = TOTAL_TIME;
        blackoutCountdown = FIRST_BLACKOUT_CD;
        maintenanceTime = 0;
        warningSent = false;
        transientBlackoutActive = false;
        transientBlackoutTicks = 0;
        currentLevel = level;
        onPermanentStart = permanentStartCb;
        onPermanentEnd = permanentEndCb;
        onTimeWarning = timeWarningCb;
        LOGGER.info("BlackoutTimerSystem initialized: phase=NORMAL, {}s total, {}s blackout CD", TOTAL_TIME, FIRST_BLACKOUT_CD);
    }

    public static void reset() {
        currentLevel = null;
        onPermanentStart = null;
        onPermanentEnd = null;
        onTimeWarning = null;
    }

    // ====== 每秒更新 (由 BlackoutMode.onTick 调用) ======

    public static void tickSecond() {
        if (currentLevel == null) return;

        // === 总时间倒计时 (所有状态下都走，修复之前的冻结 bug) ===
        totalTimeRemaining--;

        // === 60s 预警 ===
        if (totalTimeRemaining <= 60 && !warningSent) {
            warningSent = true;
            if (onTimeWarning != null) onTimeWarning.run();
        }

        // === 胜利检查 (时间归零 → 好人胜利) ===
        if (totalTimeRemaining <= 0) return;

        // === 短暂停电计时 (杀手破坏线路) ===
        if (transientBlackoutActive) {
            transientBlackoutTicks--;
            if (transientBlackoutTicks <= 0) {
                transientBlackoutActive = false;
                LOGGER.info("Transient blackout ended");
            }
        }

        // === 按当前阶段处理 ===
        switch (phase) {
            case NORMAL -> tickNormal();
            case FIRST_BLACKOUT -> {
                // 等待好人"维修线路"任务调用 restorePower()
            }
            case MAINTENANCE -> tickMaintenance();
            case SECOND_BLACKOUT -> {
                // 等待好人做任务减总时间，或杀手击杀全部好人
            }
        }
    }

    private static void tickNormal() {
        blackoutCountdown--;
        if (blackoutCountdown <= 0) {
            // 进入第一次永久停电
            phase = Phase.FIRST_BLACKOUT;
            if (onPermanentStart != null) onPermanentStart.run();
            broadcast("§c⚡ 永久停电！列车陷入黑暗！");
            broadcast("§e好人完成维修任务可恢复供电");
            LOGGER.info("Phase transition: NORMAL → FIRST_BLACKOUT");
        }
    }

    private static void tickMaintenance() {
        maintenanceTime--;
        if (maintenanceTime <= 0) {
            // 进入第二次永久停电 (不可逆)
            phase = Phase.SECOND_BLACKOUT;
            if (onPermanentStart != null) onPermanentStart.run();
            broadcast("§c备用电源耗尽！列车再次陷入黑暗！");
            broadcast("§e好人无法再恢复供电，但做可减少总时间提前胜利！");
            LOGGER.info("Phase transition: MAINTENANCE → SECOND_BLACKOUT");
        }
    }

    // ====== 供电恢复 (由 RepairWiringTask.onComplete 调用) ======

    public static void restorePower() {
        if (phase != Phase.FIRST_BLACKOUT) {
            LOGGER.warn("restorePower called but phase is {} (only valid in FIRST_BLACKOUT)", phase);
            return;
        }
        // 恢复灯光
        if (onPermanentEnd != null) onPermanentEnd.run();
        phase = Phase.MAINTENANCE;
        maintenanceTime = MAINTENANCE_DURATION;
        broadcast("§a✔ 供电已恢复！维护期 " + MAINTENANCE_DURATION + " 秒");
        broadcast("§e请在 " + MAINTENANCE_DURATION + " 秒内尽可能做任务维持供电！");
        LOGGER.info("Power restored, phase: FIRST_BLACKOUT → MAINTENANCE ({}s)", MAINTENANCE_DURATION);
    }

    // ====== 短暂停电 (杀手破坏线路) ======

    public static void triggerTransientBlackout() {
        if (transientBlackoutActive) return;
        transientBlackoutActive = true;
        transientBlackoutTicks = TRANSIENT_TICKS;
        // 调用 SRE 短暂停电 (只给效果，不改变阶段)
        if (onPermanentStart != null) onPermanentStart.run();
        broadcast("§c⚡ 线路被破坏！短暂停电！");
        LOGGER.info("Transient blackout triggered ({} ticks)", TRANSIENT_TICKS);

        // 如果在维护期，减少维护时间
        if (phase == Phase.MAINTENANCE) {
            maintenanceTime = Math.max(0, maintenanceTime - 15);
            broadcast("§c维护期减少 15 秒！");
        }
        // 如果在 NORMAL 阶段，减少停电倒计时
        if (phase == Phase.NORMAL) {
            blackoutCountdown = Math.max(0, blackoutCountdown - 15);
        }
    }

    // ====== 任务交互 API ======

    /** 好人: 减少总时间 (添加煤炭 → -30s) */
    public static void reduceTime(int seconds) {
        totalTimeRemaining = Math.max(0, totalTimeRemaining - seconds);
        LOGGER.info("Total time reduced by {}s, remaining: {}s", seconds, totalTimeRemaining);
    }

    /** 坏人: 增加总时间 (熔炉爆炸 → +15s) */
    public static void addTime(int seconds) {
        totalTimeRemaining += seconds;
        LOGGER.info("Total time increased by {}s, remaining: {}s", seconds, totalTimeRemaining);
    }

    /** 好人: 推迟第一次停电倒计时/增加维护期 (维修线路 → +15s, 维护供电 → +15s) */
    public static void delayMaintenanceOrCountdown(int seconds) {
        switch (phase) {
            case NORMAL -> {
                blackoutCountdown = Math.min(blackoutCountdown + seconds, 300);
                LOGGER.info("Blackout CD delayed by {}s, now: {}s", seconds, blackoutCountdown);
            }
            case MAINTENANCE -> {
                maintenanceTime += seconds;
                LOGGER.info("Maintenance time extended by {}s, now: {}s", seconds, maintenanceTime);
            }
            default -> LOGGER.warn("delayMaintenanceOrCountdown called in phase {}", phase);
        }
    }

    // ====== 读取器 ======

    public static Phase getPhase() { return phase; }
    public static int getTotalTimeRemaining() { return totalTimeRemaining; }
    public static int getBlackoutCountdown() { return phase == Phase.NORMAL ? blackoutCountdown : 0; }
    public static int getMaintenanceTime() { return phase == Phase.MAINTENANCE ? maintenanceTime : 0; }
    public static boolean isPermanentBlackoutActive() {
        return phase == Phase.FIRST_BLACKOUT || phase == Phase.SECOND_BLACKOUT;
    }
    public static boolean isTransientBlackoutActive() { return transientBlackoutActive; }
    public static boolean isInMaintenance() { return phase == Phase.MAINTENANCE; }
    public static boolean isTimeUp() { return totalTimeRemaining <= 0; }

    /**
     * 向后兼容: 判断是否处于停电状态 (永久或短暂)
     * @deprecated 请使用 isPermanentBlackoutActive() || isTransientBlackoutActive()
     */
    @Deprecated
    public static boolean isBlackoutActive() {
        return isPermanentBlackoutActive() || isTransientBlackoutActive();
    }

    private static void broadcast(String msg) {
        if (currentLevel == null) return;
        Component c = Component.literal(msg);
        for (ServerPlayer player : currentLevel.players()) {
            player.sendSystemMessage(c);
        }
    }
}
