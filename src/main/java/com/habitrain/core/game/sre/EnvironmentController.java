package com.habitrain.core.game.sre;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.EnvProfile;
import com.habitrain.core.config.EnvTimeSpec;
import com.habitrain.core.config.EnvironmentSettings;
import com.habitrain.core.config.PostMatchTimeRule;
import io.wifi.starrailexpress.cca.AreasWorldComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SRETrainWorldComponent;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EnvironmentController {
    private static final Logger LOGGER = LoggerFactory.getLogger("EnvironmentController");
    private static final int WEATHER_DURATION = 20 * 60 * 10;
    private static final int CLEAR_DURATION = 20 * 60 * 10;
    private static boolean eventsRegistered = false;
    private static int tickCounter = 0;

    /**
     * When a post-match win-time rule is active, idle lobby reconcile must not
     * overwrite the custom post-match day time until the next match starts.
     */
    private static boolean postMatchTimeOverrideActive = false;
    private static EnvTimeSpec postMatchOverrideTime = null;

    private EnvironmentController() {}

    public static void registerEvents() {
        if (eventsRegistered) return;
        eventsRegistered = true;
        OnGameStarted.EVENT.register(EnvironmentController::onGameStarted);
        OnGameEnd.EVENT.register(EnvironmentController::onGameEnd);
    }

    private static EnvironmentSettings settings() {
        return ConfigManager.getInstance().getEnvironmentSettings();
    }

    public static void onGameStarted(ServerLevel level) {
        try {
            clearPostMatchOverride();
            String mapId = null;
            try {
                AreasWorldComponent areas = AreasWorldComponent.KEY.get(level);
                if (areas != null) mapId = areas.mapName;
            } catch (Throwable ignored) {}
            applyMatch(level, mapId);
        } catch (Throwable t) {
            LOGGER.error("onGameStarted env apply failed", t);
        }
    }

    public static void onGameEnd(ServerLevel level, SREGameWorldComponent game) {
        try {
            GameUtils.WinStatus status = null;
            try {
                if (game != null) status = game.getLastWinStatus();
            } catch (Throwable ignored) {}
            applyPostMatch(level, status);
        } catch (Throwable t) {
            LOGGER.error("onGameEnd env apply failed", t);
        }
    }

    public static void applyLobby(ServerLevel level) {
        if (level == null) return;
        clearPostMatchOverride();
        EnvProfile lobby = settings().lobby;
        if (lobby != null && lobby.enabled) applyProfile(level, lobby, true);
    }

    public static void applyMatch(ServerLevel level, String mapId) {
        if (level == null) return;
        EnvProfile profile = settings().resolveMatchProfile(mapId);
        if (profile != null && profile.enabled) applyProfile(level, profile, true);
    }

    public static void applyPostMatch(ServerLevel level, GameUtils.WinStatus status) {
        if (level == null) return;
        EnvironmentSettings env = settings();
        boolean good = false;
        try {
            good = status != null && status.isInnocentWin();
        } catch (Throwable ignored) {}
        PostMatchTimeRule rule = good ? env.goodWin : env.otherWin;
        if (rule != null && rule.enabled) {
            EnvProfile lobby = env.lobby;
            if (lobby != null && lobby.enabled) {
                applyProfile(level, lobby, false);
            }
            EnvTimeSpec time = rule.time != null ? rule.time : EnvTimeSpec.createDefault();
            applyTimeOnly(level, time);
            postMatchTimeOverrideActive = true;
            postMatchOverrideTime = copyTime(time);
        } else {
            clearPostMatchOverride();
            applyLobby(level);
        }
    }

    private static void clearPostMatchOverride() {
        postMatchTimeOverrideActive = false;
        postMatchOverrideTime = null;
    }

    private static EnvTimeSpec copyTime(EnvTimeSpec src) {
        if (src == null) return EnvTimeSpec.createDefault();
        EnvTimeSpec t = new EnvTimeSpec();
        t.mode = src.mode;
        t.preset = src.preset;
        t.tick = src.tick;
        return t;
    }

    public static EnvProfile getActiveMatchProfile(ServerLevel level) {
        String mapId = null;
        try {
            AreasWorldComponent areas = AreasWorldComponent.KEY.get(level);
            if (areas != null) mapId = areas.mapName;
        } catch (Throwable ignored) {}
        return settings().resolveMatchProfile(mapId);
    }

    public static void applyWeatherOnly(ServerLevel level, EnvProfile.Weather weather) {
        if (level == null || weather == null) return;
        try {
            switch (weather) {
                case RAIN -> level.setWeatherParameters(0, WEATHER_DURATION, true, false);
                case THUNDER -> level.setWeatherParameters(0, WEATHER_DURATION, true, true);
                default -> level.setWeatherParameters(CLEAR_DURATION, 0, false, false);
            }
        } catch (Throwable t) {
            LOGGER.debug("applyWeatherOnly failed", t);
        }
    }

    private static void applyProfile(ServerLevel level, EnvProfile profile, boolean includeTime) {
        if (includeTime) applyTimeOnly(level, profile.time);
        applyWeatherOnly(level, profile.weather);
        try {
            SRETrainWorldComponent train = SRETrainWorldComponent.KEY.get(level);
            if (train != null) {
                train.setSnow(profile.snow);
                train.setSand(profile.sand);
                train.setFog(profile.fog);
            }
        } catch (Throwable t) {
            LOGGER.debug("train env apply failed", t);
        }
    }

    private static void applyTimeOnly(ServerLevel level, EnvTimeSpec time) {
        if (time == null) time = EnvTimeSpec.createDefault();
        long dayTime = time.resolveDayTime();
        try {
            level.setDayTime(dayTime);
        } catch (Throwable t) {
            LOGGER.debug("setDayTime failed", t);
        }
        try {
            SRETrainWorldComponent train = SRETrainWorldComponent.KEY.get(level);
            if (train != null) {
                if (time.mode == EnvTimeSpec.Mode.PRESET) {
                    train.setTimeOfDay(toSre(time.preset));
                } else {
                    for (EnvTimeSpec.Preset p : EnvTimeSpec.Preset.values()) {
                        if (p.time == dayTime) {
                            train.setTimeOfDay(toSre(p));
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("setTimeOfDay failed", t);
        }
    }

    private static SRETrainWorldComponent.TimeOfDay toSre(EnvTimeSpec.Preset p) {
        if (p == null) return SRETrainWorldComponent.TimeOfDay.DAY;
        return switch (p) {
            case NOON -> SRETrainWorldComponent.TimeOfDay.NOON;
            case NIGHT -> SRETrainWorldComponent.TimeOfDay.NIGHT;
            case MIDNIGHT -> SRETrainWorldComponent.TimeOfDay.MIDNIGHT;
            case SUNDOWN -> SRETrainWorldComponent.TimeOfDay.SUNDOWN;
            default -> SRETrainWorldComponent.TimeOfDay.DAY;
        };
    }

    public static void tick(ServerLevel level) {
        if (level == null) return;
        tickCounter++;
        if (tickCounter % 20 != 0) return;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            boolean running = game != null && game.isRunning();
            EnvironmentSettings env = settings();
            if (!running) {
                if (tickCounter % 100 == 0) {
                    EnvProfile lobby = env.lobby;
                    if (lobby != null && lobby.enabled) {
                        if (postMatchTimeOverrideActive && postMatchOverrideTime != null) {
                            maintainProfile(level, lobby, false);
                            if (!lobby.daylightCycle) {
                                applyTimeOnly(level, postMatchOverrideTime);
                            }
                        } else {
                            maintainProfile(level, lobby, true);
                        }
                    } else if (postMatchTimeOverrideActive && postMatchOverrideTime != null) {
                        applyTimeOnly(level, postMatchOverrideTime);
                    }
                }
            } else {
                EnvProfile match = getActiveMatchProfile(level);
                if (match != null && match.enabled) maintainProfile(level, match, true);
            }
        } catch (Throwable t) {
            LOGGER.debug("env tick failed", t);
        }
    }

    private static void maintainProfile(ServerLevel level, EnvProfile profile, boolean includeTime) {
        if (includeTime && !profile.daylightCycle) {
            applyTimeOnly(level, profile.time);
        }
        if (!profile.weatherCycle) {
            if (!SREWeatherController.isForcingRain(level)) {
                applyWeatherOnly(level, profile.weather);
            }
        }
    }
}
