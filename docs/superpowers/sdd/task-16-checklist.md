# Task 16 — Dual-mode manual matrix (SRE + Blackout)

Use this after jar install. Mark each row during playtest. Modes: **SRE murder** and **Blackout** unless noted.

## Setup / tools

- Force roles: existing `/forcerole` (or project equivalent) for `sin_*`
- Force virtues: `/forcemodifier` for `virtue_*`
- Build jar: `D:\Backup\mc mod\临时\habitrain_core-*.jar`

## A. Mutex + lust gate

| # | Case | Expected | SRE | BO | Pass? |
|---|------|----------|-----|----|-------|
| A1 | Two different sins assigned pre-confirm | L3 hard-cut → ≤1 sin remains | ✓ | ✓ | |
| A2 | Same sin max | `setDefaultMax(1)` — never two of same | ✓ | ✓ | |
| A3 | Lust with no true lovers in lobby | Lust demoted / not kept as lust | ✓ | ✓ | |
| A4 | Lust with dual lovers present | Lust eligible; phase1 can charge | ✓ | ✓ | |

## B. Sin happy paths (mechanics + win)

| # | Sin | Happy path | SRE | BO | Pass? |
|---|-----|------------|-----|----|-------|
| B1 | **Pride** | Crowd immunity (≥3 in r=8); G copy shop; last survivor solo win | ✓ | ✓ | |
| B2 | **Pride** | Alive blocks early good/bad wipe end | ✓ | ✓ | |
| B3 | **Envy** | Mark → gold-gated kill + loot; unmarked free; wins with killers | ✓ | ✓ BAD count | |
| B4 | **Gluttony** | Eat buffs + debuff scrub; wins with goods | ✓ | ✓ GOOD count | |
| B5 | **Wrath** | Good-weapon stages 1–5; kill lowers stage; frenzy 5 → death | ✓ | ✓ uncounted | |
| B6 | **Wrath** | Killer faction win → wrath personal win share | ✓ | ✓ | |
| B7 | **Sloth** | Safety end → sleep+shield; break → limited berserk; active wake once | ✓ | ✓ | |
| B8 | **Sloth** | Alive when normal faction would win → sloth hijack | ✓ | ✓ | |
| B9 | **Lust** | Observe charge → desire mark; true-lovers win + lust alive → lust win | ✓ | ✓ | |
| B10 | **Greed** | Bound pouch; kinds → target; trade sell/buy 0–3; cap 3; lost pouch death | ✓ | ✓ | |
| B11 | **Greed** | Target kinds reached → instant solo win | ✓ | ✓ | |

## C. Virtues (`/forcemodifier`)

| # | Virtue | Check | Pass? |
|---|--------|-------|-------|
| C1 | Humility | Task complete → nearby “thanks”, not full broadcast | |
| C2 | Mercy | Civilian; first good-kill cancel + consume | |
| C3 | Patience | Interactive task time ×1.5 | |
| C4 | Diligence | Interactive task time ×0.7 | |
| C5 | Patience+Diligence | Hard exclusive pair | |
| C6 | One virtue / player | Virtue group max 1 (incl. generous link) | |
| C7 | Temperance | Repeat buy max(base×0.5, last×0.9); order base→temperance→DynamicShop | |
| C8 | Chastity | Blocks registered poison; not betel/psycho/curse | |

## D. Blackout-specific counts / dual win

| # | Case | Expected | Pass? |
|---|------|----------|-------|
| D1 | Independent sins (pride/greed/lust/sloth) | Not in GOOD/BAD live counts | |
| D2 | Wrath | Not in GOOD/BAD; killer win shares personal | |
| D3 | Envy | Counts BAD | |
| D4 | Gluttony | Counts GOOD | |
| D5 | Pride blocks wipe | good==0 or bad==0 while pride alive → no early faction end | |
| D6 | Pride last alive | Pride solo win | |
| D7 | Victory order | Pride last → greed collect → pride block → faction → sloth/lust hijack → wrath share | |

## E. Game-end cleanup (regression)

| # | State | Expected after round end | Pass? |
|---|-------|--------------------------|-------|
| E1 | Sin CCA (all 7) | `HabiComponents.clearAll(player)` | |
| E2 | Temperance prices | `TemperanceVirtue.clearAll()` | |
| E3 | Greed trade sessions + deal counts | `GreedTradeManager.clearAll()` → `GreedDealTracker.clearAll()` | |

## F. Lang spot-check

| Locale | sin announcement/info/skills/messages | virtue announcement/info | Match? |
|--------|----------------------------------------|--------------------------|--------|
| zh_cn | all sin_* keys present (124 sin/virtue family) | all virtue_* | |
| en_us | parity with zh_cn | parity | |

Code audit (2026-07-15): **0 missing** keys in either locale for expected sin/virtue set.

## Sign-off

| | |
|--|--|
| Tester | |
| Date | |
| JAR build | |
| Notes | |
