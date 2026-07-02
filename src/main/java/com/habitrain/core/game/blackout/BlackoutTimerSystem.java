package com.habitrain.core.game.blackout;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import java.util.Map;
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

    private static final Map<ServerLevel, TimerState> instances = new java.util.HashMap<>();

    // ====== 常量 ======
    private static final int TOTAL_TIME = 300;
    private static final int FIRST_BLACKOUT_CD = 120;
    private static final int MAINTENANCE_DURATION = 60;
    private static final int TRANSIENT_TICKS = 140;

    public enum Phase {
        NORMAL,
        FIRST_BLACKOUT,
        MAINTENANCE,
        SECOND_BLACKOUT
    }

    private static TimerState getOrCreate(ServerLevel level) {
        return instances.computeIfAbsent(level, k -> new TimerState());
    }

    private static class TimerState {
        Phase phase = Phase.NORMAL;
        int totalTimeRemaining = TOTAL_TIME;
        int blackoutCountdown = FIRST_BLACKOUT_CD;
        int maintenanceTime = 0;
        boolean warningSent = false;
        boolean transientBlackoutActive = false;
        int transientBlackoutTicks = 0;
        ServerLevel currentLevel = null;
        Runnable onPermanentStart = null;
        Runnable onPermanentEnd = null;
        Runnable onTimeWarning = null;
    }

    public static void init(ServerLevel level, Runnable permanentStartCb, Runnable permanentEndCb, Runnable timeWarningCb) {
        var s = new TimerState();
        s.currentLevel = level;
        s.onPermanentStart = permanentStartCb;
        s.onPermanentEnd = permanentEndCb;
        s.onTimeWarning = timeWarningCb;
        instances.put(level, s);
        LOGGER.info("BlackoutTimerSystem initialized for level {}: phase=NORMAL, {}s total, {}s blackout CD",
                level.dimension().location(), TOTAL_TIME, FIRST_BLACKOUT_CD);
    }

    public static void reset(ServerLevel level) {
        instances.remove(level);
    }

    // ====== 每秒更新 (由 BlackoutMode.onTick 调用) ======

    public static void tickSecond(ServerLevel level) {
        var s = getOrCreate(level);
        if (s.currentLevel == null) return;

        s.totalTimeRemaining--;

        if (s.totalTimeRemaining <= 60 && !s.warningSent) {
            s.warningSent = true;
            if (s.onTimeWarning != null) s.onTimeWarning.run();
        }

        if (s.totalTimeRemaining <= 0) return;

        if (s.transientBlackoutActive) {
            s.transientBlackoutTicks--;
            if (s.transientBlackoutTicks <= 0) {
                s.transientBlackoutActive = false;
                LOGGER.info("Transient blackout ended for level {}", level.dimension().location());
            }
        }

        switch (s.phase) {
            case NORMAL -> tickNormal(level, s);
            case FIRST_BLACKOUT -> {}
            case MAINTENANCE -> tickMaintenance(level, s);
            case SECOND_BLACKOUT -> {}
        }
    }

    private static void tickNormal(ServerLevel level, TimerState s) {
        s.blackoutCountdown--;
        if (s.blackoutCountdown <= 0) {
            s.phase = Phase.FIRST_BLACKOUT;
            if (s.onPermanentStart != null) s.onPermanentStart.run();
            broadcast(level, "§c⚡ 永久停电！列车陷入黑暗！");
            broadcast(level, "§e好人完成维修任务可恢复供电");
            LOGGER.info("Phase transition: NORMAL → FIRST_BLACKOUT for level {}", level.dimension().location());
        }
    }

    private static void tickMaintenance(ServerLevel level, TimerState s) {
        s.maintenanceTime--;
        if (s.maintenanceTime <= 0) {
            s.phase = Phase.SECOND_BLACKOUT;
            if (s.onPermanentStart != null) s.onPermanentStart.run();
            broadcast(level, "§c备用电源耗尽！列车再次陷入黑暗！");
            broadcast(level, "§e好人无法再恢复供电，但做可减少总时间提前胜利！");
            LOGGER.info("Phase transition: MAINTENANCE → SECOND_BLACKOUT for level {}", level.dimension().location());
        }
    }

    public static void restorePower(ServerLevel level) {
        var s = getOrCreate(level);
        if (s.phase != Phase.FIRST_BLACKOUT) {
            LOGGER.warn("restorePower called but phase is {} for level {}", s.phase, level.dimension().location());
            return;
        }
        if (s.onPermanentEnd != null) s.onPermanentEnd.run();
        s.phase = Phase.MAINTENANCE;
        s.maintenanceTime = MAINTENANCE_DURATION;
        broadcast(level, "§a✔ 供电已恢复！维护期 " + MAINTENANCE_DURATION + " 秒");
        broadcast(level, "§e请在 " + MAINTENANCE_DURATION + " 秒内尽可能做任务维持供电！");
        LOGGER.info("Power restored for level {}", level.dimension().location());
    }

    public static void triggerTransientBlackout(ServerLevel level) {
        var s = getOrCreate(level);
        if (s.transientBlackoutActive) return;
        s.transientBlackoutActive = true;
        s.transientBlackoutTicks = TRANSIENT_TICKS;
        if (s.onPermanentStart != null) s.onPermanentStart.run();
        broadcast(level, "§c⚡ 线路被破坏！短暂停电！");
        LOGGER.info("Transient blackout triggered for level {} ({} ticks)", level.dimension().location(), TRANSIENT_TICKS);

        if (s.phase == Phase.MAINTENANCE) {
            s.maintenanceTime = Math.max(0, s.maintenanceTime - 15);
            broadcast(level, "§c维护期减少 15 秒！");
        }
        if (s.phase == Phase.NORMAL) {
            s.blackoutCountdown = Math.max(0, s.blackoutCountdown - 15);
        }
    }

    public static void reduceTime(ServerLevel level, int seconds) {
        var s = getOrCreate(level);
        s.totalTimeRemaining = Math.max(0, s.totalTimeRemaining - seconds);
        LOGGER.info("Total time reduced by {}s for level {}, remaining: {}s", seconds, level.dimension().location(), s.totalTimeRemaining);
    }

    public static void addTime(ServerLevel level, int seconds) {
        var s = getOrCreate(level);
        s.totalTimeRemaining += seconds;
        LOGGER.info("Total time increased by {}s for level {}, remaining: {}s", seconds, level.dimension().location(), s.totalTimeRemaining);
    }

    public static void delayMaintenanceOrCountdown(ServerLevel level, int seconds) {
        var s = getOrCreate(level);
        switch (s.phase) {
            case NORMAL -> {
                s.blackoutCountdown = Math.min(s.blackoutCountdown + seconds, 300);
                LOGGER.info("Blackout CD delayed by {}s for level {}, now: {}s", seconds, level.dimension().location(), s.blackoutCountdown);
            }
            case MAINTENANCE -> {
                s.maintenanceTime += seconds;
                LOGGER.info("Maintenance time extended by {}s for level {}, now: {}s", seconds, level.dimension().location(), s.maintenanceTime);
            }
            default -> LOGGER.warn("delayMaintenanceOrCountdown called in phase {} for level {}", s.phase, level.dimension().location());
        }
    }

    // ====== 读取器 ======

    public static Phase getPhase(ServerLevel level) { return getOrCreate(level).phase; }
    public static int getTotalTimeRemaining(ServerLevel level) { return getOrCreate(level).totalTimeRemaining; }
    public static int getBlackoutCountdown(ServerLevel level) {
        var s = getOrCreate(level);
        return s.phase == Phase.NORMAL ? s.blackoutCountdown : 0;
    }
    public static int getMaintenanceTime(ServerLevel level) {
        var s = getOrCreate(level);
        return s.phase == Phase.MAINTENANCE ? s.maintenanceTime : 0;
    }
    public static boolean isPermanentBlackoutActive(ServerLevel level) {
        var p = getOrCreate(level).phase;
        return p == Phase.FIRST_BLACKOUT || p == Phase.SECOND_BLACKOUT;
    }
    public static boolean isTransientBlackoutActive(ServerLevel level) { return getOrCreate(level).transientBlackoutActive; }
    public static boolean isInMaintenance(ServerLevel level) { return getOrCreate(level).phase == Phase.MAINTENANCE; }
    public static boolean isTimeUp(ServerLevel level) { return getOrCreate(level).totalTimeRemaining <= 0; }

    private static void broadcast(ServerLevel level, String msg) {
        Component c = Component.literal(msg);
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(c);
        }
    }
}
