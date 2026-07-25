# Mode / Map Vote List Order Design

**Date:** 2026-07-17  
**Mod:** habitrain_core (`哈比列车api`)

## Problem

- Map vote candidates follow filesystem/registry order; players see the same order every time.
- Mode vote candidates follow `GameModeRegistry` iteration order; ops cannot pin a preferred mode order in config UI.

## Goals

1. **Map vote:** each time `MAP_VOTING` starts, shuffle the candidate list with world RNG; server payload order is display order (already true).
2. **Mode vote:** ops reorder modes with ↑/↓ in Vote settings; order persists in `modeMapVote.modes` LinkedHashMap JSON key order; vote start uses that order.

## Non-goals

- Map list GUI reorder
- Mid-vote runtime reorder API
- Blackout exile/sheriff votes

## Design

### Map shuffle

In `ModeMapVoteOrchestrator.onModeResolved`, after building `mapOptions` and before `OptionVoteManager.start`:

```java
Collections.shuffle(mapOptions, new java.util.Random(level.getRandom().nextLong()));
```

### Mode order

1. `ModeMapVoteSettings.modes` remains `LinkedHashMap`; `toJson`/`fromJson` preserve insertion order.
2. `VoteTabScreen`: mode rows gain ↑/↓; swap in display list then rebuild `settings.modes` LinkedHashMap.
3. `ModeMapVoteOrchestrator.start` builds mode IDs:
   - first walk `settings.modes.keySet()` (config order), apply enable + `config.modeIds` filters;
   - then append remaining registry IDs that pass filters and are not yet listed.

## Verification

- Start map vote twice → option order differs; all clients match.
- Reorder modes in GUI, save, restart vote → mode list matches config order.
