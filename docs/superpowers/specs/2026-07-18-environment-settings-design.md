# Environment Settings (Lobby / Match / Post-Match) Design

**Date:** 2026-07-18  
**Status:** Approved for planning  
**Mod:** `habitrain_core` (哈比列车api)  
**Approach:** Config-center new tab + runtime `EnvironmentController` (Approach A)

## 1. Problem

Every match end currently forces a daytime / clear-weather feel:

- `SREWeatherController` clears rain it forced when the match stops.
- SRE `SRETrainWorldComponent` defaults / maintains `TimeOfDay.DAY` (1000).

Operators want explicit control over:

1. **Lobby** environment after matches and while idle.
2. **In-match** environment, selected automatically by **map id**.
3. **Post-match time** that depends on who won (good vs killer/neutral), with independent toggles.
4. Compatibility with the existing **low-player auto-rain** feature, including a master switch.

## 2. Goals

- Add a dedicated Mod Menu config tab: **环境设置**.
- Persist settings in `habitrain_core.json` under `environment`.
- Apply settings at clear lifecycle points without editing map files on disk.
- Support fixed time presets **and** arbitrary day-time ticks.
- Keep multiplayer edit rules identical to other config tabs (OP-only remote edit).

## 3. Non-goals

- Editing SRE map asset files / permanent `AreasSettings` on disk.
- Client-side authoritative weather/time writes.
- Per-player environments.
- Full fog-shape editor beyond enable + `fogEnd` (if API allows).

## 4. Architecture

```
ConfigRootScreen (+ TAB_ENV)
        │
        ▼
EnvironmentTabScreen  ──读写──▶  EnvironmentSettings (repo/store/sync)
                                        │
                                        ▼
                              EnvironmentController (server)
                     ┌──────────┼──────────┐
                     ▼          ▼          ▼
              ServerLevel  SRETrainWorld  SREWeatherController
              day/weather  snow/sand/fog  low-player rain (gated)
```

| Unit | Responsibility | Must not |
|---|---|---|
| `EnvTimeSpec` / `EnvProfile` / `PostMatchTimeRule` / `EnvironmentSettings` | Data + JSON + defaults | Touch world |
| `EnvironmentTabScreen` | Edit UI | Apply world state |
| `EnvironmentController` | Apply/maintain world from settings | Own long-lived UI state |
| `SREWeatherController` | Optional dynamic rain while match running | Steal post-match/lobby control when disabled |

### 4.1 Apply triggers

| Trigger | Action |
|---|---|
| Server ready / idle reconcile | `applyLobby(level)` |
| `OnGameStarted` | Resolve map id → `applyMatch(level, mapId)` |
| `OnGameEnd` | `applyPostMatch(level, lastWinStatus)` |
| Config save while idle | Re-`applyLobby` |
| Match tick (1s) | Low-player rain (if enabled) + cycle locks |
| Idle tick (low frequency) | Reconcile lobby if enabled (defend against other systems) |

### 4.2 Priority (high → low)

1. In-match low-player rain **only if** `lowPlayerRain.enabled`.
2. Phase profile: match map profile / post-match time rule / lobby profile.
3. Map-native `AreasSettings` (left alone when our profile `enabled=false`).

Runtime overrides only; map files are never written.

## 5. Data model

Root key in `config/habitrain_core.json`:

```json
{
  "environment": {
    "lobby": { /* EnvProfile */ },
    "match": {
      "defaultProfile": { /* EnvProfile */ },
      "maps": {
        "<mapId>": { /* EnvProfile */ }
      }
    },
    "postMatch": {
      "goodWin": { "enabled": false, "time": { /* EnvTimeSpec */ } },
      "otherWin": { "enabled": false, "time": { /* EnvTimeSpec */ } }
    },
    "lowPlayerRain": {
      "enabled": true,
      "minPlayers": 8
    }
  }
}
```

### 5.1 `EnvTimeSpec`

| Field | Type | Notes |
|---|---|---|
| `mode` | `PRESET` \| `TICK` | Default `PRESET` |
| `preset` | enum | `DAY`(1000), `NOON`(6000), `NIGHT`(13000), `MIDNIGHT`(18000), `SUNDOWN`(12800) — same values as SRE `SRETrainWorldComponent.TimeOfDay` |
| `tick` | int | Used when `mode=TICK`; clamp `0..23999` |

Invalid preset → `DAY`. Missing fields → defaults.

### 5.2 `EnvProfile`

| Field | Type | Default (lobby) | Default (match default) |
|---|---|---|---|
| `enabled` | bool | `true` | `false` (do not steal map-native until configured) |
| `time` | EnvTimeSpec | PRESET/DAY | PRESET/DAY |
| `weather` | `CLEAR` \| `RAIN` \| `THUNDER` | `CLEAR` | `CLEAR` |
| `snow` | bool | `true` | `true` |
| `sand` | bool | `true` | `true` |
| `fog` | bool | `true` | `true` |
| `fogEnd` | float | `192.0` | `192.0` |
| `daylightCycle` | bool | `false` | `false` |
| `weatherCycle` | bool | `false` | `false` |

`enabled=false` means: **do not apply this profile at all** for that phase.

### 5.3 `PostMatchTimeRule`

| Field | Type | Default |
|---|---|---|
| `enabled` | bool | `false` |
| `time` | EnvTimeSpec | PRESET/DAY for both rules (safe if enabled without further edits) |

Two rules:

- `goodWin` — when `WinStatus.isInnocentWin() == true`
- `otherWin` — all other statuses (killer, neutral/custom, none, null, `NOT_MODIFY`, etc.)

Post-match rules change **time only**. Weather / snow / sand / fog always follow lobby profile when lobby is enabled; if lobby disabled and only a post-match time rule is enabled, only time is written.

### 5.4 `lowPlayerRain`

| Field | Type | Default |
|---|---|---|
| `enabled` | bool | `true` (preserve current behavior) |
| `minPlayers` | int | `8` (clamp ≥ 1) |

### 5.5 Match profile resolution

```
profile =
  maps[mapId] if present
  else match.defaultProfile

if profile.enabled → apply
else → no match override (map-native remains)
```

`mapId` source: `AreasWorldComponent.mapName` on the started level. Empty / missing → `defaultProfile` only.

Map list for UI: union of

- `SREModeStartAdapter.getAvailableMaps` when a world/server is available
- existing keys in `match.maps`
- keys known from mode-map vote settings (same idea as Vote tab)

### 5.6 Code types (suggested paths)

- `com.habitrain.core.config.EnvTimeSpec`
- `com.habitrain.core.config.EnvProfile`
- `com.habitrain.core.config.PostMatchTimeRule`
- `com.habitrain.core.config.EnvironmentSettings`
- `com.habitrain.core.game.sre.EnvironmentController`
- `com.habitrain.core.client.gui.config.EnvironmentTabScreen`
- Wire through `ConfigRepository`, `ConfigManager`, `ConfigStore`, `ConfigSync`

## 6. UI

### 6.1 Root tab

`ConfigRootScreen`:

- Add `TAB_ENV = 4`
- Label: `环境设置`
- Accent: distinct from existing four (e.g. `0xFF55C28A`)
- Lazy-init `EnvironmentTabScreen` like other tabs
- Forward mouse/keyboard like Global/Vote tabs

### 6.2 Sub-tabs inside Environment tab

1. **大厅环境** — full `EnvProfile` editor for `lobby`
2. **对局环境** — left map list + right `EnvProfile`; button/row for `defaultProfile`
3. **局后时间** — `goodWin` / `otherWin` enable toggles + time editors
4. **动态雨** — `lowPlayerRain.enabled` + `minPlayers`

### 6.3 Shared `EnvProfile` editor widgets

- Master enable toggle
- Time: mode cycle `PRESET` / `TICK`
  - PRESET: cycle or buttons for DAY/NOON/NIGHT/MIDNIGHT/SUNDOWN
  - TICK: `EditBox` 0–23999 + apply
- Weather: cycle CLEAR/RAIN/THUNDER
- Toggles: snow, sand, fog
- Optional `fogEnd` field when fog on
- Toggles: daylightCycle, weatherCycle

Immediate write path matches Global tab: mutate `ConfigManager` + mark dirty / save on change (same persistence pattern as other settings).

### 6.4 Permissions

Reuse `LiveConfigAccess.canEditRemoteConfigs()` / denied toast. Non-OP remote clients remain read-only.

## 7. Runtime application details

### 7.1 Writing a profile

For an enabled profile on a `ServerLevel`:

1. **Time**
   - `PRESET`: `level.setDayTime(preset.time)` and `SRETrainWorldComponent.setTimeOfDay(preset)`
   - `TICK`: `level.setDayTime(tick)`; if tick equals a known preset value, also set that `TimeOfDay`
2. **Weather** via `level.setWeatherParameters(...)`
   - CLEAR: long clear duration, rain=0, raining=false, thundering=false
   - RAIN: clear=0, long rain, raining=true, thundering=false
   - THUNDER: clear=0, long rain, raining=true, thundering=true
   - Use long durations (e.g. 10 minutes of ticks) to avoid short bounce-back
3. **Snow / sand / fog** via `SRETrainWorldComponent` setters
4. Sync train component when dirty

All SRE/world calls wrapped in try/catch; log and continue per field.

### 7.2 Post-match

```
OnGameEnd(level, gameWorld):
  status = gameWorld.getLastWinStatus()  // null-safe
  rule = (status != null && status.isInnocentWin()) ? goodWin : otherWin

  if (rule.enabled):
      if lobby.enabled: apply lobby non-time fields
      apply rule.time
  else:
      applyLobby(level)   // no-op if lobby.disabled
```

### 7.3 Low-player rain compatibility

Update `SREWeatherController`:

| Condition | Behavior |
|---|---|
| `lowPlayerRain.enabled == false` | Entire controller no-op (including end-of-match clear) |
| Enabled + match running + alive < minPlayers | Force rain (existing mechanism flag) |
| Enabled + match running + alive ≥ minPlayers | If we forced rain, restore **current match profile weather** (not unconditional CLEAR) |
| Enabled + match not running | Do **not** clear weather here; `EnvironmentController` owns lobby/post-match |

`minPlayers` read from config each check (or cached on config reload).

### 7.4 Cycle locks

When the active phase profile has `daylightCycle=false` / `weatherCycle=false`, maintain configured time/weather on a low-frequency tick so vanilla/SRE drift cannot undo them during that phase.

When cycle flags are true, do not re-lock every tick (allow natural progression after initial apply).

### 7.5 Config hot reload

| Current phase | On environment save |
|---|---|
| Idle / lobby | Immediately `applyLobby` |
| In match | Do not full re-apply mid-match (avoid jarring snaps); low-player rain switch + minPlayers take effect next rain check; cycle maintain uses new flags if already in match only for future ticks if we re-read config live |

Simplest consistent rule: **controller always reads latest ConfigManager state** on each apply/tick; mid-match full profile re-apply is **not** forced on save.

## 8. Integration points

| Location | Change |
|---|---|
| `ConfigRootScreen` | 5th tab + routing |
| `ConfigRepository` / `ConfigManager` | hold + expose `EnvironmentSettings` |
| `ConfigStore` / `ConfigSync` | load/save/sync `environment` |
| `SREGameModeBase` or dedicated bootstrap | register OnGameStarted/OnGameEnd for environment |
| `ModTickHandler` | idle reconcile + optional maintain |
| `SREWeatherController` | gate on `lowPlayerRain` + restore match weather not blind CLEAR |

Existing voice-group `OnGameEnd` handler stays; environment registers independently (order: either is fine if both only use world/component state already set by SRE).

## 9. Error handling & boundaries

- Missing `environment` key → full defaults; never wipe other config sections.
- Unknown map id → default match profile only.
- Null / unknown win status → `otherWin` branch.
- SRE classes missing → controller logs once-level errors, no crash.
- Multi-world: act on the `ServerLevel` provided by SRE events; lobby reconcile uses the same overworld/level policy as existing SRE helpers.
- Illegal JSON numbers/enums → clamp / default per field, not whole-file reset.

## 10. Acceptance criteria

1. Idle lobby reflects lobby profile immediately after edit (when enabled).
2. Match start applies per-map profile when enabled; otherwise leaves map-native env.
3. Good-win and other-win post-match time toggles work independently; only time differs from lobby weather stack.
4. Low-player rain master switch off → no forced rain and no end-match weather steal; on → rain below threshold, restore match weather above threshold.
5. Settings persist in `habitrain_core.json` and round-trip via config sync.
6. Non-OP remote clients cannot edit.
7. `./gradlew clean build` succeeds; jar copied to `D:\Backup\mc mod\临时\` per project rule after implementation.

## 11. Testing notes

Manual / playtest focused:

- Toggle lobby to NIGHT + RAIN + snow off → end a match → lobby stays as configured.
- Enable only `goodWin` post time to MIDNIGHT → force passenger win → midnight; killer win → normal lobby time.
- Configure map A rain, map B clear → start each map → correct weather.
- Disable low-player rain → drop below 8 players mid-match → no rain.
- Enable low-player rain with match profile THUNDER → drop below 8 (rain forced) → recover to ≥8 → returns toward match profile weather, not forced perpetual CLEAR unless profile says CLEAR.

## 12. Implementation sketch (for planning)

1. Config model + store/sync/manager wiring + defaults.
2. `EnvironmentController` apply helpers + event hooks + tick maintain.
3. Refactor `SREWeatherController` for switch + match weather restore.
4. `EnvironmentTabScreen` + root tab integration.
5. Build + smoke verify.

## 13. Decisions log

| Topic | Decision |
|---|---|
| Placement | New root tab 环境设置 |
| Approach | Runtime controller + JSON config (not map file edits) |
| Match maps | Per-map table + default profile |
| Time input | PRESET + TICK |
| Post-match scope | Lobby time after end only |
| Win split | `isInnocentWin` → good; else other |
| Lobby/match breadth | Full SRE-aligned env fields listed above |
| Low-player rain | Keep, add enable + minPlayers, compatible restore |
| Match default enabled | `false` (opt-in override) |
| Post-match rule default enabled | `false` |
