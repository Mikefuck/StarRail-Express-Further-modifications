package com.habitrain.core.game.blackout;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Blackout mode timer system.
 */
public class BlackoutTimerSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutTimer");

    private static final Map<ResourceKey<Level>, TimerState> instances = new HashMap<>();

    private static final int TOTAL_TIME = 300;
    private static final int FIRST_BLACKOUT_CD = 120;
    private static final int MAINTENANCE_DURATION = 60;
    private static final int TRANSIENT_TICKS = 140;
    private static final int TRANSIENT_PENALTY_SECONDS = 15;

    public enum Phase {
        NORMAL,
        FIRST_BLACKOUT,
        MAINTENANCE,
        SECOND_BLACKOUT
    }

    private static TimerState getOrCreate(ServerLevel level) {
        return instances.computeIfAbsent(level.dimension(), ignored -> new TimerState());
    }

    private static final class TimerState {
        Phase phase = Phase.NORMAL;
        int totalTimeRemaining = TOTAL_TIME;
        int blackoutCountdown = FIRST_BLACKOUT_CD;
        int maintenanceTime = 0;
        boolean warningSent = false;
        boolean transientBlackoutActive = false;
        int transientBlackoutTicks = 0;
        boolean initialized = false;
        Runnable onPermanentStart = null;
        Runnable onPermanentEnd = null;
        Runnable onTimeWarning = null;
    }

    public static void init(ServerLevel level, Runnable permanentStartCb, Runnable permanentEndCb, Runnable timeWarningCb) {
        var s = new TimerState();
        s.initialized = true;
        s.onPermanentStart = permanentStartCb;
        s.onPermanentEnd = permanentEndCb;
        s.onTimeWarning = timeWarningCb;
        instances.put(level.dimension(), s);
        LOGGER.info("BlackoutTimerSystem initialized for level {}: phase=NORMAL, {}s total, {}s blackout CD",
                level.dimension().location(), TOTAL_TIME, FIRST_BLACKOUT_CD);
    }

    public static void reset(ServerLevel level) {
        instances.remove(level.dimension());
    }

    public static void tickSecond(ServerLevel level) {
        var s = getOrCreate(level);
        if (!s.initialized) return;

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
            broadcast(level, "§c⚡ 永久停电已开始！");
            broadcast(level, "§e修理任务可以恢复电力供应。");
            LOGGER.info("Phase transition: NORMAL -> FIRST_BLACKOUT for level {}", level.dimension().location());
        }
    }

    private static void tickMaintenance(ServerLevel level, TimerState s) {
        s.maintenanceTime--;
        if (s.maintenanceTime <= 0) {
            s.phase = Phase.SECOND_BLACKOUT;
            if (s.onPermanentStart != null) s.onPermanentStart.run();
            broadcast(level, "§c⚡ 备用电源耗尽，永久停电恢复！");
            broadcast(level, "§e尽快完成任务来减少剩余时间。");
            LOGGER.info("Phase transition: MAINTENANCE -> SECOND_BLACKOUT for level {}", level.dimension().location());
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
        broadcast(level, "§a⚡ 电力已恢复！维护阶段持续 " + MAINTENANCE_DURATION + " 秒。");
        broadcast(level, "§e保持系统运行 " + MAINTENANCE_DURATION + " 秒以完成维护。");
        LOGGER.info("Power restored for level {}", level.dimension().location());
    }

    public static void triggerTransientBlackout(ServerLevel level) {
        var s = getOrCreate(level);
        if (!s.initialized || s.transientBlackoutActive) return;
        s.transientBlackoutActive = true;
        s.transientBlackoutTicks = TRANSIENT_TICKS;
        broadcast(level, "§c⚡ 短暂停电！");
        LOGGER.info("Transient blackout triggered for level {} ({} ticks)", level.dimension().location(), TRANSIENT_TICKS);

        if (s.phase == Phase.MAINTENANCE) {
            s.maintenanceTime = Math.max(0, s.maintenanceTime - TRANSIENT_PENALTY_SECONDS);
            broadcast(level, "§c维护时间减少了 " + TRANSIENT_PENALTY_SECONDS + " 秒。");
        }
        if (s.phase == Phase.NORMAL) {
            s.blackoutCountdown = Math.max(0, s.blackoutCountdown - TRANSIENT_PENALTY_SECONDS);
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
                s.blackoutCountdown = Math.min(s.blackoutCountdown + seconds, TOTAL_TIME);
                LOGGER.info("Blackout CD delayed by {}s for level {}, now: {}s", seconds, level.dimension().location(), s.blackoutCountdown);
            }
            case MAINTENANCE -> {
                s.maintenanceTime += seconds;
                LOGGER.info("Maintenance time extended by {}s for level {}, now: {}s", seconds, level.dimension().location(), s.maintenanceTime);
            }
            default -> LOGGER.warn("delayMaintenanceOrCountdown called in phase {} for level {}", s.phase, level.dimension().location());
        }
    }

    public static void reduceMaintenanceOrCountdown(ServerLevel level, int seconds) {
        var s = getOrCreate(level);
        switch (s.phase) {
            case NORMAL -> {
                s.blackoutCountdown = Math.max(0, s.blackoutCountdown - seconds);
                LOGGER.info("Blackout CD reduced by {}s for level {}, now: {}s",
                        seconds, level.dimension().location(), s.blackoutCountdown);
            }
            case MAINTENANCE -> {
                s.maintenanceTime = Math.max(0, s.maintenanceTime - seconds);
                LOGGER.info("Maintenance time reduced by {}s for level {}, now: {}s",
                        seconds, level.dimension().location(), s.maintenanceTime);
            }
            default -> LOGGER.warn("reduceMaintenanceOrCountdown called in phase {} for level {}",
                    s.phase, level.dimension().location());
        }
    }

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
        var s = getOrCreate(level);
        return s.phase == Phase.FIRST_BLACKOUT || s.phase == Phase.SECOND_BLACKOUT;
    }
    public static boolean isTransientBlackoutActive(ServerLevel level) { return getOrCreate(level).transientBlackoutActive; }
    public static boolean isInMaintenance(ServerLevel level) { return getOrCreate(level).phase == Phase.MAINTENANCE; }
    public static boolean isTimeUp(ServerLevel level) { return getOrCreate(level).totalTimeRemaining <= 0; }

    private static void broadcast(ServerLevel level, String msg) {
        BlackoutMode.broadcast(level, msg);
    }
}
