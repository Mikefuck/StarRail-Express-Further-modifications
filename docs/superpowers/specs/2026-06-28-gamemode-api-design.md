# 哈比列车 GameMode API — 设计规格书

> **日期**: 2026-06-28
> **项目**: 哈比列车核心 (HabiTrain Core) — 从任务API重构为通用游戏模式框架
> **状态**: 设计已批准

---

## 1. 概述

将现有的 `habitrain_taskapi` (哈比列车任务API) 彻底重构为 `habitrain_core` (哈比列车核心)，从单一的任务注册 API 扩展为完整的游戏模式框架。新增 `GameMode` 接口体系，允许 DLC 模组注册独立的游戏模式（含生命周期、任务分类、配置作用域），同时保留对星穹列车 (SRE) 的完整支持。

### 重构原则

- **完全重命名** — mod id、包名、类名全部更新，不提供向后兼容
- **SRE 硬依赖** — `starrailexpress` 仍为强制前置，但所有 SRE 代码隔离在 `game/sre/` 包中
- **任务系统保留** — `HabiTaskCategory` 枚举保留，新增 per-GameMode 的 `TaskCategory` 扩展
- **无冗余** — 移除废弃的自动录制回放功能

---

## 2. 命名方案

| 项目 | 当前值 | 新值 |
|------|--------|------|
| Mod ID | `habitrain_taskapi` | `habitrain_core` |
| 显示名 | Mike任务API | 哈比列车核心 (HabiTrain Core) |
| 根包名 | `com.habitrain.taskapi` | `com.habitrain.core` |
| 主类 | `HabiTrainTaskAPI` | `HabiTrainCore` |
| 配置文件 | `habitrain_taskapi.json` | `habitrain_core.json` |

### 附属模组

| 项目 | 当前值 | 新值 |
|------|--------|------|
| Mod ID | `test_more_tasks` | `habitrain_more_tasks` |
| 显示名 | Test More Tasks | HabiTrain More Tasks |
| 根包名 | `com.example` | `com.habitrain.moretasks` |
| 主类 | `TemplateMod` | `HabiTrainMoreTasks` |

---

## 3. 包结构

```
com.habitrain.core
├── HabiTrainCore.java                         主入口
│
├── api/                                       公开 API
│   ├── GameMode.java                          游戏模式接口
│   ├── GameModeLifecycle.java                 生命周期钩子集合
│   ├── GameModeRegistry.java                  模式注册中心
│   ├── TaskCategory.java                      per-GameMode 任务分类
│   ├── TaskDefinition.java                    任务定义（取代 HabiTaskDefinition）
│   ├── TaskInstance.java                      任务运行时实例（取代 HabiTaskInstance）
│   ├── TaskRegistry.java                      任务注册中心（取代 HabiTaskRegistry）
│   └── WinResult.java                         胜利结果值对象
│
├── game/                                       GameMode 实现
│   ├── AbstractGameMode.java                  骨架实现
│   └── sre/                                   SRE 集成模块（唯一接触 SRE 类的包）
│       ├── SREGameModeBase.java               SRE 模式公共基类
│       ├── SREMurderMode.java                 谋杀模式
│       ├── SERepairMode.java                  修机模式
│       ├── TaskEnumHelper.java                迁移，不变
│       └── mixin/
│           ├── MapScannerMixin.java
│           ├── GenerateTaskMixin.java
│           ├── ServerTickMixin.java
│           ├── RoleMethodDispatcherMixin.java
│           ├── ServerReplayRecorderMixin.java
│           └── NunchuckCooldownMixin.java
│
├── task/                                       任务系统核心
│   ├── TaskManager.java                      取代 HabiTaskManager
│   ├── TaskBalancer.java                     自动平衡逻辑（独立类）
│   └── Engine.java                           任务分配引擎
│
├── config/                                     配置系统
│   ├── ConfigManager.java                    取代 HabiConfigManager
│   ├── TaskConfigEntry.java                  取代 HabiTaskConfigEntry
│   └── GameModeConfigScope.java              per-GameMode 配置
│
├── network/                                    网络同步
│   ├── TaskConfigPayload.java
│   ├── ActiveTaskPayload.java
│   ├── ConfigUpdatePayload.java
│   ├── ShaderConfigPayload.java
│   └── ShaderInfoPayload.java
│
├── misc/                                       杂项工具
│   └── EffectOwnershipTracker.java            保留现有实现
│
└── client/                                     客户端
    ├── HabiTrainCoreClient.java
    ├── gui/
    │   ├── ConfigScreen.java
    │   ├── TaskListScreen.java
    │   ├── TaskEditScreen.java
    │   ├── GlobalSettingsScreen.java
    │   └── ShaderWhitelistScreen.java
    ├── cache/ActiveTaskCache.java
    └── mixin/
        ├── HudTaskMixin.java
        ├── InstinctColorMixin.java
        ├── InstinctCacheFixMixin.java
        ├── CustomTaskBlockRendererMixin.java
        └── StarRailExpressTitleScreenMixin.java
```

---

## 4. GameMode 接口设计

### 4.1 GameMode 接口

```java
public interface GameMode {
    String getId();
    String getDisplayName();
    List<TaskCategory> getTaskCategories();
    boolean isActive(ServerLevel level);

    // 生命周期 (全部 default，按需重写)
    default void onPreStart(ServerLevel level) {}
    default void onStart(ServerLevel level) {}
    default void onTick(ServerLevel level) {}
    default void onPlayerJoin(ServerPlayer player) {}
    default void onPlayerLeave(ServerPlayer player) {}
    default void onTaskComplete(ServerPlayer player, TaskInstance task) {}
    default Optional<WinResult> checkWinCondition(ServerLevel level);
    default void onEnd(ServerLevel level, WinResult result) {}
    default void onCleanup(ServerLevel level) {}

    // 任务行为拦截
    default List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player);
    default boolean overrideCompletionCheck(ServerPlayer player, TaskInstance task);
    default void onTaskAssign(ServerPlayer player, TaskInstance task);
    default void onTaskTick(ServerPlayer player, TaskInstance task);
    default void onTaskProgressChange(ServerPlayer player, TaskInstance task, int oldProgress);
}
```

### 4.2 TaskCategory

```java
public class TaskCategory {
    private final String id;           // "sre:murder", "sre:repair", "mymode:challenge"
    private final String displayName;
    private final String gameModeId;   // 所属 GameMode ID
}
```

- 原 `HabiTaskCategory` 枚举保留，SRE 原版任务继续使用
- 自定义 GameMode 使用 `TaskCategory` 类定义自有分类

### 4.3 GameModeRegistry

```java
public class GameModeRegistry {
    static void register(String modId, String modeId, GameMode mode);
    static GameMode get(String fullId);
    static Collection<GameMode> getAll();
    static GameMode getActiveForLevel(ServerLevel level);
    static boolean isFrozen();
    static void freeze();
}
```

---

## 5. 任务系统变化

### 5.1 TaskRegistry

`HabiTaskRegistry` → `TaskRegistry`，新增 `gameMode` 参数过滤：

```java
// SRE 原版模式任务（保留现有方式）
TaskRegistry.register("my_mod", "pet_cat", builder -> builder
    .displayName("摸猫猫")
    .originalCategory(HabiTaskCategory.MURDER)
    .weight(1.0f)
);

// 自定义 GameMode 任务
TaskRegistry.register("my_mod", "challenge", builder -> builder
    .displayName("挑战任务")
    .gameMode("my_mod:battle_royale")
    .customCategory(new TaskCategory("br:loot", "战利品", "my_mod:battle_royale"))
    .weight(2.0f)
);
```

### 5.2 新增回调与属性

在原有回调基础上新增：

| 新增项 | 类型 | 说明 |
|--------|------|------|
| `.onRemove()` | 回调 | 任务被移除时清理资源 |
| `.onFail()` | 回调 | 任务失败时触发 |
| `.onProgressUpdate()` | 回调 | 进度变化时通知 |
| `.timeLimit(seconds)` | int | 限时任务，超时自动失败 |
| `.canRepeat(boolean)` | boolean | 是否可重复分配 |
| `.tags("a","b")` | String... | 自定义标签（其他模组可读取） |

---

## 6. SRE 集成层

### 6.1 架构

```
HabiTrainCore.java (纯框架)
   ├── ConfigManager 初始化
   ├── GameModeRegistry：注册内置 SRE 模式
   ├── 网络包注册
   └── 通用命令 (/instantgroup)

SREGameModeBase.java (SRE 公共基类，继承 AbstractGameMode)
   ├── registerOriginalTasksAsBuiltin()
   ├── 语音群组管理 (LobbyChat)
   ├── OnGameStarted / OnGameEnd 事件处理
   ├── 玩家大厅语音加入逻辑
   └── 光影白名单 (框架通用，留在 HabiTrainCore)

SREMurderMode extends SREGameModeBase
   ├── getId() = "sre:murder"
   ├── isActive(): 检测当前是否为谋杀模式
   └── getTaskCategories(): MURDER + ALL

SERepairMode extends SREGameModeBase
   ├── getId() = "sre:repair"
   ├── isActive(): 检测当前是否为修机模式
   └── getTaskCategories(): REPAIR + ALL
```

### 6.2 移除项

- ❌ 自动录制回放 (`autoReplayRecording`、replay start/stop、反射静默 ServerReplay)
- ❌ `HabiConfigManager` 中的 `autoReplayRecording` 字段和方法

---

## 7. 配置系统

### 7.1 配置文件结构

```json
{
  "global": {
    "dlcProbabilityTarget": 0.5,
    "shaderWhitelistEnabled": false,
    "shaderWhitelist": []
  },
  "tasks": {
    "habitrain_core:sleep": { "enabled": true, "instinctColor": -12517376, ... }
  },
  "gameModes": {
    "my_mod:battle_royale": {
      "enabled": true,
      "customSettings": { "maxPlayers": 16, "timeLimit": 600 }
    }
  }
}
```

### 7.2 类对应

| 旧类 | 新类 | 说明 |
|------|------|------|
| `HabiConfigManager` | `ConfigManager` | 全局配置管理 |
| `HabiTaskConfigEntry` | `TaskConfigEntry` | 任务级配置（不变） |
| (无) | `GameModeConfigScope` | per-GameMode 配置，DLC 可读写自定义 setting |

---

## 8. 网络同步

| 方向 | 旧包名 | 新包名 | 变化 |
|------|--------|--------|------|
| S→C | `TaskConfigSyncPayload` | `TaskConfigPayload` | 新增可选 GameMode 配置段 |
| S→C | `ActiveCustomTaskPayload` | `ActiveTaskPayload` | 通用化（不再限 CUSTOM 任务） |
| C→S | `ConfigUpdateC2SPayload` | `ConfigUpdatePayload` | 同现有逻辑 |
| S→C | `ShaderConfigSyncS2CPayload` | `ShaderConfigPayload` | 不变 |
| C→S | `ShaderPackInfoC2SPayload` | `ShaderInfoPayload` | 不变 |

---

## 9. 附属模组迁移

`habitrain_more_tasks`（原 `test_more_tasks`）需变更：

- `fabric.mod.json`: mod id、依赖名 (`habitrain_taskapi` → `habitrain_core`)
- `build.gradle`: 依赖路径更新
- 所有 Java 文件：包名 `com.example` → `com.habitrain.moretasks`
- API import 变更:
  - `HabiTaskRegistry` → `TaskRegistry`
  - `HabiTaskInstance` → `TaskInstance`
  - `EffectOwnershipTracker` → `com.habitrain.core.misc.EffectOwnershipTracker`
- 资源文件路径：`assets/test_more_tasks/` → `assets/habitrain_more_tasks/`
- 音效注册不变，业务逻辑（槟榔、背包、猫猫、对视等）完全保留

---

## 10. 实施顺序

| # | 阶段 | 内容 |
|---|------|------|
| 1 | 新建包结构 | 创建 `com.habitrain.core.{api,game,task,config,network,misc,client}` |
| 2 | API 层 | `GameMode` / `GameModeRegistry` / `GameModeLifecycle` / `TaskCategory` |
| 3 | 任务系统 | `TaskRegistry` / `TaskDefinition` / `TaskInstance` / `TaskManager` / `TaskBalancer` / `Engine` |
| 4 | SRE 集成 | `SREGameModeBase` / `SREMurderMode` / `SERepairMode` + mixin 迁移 |
| 5 | 配置系统 | `ConfigManager` / `TaskConfigEntry` / `GameModeConfigScope` |
| 6 | 网络同步 | 5 个 payload 重命名 + 通用化 |
| 7 | 主类整合 | `HabiTrainCore` 整合所有模块，移除自动录制 |
| 8 | 客户端 | `HabiTrainCoreClient` + GUI + cache + client mixin |
| 9 | 清理 | 删除 `com.habitrain.taskapi` 遗留包 |
| 10 | 附属模组 | 迁移 `test_more_tasks` → `habitrain_more_tasks` |

---

## 11. 不包含的范围

- 本设计**不包含**对 SRE 本体的修改
- 本设计**不包含**对 betel-nut-mod 的依赖变更
- 本设计**不包含**对其他已有 mixin 的业务逻辑修改（仅重命名/移动）
- 通用事件总线（跨 GameMode 通信）推迟到后续版本
