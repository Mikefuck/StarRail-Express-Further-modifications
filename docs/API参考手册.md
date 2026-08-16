# 哈比列车核心 (HabiTrain Core) — API 参考手册

> **模组 ID:** `habitrain_core`  
> **平台:** Minecraft 1.21.1 · Fabric · Java 21  
> **公开包:** `com.habitrain.core.api`  
> **配置:** `config/habitrain_core.json`  
> **文档日期:** 2026-08-09

本手册描述 **DLC / 附属模组可依赖的公开 API**。`task/`、`game/`、`network/`、`config/` 等包为内部实现，**不保证**跨版本稳定，请勿直接依赖。

---

## 目录

1. [概述](#1-概述)
2. [功能能力一览](#2-功能能力一览)
3. [任务系统](#3-任务系统)
4. [游戏模式](#4-游戏模式)
5. [SRE 集成抽象](#5-sre-集成抽象)
6. [投票 API](#6-投票-api)
7. [道具回收](#7-道具回收-itemreclaimhelper)
8. [端到端示例](#8-端到端示例)
9. [约束与陷阱](#9-约束与陷阱)
10. [类索引](#10-类索引)

---

## 1. 概述

### 1.1 职责边界

| 层级 | 包 | 用途 |
|------|-----|------|
| **公开 API** | `com.habitrain.core.api` | 注册任务/模式、投票、胜负结果、道具回收 |
| 内部引擎 | `task/`、`game/`、`network/`、`config/`、`client/` | 核心实现，勿当稳定 API |

### 1.2 初始化与冻结

在 `ModInitializer.onInitialize()` 中注册任务与游戏模式。核心在加载完成后会 **freeze** 注册表：

| 注册表 | freeze 后 |
|--------|-----------|
| `TaskRegistry` | 不可再 `register`；会 `TaskPoolBuilder.invalidateAll()` |
| `GameModeRegistry` | 不可再 `register` |

**务必在本模组 `onInitialize` 阶段完成注册**（Fabric 保证依赖顺序时，DLC 的 `onInitialize` 在依赖声明正确的前提下会按序执行）。

### 1.3 旧名对照（已无兼容层）

| 旧 (habitrain_taskapi) | 新 (habitrain_core) |
|------------------------|---------------------|
| `HabiTaskRegistry` | `TaskRegistry` |
| `HabiTaskDefinition` | `TaskDefinition` |
| `HabiTaskInstance` | `TaskInstance` |
| `HabiTaskCategory` (枚举) | `TaskCategory` (类 + 标准常量) |
| mod id `habitrain_taskapi` | `habitrain_core` |
| 配置 `habitrain_taskapi.json` | `habitrain_core.json` |
| 颜色 `java.awt.Color` | `int` ARGB |

### 1.4 本仓库已注册的 GameMode 示例

| Registry fullId | `GameMode.getId()` | 说明 |
|-----------------|--------------------|------|
| `habitrain_core:sre:murder` | 由实现返回 | SRE 谋杀 |
| `habitrain_core:sre:repair` | 由实现返回 | SRE 修机 |
| `habitrain_core:habitrain:blackout` | `habitrain:blackout` | 停电模式 |

注意：`GameModeRegistry.register(modId, modeId, mode)` 的 **存储键** 为 `modId + ":" + modeId`，与 `mode.getId()` **可以不同**。启停时使用的是 **Registry fullId**（`start`/`get` 的参数）。

---

## 2. 功能能力一览

| 子系统 | 能力 | 入口类型 |
|--------|------|----------|
| **任务注册** | 注册/查询/按模式或分类过滤/冻结 | `TaskRegistry`, `TaskDefinition` |
| **任务运行时** | 进度、限时失败、tick 生命周期、NBT | `TaskInstance` |
| **任务分类** | 标准分类 + per-mode 自定义分类 | `TaskCategory` |
| **任务回调** | 分配/完成/移除/失败/回收/tick/可分配/进度变化 | `TaskDefinition.Builder` |
| **时间影响声明** | 停电相关轴与 delta（自适应刷新等） | `TaskDefinition.TimeImpact` |
| **方块扫描元数据** | 透视用 `scanBlocks` / `scanBlockIds`、`instinctColor` | `TaskDefinition` |
| **游戏模式** | 生命周期、胜负、任务拦截钩子 | `GameMode` |
| **模式注册与调度** | 注册、start/stop、tick、按世界查 active | `GameModeRegistry` |
| **胜负结果** | 获胜者列表 + 原因 | `WinResult` |
| **SRE 桥** | 自定义胜通知；停电启动解耦 | `GameStateProvider`, `SREGameLauncher` |
| **通用选项投票** | 非玩家 UUID 的选项投票 | `OptionVoteApi`, `VoteOption`, `VoteResult` |
| **模式→地图投票** | 大厅两阶段投票编排 | `ModeMapVoteApi`, `ModeMapVoteConfig`, `ModeMapVoteSnapshot` |
| **道具回收** | grant NBT 标记、取消路径回收 | `ItemReclaimHelper` |

---

## 3. 任务系统

### 3.1 `TaskCategory`

**职责:** 任务分类（按 `id` 相等）。标准常量覆盖 SRE 旧枚举场景；停电等模式可自建实例。

| 常量 | id | displayName | gameModeId |
|------|-----|-------------|------------|
| `MURDER` | `sre:murder` | 谋杀模式 | `sre:base` |
| `REPAIR` | `sre:repair` | 修机模式 | `sre:base` |
| `ALL` | `sre:all` | 通用任务 | `sre:base` |
| `CUSTOM` | `sre:custom` | 自定义任务 | `sre:base` |

**构造:**

```java
new TaskCategory(String id, String displayName, String gameModeId)
```

**方法:**

| 方法 | 说明 |
|------|------|
| `getId()` | 分类唯一 id |
| `getDisplayName()` | 显示名 |
| `getGameModeId()` | 所属游戏模式 id（逻辑归属，非 Registry fullId） |
| `equals` / `hashCode` | 仅比较 `id` |

**停电模式示例（本仓库）:**

```java
new TaskCategory("habitrain:blackout_good", "好人任务", "habitrain:blackout");
new TaskCategory("habitrain:blackout_bad",  "坏人任务", "habitrain:blackout");
```

---

### 3.2 `TaskDefinition`

**职责:** 不可变任务定义（元数据 + 回调）。通过 `Builder` 构建，由 `TaskRegistry` 注册。

**fullId:** `modId + ":" + taskId`（例如 `habitrain_core:pet_cat`）。

#### 3.2.1 元数据 Getter

| 方法 | 类型 | 默认 (Builder) | 说明 |
|------|------|----------------|------|
| `getFullId()` | `String` | — | `modId:taskId` |
| `getModId()` / `getTaskId()` | `String` | — | 命名空间与本地 id |
| `getDisplayName()` | `String` | `taskId` | 显示名 |
| `getCategory()` | `TaskCategory` | `ALL` | 主分类 |
| `getCustomCategory()` | `TaskCategory` | `null` | 可选自定义分类 |
| `getGameModeId()` | `String` | `"sre:base"` | 逻辑所属模式 |
| `getWeight()` | `float` | `1.0f` | 权重池权重 |
| `getBlockTypeId()` | `int` | `-1` | SRE 方块类型兼容 id；`-1` 无 |
| `getInstinctColorRGB()` | `int` | `0xB4C8C8C8` | 透视/本能色 **ARGB** |
| `canDirectlyWin()` | `boolean` | `false` | 是否可直接触发自定义胜 |
| `getScanBlocks()` | `Set<Block>` | 空 | 扫描方块（运行时 Block） |
| `getScanBlockIds()` | `Set<String>` | 空 | 扫描方块 id 字符串（如 `yuushya:white_cat`） |
| `getTimeLimit()` | `int` | `0` | 限时秒数；`0` = 不限时 |
| `canRepeat()` | `boolean` | `false` | 是否可重复分配 |
| `isShareProgress()` | `boolean` | `false` | 是否共享进度 |
| `getTags()` | `List<String>` | 空 | 标签 |
| `getTimeImpact()` | `TimeImpact` | `null` | 对停电计时器的声明式影响 |

#### 3.2.2 `TimeImpact`

```java
public record TimeImpact(TimeAxis axis, int deltaSeconds)
```

| `TimeAxis` | 含义 |
|------------|------|
| `MAINTENANCE_OR_COUNTDOWN` | 增减停电倒计时/维护时间 |
| `TOTAL_TIME` | 增减对局总时间 |
| `RESTORE_POWER` | 触发恢复供电 |
| `TRANSIENT` | 触发瞬时停电惩罚 |

`deltaSeconds`：正 = 增加相关时间，负 = 减少。用于自适应刷新等逻辑从声明派生阈值，**避免在 onComplete 写魔法数字**。

#### 3.2.3 回调分发

| 方法 | 何时调用 | 无 handler 时 |
|------|----------|----------------|
| `onAssign(player, instance)` | 任务分配给玩家 | 无操作 |
| `onComplete(player, instance)` | 完成检测通过后 | 无操作 |
| `onRemove(player, instance)` | 任务从玩家移除 | 无操作 |
| `onFail(player, instance)` | 失败（含超时 `markFailed`） | 无操作 |
| `onReclaim(player, instance)` | **仅取消/隐藏等回收路径**（非成功完成） | 无操作 |
| `checkCompletion(player, instance)` | 完成判定 | `instance.isFulfilled()` |
| `onTick(player, instance)` | 每 tick（未完成时） | 无操作 |
| `canAssign(player, instance)` | 是否允许分配 | `true` |
| `canAssign(player)` | 无 instance 时的安全重载 | 同上，`instance=null` |
| `onProgressUpdate(player, instance, oldProgress)` | 进度变化 | 无操作 |

`ProgressUpdateHandler`:

```java
void onProgressUpdate(Player player, TaskInstance task, int oldProgress);
```

#### 3.2.4 `TaskDefinition.Builder`

```java
new TaskDefinition.Builder(String modId, String taskId)
// 或通过 TaskRegistry.register(modId, taskId, consumer)
```

| Builder 方法 | 说明 |
|--------------|------|
| `displayName(String)` | 显示名 |
| `category(TaskCategory)` | 主分类 |
| `customCategory(TaskCategory)` | 自定义分类 |
| `gameMode(String gameModeId)` | 逻辑模式 id |
| `weight(float)` | 权重 |
| `blockTypeId(int)` | 方块类型 id |
| `instinctColor(int argb)` | ARGB 整型 |
| `instinctColor(int r,g,b,a)` | 分量打包为 ARGB |
| `canDirectlyWin(boolean)` | 可直接获胜 |
| `scanBlocks(Block...)` | 扫描 Block 集合 |
| `scanBlockIds(String...)` | 扫描 id 字符串 |
| `timeLimit(int seconds)` | 限时 |
| `canRepeat(boolean)` | 可重复 |
| `shareProgress(boolean)` | 共享进度 |
| `tags(String...)` | 标签 |
| `timeImpact(TimeAxis, int deltaSeconds)` | 时间影响 |
| `onAssign` / `onComplete` / `onRemove` / `onFail` / `onReclaim` | 生命周期回调 |
| `completionChecker(BiFunction<Player,TaskInstance,Boolean>)` | 完成检测 |
| `onTick` / `canAssign` / `onProgressUpdate` | tick / 过滤 / 进度 |
| `build()` | 生成不可变 `TaskDefinition` |

---

### 3.3 `TaskInstance`

**职责:** 运行时任务实例（进度、失败/完成状态、限时计时）。

| 方法 | 说明 |
|------|------|
| `getDefinition()` | 关联定义 |
| `getFullId()` / `getName()` | 定义 fullId / displayName |
| `getProgress()` / `setProgress(int)` | 进度；变化时触发 `onProgressUpdate` |
| `getMaxProgress()` / `setMaxProgress(int)` | 最大进度；**钳制为 ≥ 1**（防 0/负导致立刻完成或永不完成） |
| `isFulfilled()` / `setFulfilled(boolean)` | 是否已结束（完成或失败都会 fulfilled） |
| `isFailed()` | 是否失败 |
| `markFailed()` | `failed=true` 且 `fulfilled=true` |
| `tick(Player)` | 每服务端 tick：限时 → `onTick` → `checkCompletion` → `onComplete`/`onFail` |
| `toNbt()` / `fromNbt(CompoundTag)` | 序列化；`fromNbt` 在定义未注册时返回 `null` |

**限时:** `timeLimit > 0` 时，`elapsedTicks >= timeLimit * 20` 则 `markFailed()` + `onFail`。

**进度回调玩家:** tick 内使用当前 player；tick 外 `setProgress` 回退到 `ownerPlayer`（由 `tick`/`onAssign` 路径写入）。

---

### 3.4 `TaskRegistry`

**职责:** 全局任务注册表（`LinkedHashMap` 保序）。

| 方法 | 说明 |
|------|------|
| `register(TaskDefinition)` | 直接注册；重复 fullId 抛 `IllegalArgumentException`；已 freeze 抛 `IllegalStateException` |
| `register(modId, taskId, Consumer<Builder>)` | Builder 便捷注册，返回定义 |
| `get(fullId)` | 查询，无则 `null` |
| `getAll()` / `getAllIds()` / `size()` / `isRegistered(fullId)` | 只读视图/统计 |
| `getByGameMode(gameModeId)` | 按 `TaskDefinition.getGameModeId()` 过滤 |
| `getByCategory(category)` | 分类相等 **或** 定义分类为 `TaskCategory.ALL` |
| `freeze()` / `isFrozen()` | 冻结；`freeze` 会 `TaskPoolBuilder.invalidateAll()` |

---

## 4. 游戏模式

### 4.1 `GameMode`（接口）

**职责:** DLC 实现并注册的完整模式契约：生命周期 + 任务拦截。

#### 必选

| 方法 | 说明 |
|------|------|
| `getId()` | 模式逻辑 id（如 `habitrain:blackout`） |
| `getDisplayName()` | 显示名 |
| `getTaskCategories()` | 本模式任务分类列表 |
| `isActive(ServerLevel)` | 被动判定是否在该世界激活（Registry 缓存用） |

#### 生命周期（均有默认空实现，除 `checkWinCondition` 默认 empty）

| 方法 | 说明 |
|------|------|
| `onPreStart(level)` | 准备：地图/角色等 |
| `onStart(level)` | 正式开始 |
| `onTick(level)` | 每 tick（由 `GameModeRegistry.tickAll` 对 **ACTIVE_MODES** 调用） |
| `onPlayerJoin` / `onPlayerLeave` | 玩家进出 |
| `onTaskComplete(player, task)` | 任务完成 |
| `checkWinCondition(level)` | 返回非空 `Optional<WinResult>` 表示应结束 |
| `onEnd(level, result)` | 结束 |
| `onCleanup(level)` | 清理；**start 失败或 stop 时 finally 会尽量调用** |

#### 任务行为拦截

| 方法 | 说明 |
|------|------|
| `filterAvailableTasks(tasks, player)` | 分配前过滤；默认原样返回 |
| `onTaskAssign` / `onTaskTick` / `onTaskProgressChange` | 任务侧钩子 |
| `overrideCompletionCheck(player, task)` | 非空 `Optional` 时 **覆盖** 任务自身 checker |

---

### 4.2 `GameModeRegistry`

**职责:** 模式注册 + 按世界 active 管理。

| 方法 | 说明 |
|------|------|
| `register(modId, modeId, mode)` | fullId = `modId:modeId`；重复/已 freeze 抛异常 |
| `get(fullId)` / `getAll()` / `getAllIds()` / `isRegistered` / `size` | 查询 |
| `start(fullId, level)` | 若该世界已有 active 则抛异常；`onPreStart`+`onStart`；失败则 remove + `onCleanup` 后重抛 |
| `stop(level, WinResult)` | `onEnd`（捕获异常）+ finally `onCleanup`；invalidate 任务池缓存 |
| `stop(level)` | 等价 `stop(level, WinResult.forceEnd("管理员终止"))` |
| `tickAll(server)` | 遍历 **ACTIVE_MODES** 中各 level 的 `onTick`（不含仅被动 isActive 的模式） |
| `getActiveForLevel(level)` | 先查 ACTIVE_MODES；否则遍历 `isActive` 并缓存 |
| `isActiveInLevel(level)` | 是否在 ACTIVE_MODES 中（**不含**被动 isActive） |
| `freeze()` / `isFrozen()` | 冻结注册 |

**主动 active vs 被动 isActive:**

- `start` 写入 `ACTIVE_MODES` → `tickAll` / `isActiveInLevel` 可见  
- 仅实现 `isActive`、未 `start` 时，`getActiveForLevel` 仍可能返回该模式（被动缓存，start/stop 时失效）

---

### 4.3 `WinResult`

**职责:** 胜负值对象。

| API | 说明 |
|-----|------|
| `new WinResult(List<UUID> winners, String reason)` | winners 空安全为不可变列表 |
| `singleWinner(uuid, reason)` | 单胜 |
| `noWinner(reason)` | 无胜者 |
| `forceEnd(reason)` | 强制结束（winners 空） |
| `getWinners()` / `getReason()` / `hasWinner()` | 访问器 |

---

## 5. SRE 集成抽象

这两个接口用于 **核心内部解耦**（避免 `TaskManager` / `BlackoutMode` 编译期硬绑 SRE 具体类）。DLC 通常 **不直接实现**，除非做自定义绑定层。

### 5.1 `GameStateProvider`

```java
@FunctionalInterface
void triggerCustomWin(ServerLevel level, String customWinnerId, UUID winnerPlayerId);
```

- `customWinnerId` 格式约定：`modId_taskId_win`  
- 本仓库由 `SREGameStateProvider` 注入 `TaskManager`

### 5.2 `SREGameLauncher`

```java
@FunctionalInterface
void startBlackoutGame(ServerLevel level);
```

- 本仓库 `BlackoutMode.setSreGameLauncher(SREBlackoutGameLauncher.INSTANCE)`  
- 用于在停电模式中启动 SRE 侧对局流程

---

## 6. 投票 API

### 6.1 通用选项投票

#### `VoteOption`

```java
record VoteOption(String id, String displayName)
```

- `id` 不可 null/blank  
- `displayName` 空则回退为 `id`  
- 候选项是 **字符串 id**（模式/地图等），不是玩家 UUID

#### `VoteResult`

```java
record VoteResult(String voteId, @Nullable String winnerId, Map<String, Integer> tallies, boolean randomPick)
```

| 字段 | 说明 |
|------|------|
| `voteId` | 投票实例 id |
| `winnerId` | 胜出 option id |
| `tallies` | 各 option 得票（含 0）不可变 Map |
| `randomPick` | 全员 0 票或并列最高后 **随机** 决出时为 true |

#### `OptionVoteApi`

薄包装 `OptionVoteManager`：

| 方法 | 说明 |
|------|------|
| `start(level, voteId, options, durationSeconds, onResolved)` | 启动；内部标题默认「投票」；返回是否成功 |
| `cast(level, voter, optionId)` | 对当前 active 投票；`optionId == null` 表示弃票 |
| `isActive(level)` | 是否进行中 |
| `cancel(level)` | 取消 |

网络路径应使用内部 `OptionVoteManager.cast(..., clientVoteId, ...)` 并校验 voteId；API 的 `cast` 使用当前 level 的 `currentVoteId`。

---

### 6.2 模式 → 地图两阶段投票

#### `ModeMapVoteConfig`

单次投票覆盖项：

| 字段 | 默认 | 说明 |
|------|------|------|
| `modeDurationSeconds` | `-1` | ≤0 表示用全局 `ModeMapVoteSettings` |
| `mapDurationSeconds` | `-1` | 同上 |
| `modeIds` | `null` | null = 已注册且配置启用的全部模式 |
| `mapIds` | `null` | null = 可用地图（仍受 enable / 模式白名单过滤） |

#### `ModeMapVoteSnapshot`

```java
record ModeMapVoteSnapshot(
    String phase,              // IDLE | MODE_VOTING | MAP_VOTING | SWITCHING_MAP | STARTING_MODE
    @Nullable String selectedModeId,
    @Nullable String selectedMapId,
    int remainingSeconds
)
```

#### `ModeMapVoteApi`

| 方法 | 说明 |
|------|------|
| `start(level)` | 默认 config |
| `start(level, config)` | 自定义覆盖 |
| `cancel(level)` | 取消编排 |
| `isRunning(level)` | 是否运行中 |
| `getSnapshot(level)` | `Optional` 只读快照 |

本仓库命令入口示例：`/habi_api` 相关（见 `CommandRegistrar`）。

---

## 7. 道具回收 (`ItemReclaimHelper`)

**职责:** 任务发放的物理道具在 **取消/隐藏** 时回收，避免未完成仍白嫖强力物品。

| 常量/方法 | 说明 |
|-----------|------|
| `GRANT_TAG_KEY` | `"habitrain_grant"` — CUSTOM_DATA 中的 NBT 键 |
| `tagGrantedItem(stack, fullId)` | 给物品打 grant 标签（1.21 `DataComponents.CUSTOM_DATA`） |
| `matchesGrant(stack, fullId)` | 是否匹配标签 |
| `reclaim(player, fullId)` | 扫描主背包+装备+副手，匹配则 `setCount(0)` |
| `reclaimForTask(player, task)` | 先 `definition.onReclaim`，再按 fullId `reclaim` |

**路径约定:**

| 路径 | 是否回收 |
|------|----------|
| 成功完成 | **否**（保留道具作奖励） |
| 取消 / 隐藏 / 强制替换 / 超时失败等 | **是**（调用 reclaim 路径） |

发放时推荐：`tagGrantedItem` + 任务 `onReclaim` 中 `reclaim(player, fullId)`，或统一走 `reclaimForTask`。

---

## 8. 端到端示例

### 8.1 注册简单任务

```java
// 在 DLC ModInitializer.onInitialize() 中
TaskRegistry.register("my_dlc", "wave_hello", b -> b
    .displayName("挥手问好")
    .category(TaskCategory.MURDER)
    .gameMode("sre:base")
    .weight(1.0f)
    .instinctColor(100, 200, 255, 200)
    .onAssign((player, task) -> task.setMaxProgress(3))
    .onTick((player, task) -> {
        // 自行推进进度；达 max 后由 completionChecker 或默认 isFulfilled 完成
    })
    .completionChecker((player, task) ->
        task.getProgress() >= task.getMaxProgress())
    .onComplete((player, task) -> {
        // 奖励等
    })
);
```

### 8.2 发放可回收道具

```java
TaskRegistry.register("my_dlc", "find_tool", b -> b
    .displayName("找到工具")
    .category(TaskCategory.ALL)
    .onAssign((player, task) -> {
        ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
        ItemReclaimHelper.tagGrantedItem(tool, "my_dlc:find_tool");
        player.getInventory().add(tool);
    })
    .onReclaim((player, task) ->
        ItemReclaimHelper.reclaim(player, "my_dlc:find_tool"))
    // 成功完成不调用 onReclaim；引擎取消路径应调用 reclaimForTask
);
```

### 8.3 实现并注册 GameMode

```java
public final class ArenaMode implements GameMode {
    public static final String ID = "my_dlc:arena";
    public static final TaskCategory ARENA =
        new TaskCategory("my_dlc:arena_main", "竞技任务", ID);

    @Override public String getId() { return ID; }
    @Override public String getDisplayName() { return "竞技场"; }
    @Override public List<TaskCategory> getTaskCategories() { return List.of(ARENA); }
    @Override public boolean isActive(ServerLevel level) { /* 你的状态 */ return false; }

    @Override
    public void onStart(ServerLevel level) { /* 分配角色、清场 */ }

    @Override
    public Optional<WinResult> checkWinCondition(ServerLevel level) {
        // return Optional.of(WinResult.singleWinner(uuid, "最后一人"));
        return Optional.empty();
    }

    @Override
    public List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player) {
        return tasks.stream()
            .filter(t -> ID.equals(t.getGameModeId()) || TaskCategory.ALL.equals(t.getCategory()))
            .toList();
    }
}

// onInitialize:
GameModeRegistry.register("my_dlc", "arena", new ArenaMode());
// Registry fullId = "my_dlc:arena"
// 启停: GameModeRegistry.start("my_dlc:arena", level);
```

### 8.4 通用选项投票

```java
List<VoteOption> opts = List.of(
    new VoteOption("map_a", "车站"),
    new VoteOption("map_b", "车站夜景")
);
OptionVoteApi.start(level, "my_vote_1", opts, 30, result -> {
    // result.winnerId(), result.randomPick(), result.tallies()
});
```

### 8.5 模式→地图大厅投票

```java
ModeMapVoteConfig cfg = new ModeMapVoteConfig();
cfg.modeDurationSeconds = 45;
cfg.mapDurationSeconds = 45;
// cfg.modeIds = List.of("habitrain_core:habitrain:blackout"); // 可选限制
ModeMapVoteApi.start(level, cfg);

ModeMapVoteApi.getSnapshot(level).ifPresent(snap -> {
    // snap.phase(), snap.selectedModeId(), snap.remainingSeconds()
});
```

### 8.6 停电任务时间影响（声明式）

```java
TaskRegistry.register("habitrain_core", "add_coal", b -> b
    .displayName("加煤")
    .category(/* blackout good */)
    .gameMode("habitrain:blackout")
    .timeImpact(TaskDefinition.TimeImpact.TimeAxis.MAINTENANCE_OR_COUNTDOWN, 30)
    // onComplete 内改时间时应与 timeImpact 声明一致
);
```

---

## 9. 约束与陷阱

1. **Freeze 后禁止注册** — 运行时动态注册会抛 `IllegalStateException`。  
2. **颜色是 int ARGB** — 不要用 `java.awt.Color`；可用 `instinctColor(r,g,b,a)`。  
3. **`onReclaim` ≠ `onRemove`** — `onRemove` 清效果/状态；`onReclaim` 仅取消路径回收道具；完成不 reclaim。  
4. **`setMaxProgress` 最小为 1** — 传入 0/负数会被抬到 1。  
5. **Registry fullId vs `getId()`** — `start`/`get` 用 `modId:modeId`；任务 `gameMode(...)` 常用逻辑 id（如 `habitrain:blackout`）。两边不要混用。  
6. **每 level 同时仅一个 ACTIVE 模式** — `start` 在已有 active 时失败。  
7. **`tickAll` 只 tick ACTIVE_MODES** — 纯被动 `isActive` 不会被 `tickAll` 驱动。  
8. **`TaskRegistry.getByCategory`** — 定义分类为 `ALL` 的任务会出现在任意查询分类结果中。  
9. **旧文档** — `docs/使用教程.md` 仍可能写 `habitrain_taskapi` / `HabiTask*`；以本手册与源码为准。  
10. **公开 API 边界** — 不要依赖 `TaskManager`、`OptionVoteManager` 等内部类；优先用本包 `*Api` / `*Registry`。

---

## 10. 类索引

| 类型 | 种类 | 一句话 |
|------|------|--------|
| `TaskCategory` | class | 任务分类 + 标准常量 |
| `TaskDefinition` | class | 任务定义 + Builder + TimeImpact |
| `TaskDefinition.ProgressUpdateHandler` | interface | 进度回调 |
| `TaskDefinition.TimeImpact` | record | 停电时间影响声明 |
| `TaskDefinition.TimeImpact.TimeAxis` | enum | 时间轴 |
| `TaskInstance` | class | 运行时实例 |
| `TaskRegistry` | class | 任务注册中心 |
| `GameMode` | interface | 游戏模式契约 |
| `GameModeRegistry` | class | 模式注册与调度 |
| `WinResult` | class | 胜负结果 |
| `GameStateProvider` | interface | SRE 自定义胜 |
| `SREGameLauncher` | interface | SRE 停电启动 |
| `VoteOption` | record | 投票选项 |
| `VoteResult` | record | 选项投票结果 |
| `OptionVoteApi` | class | 通用选项投票 API |
| `ModeMapVoteConfig` | class | 模式地图投票配置 |
| `ModeMapVoteSnapshot` | record | 模式地图投票快照 |
| `ModeMapVoteApi` | class | 模式地图投票 API |
| `ItemReclaimHelper` | class | 任务道具 grant/回收 |

---

## 11. 角色替换与修改 API

> **包:** `com.habitrain.core.api.role`  
> **入口:** `RoleOverrideApi`  
> **版本:** v1.0 (2026-07-25)

### 11.1 概述

本 API 允许 DLC/附属模组**替换**或**修改**哈比列车中已注册的 SRE 角色（包括原版 `starrailexpress:killer`、`starrailexpress:civilian` 以及 `habitrain_core` 的自定义角色）。注意：上游 SRE 的 mod ID 是 `starrailexpress`，角色 ID 一律写作 `starrailexpress:角色名`，不是 `sre:`。

- **REPLACE（替换）**：用新角色完全取代目标角色。原角色从分配池、角色介绍书、命令补全中隐藏，新角色取而代之。
- **MODIFY（调整）**：不改变目标角色 ID，动态覆盖其显示名、颜色、商店、初始物品、阵营 flags、生成参数、技能/被动、胜利条件。

**核心原则：**
- Core 掌控启用权 — 外部模组只提交定义，最终是否生效由 core 的配置 + 冲突裁决决定。
- 不物理删除原角色 — 采用过滤/补丁层实现隐藏。
- 新角色 ID 必须是 `modId:roleName` 格式。
- 同一目标不能同时生效 REPLACE 和 MODIFY。
- 多条覆盖同一目标 → 冲突，需在 ModMenu 中手动选择。

### 11.1.1 v1/v2 状态（2026-08-16 审核后）

- **v1（本 API）仍是正式兼容接口，不弃用。** v2 角色扩展 API 保持 preview / experimental：已补齐 v1 字段级 MODIFY 对等（名称、描述、初始物品、商店、胜利条件），但尚未完成真实 SRE 双端与复杂角色端到端验收，因此不标 Stable。
- v2 中已可迁移的字段见下表；无等价能力的字段在 v2 补齐前继续使用本 API（或 provider 自有 Mixin）。

**v1 → v2 逐字段迁移矩阵：**

| v1 字段/能力 | v2 对应 | 状态 |
|---|---|---|
| `colorPatch` | `RolePatch.color` / `RolePatch.colorProvider` | 可迁移 |
| `flagsPatch` | `RolePatch.flagsPatch`（另有对应布尔字段） | 可迁移 |
| `spawnInfoPatch` | `RolePatch.spawnInfoPatch`（另有 defaultMax 等字段） | 可迁移 |
| `managedSkillPatch` | `RoleSkillPatch` | 可迁移 |
| `roleBookAppendices` | `RoleBookPatch.append` | 可迁移 |
| `namePatch` | `RolePatch.namePatch` | 可迁移（2026-08-16） |
| `descriptionPatch` | `RolePatch.descriptionPatch` | 可迁移（2026-08-16） |
| `simpleDescriptionPatch` | `RolePatch.simpleDescriptionPatch` | 可迁移（2026-08-16） |
| `shopPatch` | `RolePatch.shopPatch` | 可迁移（2026-08-16） |
| `shopTransform` | `RolePatch.shopTransform` | 可迁移（2026-08-16） |
| `defaultItemsPatch` | `RolePatch.defaultItemsPatch` | 可迁移（2026-08-16） |
| `winConditionHook` | `RolePatch.winConditionHook`（已接入 SRE/Blackout 胜利桥） | 可迁移（2026-08-16） |
| callback 动态语义 | 无声明式等价能力 | 暂不可迁移（按场景使用 provider Mixin） |

> 能力矩阵（STABLE / EXPERIMENTAL / UNSUPPORTED / REQUIRES_PROVIDER_MIXIN）见《角色扩展API-v2使用教程.md》。

### 11.2 注册入口

所有注册必须在模组的 `onInitialize()` 中调用。注册在 `SERVER_STARTED` 时冻结。

```java
// 替换角色
RoleOverrideApi.registerReplace(ReplaceRoleDefinition.builder()
    .sourceModId("my_dlc")
    .displayName(Component.literal("暗影列车员"))
    .customTypeLabel("完全替换")
    .targetRoleId(ResourceLocation.parse("starrailexpress:killer"))
    .replacementRole(shadowKiller)
    .build());

// 调整角色
RoleOverrideApi.registerModify(ModifyRoleDefinition.builder()
    .sourceModId("my_dlc")
    .displayName(Component.literal("杀手·狂暴化"))
    .customTypeLabel("属性调整")
    .targetRoleId(ResourceLocation.parse("starrailexpress:killer"))
    .namePatch((original, server) -> Component.literal("狂暴杀手"))
    .shopPatch((original, server) -> MyShops.berzerkShop())
    .build());
```

### 11.3 REPLACE 完整示例

```java
public class MyDlcMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // 1. 构造新角色（遵循 NormalRole 构造规则）
        SRERole shadowKiller = new NormalRole(
            ResourceLocation.fromNamespaceAndPath("my_dlc", "shadow_killer"),
            0x5A3A8A,   // 颜色 RGB
            false,      // isInnocent
            true,       // canUseKiller
            SRERole.MoodType.FAKE,
            Integer.MAX_VALUE,  // maxSprintTime
            true        // canSeeTime
        );

        // 2. 注册技能（由 DLC 自己负责）
        RoleSkill.register(shadowKiller, RoleSkill.skill(
            ResourceLocation.fromNamespaceAndPath("my_dlc", "shadow_teleport"),
            "skill.my_dlc.shadow_teleport",
            ctx -> { /* 技能逻辑 */ }
        ).cooldownSeconds(30).showOnHud(true).build());

        // 3. 提交替换意图（不要调用 TMMRoles.registerRole！）
        RoleOverrideApi.registerReplace(ReplaceRoleDefinition.builder()
            .sourceModId("my_dlc")
            .displayName(Component.literal("暗影列车员"))
            .description(Component.literal("来自暗影的杀手，拥有传送能力"))
            .customTypeLabel("完全替换")
            .targetRoleId(ResourceLocation.parse("starrailexpress:killer"))
            .replacementRole(shadowKiller)
            .build());
    }
}
```

### 11.4 MODIFY 补丁说明

MODIFY 支持以下补丁类型，每个都是可选的：

| 补丁 | 接口 | 说明 |
|------|------|------|
| 显示名 | `NamePatch` | 覆盖 `getName()` |
| 颜色 | `ColorPatch` | 覆盖 `getColor()` |
| 商店 | `ShopPatch` | 完全替换 `getShopEntries()` |
| 初始物品 | `DefaultItemsPatch` | 完全替换 `getDefaultItems()` |
| 阵营 flags | `FlagsPatch` | 修改 isInnocent/canUseKiller/isNeutrals 等 |
| 生成参数 | `SpawnInfoPatch` | 修改 defaultMax/enableChance 等 |
| 技能注册 | `SkillRegistrar` | 在 MODIFY 启用时注册技能 |
| 胜利条件 | `WinConditionHook` | 劫持停电模式胜利判定 |

```java
RoleOverrideApi.registerModify(ModifyRoleDefinition.builder()
    .sourceModId("my_dlc")
    .displayName(Component.literal("平民·强化"))
    .targetRoleId(ResourceLocation.parse("starrailexpress:civilian"))
    .namePatch((original, server) -> Component.literal("武装平民"))
    .colorPatch((original, server) -> 0x00FF00)
    .flagsPatch((original, server, out) -> {
        out.canUseKiller = true;
        out.isInnocent = true;
    })
    .shopPatch((original, server) -> List.of(
        new KillerKnifeShopEntry(1, 0)
    ))
    .skillRegistrar(original -> {
        RoleSkill.register(original, RoleSkill.skill(
            ResourceLocation.fromNamespaceAndPath("my_dlc", "civilian_boost"),
            "skill.my_dlc.civilian_boost",
            ctx -> { /* 技能逻辑 */ }
        ).cooldownSeconds(60).build());
    })
    .winConditionHook(ctx -> {
        if (ctx.roleIsModified()) {
            // 自定义胜利判定
            return WinResult.singleWinner(
                ctx.level().players().get(0).getUUID(),
                "武装平民胜利"
            );
        }
        return null;
    })
    .build());
```

### 11.5 ID 与命名规范

- 新角色 ID 必须是 `modId:roleName` 格式（标准 `ResourceLocation`）。
- `sourceModId` 必须与 Fabric 模组 ID 一致，core 会校验该模组是否已加载。
- `replacementRole.identifier()` 的 namespace 必须与 `sourceModId` 一致。
- 不要自己调用 `TMMRoles.registerRole()` — core 会在启用时自动注册。

### 11.6 冲突与启用机制

- **全局开关**：ModMenu "角色覆盖" Tab 顶部有全局总开关，关闭时所有覆盖不生效。
- **逐条目开关**：每个条目可单独启用/停用。
- **冲突检测**：同一目标有多条 REPLACE、多条 MODIFY、或 REPLACE+MODIFY 共存时，全部标记为 CONFLICT，均不生效。
- **冲突解决**：在 ModMenu 中启用一条时，core 自动关闭同目标的其他条目。
- **实时生效**：开关变更后立即影响分配池、角色介绍书、命令补全；但不影响已分配到玩家身上的角色。

### 11.7 ModMenu 展示

在 ModMenu 配置页新增"角色覆盖" Tab，显示：

- 全局总开关
- 冲突横幅（有冲突时显示黄色提示）
- 条目列表（每行显示：类型标签[替换/调整]、显示名、目标角色 ID、启用状态）
- 点击行切换启用/停用
- 非 OP 客户端只读

DLC 可通过 `customTypeLabel` 自定义类型标签（如"完全替换"、"属性调整"），显示在条目行中。

### 11.8 运行时查询

```java
// 判断某角色是否被替换
boolean replaced = RoleOverrideApi.isReplaced(ResourceLocation.parse("starrailexpress:killer"));

// 获取替换后的角色实例
SRERole replacement = RoleOverrideApi.getReplacement(ResourceLocation.parse("starrailexpress:killer"));

// 判断某角色是否被修改
boolean modified = RoleOverrideApi.isModified(ResourceLocation.parse("starrailexpress:civilian"));

// 获取生效的 MODIFY 定义
ModifyRoleDefinition def = RoleOverrideApi.getActiveModify(ResourceLocation.parse("starrailexpress:civilian"));

// 获取所有生效条目
Collection<RoleOverrideEntry> entries = RoleOverrideApi.getEffectiveEntries();
```

### 11.9 限制与注意事项

1. **注册时机**：必须在 `onInitialize()` 中调用，`SERVER_STARTED` 后冻结。
2. **技能无注销 API**：MODIFY 禁用后，已注册的技能不会自动注销。建议在技能 handler 内用 `RoleOverrideApi.isModified(targetId)` 做守卫。
3. **局中不改**：开关变更不影响已分配角色的玩家。
4. **不物理删除**：被替换的原角色仍在 `TMMRoles.ROLES` 中，只是被过滤隐藏。
5. **CCA 组件**：REPLACE 新角色需要自己设置 `setComponentKey()` 并注册 CCA 组件。
6. **商店覆盖**：MODIFY 的 `shopPatch` 完全替换商店列表，不会与原有商店合并。

### 11.10 类索引

| 类型 | 说明 |
|------|------|
| `RoleOverrideApi` | 注册入口 + 运行时查询 |
| `ReplaceRoleDefinition` | REPLACE 定义 + Builder |
| `ModifyRoleDefinition` | MODIFY 定义 + Builder |
| `RoleOverrideEntry` | 运行时条目视图 |
| `RoleOverrideKind` | REPLACE / MODIFY 枚举 |
| `OverrideStatus` | ACTIVE / CONFLICT / DISABLED / INVALID / PENDING |
| `NamePatch` | 显示名补丁接口 |
| `ColorPatch` | 颜色补丁接口 |
| `ShopPatch` | 商店补丁接口 |
| `DefaultItemsPatch` | 初始物品补丁接口 |
| `FlagsPatch` | 阵营 flags 补丁接口 |
| `SpawnInfoPatch` | 生成参数补丁接口 |
| `SkillRegistrar` | 技能注册回调 |
| `WinConditionHook` | 胜利条件钩子 |
| `BlackoutWinCheckContext` | 胜利检查上下文 |

---

## 附录 A — 任务 tick 流水线（引擎侧约定）

```
TaskInstance.tick(player)
  ├─ 若 fulfilled → return
  ├─ 记录 owner / progressUpdatePlayer
  ├─ timeLimit 超时 → markFailed + onFail → return
  ├─ definition.onTick
  └─ definition.checkCompletion → true 则 fulfilled + onComplete
```

GameMode 侧额外钩子（`onTaskTick` / `overrideCompletionCheck` 等）由内部任务引擎在分配/tick 时调用；实现模式时可依赖这些拦截点，无需改任务定义。

---

## 附录 B — 相关内部文档（非 API 契约）

| 路径 | 说明 |
|------|------|
| `AGENTS.md` | 仓库代理说明、命令、网络表 |
| `docs/superpowers/guides/blackout-task-writing-guide.md` | 停电交互任务编写习惯 |
| `docs/superpowers/specs/2026-06-28-gamemode-api-design.md` | GameMode 历史设计 |
| `docs/使用教程.md` | **可能过时**，优先本手册 |

---


---

## 附录 C — 角色扩展 v2：审核 2026-08-14 修复记录

本附录记录《哈比列车角色扩展 API 审核报告-2026-08-14》落地情况（分支 restore/merged-20260809）。

### P0（必须优先，已修复）

- **P0-1 状态删除/重置同步**：RoleStateSyncPayload 新增 removed 标志与单调 revision。reset / clearRoundState / clearWorldState 对每个实际删除的 slot 按原 SyncPolicy 广播删除；客户端 RoleStateClientCache 按完整 slot key 删除镜像。业务上的 null 值仍以「存在但为 null」的 value payload 传输，与删除严格区分。
- **P0-2 迟加入/重连全量同步**：RoleStateServiceImpl.sendCurrentStateTo(player) 在 JOIN 的 manifest/snapshot 之后推送该玩家有权接收的全部当前 slot（OWNER / OWNER_AND_TRACKING / ALL；NONE / SERVER_ONLY 永不下发）。
- **P0-3 ALIAS 同源冲突**：alias from 为独占冲突键。重复源在诊断视图标 CONFLICT 并从 activeAliases() / resolveAlias() 中排除；可通过 RoleExtensionConfigService 的 winnerFor(from, "alias") 配置胜者；禁用其中一条后另一条自动恢复。解析与快照不再依赖注册顺序。

### P1（高优先，已修复或降级标注）

- **P1-1 收紧注册入口**：RoleExtensionApi.registrar() 改为只读 facade，任何注册方法一律抛错。hooks/state/action/voice/chat 与 ADD/MODIFY/REPLACE/ALIAS 一样只能来自 habitrain:role_extensions 入口点的 provider-scoped 事务；core 自身的 HabiRoleHooks / SevenSinV2Hooks 已迁入 CoreRoleExtensionProvider。
- **P1-2 客户端扩展兑现/标注**：RoleManifestService 将笼统的 client_ext 拆分为 client_hud / client_instinct / client_skin，并把尚未有运行时消费点的 client_name_render / client_screen 列入 experimentalCapabilities（manifest 与 payload 均已携带）。RoleNameRenderRule / RoleScreenSpec 标注 experimental，注册时打警告。2026-08-16 更新：HUD ICON/PROGRESS/COOLDOWN/CHARGE 已有基础 fallback 渲染；RoleNameRenderRule 的 NAMEPLATE 阶段已接入 EntityRendererMixin（hide/color）；RoleScreenSpec 新增 stock RoleScreen 分发入口（RoleClientExtensionHooks.openRoleScreen()）。
- **P1-3 语音/聊天能力**：语音 adapter 现在填充真实语音群组 id（VoicechatConnection.getGroup().getId()）与说话者→接收者距离，isolateGroup + hearWorld(false) 与 maxDistance 真正生效。聊天 muteReceive 因 Fabric 1.21.1 无按接收者过滤事件，明确标注 experimental 并警告，muteSend 继续生效。
- **P1-4 握手门控**：新增 C2S RoleHandshakeReportPayload（客户端收到 manifest 后回传本地 manifest）与服务端 RoleHandshakeGate。未上报 / API 不兼容 / 缺少 required provider → 角色动作被拒绝（新原因键 roleapi.action.handshake，附明确提示）；presentation 不匹配仅降级不阻断。
  - **定义哈希边界（复审 2026-08-16 P1）**：stock 客户端现在上报服务端 RoleSnapshotPayload 中的 definitionHash，因此 manifest 与 snapshot 的哈希不一致会被 HASH_MISMATCH 捕获；但它仍不是客户端独立计算服务端编译视图的指纹，独立双端校验尚未闭环。
- **P1-5 客户端状态 slot 身份**：RoleStateClientCache 的 slot key 纳入 worldKey 与 owner；「最新值」按服务端单调 revision 选择，不再使用 schema dataVersion。

### P2（中优先，core 内可完成项）

- **P2-1 消费者迁移**：BlackoutRoleManager（雇佣警/警长禁用）、SevenSinsMutex.fallbackNonSin、MikeCodeEditSkill.buildPool、WrathComponent.buildNonSinPool、CrimeScapegoatComponent.randomKillerPool 改为通过 RoleCatalogApi（新增 RoleCatalogConsumer.visiblePool() / resolveOrOriginal()）解析，快照未编译时回退 v1 路径，使 v2 ADD/REPLACE 角色进入原有消费者路径。
- **P2-3 动作目标模型**：仍为 NONE / PLAYER_UUID；slot/slotgroup/card 等扩展目标列为后续设计，不在本版承诺。
- **P2-5 文档**：本节即为此项；完整验收（专服、网络、可选依赖、多局冒烟）仍待游戏内验证。

### 复审 2026-08-14 追加修复

- **P2 状态快照撤销**：RoleStateSyncPayload 新增 snapshot 标志；sendCurrentStateTo 以 snapshot=true 批量推送按权限过滤的全量快照，客户端在批次首个 payload 时清空镜像再应用。观战者切换跟踪目标或玩家切换维度（LifecycleEventsRegistrar 每 tick 检测 camera/维度变化）都会触发重同步，修复"停止跟踪后重新跟踪仍残留旧镜像"的边界缺口。
- **P2 聊天 muteReceive**：维持降级标注（Fabric 1.21.1 无按接收者过滤事件）；注册时警告 + 文档明示不受支持，muteSend 继续生效。
- **P2 registrar Javadoc**：RoleExtensionApi 类说明与 registrar() 方法注释改为"仅兼容保留、只读、任何写操作抛错"，不再暗示可用 registrar() 声明角色。
- **工作区清洁**：恢复项目根目录 .gitignore（构建/缓存/运行/日志/备份均忽略），清理根目录临时构建日志。

*文档生成自 `src/main/java/com/habitrain/core/api` 源码；若 API 变更请同步更新本文件。*