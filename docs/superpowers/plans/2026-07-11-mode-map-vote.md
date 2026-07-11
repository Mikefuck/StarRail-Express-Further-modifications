# Mode→Map Two-Phase Vote Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a public habi_api option-vote system that runs mode vote (15s) then map vote (15s), then `MapManager.loadMap` followed by mode start, with ModMenu controls for enable flags and display names.

**Architecture:** A dimension-scoped generic `OptionVoteManager` (string option candidates, not player UUIDs) plus `ModeMapVoteOrchestrator` state machine. Config lives under `modeMapVote` in `habitrain_core.json` and syncs via existing FullConfig JSON. SRE is only touched through a small adapter for `MapManager` / `GameUtils`.

**Tech Stack:** Fabric 1.21.1, Java 21, Gradle/Loom, StarRailExpress 4.3.0 (`libs/star_rail_express-4.3.0.jar`), existing habitrain_core config/network/GUI patterns.

## Global Constraints

- Java 21 (`options.release = 21`)
- Minecraft 1.21.1 + Fabric; hard dep on `starrailexpress` via local JAR
- No unit-test harness in this repo — verify with `./gradlew clean build` compile + manual runtime checklist
- After any code change: `./gradlew clean build` and ensure JAR lands in `D:\Backup\mc mod\临时\` (project CLAUDE.md / `copyReleaseJar`)
- Do **not** modify exile/sheriff vote managers or `VotePurpose.EXILE|SHERIFF` behavior
- Do **not** call SRE `MapVotingManager`
- Option IDs for modes use **GameModeRegistry fullId** (`modId:modeId`, e.g. `habitrain_core:habitrain:blackout`)
- Address user as Mike only in user-facing chat, not in commit messages
- Forbidden path: never touch `D:\Backup\mc mod\backup\`

## File Structure Map

### Create

```
src/main/java/com/habitrain/core/
├── api/
│   ├── VoteOption.java
│   ├── VoteResult.java
│   ├── ModeMapVoteConfig.java
│   ├── ModeMapVoteSnapshot.java
│   ├── OptionVoteApi.java
│   └── ModeMapVoteApi.java
├── vote/
│   ├── OptionVoteManager.java
│   └── ModeMapVoteOrchestrator.java
├── config/
│   ├── ModeMapVoteSettings.java
│   ├── ModeVoteEntry.java
│   └── MapVoteEntry.java
├── network/
│   ├── OptionVotePayload.java
│   └── OptionVoteCastPayload.java
├── game/sre/
│   └── SREModeStartAdapter.java
└── client/gui/
    ├── OptionVoteState.java
    ├── OptionVoteScreen.java
    └── config/
        ├── VoteTabScreen.java
        └── ModeAllowedMapsScreen.java
```

### Modify

```
src/main/java/com/habitrain/core/
├── config/ConfigRepository.java      — hold ModeMapVoteSettings
├── config/ConfigStore.java           — load/save/buildJsonRoot modeMapVote
├── config/ConfigManager.java         — accessors + ensureDefaults helpers
├── NetworkRegistrar.java             — register option vote payloads
├── C2SReceiverRegistrar.java         — route OptionVoteCastPayload
├── CommandRegistrar.java             — /habi_api vote start|cancel|status
├── ModTickHandler.java               — 1Hz option vote + orchestrator tick
├── LifecycleEventsRegistrar.java     — join resync, stop reset, optional defaults scan
├── client/NetworkReceiverRegistrar.java — S2C OptionVotePayload → OptionVoteState
├── client/BlackoutKeyHandler.java    — V prioritizes option vote when active
├── client/gui/BlackoutHudOverlay.java — tip when option vote active (if needed)
├── client/gui/config/ConfigRootScreen.java — 4th tab 投票设置
├── client/network/PayloadSenders.java — sendOptionVoteCast helper
└── resources/assets/habitrain_core/lang/{zh_cn,en_us}.json — keys if any
```

---

### Task 1: Config model (`modeMapVote`)

**Files:**
- Create: `src/main/java/com/habitrain/core/config/ModeVoteEntry.java`
- Create: `src/main/java/com/habitrain/core/config/MapVoteEntry.java`
- Create: `src/main/java/com/habitrain/core/config/ModeMapVoteSettings.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigRepository.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigStore.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java`

**Interfaces:**
- Consumes: existing `ConfigRepository` / `ConfigStore.buildJsonRoot` / `load` / `mergeFromJson` patterns
- Produces:
  - `ModeMapVoteSettings` with fields: `boolean enabled`, `int modeDurationSeconds`, `int mapDurationSeconds`, `Map<String, ModeVoteEntry> modes`, `Map<String, MapVoteEntry> maps`
  - `ModeVoteEntry`: `boolean enabled`, `String displayName`, `List<String> allowedMaps`
  - `MapVoteEntry`: `boolean enabled`, `String displayName`
  - `ConfigManager.getModeMapVoteSettings()` / `setModeMapVoteSettings(...)` / `ensureModeMapVoteDefaults(Collection<String> modeIds, Collection<String> mapIds)`

- [ ] **Step 1: Add entry classes**

```java
// ModeVoteEntry.java
package com.habitrain.core.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

public final class ModeVoteEntry {
    public boolean enabled = true;
    public String displayName = "";
    public List<String> allowedMaps = new ArrayList<>();

    public static ModeVoteEntry createDefault() {
        return new ModeVoteEntry();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("displayName", displayName != null ? displayName : "");
        JsonArray arr = new JsonArray();
        if (allowedMaps != null) {
            for (String id : allowedMaps) arr.add(id);
        }
        o.add("allowedMaps", arr);
        return o;
    }

    public static ModeVoteEntry fromJson(JsonObject o) {
        ModeVoteEntry e = new ModeVoteEntry();
        if (o.has("enabled")) e.enabled = o.get("enabled").getAsBoolean();
        if (o.has("displayName")) e.displayName = o.get("displayName").getAsString();
        e.allowedMaps = new ArrayList<>();
        if (o.has("allowedMaps") && o.get("allowedMaps").isJsonArray()) {
            for (var el : o.getAsJsonArray("allowedMaps")) {
                String s = el.getAsString();
                if (s != null && !s.isEmpty()) e.allowedMaps.add(s);
            }
        }
        return e;
    }
}
```

```java
// MapVoteEntry.java — same style: enabled + displayName, toJson/fromJson
package com.habitrain.core.config;

import com.google.gson.JsonObject;

public final class MapVoteEntry {
    public boolean enabled = true;
    public String displayName = "";

    public static MapVoteEntry createDefault() {
        return new MapVoteEntry();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("displayName", displayName != null ? displayName : "");
        return o;
    }

    public static MapVoteEntry fromJson(JsonObject o) {
        MapVoteEntry e = new MapVoteEntry();
        if (o.has("enabled")) e.enabled = o.get("enabled").getAsBoolean();
        if (o.has("displayName")) e.displayName = o.get("displayName").getAsString();
        return e;
    }
}
```

```java
// ModeMapVoteSettings.java
package com.habitrain.core.config;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModeMapVoteSettings {
    public boolean enabled = true;
    public int modeDurationSeconds = 15;
    public int mapDurationSeconds = 15;
    public final Map<String, ModeVoteEntry> modes = new LinkedHashMap<>();
    public final Map<String, MapVoteEntry> maps = new LinkedHashMap<>();

    public static ModeMapVoteSettings createDefault() {
        return new ModeMapVoteSettings();
    }

    public JsonObject toJson() { /* enabled, durations, modes{}, maps{} */ }

    public static ModeMapVoteSettings fromJson(JsonObject o) {
        ModeMapVoteSettings s = createDefault();
        // clamp durations to [5, 120]
        // parse modes/maps maps
        return s;
    }

    /** Insert missing keys only; never overwrite existing entries. */
    public void ensureDefaults(Iterable<String> modeIds, Iterable<String> mapIds) {
        for (String id : modeIds) {
            modes.computeIfAbsent(id, k -> ModeVoteEntry.createDefault());
        }
        for (String id : mapIds) {
            maps.computeIfAbsent(id, k -> MapVoteEntry.createDefault());
        }
    }
}
```

- [ ] **Step 2: Wire ConfigRepository**

Add field + getters/setters:

```java
private ModeMapVoteSettings modeMapVote = ModeMapVoteSettings.createDefault();

public ModeMapVoteSettings getModeMapVote() { return modeMapVote; }
public void setModeMapVote(ModeMapVoteSettings s) {
    this.modeMapVote = s != null ? s : ModeMapVoteSettings.createDefault();
}
```

- [ ] **Step 3: Wire ConfigStore load/save**

In `load(...)` after minigames block:

```java
if (root.has("modeMapVote") && root.get("modeMapVote").isJsonObject()) {
    repo.setModeMapVote(ModeMapVoteSettings.fromJson(root.getAsJsonObject("modeMapVote")));
} else {
    repo.setModeMapVote(ModeMapVoteSettings.createDefault());
}
```

In `buildJsonRoot(...)` before `return root`:

```java
root.add("modeMapVote", repo.getModeMapVote().toJson());
```

In load failure rebuild path and `createDefaultConfig`, reset `modeMapVote` to default.

- [ ] **Step 4: ConfigManager accessors**

```java
public ModeMapVoteSettings getModeMapVoteSettings() {
    return repository.getModeMapVote();
}

public void ensureModeMapVoteDefaults(java.util.Collection<String> modeIds,
                                      java.util.Collection<String> mapIds) {
    ModeMapVoteSettings s = repository.getModeMapVote();
    int beforeModes = s.modes.size();
    int beforeMaps = s.maps.size();
    s.ensureDefaults(modeIds, mapIds);
    if (s.modes.size() != beforeModes || s.maps.size() != beforeMaps) {
        store.markDirty();
    }
}
```

Confirm `toJsonString` / `mergeFromJsonString` / `applySyncFromJson` use `buildJsonRoot`/`load`-equivalent so `modeMapVote` rides existing FullConfig sync with **no new payload**.

- [ ] **Step 5: Compile check**

Run: `./gradlew compileJava`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/habitrain/core/config/ModeVoteEntry.java \
        src/main/java/com/habitrain/core/config/MapVoteEntry.java \
        src/main/java/com/habitrain/core/config/ModeMapVoteSettings.java \
        src/main/java/com/habitrain/core/config/ConfigRepository.java \
        src/main/java/com/habitrain/core/config/ConfigStore.java \
        src/main/java/com/habitrain/core/config/ConfigManager.java
git commit -m "feat(vote): add modeMapVote config model and JSON persistence"
```

---

### Task 2: Generic option vote engine + public API types

**Files:**
- Create: `src/main/java/com/habitrain/core/api/VoteOption.java`
- Create: `src/main/java/com/habitrain/core/api/VoteResult.java`
- Create: `src/main/java/com/habitrain/core/vote/OptionVoteManager.java`
- Create: `src/main/java/com/habitrain/core/api/OptionVoteApi.java`

**Interfaces:**
- Consumes: none from Task 1 required for compile (broadcast stubs OK)
- Produces:
  - `record VoteOption(String id, String displayName)`
  - `record VoteResult(String voteId, String winnerId, Map<String,Integer> tallies, boolean randomPick)`
  - `OptionVoteManager.start/cast/tickSecond/cancel/reset/isActive/onVoterRemoved/syncTo`
  - `OptionVoteApi` thin wrappers

- [ ] **Step 1: API value types**

```java
package com.habitrain.core.api;

public record VoteOption(String id, String displayName) {
    public VoteOption {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("option id");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
    }
}
```

```java
package com.habitrain.core.api;

import java.util.Map;
import javax.annotation.Nullable; // or org.jetbrains.annotations.Nullable

public record VoteResult(
        String voteId,
        @Nullable String winnerId,
        Map<String, Integer> tallies,
        boolean randomPick
) {}
```

- [ ] **Step 2: Implement OptionVoteManager**

Mirror exile patterns (`ConcurrentMap<ResourceKey<Level>, State>`, 1Hz tick, content-hash broadcast gate) but candidates are `List<VoteOption>`.

Key behavior:

```java
public final class OptionVoteManager {
    private static final class State {
        boolean active;
        String voteId;
        String title;
        String description;
        int remainingSeconds;
        int totalSeconds;
        final List<VoteOption> options = new ArrayList<>();
        final Map<UUID, String> votesByVoter = new HashMap<>(); // voter -> optionId
        Consumer<VoteResult> onResolved;
        int lastPayloadHash;
    }

    public static boolean start(ServerLevel level, String voteId, String title, String description,
                                List<VoteOption> options, int durationSeconds,
                                Consumer<VoteResult> onResolved) {
        // reject if active or options empty or duration < 1
        // copy options, set remaining=total=durationSeconds
        // broadcast active=true
        // return true
    }

    public static void cast(ServerLevel level, UUID voterId, @Nullable String optionId) {
        // if !active return
        // if optionId != null && not in candidates return
        // optionId null => remove vote; else put
        // broadcast
    }

    public static void tickSecond(ServerLevel level) {
        // remaining-- ; at 0 resolve
    }

    private static void resolve(ServerLevel level, State state) {
        // tallies for every option id (0 default)
        // max; ties -> random among top using level.getRandom()
        // if all tallies 0 -> random among all options, randomPick=true
        // state.active=false; broadcast close; invoke onResolved on server thread
    }

    public static void onVoterRemoved(ServerLevel level, UUID voterId) { /* remove vote; rebroadcast if active */ }
    public static void cancel(ServerLevel level) { /* active=false; clear callback without resolve OR resolve cancelled — use cancel without onResolved; broadcast close */ }
    public static void reset(ServerLevel level) { STATES.remove(level.dimension()); }
    public static boolean isActive(ServerLevel level) { ... }
    public static void syncTo(ServerPlayer player) { /* if active send current payload to one player */ }
}
```

For broadcast in this task, call a package-visible method that will be filled in Task 3:

```java
// temporary: log + no-op until OptionVotePayload exists, OR implement payload in same task if preferred.
// Prefer implementing payload in Task 3; here keep broadcastState(...) private method throwing no errors:
com.habitrain.core.network.OptionVotePayload.broadcastToAll(...); // added in Task 3 — implement Task 2+3 together if compile requires
```

**Implementation note:** If compile fails without payload, complete Task 3 network classes in the same commit wave before finishing this task’s compile step.

- [ ] **Step 3: OptionVoteApi**

```java
public final class OptionVoteApi {
    private OptionVoteApi() {}
    public static boolean start(ServerLevel level, String voteId, List<VoteOption> options,
                                int durationSeconds, Consumer<VoteResult> onResolved) {
        return OptionVoteManager.start(level, voteId, "投票", "", options, durationSeconds, onResolved);
    }
    public static boolean cast(ServerLevel level, UUID voter, String optionId) {
        OptionVoteManager.cast(level, voter, optionId);
        return OptionVoteManager.isActive(level);
    }
    public static boolean isActive(ServerLevel level) { return OptionVoteManager.isActive(level); }
    public static void cancel(ServerLevel level) { OptionVoteManager.cancel(level); }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileJava`  
Expected: SUCCESS (after Task 3 payloads exist — if blocked, proceed to Task 3 then return)

- [ ] **Step 5: Commit** (may be combined with Task 3)

```bash
git add src/main/java/com/habitrain/core/api/VoteOption.java \
        src/main/java/com/habitrain/core/api/VoteResult.java \
        src/main/java/com/habitrain/core/vote/OptionVoteManager.java \
        src/main/java/com/habitrain/core/api/OptionVoteApi.java
git commit -m "feat(vote): add generic OptionVoteManager and OptionVoteApi"
```

---

### Task 3: Network payloads + client state/screen + wiring

**Files:**
- Create: `src/main/java/com/habitrain/core/network/OptionVotePayload.java`
- Create: `src/main/java/com/habitrain/core/network/OptionVoteCastPayload.java`
- Create: `src/main/java/com/habitrain/core/client/gui/OptionVoteState.java`
- Create: `src/main/java/com/habitrain/core/client/gui/OptionVoteScreen.java`
- Modify: `src/main/java/com/habitrain/core/NetworkRegistrar.java`
- Modify: `src/main/java/com/habitrain/core/C2SReceiverRegistrar.java`
- Modify: `src/main/java/com/habitrain/core/client/NetworkReceiverRegistrar.java`
- Modify: `src/main/java/com/habitrain/core/client/network/PayloadSenders.java`
- Modify: `src/main/java/com/habitrain/core/client/BlackoutKeyHandler.java`
- Modify: `src/main/java/com/habitrain/core/client/gui/BlackoutHudOverlay.java` (optional tip line)
- Modify: `src/main/java/com/habitrain/core/client/ClientLifecycleHandler.java` (clear OptionVoteState on disconnect if such hook exists)

**Interfaces:**
- Consumes: `OptionVoteManager.cast`, payload field shapes
- Produces: working S2C/C2S option vote loop openable with V

- [ ] **Step 1: OptionVotePayload (S2C)**

Pattern after `BlackoutVotePayload`, but entries use `String optionId` not UUID:

```java
public record OptionVotePayload(
        String voteId,
        boolean active,
        int remainingSeconds,
        int totalSeconds,
        int maxSelections,
        String title,
        String description,
        List<Entry> candidates
) implements CustomPacketPayload {
    public record Entry(String optionId, String displayName, int votes) {}
    public static final Type<OptionVotePayload> TYPE = new Type<>(HabiTrainCore.id("option_vote"));
    // CODEC with MAX_CANDIDATES=64, utf limits on ids/names
    public static void register() { PayloadTypeRegistry.playS2C().register(TYPE, CODEC); }
    public static void broadcastToAll(MinecraftServer server, ...) { ... }
    public static void sendTo(ServerPlayer player, ...) { ... }
}
```

- [ ] **Step 2: OptionVoteCastPayload (C2S)**

```java
public record OptionVoteCastPayload(String voteId, @Nullable String optionId) implements CustomPacketPayload {
    // write: voteId utf(64); hasOption bool; optional optionId utf(64)
    public static void register() { PayloadTypeRegistry.playC2S().register(TYPE, CODEC); }
}
```

IDs: `habitrain_core:option_vote` / `habitrain_core:option_vote_cast`.

- [ ] **Step 3: Hook manager broadcast + registrars**

`OptionVoteManager.broadcastState` → `OptionVotePayload.broadcastToAll`.

`NetworkRegistrar.init`: `OptionVotePayload.register(); OptionVoteCastPayload.register();` and bump log count.

`C2SReceiverRegistrar`:

```java
ServerPlayNetworking.registerGlobalReceiver(OptionVoteCastPayload.TYPE, (payload, context) -> {
    context.server().execute(() -> {
        ServerPlayer voter = context.player();
        if (voter == null) return;
        OptionVoteManager.cast(voter.serverLevel(), voter.getUUID(), payload.optionId());
        // optionally ignore mismatched voteId inside manager
    });
});
```

Inside `cast`, if `payload.voteId` does not match state.voteId, no-op.

- [ ] **Step 4: Client state + screen**

`OptionVoteState` — static fields mirroring payload; `update(OptionVotePayload)`; `clear()`; getters; `isSelected(String)`; `getSelectedOptionId()`.

`OptionVoteScreen` — copy structure from `BlackoutVoteScreen` but:
- rows use `Entry.displayName` / `optionId`
- click → `PayloadSenders.sendOptionVoteCast(voteId, optionId)` or revoke if already selected
- close when `!OptionVoteState.isActive()`

`PayloadSenders`:

```java
public static void sendOptionVoteCast(String voteId, @Nullable String optionId) {
    ClientPlayNetworking.send(new OptionVoteCastPayload(voteId, optionId));
}
```

`NetworkReceiverRegistrar`:

```java
ClientPlayNetworking.registerGlobalReceiver(OptionVotePayload.TYPE, (payload, context) -> {
    context.client().execute(() -> OptionVoteState.update(payload));
});
```

- [ ] **Step 5: Key priority**

In `BlackoutKeyHandler.openVote`:

```java
// Highest priority: generic option vote (mode/map lobby vote)
if (OptionVoteState.isActive()) {
    if (client.screen instanceof OptionVoteScreen) return;
    client.setScreen(new OptionVoteScreen(client.screen));
    return;
}
// existing sheriff then exile...
```

- [ ] **Step 6: Compile**

Run: `./gradlew compileJava`  
Expected: SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/habitrain/core/network/OptionVotePayload.java \
        src/main/java/com/habitrain/core/network/OptionVoteCastPayload.java \
        src/main/java/com/habitrain/core/client/gui/OptionVoteState.java \
        src/main/java/com/habitrain/core/client/gui/OptionVoteScreen.java \
        src/main/java/com/habitrain/core/NetworkRegistrar.java \
        src/main/java/com/habitrain/core/C2SReceiverRegistrar.java \
        src/main/java/com/habitrain/core/client/NetworkReceiverRegistrar.java \
        src/main/java/com/habitrain/core/client/network/PayloadSenders.java \
        src/main/java/com/habitrain/core/client/BlackoutKeyHandler.java \
        src/main/java/com/habitrain/core/vote/OptionVoteManager.java
git commit -m "feat(vote): option vote network payload and client UI"
```

---

### Task 4: SRE adapter + ModeMap orchestrator + ModeMapVoteApi

**Files:**
- Create: `src/main/java/com/habitrain/core/game/sre/SREModeStartAdapter.java`
- Create: `src/main/java/com/habitrain/core/vote/ModeMapVoteOrchestrator.java`
- Create: `src/main/java/com/habitrain/core/api/ModeMapVoteConfig.java`
- Create: `src/main/java/com/habitrain/core/api/ModeMapVoteSnapshot.java`
- Create: `src/main/java/com/habitrain/core/api/ModeMapVoteApi.java`

**Interfaces:**
- Consumes: `OptionVoteManager`, `ModeMapVoteSettings`, `GameModeRegistry`, SRE `MapManager`/`GameUtils`/`SREGameModes`/`SREGameWorldComponent`
- Produces: `ModeMapVoteApi.start/cancel/isRunning/getSnapshot`

- [ ] **Step 1: SREModeStartAdapter**

```java
package com.habitrain.core.game.sre;

public final class SREModeStartAdapter {
    private SREModeStartAdapter() {}

    public static boolean isSreGameBlocking(ServerLevel level) {
        try {
            if (io.wifi.starrailexpress.game.GameUtils.isStartingGame) return true;
            var gw = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(level);
            return gw != null && gw.isRunning();
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<String> getAvailableMaps(ServerLevel level) {
        try {
            return new ArrayList<>(io.wifi.starrailexpress.game.MapManager.getAvailableMaps(level));
        } catch (Throwable t) {
            return List.of();
        }
    }

    public static boolean loadMap(ServerLevel level, String mapId) {
        try {
            return io.wifi.starrailexpress.game.MapManager.loadMap(level, mapId);
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.error("loadMap failed: {}", mapId, t);
            return false;
        }
    }

    /**
     * @param registryFullId e.g. habitrain_core:habitrain:blackout
     */
    public static boolean startMode(ServerLevel level, String registryFullId) {
        // blackout path
        if (registryFullId.endsWith(":habitrain:blackout")
                || "habitrain_core:habitrain:blackout".equals(registryFullId)) {
            GameModeRegistry.start("habitrain_core:habitrain:blackout", level);
            return true;
        }
        // murder
        if (registryFullId.contains("sre:murder")) {
            var mode = io.wifi.starrailexpress.api.SREGameModes.MURDER;
            int ticks = io.wifi.starrailexpress.game.GameConstants.getInTicks(mode.defaultStartTime, 0);
            io.wifi.starrailexpress.game.GameUtils.startGame(level, mode, ticks);
            return true;
        }
        // repair
        if (registryFullId.contains("sre:repair")) {
            var mode = io.wifi.starrailexpress.api.SREGameModes.REPAIR_ESCAPE_MODE;
            int ticks = io.wifi.starrailexpress.game.GameConstants.getInTicks(mode.defaultStartTime, 0);
            io.wifi.starrailexpress.game.GameUtils.startGame(level, mode, ticks);
            return true;
        }
        // generic explicit registry start
        if (GameModeRegistry.isRegistered(registryFullId)) {
            GameModeRegistry.start(registryFullId, level);
            return true;
        }
        return false;
    }
}
```

If murder/repair need forced ready in practice, after verifying runtime, call `GameUtils.setForcedReadyPlayers(...)` with online player UUIDs before `startGame` — document in commit if used.

- [ ] **Step 2: ModeMapVoteConfig / Snapshot records**

```java
public final class ModeMapVoteConfig {
    public int modeDurationSeconds = -1; // -1 = use settings
    public int mapDurationSeconds = -1;
    public List<String> modeIds; // null = all
    public List<String> mapIds;  // null = all available
}

public record ModeMapVoteSnapshot(
        String phase, // IDLE|MODE_VOTING|MAP_VOTING|SWITCHING_MAP|STARTING_MODE
        @Nullable String selectedModeId,
        @Nullable String selectedMapId,
        int remainingSeconds
) {}
```

- [ ] **Step 3: ModeMapVoteOrchestrator**

```java
public final class ModeMapVoteOrchestrator {
    public enum Phase { IDLE, MODE_VOTING, MAP_VOTING, SWITCHING_MAP, STARTING_MODE }

    private static final class Session {
        Phase phase = Phase.IDLE;
        ModeMapVoteConfig config;
        String selectedModeId;
        String selectedMapId;
        // optional: cache resolved mode display for messages
    }

    public static boolean start(ServerLevel level, ModeMapVoteConfig config) { ... }
    public static void cancel(ServerLevel level) { ... }
    public static boolean isRunning(ServerLevel level) { ... }
    public static ModeMapVoteSnapshot snapshot(ServerLevel level) { ... }
    public static void reset(ServerLevel level) { ... }
    public static void onPlayerJoin(ServerPlayer player) {
        OptionVoteManager.syncTo(player);
    }
}
```

`start` algorithm:

1. Read `ModeMapVoteSettings s = ConfigManager.getInstance().getModeMapVoteSettings()`
2. If `!s.enabled` → false
3. If session running or `OptionVoteManager.isActive` → false
4. If `SREModeStartAdapter.isSreGameBlocking(level)` → false
5. If `GameModeRegistry.isActiveInLevel(level)` → false
6. Collect mode fullIds from `GameModeRegistry.getAllIds()` (or registry keys — **use the same string `GameModeRegistry.register` stored**, i.e. `getAllIds()`)
7. Filter by `config.modeIds` if non-null
8. Filter by `s.modes.get(id).enabled` (missing entry = enabled)
9. `ensureModeMapVoteDefaults(modeIds, mapIdsScan)` where mapIdsScan = `getAvailableMaps`
10. Build `List<VoteOption>` with displayName from settings or `GameMode.getDisplayName()`
11. If empty → false
12. duration = config.modeDurationSeconds > 0 ? config : s.modeDurationSeconds
13. `OptionVoteManager.start(level, "mode", "模式投票", "选择本局游戏模式", options, duration, result -> onModeResolved(level, result))`
14. session.phase = MODE_VOTING

`onModeResolved`:

1. session.selectedModeId = result.winnerId(); announce
2. Build map options:
   - available = adapter.getAvailableMaps
   - filter maps.enabled
   - modeEntry.allowedMaps non-empty → intersect
   - config.mapIds non-null → intersect
   - displayName from settings
3. If empty → announce, phase IDLE, return
4. Start option vote voteId=`map`, title 地图投票, duration map seconds
5. phase MAP_VOTING

`onMapResolved`:

1. selectedMapId = winner
2. phase SWITCHING_MAP; subtitle 正在加载地图
3. `loadMap`; on fail announce + IDLE
4. phase STARTING_MODE
5. `startMode(selectedModeId)`; on fail announce (map stays)
6. phase IDLE; clear session

Use `SubtitleNotifier.sendTop` to all players in level for announcements.

- [ ] **Step 4: ModeMapVoteApi**

```java
public final class ModeMapVoteApi {
    public static boolean start(ServerLevel level) {
        return start(level, new ModeMapVoteConfig());
    }
    public static boolean start(ServerLevel level, ModeMapVoteConfig config) {
        return ModeMapVoteOrchestrator.start(level, config != null ? config : new ModeMapVoteConfig());
    }
    public static boolean cancel(ServerLevel level) {
        ModeMapVoteOrchestrator.cancel(level);
        return true;
    }
    public static boolean isRunning(ServerLevel level) {
        return ModeMapVoteOrchestrator.isRunning(level);
    }
    public static Optional<ModeMapVoteSnapshot> getSnapshot(ServerLevel level) {
        return Optional.ofNullable(ModeMapVoteOrchestrator.snapshot(level));
    }
}
```

- [ ] **Step 5: Compile**

Run: `./gradlew compileJava`  
Expected: SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/SREModeStartAdapter.java \
        src/main/java/com/habitrain/core/vote/ModeMapVoteOrchestrator.java \
        src/main/java/com/habitrain/core/api/ModeMapVoteConfig.java \
        src/main/java/com/habitrain/core/api/ModeMapVoteSnapshot.java \
        src/main/java/com/habitrain/core/api/ModeMapVoteApi.java
git commit -m "feat(vote): mode→map orchestrator and SRE start adapter"
```

---

### Task 5: Commands + tick + lifecycle hooks

**Files:**
- Modify: `src/main/java/com/habitrain/core/CommandRegistrar.java`
- Modify: `src/main/java/com/habitrain/core/ModTickHandler.java`
- Modify: `src/main/java/com/habitrain/core/LifecycleEventsRegistrar.java`

**Interfaces:**
- Consumes: `ModeMapVoteApi`, `OptionVoteManager`
- Produces: runnable `/habi_api vote` and 1Hz ticks

- [ ] **Step 1: Commands**

Under existing `habi_api` literal, add:

```java
.then(Commands.literal("vote")
    .requires(source -> source.hasPermission(2))
    .then(Commands.literal("start").executes(ctx -> {
        ServerLevel level = ctx.getSource().getLevel();
        boolean ok = ModeMapVoteApi.start(level);
        if (ok) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a已启动模式→地图投票"), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("§c无法启动投票（已禁用/进行中/对局已运行/无候选）"));
        return 0;
    }))
    .then(Commands.literal("cancel").executes(ctx -> {
        ModeMapVoteApi.cancel(ctx.getSource().getLevel());
        ctx.getSource().sendSuccess(() -> Component.literal("§e已取消投票"), true);
        return 1;
    }))
    .then(Commands.literal("status").executes(ctx -> {
        var snap = ModeMapVoteApi.getSnapshot(ctx.getSource().getLevel()).orElse(null);
        if (snap == null || "IDLE".equals(snap.phase())) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7当前无投票"), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal(
                "§e阶段: " + snap.phase()
                + " §7剩余: " + snap.remainingSeconds()
                + "s §e模式: " + snap.selectedModeId()
                + " §e地图: " + snap.selectedMapId()), false);
        }
        return 1;
    }))
)
```

Update log line listing commands.

- [ ] **Step 2: Tick**

In `ModTickHandler`, keep a static `tickAccumulator` or reuse a simple counter:

```java
private static int voteTickCounter = 0;

// inside END_SERVER_TICK:
voteTickCounter++;
if (voteTickCounter % 20 == 0) {
    for (ServerLevel level : server.getAllLevels()) {
        OptionVoteManager.tickSecond(level);
    }
}
```

Orchestrator resolve is callback-driven from OptionVoteManager; no separate orchestrator tick unless SWITCHING needs async — keep loadMap synchronous on resolve callback (server thread).

- [ ] **Step 3: Lifecycle**

`SERVER_STOPPING` loop: `OptionVoteManager.reset(level); ModeMapVoteOrchestrator.reset(level);`

`JOIN`: after config sync, `ModeMapVoteOrchestrator.onPlayerJoin(player);`

`DISCONNECT`: `OptionVoteManager.onVoterRemoved(player.serverLevel(), player.getUUID());`

`SERVER_STARTED` (optional): after freeze, for overworld call `ensureModeMapVoteDefaults` with `GameModeRegistry.getAllIds()` and empty maps or try getAvailableMaps if level ready — if maps require level, do defaults on first `vote start` only (already in orchestrator). Prefer orchestrator-only to avoid empty world path issues.

- [ ] **Step 4: Compile + commit**

```bash
./gradlew compileJava
git add src/main/java/com/habitrain/core/CommandRegistrar.java \
        src/main/java/com/habitrain/core/ModTickHandler.java \
        src/main/java/com/habitrain/core/LifecycleEventsRegistrar.java
git commit -m "feat(vote): wire vote commands, tick, and lifecycle"
```

---

### Task 6: ModMenu 投票设置 Tab

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/config/VoteTabScreen.java`
- Create: `src/main/java/com/habitrain/core/client/gui/config/ModeAllowedMapsScreen.java`
- Modify: `src/main/java/com/habitrain/core/client/gui/config/ConfigRootScreen.java`
- Modify: save path used by other tabs (likely `ConfigUpdatePayload` via existing save helper — follow `GlobalTabScreen` / `TaskTabScreen` patterns)

**Interfaces:**
- Consumes: `ConfigManager.getModeMapVoteSettings()`, `GameModeRegistry` (client may only see synced settings + known mode ids from config keys), `LiveConfigAccess`
- Produces: editable UI that persists `modeMapVote` through existing config save/sync

- [ ] **Step 1: Expand ConfigRootScreen tabs**

```java
public static final int TAB_VOTE = 3;
private static final String[] TAB_LABELS = {"任务配置", "小游戏", "全局设置", "投票设置"};
private static final int[] TAB_ACCENTS = { 0xFF57C6D6, 0xFFD4A55A, 0xFF8B6B47, 0xFF7C9CFF };
// lazy-init VoteTabScreen voteTab
// render/click switch case TAB_VOTE
```

- [ ] **Step 2: VoteTabScreen**

Layout responsibilities (match SharedGuiKit style of other tabs):

1. Checkbox/toggle: `settings.enabled`
2. Two numeric fields or cycle buttons: mode/map duration (clamp 5–120)
3. Scrollable mode rows from `settings.modes` union known registry ids if available client-side:
   - enable toggle
   - displayName text field
   - button「可选地图」→ `ModeAllowedMapsScreen`
4. Scrollable map rows from `settings.maps`:
   - enable toggle
   - displayName text field
5. Save button: write back into `ConfigManager.getModeMapVoteSettings()`, `markDirty`/`save` / send `ConfigUpdatePayload` like other tabs when `remoteEditable`

Read-only when `!remoteEditable` — reuse denial helper.

- [ ] **Step 3: ModeAllowedMapsScreen**

Parent: VoteTabScreen or ConfigRootScreen.  
List all keys in `settings.maps` (and any in `allowedMaps` not in maps).  
Multi-select checkboxes; **Save**: set `modeEntry.allowedMaps` to selected ids; empty selection clears list (meaning unrestricted per spec).

- [ ] **Step 4: Ensure client config apply path keeps modeMapVote**

If `mergeFromJson` / `applySyncFromJson` re-load full root via ConfigStore methods, Task 1 already covers it. If merge is hand-rolled field-by-field, **must** add `modeMapVote` branch — inspect `ConfigSync.java` / `ConfigManager.mergeFromJsonString` and patch.

- [ ] **Step 5: Compile + commit**

```bash
./gradlew compileJava
git add src/main/java/com/habitrain/core/client/gui/config/
git commit -m "feat(vote): ModMenu vote settings tab for modes and maps"
```

---

### Task 7: End-to-end hardening + full build

**Files:**
- Touch-ups only as needed (lang keys, HUD tip, null-safety)
- Possibly `ClientLifecycleHandler` clear `OptionVoteState`

- [ ] **Step 1: Disconnect clear**

On client disconnect/reset, `OptionVoteState.clear()` next to blackout vote clears.

- [ ] **Step 2: Manual runtime checklist** (document results in commit message or leave for Mike)

1. `/habi_api vote start` with ≥1 player — mode UI via V  
2. Vote / wait 15s — map UI appears with filtered maps  
3. After map — map loads then mode starts (test blackout at least)  
4. Disable a mode in ModMenu — absent from next start  
5. Mode allowedMaps restricts map list  
6. Map displayName shows Chinese  
7. Total switch off — start fails  
8. During SRE running game — start fails  
9. Exile vote still works in blackout  
10. `/habi_api vote cancel` mid-mode  

- [ ] **Step 3: Full build + jar copy**

Run:

```bash
./gradlew clean build
```

Expected: BUILD SUCCESSFUL; jar in `build/libs/` and `D:\Backup\mc mod\临时\` via `copyReleaseJar`.

- [ ] **Step 4: Final commit**

```bash
git add -A  # only vote-related leftovers
git commit -m "feat(vote): finalize mode-map vote integration"
```

---

## Spec Coverage Checklist

| Spec section | Task |
|--------------|------|
| OptionVoteApi / generic engine | 2 |
| ModeMapVoteApi + state machine | 4 |
| 15s+15s durations configurable | 1, 4, 6 |
| Mode candidates from GameModeRegistry + enable filter | 4, 1 |
| Maps from train_maps/MapManager + enable + per-mode whitelist | 4, 1 |
| Display names config + ModMenu | 1, 6 |
| Full ModMenu suite (master switch, mode switch/name/maps, map switch/name) | 6 |
| loadMap then startMode | 4 |
| Highest vote / tie random / 0-vote random | 2 |
| Commands vote start/cancel/status | 5 |
| Network + OptionVoteScreen + V priority | 3 |
| Lifecycle join/stop/disconnect | 5 |
| No exile/sheriff changes / no MapVotingManager | all tasks |
| FullConfig sync for settings | 1 (+6 verify merge) |
| Build + 临时 jar | 7 |

## Placeholder / consistency notes (self-review)

- Option IDs: always registry **fullId** for modes; map **file id** from `MapManager`.
- `OptionVoteManager` must accept `voteId` on cast to ignore stale packets when map phase starts (`mode` → `map`).
- `cancel` must cancel both orchestrator session and active option vote without starting loadMap.
- Config merge path: verify `ConfigSync` / `mergeFromJsonString` not field-whitelisted before claiming ModMenu multiplayer save works.
- No unit tests in repo — compile + runtime checklist only.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-11-mode-map-vote.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks  
2. **Inline Execution** — same session with `executing-plans`, batched with checkpoints  

Which approach?
