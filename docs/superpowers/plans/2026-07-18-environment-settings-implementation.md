# Environment Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Mod Menu「环境设置」tab and server-side controller so lobby, per-map match, and post-match win-based environments are configurable, with low-player rain remaining compatible via a master switch.

**Architecture:** Persist `EnvironmentSettings` under `habitrain_core.json` → `environment`. UI edits via new root tab. Server `EnvironmentController` applies profiles on idle / `OnGameStarted` / `OnGameEnd`. `SREWeatherController` is gated by `lowPlayerRain` and restores match-profile weather instead of blind CLEAR.

**Tech Stack:** Fabric 1.21.1, Java 21, Gson config (existing), SRE CCA (`SRETrainWorldComponent`, `OnGameStarted`/`OnGameEnd`, `AreasWorldComponent.mapName`, `GameUtils.WinStatus`), existing ConfigRootScreen tab pattern.

**Spec:** `docs/superpowers/specs/2026-07-18-environment-settings-design.md`

## Global Constraints

- Address user as **Mike**.
- File access only under `D:\Backup\mc mod\`; never touch `D:\Backup\mc mod\backup\`.
- After finishing implementation changes to this mod: run `./gradlew clean build` in `哈比列车api` and ensure jar lands in `D:\Backup\mc mod\临时\` (project already has `copyReleaseJar` on `assemble`).
- Do not edit SRE map files on disk; runtime override only.
- No new Gradle test framework in this plan (repo has none); verify via compile + JSON round-trip asserts in model `main` helper or manual checks listed per task.
- Keep remote edit rules: non-OP read-only via `LiveConfigAccess`.

## File map

| File | Role |
|---|---|
| `src/main/java/com/habitrain/core/config/EnvTimeSpec.java` | PRESET/TICK time model |
| `src/main/java/com/habitrain/core/config/EnvProfile.java` | Full environment snapshot |
| `src/main/java/com/habitrain/core/config/PostMatchTimeRule.java` | Post-match time + enable |
| `src/main/java/com/habitrain/core/config/EnvironmentSettings.java` | Root: lobby/match/post/lowPlayerRain |
| `src/main/java/com/habitrain/core/config/ConfigRepository.java` | Hold settings |
| `src/main/java/com/habitrain/core/config/ConfigManager.java` | get/set + markDirty |
| `src/main/java/com/habitrain/core/config/ConfigStore.java` | load/save JSON key `environment` |
| `src/main/java/com/habitrain/core/config/ConfigSync.java` | full load + merge + client sync |
| `src/main/java/com/habitrain/core/game/sre/EnvironmentController.java` | applyLobby/Match/PostMatch + maintain |
| `src/main/java/com/habitrain/core/game/sre/SREWeatherController.java` | Gate + restore match weather |
| `src/main/java/com/habitrain/core/game/sre/SREGameModeBase.java` or bootstrap | Register env OnGameStarted/End |
| `src/main/java/com/habitrain/core/ModTickHandler.java` | Idle reconcile + controller maintain |
| `src/main/java/com/habitrain/core/LifecycleEventsRegistrar.java` | applyLobby on SERVER_STARTED |
| `src/main/java/com/habitrain/core/client/gui/config/EnvironmentTabScreen.java` | New tab UI |
| `src/main/java/com/habitrain/core/client/gui/config/ConfigRootScreen.java` | 5th tab wiring |

---

### Task 1: Config data models + JSON

**Files:**
- Create: `src/main/java/com/habitrain/core/config/EnvTimeSpec.java`
- Create: `src/main/java/com/habitrain/core/config/EnvProfile.java`
- Create: `src/main/java/com/habitrain/core/config/PostMatchTimeRule.java`
- Create: `src/main/java/com/habitrain/core/config/EnvironmentSettings.java`

**Interfaces:**
- Produces:
  - `EnvTimeSpec.createDefault()`, `toJson()`, `fromJson(JsonObject)`, `resolveDayTime(): long`, `clampTick(int): int`
  - `EnvProfile.createLobbyDefault()`, `createMatchDefault()`, `toJson()`, `fromJson(JsonObject)`, `copy()`
  - `PostMatchTimeRule.createDefault()`, `toJson()`, `fromJson(JsonObject)`
  - `EnvironmentSettings.createDefault()`, `toJson()`, `fromJson(JsonObject)`, `resolveMatchProfile(String mapId): EnvProfile`

- [ ] **Step 1: Add `EnvTimeSpec`**

```java
package com.habitrain.core.config;

import com.google.gson.JsonObject;

/** Time either as SRE-aligned preset or raw dayTime tick 0..23999. */
public final class EnvTimeSpec {
    public enum Mode { PRESET, TICK }
    public enum Preset {
        DAY(1000), NOON(6000), NIGHT(13000), MIDNIGHT(18000), SUNDOWN(12800);
        public final int time;
        Preset(int time) { this.time = time; }
        public static Preset fromName(String s) {
            if (s == null) return DAY;
            try { return Preset.valueOf(s.trim().toUpperCase()); }
            catch (Exception e) { return DAY; }
        }
        public static Preset nearest(int tick) {
            Preset best = DAY;
            int bestDist = Integer.MAX_VALUE;
            int t = clampTick(tick);
            for (Preset p : values()) {
                int d = Math.abs(p.time - t);
                if (d < bestDist) { bestDist = d; best = p; }
            }
            return best;
        }
    }

    public Mode mode = Mode.PRESET;
    public Preset preset = Preset.DAY;
    public int tick = 1000;

    public static EnvTimeSpec createDefault() { return new EnvTimeSpec(); }

    public static int clampTick(int t) {
        if (t < 0) return 0;
        if (t > 23999) return 23999;
        return t;
    }

    public long resolveDayTime() {
        if (mode == Mode.TICK) return clampTick(tick);
        return (preset != null ? preset : Preset.DAY).time;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode.name());
        o.addProperty("preset", (preset != null ? preset : Preset.DAY).name());
        o.addProperty("tick", clampTick(tick));
        return o;
    }

    public static EnvTimeSpec fromJson(JsonObject o) {
        EnvTimeSpec s = createDefault();
        if (o == null) return s;
        if (o.has("mode")) {
            try { s.mode = Mode.valueOf(o.get("mode").getAsString().trim().toUpperCase()); }
            catch (Exception ignored) { s.mode = Mode.PRESET; }
        }
        if (o.has("preset")) s.preset = Preset.fromName(o.get("preset").getAsString());
        if (o.has("tick")) s.tick = clampTick(o.get("tick").getAsInt());
        return s;
    }
}
```

- [ ] **Step 2: Add `EnvProfile`**

```java
package com.habitrain.core.config;

import com.google.gson.JsonObject;

public final class EnvProfile {
    public enum Weather { CLEAR, RAIN, THUNDER }

    public boolean enabled = true;
    public EnvTimeSpec time = EnvTimeSpec.createDefault();
    public Weather weather = Weather.CLEAR;
    public boolean snow = true;
    public boolean sand = true;
    public boolean fog = true;
    public float fogEnd = 192f;
    public boolean daylightCycle = false;
    public boolean weatherCycle = false;

    public static EnvProfile createLobbyDefault() {
        EnvProfile p = new EnvProfile();
        p.enabled = true;
        return p;
    }

    /** Match default is opt-in so map-native AreasSettings win until configured. */
    public static EnvProfile createMatchDefault() {
        EnvProfile p = createLobbyDefault();
        p.enabled = false;
        return p;
    }

    public EnvProfile copy() {
        return fromJson(toJson());
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.add("time", (time != null ? time : EnvTimeSpec.createDefault()).toJson());
        o.addProperty("weather", (weather != null ? weather : Weather.CLEAR).name());
        o.addProperty("snow", snow);
        o.addProperty("sand", sand);
        o.addProperty("fog", fog);
        o.addProperty("fogEnd", fogEnd);
        o.addProperty("daylightCycle", daylightCycle);
        o.addProperty("weatherCycle", weatherCycle);
        return o;
    }

    public static EnvProfile fromJson(JsonObject o) {
        EnvProfile p = createLobbyDefault();
        if (o == null) return p;
        if (o.has("enabled")) p.enabled = o.get("enabled").getAsBoolean();
        if (o.has("time") && o.get("time").isJsonObject()) {
            p.time = EnvTimeSpec.fromJson(o.getAsJsonObject("time"));
        }
        if (o.has("weather")) {
            try { p.weather = Weather.valueOf(o.get("weather").getAsString().trim().toUpperCase()); }
            catch (Exception ignored) { p.weather = Weather.CLEAR; }
        }
        if (o.has("snow")) p.snow = o.get("snow").getAsBoolean();
        if (o.has("sand")) p.sand = o.get("sand").getAsBoolean();
        if (o.has("fog")) p.fog = o.get("fog").getAsBoolean();
        if (o.has("fogEnd")) p.fogEnd = o.get("fogEnd").getAsFloat();
        if (o.has("daylightCycle")) p.daylightCycle = o.get("daylightCycle").getAsBoolean();
        if (o.has("weatherCycle")) p.weatherCycle = o.get("weatherCycle").getAsBoolean();
        return p;
    }
}
```

- [ ] **Step 3: Add `PostMatchTimeRule` + `EnvironmentSettings`**

```java
// PostMatchTimeRule.java
package com.habitrain.core.config;

import com.google.gson.JsonObject;

public final class PostMatchTimeRule {
    public boolean enabled = false;
    public EnvTimeSpec time = EnvTimeSpec.createDefault();

    public static PostMatchTimeRule createDefault() { return new PostMatchTimeRule(); }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.add("time", (time != null ? time : EnvTimeSpec.createDefault()).toJson());
        return o;
    }

    public static PostMatchTimeRule fromJson(JsonObject o) {
        PostMatchTimeRule r = createDefault();
        if (o == null) return r;
        if (o.has("enabled")) r.enabled = o.get("enabled").getAsBoolean();
        if (o.has("time") && o.get("time").isJsonObject()) {
            r.time = EnvTimeSpec.fromJson(o.getAsJsonObject("time"));
        }
        return r;
    }
}
```

```java
// EnvironmentSettings.java
package com.habitrain.core.config;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EnvironmentSettings {
    public EnvProfile lobby = EnvProfile.createLobbyDefault();
    public EnvProfile matchDefaultProfile = EnvProfile.createMatchDefault();
    public final Map<String, EnvProfile> matchMaps = new LinkedHashMap<>();
    public PostMatchTimeRule goodWin = PostMatchTimeRule.createDefault();
    public PostMatchTimeRule otherWin = PostMatchTimeRule.createDefault();
    public boolean lowPlayerRainEnabled = true;
    public int lowPlayerRainMinPlayers = 8;

    public static EnvironmentSettings createDefault() {
        return new EnvironmentSettings();
    }

    public int clampedMinPlayers() {
        return Math.max(1, lowPlayerRainMinPlayers);
    }

    /** map entry if present, else matchDefaultProfile (never null). */
    public EnvProfile resolveMatchProfile(String mapId) {
        if (mapId != null && !mapId.isBlank()) {
            EnvProfile p = matchMaps.get(mapId);
            if (p != null) return p;
        }
        return matchDefaultProfile != null ? matchDefaultProfile : EnvProfile.createMatchDefault();
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.add("lobby", (lobby != null ? lobby : EnvProfile.createLobbyDefault()).toJson());

        JsonObject match = new JsonObject();
        match.add("defaultProfile",
                (matchDefaultProfile != null ? matchDefaultProfile : EnvProfile.createMatchDefault()).toJson());
        JsonObject maps = new JsonObject();
        for (Map.Entry<String, EnvProfile> e : matchMaps.entrySet()) {
            maps.add(e.getKey(), e.getValue().toJson());
        }
        match.add("maps", maps);
        root.add("match", match);

        JsonObject post = new JsonObject();
        post.add("goodWin", (goodWin != null ? goodWin : PostMatchTimeRule.createDefault()).toJson());
        post.add("otherWin", (otherWin != null ? otherWin : PostMatchTimeRule.createDefault()).toJson());
        root.add("postMatch", post);

        JsonObject rain = new JsonObject();
        rain.addProperty("enabled", lowPlayerRainEnabled);
        rain.addProperty("minPlayers", clampedMinPlayers());
        root.add("lowPlayerRain", rain);
        return root;
    }

    public static EnvironmentSettings fromJson(JsonObject o) {
        EnvironmentSettings s = createDefault();
        if (o == null) return s;
        if (o.has("lobby") && o.get("lobby").isJsonObject()) {
            s.lobby = EnvProfile.fromJson(o.getAsJsonObject("lobby"));
        }
        if (o.has("match") && o.get("match").isJsonObject()) {
            JsonObject match = o.getAsJsonObject("match");
            if (match.has("defaultProfile") && match.get("defaultProfile").isJsonObject()) {
                s.matchDefaultProfile = EnvProfile.fromJson(match.getAsJsonObject("defaultProfile"));
            }
            if (match.has("maps") && match.get("maps").isJsonObject()) {
                JsonObject maps = match.getAsJsonObject("maps");
                for (var e : maps.entrySet()) {
                    if (e.getValue().isJsonObject()) {
                        s.matchMaps.put(e.getKey(), EnvProfile.fromJson(e.getValue().getAsJsonObject()));
                    }
                }
            }
        }
        if (o.has("postMatch") && o.get("postMatch").isJsonObject()) {
            JsonObject post = o.getAsJsonObject("postMatch");
            if (post.has("goodWin") && post.get("goodWin").isJsonObject()) {
                s.goodWin = PostMatchTimeRule.fromJson(post.getAsJsonObject("goodWin"));
            }
            if (post.has("otherWin") && post.get("otherWin").isJsonObject()) {
                s.otherWin = PostMatchTimeRule.fromJson(post.getAsJsonObject("otherWin"));
            }
        }
        if (o.has("lowPlayerRain") && o.get("lowPlayerRain").isJsonObject()) {
            JsonObject rain = o.getAsJsonObject("lowPlayerRain");
            if (rain.has("enabled")) s.lowPlayerRainEnabled = rain.get("enabled").getAsBoolean();
            if (rain.has("minPlayers")) {
                s.lowPlayerRainMinPlayers = Math.max(1, rain.get("minPlayers").getAsInt());
            }
        }
        return s;
    }
}
```

- [ ] **Step 4: Round-trip sanity (manual)**

In a scratch note or temporary main (delete before commit if used), verify:

```java
EnvironmentSettings a = EnvironmentSettings.createDefault();
a.lobby.weather = EnvProfile.Weather.RAIN;
a.matchMaps.put("demo", EnvProfile.createMatchDefault());
a.matchMaps.get("demo").enabled = true;
a.goodWin.enabled = true;
a.goodWin.time.mode = EnvTimeSpec.Mode.TICK;
a.goodWin.time.tick = 18000;
EnvironmentSettings b = EnvironmentSettings.fromJson(a.toJson());
assert b.lobby.weather == EnvProfile.Weather.RAIN;
assert b.resolveMatchProfile("demo").enabled;
assert b.goodWin.time.resolveDayTime() == 18000L;
assert b.resolveMatchProfile("missing").enabled == false; // match default
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/config/EnvTimeSpec.java \
  src/main/java/com/habitrain/core/config/EnvProfile.java \
  src/main/java/com/habitrain/core/config/PostMatchTimeRule.java \
  src/main/java/com/habitrain/core/config/EnvironmentSettings.java
git commit -m "feat(env): add environment settings data models"
```

---

### Task 2: Wire ConfigRepository / Store / Sync / Manager

**Files:**
- Modify: `src/main/java/com/habitrain/core/config/ConfigRepository.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigStore.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigSync.java`

**Interfaces:**
- Consumes: `EnvironmentSettings` from Task 1
- Produces: `ConfigManager.getEnvironmentSettings()`, `setEnvironmentSettings(EnvironmentSettings)`, `markEnvironmentDirty()` pattern via existing `store.markDirty()` after mutators

- [ ] **Step 1: Repository field**

In `ConfigRepository` add:

```java
private EnvironmentSettings environment = EnvironmentSettings.createDefault();

public EnvironmentSettings getEnvironment() {
    return environment != null ? environment : EnvironmentSettings.createDefault();
}

public void setEnvironment(EnvironmentSettings s) {
    this.environment = s != null ? s : EnvironmentSettings.createDefault();
}
```

- [ ] **Step 2: ConfigManager accessors**

```java
public EnvironmentSettings getEnvironmentSettings() {
    return repository.getEnvironment();
}

public void setEnvironmentSettings(EnvironmentSettings settings) {
    repository.setEnvironment(settings);
    store.markDirty();
}

/** Call after in-place mutation of the live EnvironmentSettings graph. */
public void markEnvironmentDirty() {
    store.markDirty();
}
```

- [ ] **Step 3: ConfigStore load/save**

In `load(...)` after modeMapVote block (and in catch rebuild defaults):

```java
if (root.has("environment") && root.get("environment").isJsonObject()) {
    repo.setEnvironment(EnvironmentSettings.fromJson(root.getAsJsonObject("environment")));
} else {
    repo.setEnvironment(EnvironmentSettings.createDefault());
}
```

In `createDefaultConfig`:

```java
repo.setEnvironment(EnvironmentSettings.createDefault());
```

In `buildJsonRoot`:

```java
root.add("environment", repo.getEnvironment().toJson());
```

In load failure recovery path that rebuilds defaults, also `repo.setEnvironment(EnvironmentSettings.createDefault())`.

- [ ] **Step 4: ConfigSync full replace + merge**

In `loadFromJsonString`:

```java
EnvironmentSettings newEnv = EnvironmentSettings.createDefault();
// ... parse:
if (root.has("environment") && root.get("environment").isJsonObject()) {
    newEnv = EnvironmentSettings.fromJson(root.getAsJsonObject("environment"));
}
// after other assigns:
repo.setEnvironment(newEnv);
```

In `mergeFromJsonString` (full replace of environment object when present — simpler and matches “whole settings blob”):

```java
if (root.has("environment") && root.get("environment").isJsonObject()) {
    repo.setEnvironment(EnvironmentSettings.fromJson(root.getAsJsonObject("environment")));
}
```

- [ ] **Step 5: Compile**

```bash
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/habitrain/core/config/ConfigRepository.java \
  src/main/java/com/habitrain/core/config/ConfigManager.java \
  src/main/java/com/habitrain/core/config/ConfigStore.java \
  src/main/java/com/habitrain/core/config/ConfigSync.java
git commit -m "feat(env): persist environment settings in config store"
```

---

### Task 3: EnvironmentController (apply + maintain)

**Files:**
- Create: `src/main/java/com/habitrain/core/game/sre/EnvironmentController.java`

**Interfaces:**
- Consumes: `ConfigManager.getEnvironmentSettings()`, SRE components
- Produces:
  - `EnvironmentController.applyLobby(ServerLevel)`
  - `EnvironmentController.applyMatch(ServerLevel, String mapId)`
  - `EnvironmentController.applyPostMatch(ServerLevel, WinStatus)`
  - `EnvironmentController.onGameStarted(ServerLevel)`
  - `EnvironmentController.onGameEnd(ServerLevel, SREGameWorldComponent)`
  - `EnvironmentController.tick(ServerLevel)` // maintain + idle reconcile
  - `EnvironmentController.registerEvents()` // once
  - `EnvironmentController.getActiveMatchProfile(ServerLevel): EnvProfile` // for weather restore

- [ ] **Step 1: Implement controller skeleton**

```java
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
                applyProfile(level, lobby, false); // non-time first
            }
            applyTimeOnly(level, rule.time != null ? rule.time : EnvTimeSpec.createDefault());
        } else {
            applyLobby(level);
        }
    }

    /** Used by SREWeatherController when clearing forced rain. */
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
                // fogEnd: AreasSettings has fogEnd; Train component may not — skip if no API
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
                    // exact preset match only
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

    /** Call once per server tick from ModTickHandler (overworld). */
    public static void tick(ServerLevel level) {
        if (level == null) return;
        tickCounter++;
        if (tickCounter % 20 != 0) return; // 1 Hz
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
            boolean running = game != null && game.isRunning();
            EnvironmentSettings env = settings();
            if (!running) {
                // idle reconcile every 5s
                if (tickCounter % 100 == 0 && env.lobby != null && env.lobby.enabled) {
                    maintainProfile(level, env.lobby);
                }
            } else {
                EnvProfile match = getActiveMatchProfile(level);
                if (match != null && match.enabled) maintainProfile(level, match);
            }
        } catch (Throwable t) {
            LOGGER.debug("env tick failed", t);
        }
    }

    private static void maintainProfile(ServerLevel level, EnvProfile profile) {
        if (!profile.daylightCycle) {
            applyTimeOnly(level, profile.time);
        }
        if (!profile.weatherCycle) {
            // Only re-assert weather if low-player rain is not currently forcing
            // (SREWeatherController owns force flag). Simple approach: re-apply weather
            // when not raining-or profile wants rain/thunder.
            applyWeatherOnly(level, profile.weather);
        }
    }
}
```

**Note for implementer:** During match with low-player rain active, `maintainProfile` re-applying CLEAR can fight the rain controller. Fix maintain weather as:

```java
if (!profile.weatherCycle) {
    if (!SREWeatherController.isForcingRain(level)) {
        applyWeatherOnly(level, profile.weather);
    }
}
```

Add `isForcingRain(ServerLevel)` on weather controller in Task 4.

- [ ] **Step 2: Compile**

```bash
./gradlew compileJava
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/EnvironmentController.java
git commit -m "feat(env): add EnvironmentController apply paths"
```

---

### Task 4: Low-player rain compatibility + hooks

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/sre/SREWeatherController.java`
- Modify: `src/main/java/com/habitrain/core/ModTickHandler.java`
- Modify: `src/main/java/com/habitrain/core/LifecycleEventsRegistrar.java`
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java` **or** `SREGameModeBase` — call `EnvironmentController.registerEvents()` once at init

**Interfaces:**
- Consumes: `ConfigManager.getEnvironmentSettings().lowPlayerRain*`, `EnvironmentController.applyWeatherOnly`, `getActiveMatchProfile`
- Produces: `SREWeatherController.isForcingRain(ServerLevel): boolean`

- [ ] **Step 1: Update `SREWeatherController`**

Key behavior changes:

```java
// at start of tick():
EnvironmentSettings env = ConfigManager.getInstance().getEnvironmentSettings();
if (!env.lowPlayerRainEnabled) {
    // optional: clear forced flag without touching weather
    DimensionWeatherState state = getOrCreateState(overworld.dimension());
    state.forcedRainByLowPlayers = false;
    return;
}
int minPlayers = env.clampedMinPlayers();

// when !gameRunning:
// REMOVE weather clear block — EnvironmentController owns lobby/post-match
if (!gameRunning) {
    state.forcedRainByLowPlayers = false;
    return;
}

// when alive >= min and forced:
if (state.forcedRainByLowPlayers) {
    EnvProfile match = EnvironmentController.getActiveMatchProfile(overworld);
    if (match != null && match.enabled) {
        EnvironmentController.applyWeatherOnly(overworld, match.weather);
    } else {
        overworld.setWeatherParameters(CLEAR_DURATION_TICKS, 0, false, false);
    }
    state.forcedRainByLowPlayers = false;
}
```

Add:

```java
public static boolean isForcingRain(ServerLevel level) {
    if (level == null) return false;
    DimensionWeatherState state = WEATHER_STATES.get(level.dimension());
    return state != null && state.forcedRainByLowPlayers;
}
```

Use `minPlayers` variable instead of `MIN_PLAYERS` constant for the comparison (keep constant as default documentation only or remove).

- [ ] **Step 2: Register controller events**

In `HabiTrainCore.onInitialize()` (or next to other registrars):

```java
EnvironmentController.registerEvents();
```

- [ ] **Step 3: ModTickHandler**

After weather controller tick:

```java
for (ServerLevel world : server.getAllLevels()) {
    if (world.dimension() == Level.OVERWORLD) {
        EnvironmentController.tick(world);
    }
}
```

- [ ] **Step 4: SERVER_STARTED lobby apply**

In `LifecycleEventsRegistrar` after config load:

```java
try {
    ServerLevel overworld = server.getLevel(Level.OVERWORLD);
    if (overworld != null) {
        EnvironmentController.applyLobby(overworld);
    }
} catch (Throwable t) {
    LOGGER.debug("initial lobby env apply skipped", t);
}
```

- [ ] **Step 5: Optional — re-apply lobby when config saved while idle**

If `ConfigManager.save()` already has callback hook used elsewhere, attach:

```java
// only if easy: in setEnvironmentSettings / markEnvironmentDirty path do not auto-apply from client thread.
// Server-side: EnvironmentController.tick idle reconcile every 5s is enough for v1.
```

Do **not** call apply from client GUI thread.

- [ ] **Step 6: Compile + commit**

```bash
./gradlew compileJava
git add src/main/java/com/habitrain/core/game/sre/SREWeatherController.java \
  src/main/java/com/habitrain/core/ModTickHandler.java \
  src/main/java/com/habitrain/core/LifecycleEventsRegistrar.java \
  src/main/java/com/habitrain/core/HabiTrainCore.java
git commit -m "feat(env): hook environment apply and gate low-player rain"
```

---

### Task 5: EnvironmentTabScreen UI

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/config/EnvironmentTabScreen.java`

**Interfaces:**
- Consumes: `ConfigManager.getEnvironmentSettings()`, `markEnvironmentDirty()`, `LiveConfigAccess`, `SharedGuiKit`, `SREModeStartAdapter` / vote map keys for list
- Produces: tab with same method surface as `GlobalTabScreen` / `VoteTabScreen`:
  - `render`, `mouseClicked`, `mouseDragged`, `mouseReleased`, `mouseScrolled`, `keyPressed`, `charTyped`

- [ ] **Step 1: Scaffold screen with 4 sub-tabs**

```java
public class EnvironmentTabScreen {
    public static final int SUB_LOBBY = 0;
    public static final int SUB_MATCH = 1;
    public static final int SUB_POST = 2;
    public static final int SUB_RAIN = 3;
    private static final String[] SUB_LABELS = {"大厅环境", "对局环境", "局后时间", "动态雨"};
    private static final int ACCENT = 0xFF55C28A;

    private final ConfigRootScreen root;
    private final Font font;
    private final boolean editable;
    private int subTab = SUB_LOBBY;
    // match list selection: null => editing defaultProfile; non-null => map id
    private String selectedMapId = null;
    // scroll state like GlobalTabScreen
}
```

- [ ] **Step 2: EnvProfile editor block**

Implement private helpers that draw/edit a profile and call `ConfigManager.getInstance().markEnvironmentDirty()` on each change:

- enable toggle
- time mode toggle PRESET/TICK
- preset cycle button (5 presets)
- tick EditBox + 应用
- weather cycle CLEAR/RAIN/THUNDER
- snow/sand/fog toggles
- fogEnd field (optional)
- daylightCycle / weatherCycle toggles

Always mutate the live object graph from `getEnvironmentSettings()` (same pattern as VoteTab mutating settings in place).

- [ ] **Step 3: Sub-tab contents**

**大厅:** editor bound to `settings.lobby`

**对局:**
- Left list rows: `§e默认(defaultProfile)` then each map id
- Map id sources (LinkedHashSet union):
  1. `settings.matchMaps.keySet()`
  2. `ConfigManager.getModeMapVoteSettings().maps.keySet()`
  3. If singleplayer server available: `SREModeStartAdapter.getAvailableMaps(overworld)`
- Selecting a map that has no entry: create `EnvProfile.createMatchDefault()` and put into `matchMaps`, mark dirty
- Right side: editor for selected profile
- Button「删除地图覆盖」only when map selected (remove key → fall back to default)

**局后时间:**
- Section 好人胜利 (`goodWin`): enable + time editor
- Section 杀手/中立等 (`otherWin`): enable + time editor
- Helper text: `好人 = isInnocentWin()；其余走杀手/中立`

**动态雨:**
- enable toggle `lowPlayerRainEnabled`
- minPlayers EditBox + 应用 (clamp ≥ 1)

- [ ] **Step 4: Permission**

If `!editable`, widgets inactive and clicks call `LiveConfigAccess.showDeniedMessage()` like Global tab.

- [ ] **Step 5: Compile**

```bash
./gradlew compileJava
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/EnvironmentTabScreen.java
git commit -m "feat(env): add Environment settings tab UI"
```

---

### Task 6: ConfigRootScreen integration

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/gui/config/ConfigRootScreen.java`

- [ ] **Step 1: Add 5th tab constants**

```java
public static final int TAB_ENV = 4;
private static final String[] TAB_LABELS = {"任务配置", "小游戏", "全局设置", "投票设置", "环境设置"};
private static final int[] TAB_ACCENTS = {
        0xFF57C6D6, 0xFFD4A55A, 0xFF8B6B47, 0xFF7C9CFF, 0xFF55C28A
};
private EnvironmentTabScreen envTab;
```

- [ ] **Step 2: init / render / input routing**

Mirror vote/global for `TAB_ENV` in:

- `init()` lazy create `envTab`
- `render` switch
- `mouseClicked`, `mouseDragged`, `mouseReleased`, `mouseScrolled`, `keyPressed`, `charTyped`

- [ ] **Step 3: Compile**

```bash
./gradlew compileJava
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/config/ConfigRootScreen.java
git commit -m "feat(env): wire Environment tab into config root"
```

---

### Task 7: Full build + acceptance smoke

**Files:** none new

- [ ] **Step 1: Clean build**

```bash
./gradlew clean build
```

Expected: BUILD SUCCESSFUL; jar under `build/libs/` and copied to `D:\Backup\mc mod\临时\` via `copyReleaseJar`.

- [ ] **Step 2: Manual acceptance checklist (playtest)**

1. Open Mod Menu → 哈比列车核心 → **环境设置** appears as 5th tab.
2. 大厅: set NIGHT + RAIN, save/close, idle world becomes night+rain (or within 5s reconcile).
3. 对局: enable map profile RAIN; start match on that map → rain; other map without override keeps native if default disabled.
4. 局后: enable only goodWin MIDNIGHT; force passenger win → midnight; killer win → lobby time.
5. 动态雨 off: <8 players mid-match → no forced rain.
6. 动态雨 on: <8 → rain; ≥8 → returns to match profile weather.
7. Non-OP on remote server: tab read-only.
8. Restart server: settings still in `config/habitrain_core.json` under `environment`.

- [ ] **Step 3: Final commit if any polish leftovers**

```bash
git add -A  # only env-related leftovers
git commit -m "chore(env): polish environment settings after smoke"
```

Only if there are real leftover fixes.

---

## Spec coverage checklist

| Spec requirement | Task |
|---|---|
| New root tab 环境设置 | 5–6 |
| Lobby full EnvProfile | 1, 5 |
| Match per-map + default | 1, 5 |
| Post-match good/other time + switches | 1, 3, 5 |
| PRESET + TICK time | 1, 3, 5 |
| snow/sand/fog/weather/cycles | 1, 3, 5 |
| lowPlayerRain enable + minPlayers | 1, 4, 5 |
| OnGameStarted / OnGameEnd / idle | 3, 4 |
| Persist + sync JSON | 2 |
| Do not edit map files | 3 (runtime only) |
| Win split isInnocentWin | 3 |
| Build + 临时 jar | 7 |

## Placeholder / consistency self-check

- No TBD steps.
- Types: `EnvTimeSpec.Mode`, `EnvTimeSpec.Preset`, `EnvProfile.Weather`, `EnvironmentSettings.resolveMatchProfile` used consistently across tasks.
- Weather restore uses `EnvironmentController.applyWeatherOnly` + `getActiveMatchProfile`.
- `isForcingRain` prevents maintain fighting rain controller.
