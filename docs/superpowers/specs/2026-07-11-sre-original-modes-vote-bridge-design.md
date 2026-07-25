# 原版 SRE 更多模式接入投票 — 设计规格

> **日期**: 2026-07-11  
> **项目**: 哈比列车核心 (`habitrain_core`)  
> **状态**: 设计已批准，实现中  
> **触发需求**: 投票页面不出现原版「更多模式」（如 `wifi:tnt_tag`、`wifi:lover`）；需识别并接入全部 `SREGameModes`。

---

## 1. 概述

当前 `/habi_api vote` 模式候选仅来自 `GameModeRegistry`，Core 只注册了 murder / repair / blackout。  
原版模式在外部 `io.wifi.starrailexpress.api.SREGameModes.GAME_MODES`，由 `/tmm:start <id>` 启动。

本设计通过 **初始化扫描 + 轻量 Core 代理** 将全部原版模式注册进 `GameModeRegistry`，从而自动进入投票列表，并在胜出后用 `GameUtils.startGame` 开局。

### 1.1 已确认决策

| 项 | 选择 |
|----|------|
| 范围 | 全部 `SREGameModes.GAME_MODES` |
| 默认启用 | 全部 enabled |
| 显示名 | 原始 SRE id（如 `wifi:tnt_tag`） |
| 方案 | A：自动桥接代理 |

### 1.2 非目标

- 中文模式名（除既有 3 个）
- 修改 SRE 源码 / jar
- 按当前地图过滤可投票模式
- `/tmm:start force_all_players` / 第四房人数参数
- 代理挂到 `SREGameModeBase` 语音/内置任务体系

---

## 2. 架构

```
SREGameModes.GAME_MODES
        │  onInitialize 扫描
        ▼
SREOriginalModeBridge.registerAll()
  · skip: sre:murder, canyuesama:repair_escape, sre:blackout
  · 其余 → SreOriginalModeProxy → GameModeRegistry
        │
        ▼
ModeMapVoteOrchestrator（候选来源不变）
        │
        ▼
SREModeStartAdapter.startMode
  · blackout / murder / repair 特判保留
  · 代理 → GameUtils.startGame(SRE mode)
```

### 2.1 ID 约定

| 用途 | 格式 | 例 |
|------|------|----|
| Core fullId（投票 optionId / config 键） | `habitrain_core:sre_proxy:<ns>_<path>` | `habitrain_core:sre_proxy:wifi_tnt_tag` |
| 代理 getId / UI 显示 | 原始 SRE id | `wifi:tnt_tag` |
| 开局查找 | `ResourceLocation` → `GAME_MODES` | `wifi:tnt_tag` |

### 2.2 去重

| SRE id | 处理 |
|--------|------|
| `sre:murder` | 跳过（已有 Core murder） |
| `canyuesama:repair_escape` | 跳过（已有 Core repair） |
| `sre:blackout` | 跳过（已有 Core blackout） |
| 其余 | 注册代理 |

---

## 3. 组件

### 3.1 `SreOriginalModeProxy`

- `extends AbstractGameMode`（**不**继承 `SREGameModeBase`）
- 持有 `ResourceLocation sreId`
- `getId()` / `getDisplayName()` → `sreId.toString()`
- `getTaskCategories()` → `List.of(TaskCategory.ALL)`
- `isActive`：当前 SRE 世界模式 id 与 `sreId` 精确相等
- 生命周期空实现

### 3.2 `SREOriginalModeBridge`

- `registerAll()`：try 遍历 `SREGameModes.GAME_MODES`
- skip 用 `ResourceLocation` 精确匹配
- modeId 段：`sre_proxy:` + namespace + `_` + path（path 中非 `[a-z0-9_]` → `_`）
- 已注册 fullId → skip + warn
- log 桥接数量与每个 id

### 3.3 接线

- `HabiTrainCore.onInitialize`：在内置 3 模式 + `SREBlackoutGameMode.register()` 之后、`freeze` 之前调用 `registerAll()`
- `SREModeStartAdapter.startMode`：murder/repair 之后、generic start 之前，识别 proxy → `GameUtils.startGame`
- `ModeMapVoteOrchestrator.resolveModeDisplayName`：仅当模式为 proxy 且无配置覆盖时，wire displayName = raw SRE id（避免破坏 murder/blackout 的 lang key 路径）

---

## 4. 数据流

1. 投票 start → `GameModeRegistry.getAllIds()`（含代理）
2. 配置 `enabled` / 可选 `modeIds` 过滤
3. `ensureModeMapVoteDefaults` 插入新 fullId
4. 模式胜出 → 地图投票 → `loadMap` → `startMode(fullId)`
5. proxy → `GAME_MODES.get(sreId)` → `GameUtils.startGame`

---

## 5. 边界

| 场景 | 处理 |
|------|------|
| SRE 缺失 | try/catch，仅 3 内置 |
| 地图不支持模式 | 不预过滤；SRE 开局自检 |
| officialVerify | 不伪造 |
| freeze 后 | 禁止再注册 |

---

## 6. 验证

1. 启动 log：`bridged N original SRE modes`；`/habi_api list` 含 proxy ids  
2. `/habi_api vote start` 见 `wifi:tnt_tag`、`wifi:lover`  
3. 投中 tnt_tag → 切图 → SRE 开局  
4. murder / blackout 回归  
5. Vote 设置可禁用代理  
6. `./gradlew clean build`；jar → `临时\`
