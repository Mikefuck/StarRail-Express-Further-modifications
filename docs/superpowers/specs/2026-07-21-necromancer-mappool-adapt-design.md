# 死灵 Mike 适配 + 地图池每局轮换设计

**Date:** 2026-07-21  
**Status:** Approved for implementation  
**Scope:** `habitrain_core` — Mike「代码修改」与死灵复活次数；模式→地图投票的地图池轮换/分池  
**Supersedes (partial):** 日历日换池行为 in `2026-07-17-map-pool-daily-rotation-design.md`（池编辑/skip/applyMode 仍有效；轮换触发改为每局）

## Goals

1. **死灵空缺狼位：** 当 Mike 将杀手转为非杀手时，若场上仍有存活死灵，立刻给该世界死灵 `availableRevives +1`，使死灵仍可复活「空缺狼位」队友。
2. **地图池每局轮换：** 去掉按日历日（约每日 0 点/日期变化）换池；改为每次模式投票解析地图时用**当前池**，解析后 advance 到下一池。
3. **默认 6 池、跨池可重复、均摊：** 新建空配置 seed 6 池；自动分池每池 4 张图；同一地图可出现在多个池；全局出现次数尽量平均。

## Non-goals

- 不修改 DLC（`stupid_express` / `StarRailExpress`）源码与死亡事件。
- 不覆盖非 Mike 的转职路径（替罪羊、七宗罪、愤怒等）。
- 不强制把已有 5 池配置迁移为 6 池。
- 不做「每池张数」配置 UI；不做投票权限/HUD 大改。
- 不引入硬依赖：无死灵组件时 Mike 技能仍成功。

---

## Feature 1 — Necromancer revive credit on Mike convert

### Problem

死灵复活次数仅在 `OnPlayerDeathWithKiller` 且 `canUseKillerFeatures(victim)` 时 `increaseAvailableRevives()`。  
Mike「代码修改」若把唯一/某个狼转成杀手中立或其它非杀手，该玩家未再以杀手身份死亡 → 死灵次数不涨 → 无法复活填补狼位。

### Behavior

在 `MikeCodeEditSkill.use` 中，`RoleUtils.changeRole` 与 `BlackoutRoleManager.reassignRole` **成功之后**，调用支持方法：

**条件（全部满足才 +1）：**

1. `oldRole != null && oldRole.canUseKiller()`
2. `next != null && !next.canUseKiller()`
3. 目标所在 `ServerLevel` 存在**存活且非旁观**的死灵玩家：
   - 优先识别 `pro.fazeclan.river.stupid_express` 的 `NECROMANCER`
   - 以及 noelles `CAT_NECROMANCER`（能解析到几个算几个）
4. 世界级 `NecromancerComponent` 可访问

**动作：**

- `increaseAvailableRevives()` + `sync()`
- info 日志：施法者、目标、old→next、当前次数

**不 +1 的情况：**

- 非杀手 → 任意；杀手 → 杀手；场上无存活死灵；组件缺失（反射失败 warn，不抛）

### Implementation sketch

| 项 | 说明 |
|---|---|
| 新类 | `com.habitrain.core.game.sre.role.NecromancerReviveSupport` |
| 入口 | `onKillerConvertedAway(ServerLevel level, SRERole oldRole, SRERole nextRole)` |
| 依赖 | 优先直接调用 jar 内 `NecromancerComponent`；若 compile 不可见则反射 `KEY.get(level)` / `increaseAvailableRevives` / `sync` |
| 死灵判定 | 遍历 `level.players()`，`GameUtils.isPlayerAliveAndSurvival` + `SREGameWorldComponent.getRole` 与已知 role id 比对；id 可用 `ResourceLocation` 字符串兜底 |

### Success criteria

- 死灵 + 狼，Mike 狼→中立：`availableRevives +1`，可对尸体复活。
- 无死灵：次数不变。
- 狼→另一杀手：次数不变。
- 无 DLC 组件：Mike 技能成功，无崩溃。

---

## Feature 2 — Map pool per-round rotation + balanced multi-membership

### Current (to change)

- `MapPoolRotationService.onCalendarTick`：本地日期变化时 `advance`
- `DEFAULT_POOL_COUNT = 5`
- `repartition`：shuffle 后**互斥**切段，一图只进一池
- 开局 `resolveEffectiveMaps` 不 advance

### Config defaults

| 字段/常量 | 值 |
|---|---|
| `DEFAULT_POOL_COUNT` | **6**（仅 `pools` 为空时 seed；已有 5 池存档不自动扩） |
| `MAPS_PER_POOL` | **4**（与 `PAD_TARGET` 对齐） |
| `lastRotationDate` | 保留 JSON 兼容，逻辑与 UI 不再依赖 |
| `autoRepartition` / `applyMode` / skip | 行为保留 |

### Rotation timing (B1)

在 `ModeMapVoteOrchestrator.onModeResolved`，当 `MapPoolRotationService.shouldApply`：

1. `effectiveIds = resolveEffectiveMaps(settings, candidates, rng)` — **使用当前** `activePoolIndex`
2. `advance(settings, rng)` — 切到下一启用池，供**下一局**
3. `ConfigManager.setModeMapVoteSettings` + `save`；非单机 `FullConfigSyncPayload.broadcastToAll`
4. 再 `LIMIT_VOTE` / `DIRECT_PICK`

`skip`（命令/UI）仍立即 advance，不变。

### Disable calendar advance

- `onCalendarTick`：**不再**按日期 advance（可 no-op，或仅 `ensureSeededIfNeeded` 且不改日期字段）
- `ModTickHandler` 可继续调用 no-op，减少无关键 diff

### Repartition algorithm (cross-pool duplicates + balance)

```
all = globalEnabledMapIds(settings)
poolN = rot.poolCount()
K = MAPS_PER_POOL (4)
if all empty: clear all pools; reset advanced counter; return

count[map] = 0
for i in 0..poolN-1:
  pool = rot.poolAt(i)
  pool.mapIds = []
  chosen = empty set
  while pool.mapIds.size < min(K, all.size):
    pick map in all \ chosen with minimal count[map]
    ties broken by Random
    pool.mapIds.add(map); chosen.add(map); count[map]++
rot.poolsAdvancedSinceRepartition = 0
```

性质：池内不重复；跨池可重复；出现次数尽量均摊（差 ≤ 1 当总槽位允许）。

旧「互斥切段」废弃。编辑器「重新均分」走同一算法。

### autoRepartition

每成功 advance +1；`>= poolCount` 且开关开 → 上式 repartition。

### UI copy

- `VoteTabScreen`：强调「每局轮换 · 当前池 · 图数 · 共 N 池」；弱化/去掉日期主展示
- `MapPoolEditorScreen`：按钮可改为「重新均摊分池」；说明可跨池重复（可选）

### Success criteria

- 连续两局模式投票：第二局用下一池，不依赖跨日
- 过 0 点不自动跳池
- 新空配置 6 池；重分后每池 4、跨池可重复、均摊
- candidates &lt; 4 时仍走旧全候选、不 advance

---

## Error handling

| 场景 | 行为 |
|---|---|
| 死灵组件缺失/反射失败 | Mike 成功；warn；不 +1 |
| 死灵 role 常量不可用 | ResourceLocation 字符串兜底；全失败视为无死灵 |
| 全部地图池 disabled | advance false；本局仍用 resolve 结果 |
| 0 张启用图 repartition | 各池清空 |
| 旧 5 池配置 | 保持 5，不强制 6 |

## Files (expected)

| File | Change |
|---|---|
| `.../role/skill/MikeCodeEditSkill.java` | 成功路径调用 support |
| `.../role/NecromancerReviveSupport.java` | **new** |
| `.../config/MapPoolRotationSettings.java` | DEFAULT_POOL_COUNT=6 |
| `.../vote/MapPoolRotationService.java` | 日历 no-op；均摊 repartition；MAPS_PER_POOL |
| `.../vote/ModeMapVoteOrchestrator.java` | resolve 后 advance + save |
| `.../client/gui/config/VoteTabScreen.java` | 摘要文案 |
| `.../client/gui/config/MapPoolEditorScreen.java` | 重分文案（可选） |

## Manual verification

1. 死灵+狼 → Mike 转中立 → revive +1 → 可复活  
2. 无死灵 → 次数不变  
3. 狼→狼 → 次数不变  
4. 连续两局模式投票 → 池 index 递增  
5. 重分 N 图 6 池 → 每池 4、可重复、均摊  
6. `./gradlew clean build` 并拷贝 jar 到 `D:\Backup\mc mod\临时\`

## Decisions log

| 问题 | 选择 |
|---|---|
| +1 时机 | Mike 杀手→非杀手成功时立刻 +1 |
| 死灵前提 | 场上存活死灵才 +1 |
| 轮换触发 | 模式解析地图后 advance（B1：本局用当前池） |
| 日历 | 完全去掉日期 advance |
| 每池张数 | 固定 4 |
| 默认池数 | 6（仅空 seed） |
| 死灵实现范围 | 仅 Mike 路径，不改通用 reassign |
