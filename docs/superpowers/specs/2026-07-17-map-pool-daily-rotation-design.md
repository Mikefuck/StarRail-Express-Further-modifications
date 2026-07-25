# 地图 5 池日轮换设计

**Date:** 2026-07-17  
**Status:** Approved for implementation  
**Scope:** `habitrain_core` mode→map lobby vote map candidates + Mod Menu pool editor

## Goal

- Split globally enabled maps into **N pools** (default 5, range 1–20, UI add/remove), rotate one pool per **real calendar day** (server local timezone).
- At mode-resolved time, intersect today's pool with **mode candidates**; if intersection &lt; 4, pad randomly from candidates up to min(4, |candidates|).
- If mode candidates &lt; 4 or rotation disabled → keep legacy full-candidate map vote.
- Apply mode: `LIMIT_VOTE` (restrict map vote options) or `DIRECT_PICK` (skip map vote, random one map).
- After **N** successful advances (day or skip, N = current pool count), optional **autoRepartition** into current N pools.
- Mod Menu: show current pool, add/remove pools, edit membership, toggles, skip (OP ≥ 4).

## Config

Under `modeMapVote.mapPoolRotation` (see `MapPoolRotationSettings` / `MapPoolEntry`).  
Pools list length is authoritative; empty list seeds to 5 on load.

Merge: full replace of `mapPoolRotation` object so mapId deletions and pool count changes apply.

## Runtime

Hook: `ModeMapVoteOrchestrator.onModeResolved` after building candidates.  
Calendar: `MapPoolRotationService.onCalendarTick` from `ModTickHandler` 1Hz.  
Skip: C2S `MapPoolSkipPayload` + `/habi_api mappool skip` (perm 4).

## UI

`VoteTabScreen` summary card + `MapPoolEditorScreen` sub-page (`+ 添加池` / `删本池`). Hardcoded Chinese strings. Summary visible to all; edit/skip OP4 via `LiveConfigAccess`.

## Non-goals

HUD; changing vote start perm (stays 2).
