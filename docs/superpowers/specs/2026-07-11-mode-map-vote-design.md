# 模式→地图两段式投票 — 设计规格书

> **日期**: 2026-07-11  
> **项目**: 哈比列车核心 (HabiTrain Core / `habitrain_core`)  
> **状态**: 设计已批准（对话确认 §1–§4）  
> **触发需求**: 通过 habi_api 注册/启动新投票：先向所有玩家展示模式投票 15 秒，选定模式后再开启地图投票 15 秒；结束后先切图再启动模式。

---

## 1. 概述

在 `habitrain_core` 中新增**通用选项投票引擎**与**模式→地图两段式编排器**，对外暴露公开 API 与 OP 命令。投票 UI 全自建（不复用局内放逐投票，也不复用 SRE `MapVoteScreen`）。地图候选来自服务器 `world/train_maps`（经 SRE `MapManager`），显示名与启用状态可在 ModMenu 配置；每个模式可单独启用、改显示名、配置可选地图白名单。

### 1.1 已确认决策

| 项 | 选择 |
|----|------|
| 触发 | OP 命令 + 公开 API |
| 模式候选 | 默认 `GameModeRegistry` 已注册模式，受配置开关过滤 |
| 地图候选 | `train_maps` / `MapManager.getAvailableMaps`，受总开关 + 胜出模式白名单过滤 |
| 结束后 | **先** `MapManager.loadMap`，**再**启动模式（对齐原版哈比） |
| UI | 全自建通用选项投票 UI |
| 平票 / 0 票 | 最高票；并列或 0 票在候选中随机 |
| 时长默认 | 模式 15s → 地图 15s（可配置） |
| 配置 UI | ModMenu 全套：总开关、模式开关/显示名/分模式地图、地图开关/显示名 |
| 与 SRE 地图投票 | 不调用 `MapVotingManager`；仅用 `MapManager` + `GameUtils` 做切图/开局 |

### 1.2 非目标

- 不改动 `BlackoutExileVoteManager` / 警长投票 / `VotePurpose.EXILE|SHERIFF`。
- 不做大厅人数满员自动开投。
- 不实现地图加载失败后的自动回滚。
- 不把选项投票塞进现有玩家 UUID 候选 payload。

---

## 2. 架构

```
Command / 外部模组
        │
        ▼
 ModeMapVoteApi  ──────────────►  OptionVoteApi（可单独使用）
        │                                │
        ▼                                ▼
 ModeMapVoteOrchestrator          OptionVoteManager
   (IDLE→MODE→MAP→LOAD→START)      (单选、计时、广播、结算)
        │                                │
        ├─ 读 ModeMapVote 配置            ├─ OptionVotePayload (S2C)
        ├─ GameModeRegistry               └─ OptionVoteCastPayload (C2S)
        ├─ MapManager.getAvailableMaps
        ├─ MapManager.loadMap
        └─ SREModeStartAdapter / GameModeRegistry.start
```

### 2.1 包与主要类型

| 区域 | 类型 | 职责 |
|------|------|------|
| `api/` | `OptionVoteApi`, `ModeMapVoteApi`, `VoteOption`, `VoteResult`, `ModeMapVoteConfig` | 公开面 |
| `vote/` | `OptionVoteManager`, `ModeMapVoteOrchestrator` | 服务端引擎与状态机 |
| `config/` | `ModeMapVoteSettings`（或等价 entry） | JSON 持久化与查询 |
| `network/` | `OptionVotePayload`, `OptionVoteCastPayload` | 网络 |
| `client/gui/` | `OptionVoteState`, `OptionVoteScreen` | 客户端 |
| `game/sre/` | `SREModeStartAdapter`（名可微调） | `loadMap` / `GameUtils.startGame` 隔离 |
| `CommandRegistrar` | `/habi_api vote …` | OP 入口 |
| `ConfigRootScreen` | 新 Tab「投票设置」 | ModMenu |

---

## 3. 公开 API

### 3.1 通用选项投票

```java
public final class OptionVoteApi {
    public static boolean start(ServerLevel level, String voteId,
            List<VoteOption> options, int durationSeconds,
            Consumer<VoteResult> onResolved);

    public static boolean cast(ServerLevel level, UUID voter, @Nullable String optionId);
    public static boolean isActive(ServerLevel level);
    public static void cancel(ServerLevel level);
}

public record VoteOption(String id, String displayName) {}
public record VoteResult(
        String voteId,
        @Nullable String winnerId,
        Map<String, Integer> tallies,
        boolean randomPick
) {}
```

约束：

- 每 dimension 至多一个 active 选项投票。
- `optionId == null` 表示弃票。
- 单选；新票覆盖旧票；再点已选项 = 弃票（与放逐 UX 对齐）。

### 3.2 模式→地图编排

```java
public final class ModeMapVoteApi {
    public static boolean start(ServerLevel level);
    public static boolean start(ServerLevel level, ModeMapVoteConfig config);
    public static boolean cancel(ServerLevel level);
    public static boolean isRunning(ServerLevel level);
    public static Optional<ModeMapVoteSnapshot> getSnapshot(ServerLevel level);
}

public final class ModeMapVoteConfig {
    int modeDurationSeconds = 15;
    int mapDurationSeconds = 15;
    /** null = 使用配置过滤后的 GameModeRegistry 全量 */
    List<String> modeIds;
    /** null = 使用 train_maps + 配置；非 null 时再与配置/模式白名单求交 */
    List<String> mapIds;
}
```

`start(level)` 等价于默认 config + 配置文件过滤。

---

## 4. 状态机

每 `ServerLevel` dimension 一份编排状态：

```
IDLE
  --start 成功--> MODE_VOTING
  --模式结算--> MAP_VOTING
  --地图结算--> SWITCHING_MAP
  --loadMap 成功--> STARTING_MODE
  --startMode 结束 / 任一步失败 / cancel--> IDLE
```

### 4.1 结算规则

1. 统计各 `optionId` 票数（仅当前仍在线的 voter 是否计票：保留已投出的票，玩家离线时**清除其 voter 票**，不重开计时——对齐放逐 `onPlayerRemoved`）。
2. 取最高票；并列多个 → 随机其一，`randomPick = true`。
3. 全部 0 票 → 在候选 id 中随机，`randomPick = true`。
4. 广播胜者与是否随机。

### 4.2 start 护栏

以下任一不满足则 `start` 返回 false，世界不变：

1. `modeMapVote.enabled == true`
2. 当前无 option / mode-map 投票进行中
3. `!GameUtils.isStartingGame` 且 SRE `SREGameWorldComponent` 未 running
4. 无与 `GameModeRegistry` 显式 active 模式冲突（如已 start 的 blackout）
5. 模式候选列表非空

地图段若候选为空：广播「无可用地图」，回 IDLE，不切图、不开局。

---

## 5. 配置模型

挂入现有 `config/habitrain_core.json`，键名 `modeMapVote`：

```json
"modeMapVote": {
  "enabled": true,
  "modeDurationSeconds": 15,
  "mapDurationSeconds": 15,
  "modes": {
    "habitrain_core:sre:murder": {
      "enabled": true,
      "displayName": "经典列车谋杀案",
      "allowedMaps": []
    },
    "habitrain_core:sre:repair": {
      "enabled": true,
      "displayName": "修复逃脱",
      "allowedMaps": ["map1"]
    },
    "habitrain_core:habitrain:blackout": {
      "enabled": true,
      "displayName": "停电模式",
      "allowedMaps": []
    }
  },
  "maps": {
    "map1": { "enabled": true, "displayName": "一号列车" },
    "map2": { "enabled": false, "displayName": "废弃货厢" }
  }
}
```

### 5.1 字段语义

| 字段 | 含义 |
|------|------|
| `enabled` | 总开关 |
| `modeDurationSeconds` / `mapDurationSeconds` | 两段时长 |
| `modes.*.enabled` | 是否进入模式投票 |
| `modes.*.displayName` | 投票显示名；空 → `GameMode.getDisplayName()` |
| `modes.*.allowedMaps` | 该模式地图白名单；**空 = 不限制**（再受 maps 总开关约束） |
| `maps.*.enabled` | 是否进入地图投票总表 |
| `maps.*.displayName` | 地图汉化；空 → 使用 map id |

### 5.2 候选解析

**模式（MODE_VOTING 开始时）**

1. `GameModeRegistry.getAll()`（或 config 覆盖的 modeIds 子集）  
2. 过滤 `modes[fullId].enabled != false`  
3. 显示名：配置 → 否则 `getDisplayName()`

**地图（模式结算后、MAP_VOTING 开始时）**

1. `MapManager.getAvailableMaps(level)`（底层 `world/train_maps`）  
2. 过滤 `maps[id].enabled != false`  
3. 若胜出模式 `allowedMaps` 非空 → 与之求交  
4. 若 API/config 传入 mapIds → 再求交  
5. 显示名：配置 → 否则 id  

### 5.3 自动发现

- 服务端启动与每次 `ModeMapVoteApi.start` 前：扫描已注册模式 + 可用地图。  
- 对**配置中尚不存在**的 id 补默认项：`enabled=true`, `displayName=""`, `allowedMaps=[]`。  
- **不覆盖**已有用户设置。  
- 可 `markDirty` 写回磁盘，便于 OP 之后在 GUI 改。

### 5.4 联机

- 配置随现有 FullConfig 同步链路下发。  
- 仅 OP 可改（`LiveConfigAccess.canEditRemoteConfigs()` 语义，与任务/小游戏一致）。  
- 投票进行中改配置：不打断当前票；下次 `start` 生效。

---

## 6. ModMenu「投票设置」Tab

`ConfigRootScreen` 增加 Tab（建议索引 3）：**投票设置**。

内容：

1. 总开关 + 模式/地图时长输入（合法范围建议 5–120，默认 15）。  
2. **模式列表**：启用勾选、显示名编辑、`可选地图` 按钮 → 子弹多选已知地图（全不选 = 清空白名单 = 不限制）。  
3. **地图列表**：启用勾选、显示名编辑；「刷新扫描」按钮触发服务端重扫（或依赖下次同步）。  
4. 非 OP 只读提示，与现有 Tab 一致。

保存：复用现有 config save + C2S 更新 + 全量/增量同步路径，不新造平行权限体系。

---

## 7. 网络与客户端 UI

### 7.1 数据包

| 方向 | 类型 | 字段要点 |
|------|------|----------|
| S2C | `OptionVotePayload` | `voteId`, `active`, `remainingSeconds`, `totalSeconds`, `maxSelections=1`, `title`, `description`, `List<Entry(optionId, displayName, votes)>` |
| C2S | `OptionVoteCastPayload` | `voteId`, `optionId`（可空 = 弃票） |

- 在 `NetworkRegistrar` / `C2SReceiverRegistrar` / 客户端 `NetworkReceiverRegistrar` 注册。  
- 1Hz 广播；内容 hash 未变可跳过。  
- 玩家加入时若投票 active，单播当前状态。

### 7.2 客户端

- `OptionVoteState`：快照。  
- `OptionVoteScreen`：列表单选、票数、倒计时；`active=false` 自动关闭。  
- HUD：进行中提示打开投票 + 剩余秒。  
- 键位：默认 **V** 在选项投票 active 时**优先**打开 `OptionVoteScreen`（高于放逐）；否则保持现有放逐逻辑。  
- 阶段字幕：`SubtitleNotifier` — 模式开启/结果、地图开启/结果、切图中、开局中/失败。

### 7.3 安全

- C2S 校验：`voteId` 匹配、投票 active、optionId 属于候选（弃票除外）、发送者在线。  
- 结算后的 mapId/modeId 仅服务端采用，再经 `MapManager` / Registry 校验。

---

## 8. 切图与开局

### 8.1 流水线

```
MAP resolve → SWITCHING_MAP → MapManager.loadMap(level, mapId)
  → fail: 广播, IDLE
  → ok: STARTING_MODE → 按模式映射启动 → IDLE（对局生命周期接管）
```

### 8.2 模式启动映射

| 胜出模式 | 启动 |
|----------|------|
| `habitrain:blackout` / registry fullId `habitrain_core:habitrain:blackout` | `GameModeRegistry.start("habitrain_core:habitrain:blackout", level)` |
| `sre:murder` | `GameUtils.startGame(level, SRE murder mode, defaultStartTicks)` |
| `sre:repair` | `GameUtils.startGame(level, SRE repair_escape mode, defaultStartTicks)` |
| 其它已注册 GameMode | 优先 `GameModeRegistry.start(fullId)`；若仅被动镜像且无显式 start 语义，则日志警告并广播「仅记录结果，未自动开局」 |

SRE 调用集中在 `game/sre/SREModeStartAdapter`（或同等适配器），API 包不直接依赖 SRE 实现类。

### 8.3 失败策略

| 情况 | 行为 |
|------|------|
| loadMap 失败 | 不 startMode，IDLE |
| startMode 异常 | 日志 + 广播；**不回滚**已加载地图 |
| 投票中进出服 | 清离线者选票，不重置计时 |
| 服务器停止 | orchestrator/manager reset |

---

## 9. 命令

```
/habi_api vote start     # permission 2
/habi_api vote cancel    # permission 2
/habi_api vote status    # permission 2 — phase、剩余秒、简要 tally、已选模式/地图
```

与 `ModeMapVoteApi` 共用实现；成功/失败用 `sendSuccess` / `sendFailure` 中文提示。

---

## 10. 生命周期挂载

| 钩子 | 行为 |
|------|------|
| `ModTickHandler` / 1Hz | `OptionVoteManager.tickSecond`；编排器响应 resolve 回调切阶段 |
| `SERVER_STARTED` | 可选：预扫地图/模式补全配置默认项 |
| `PLAYER_JOIN` | 若投票 active，单播 OptionVote 状态 |
| `PLAYER_DISCONNECT` | `onVoterRemoved` |
| `SERVER_STOPPING` | reset 全部 vote 状态 |

不与 `BlackoutTickCoordinator` 耦合。

---

## 11. 测试与验收

### 11.1 功能

1. OP `/habi_api vote start` → 全员可打开模式投票 UI，15s 内可改票。  
2. 模式结束后自动进入地图投票，候选符合：地图总开关 ∩ 胜出模式 `allowedMaps`。  
3. 地图结束后 `loadMap` 成功，再启动对应模式（blackout / murder / repair 各测一条路径）。  
4. 总开关关闭 → start 失败。  
5. 某模式 disabled → 不出现在模式列表。  
6. 某地图 disabled → 不出现在地图列表。  
7. 模式 A 的 `allowedMaps=[map1]` → 选 A 后地图列表仅 map1（若 map1 仍 enabled）。  
8. 显示名在 UI 中为中文配置值。  
9. 0 票 / 平票 → 随机胜出且有提示。  
10. 对局已 running 时 start 被拒绝。  
11. 联机非 OP 无法改投票配置；OP 可改并同步。

### 11.2 回归

- 放逐投票 / 号角 / 警长相关包与 UI 行为不变。  
- `/habi_api blackout` 与 `list` 仍可用。  
- 现有 Config 三 Tab 不回归。

### 11.3 构建

修改完成后：`./gradlew clean build`，JAR 复制到 `D:\Backup\mc mod\临时\`（项目强制规则）。

---

## 12. 实现顺序建议

1. 配置模型 + 读写 + 自动发现默认项  
2. `OptionVoteManager` + 网络包 + 客户端 State/Screen + 键位  
3. `ModeMapVoteOrchestrator` + API + 命令  
4. `SREModeStartAdapter`（loadMap + start 映射）  
5. ModMenu 投票设置 Tab + 同步  
6. 联调与构建

---

## 13. 风险与备注

- **SRE 内部 API 稳定性**：`MapManager.loadMap` / `getAvailableMaps` / `GameUtils.startGame` 来自依赖 jar，升级 SRE 时需回归。  
- **murder/repair 启动**：当前本模组对二者是被动 `isActive` 镜像；本功能主动 `GameUtils.startGame`，需确认与 SRE 大厅/准备区假设一致（人数、ready 等）。若 SRE 要求 ready，适配器内可按需 `setForcedReadyPlayers`（GameUtils 已有该方法）——实现阶段验证后写入计划。  
- **切图耗时**：SWITCHING 阶段应拒绝新投票并给玩家明确反馈。  
- **registry fullId vs GameMode.getId()**：配置与投票 optionId 统一使用 **GameModeRegistry 注册 fullId**（`modId:modeId`），显示与启动映射表同时接受逻辑 id 别名以防混用。

---

## 14. 对话决议记录

- 触发：命令 + 公开 API  
- 模式来源：自动已注册模式  
- 地图：train_maps + ModMenu 汉化/开关；分模式地图白名单；模式显示名可改  
- UI：全自建  
- 平票：最高票，并列/0 票随机  
- 路线：方案 A（通用引擎 + 编排器）  
- §1–§4 均已用户确认 OK
