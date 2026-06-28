# 哈比列车核心 — 旧包清理与 Bug 修复设计规格书

> **日期**: 2026-06-28
> **项目**: 哈比列车核心 (HabiTrain Core) — 从 GameMode API 重构后清理遗留的旧任务 API
> **状态**: 设计已批准

---

## 1. 概述

在 GameMode API 重构完成后，旧 `com.habitrain.taskapi.*` 包仍然保留在 JAR 中并通过独立的 `fabric.mod.json` 作为一个完整模组加载。这导致两套系统同时运行，引发一系列 Bug 和冲突。

本设计的目标：

1. **彻底删除**旧 `habitrain_taskapi` 包及其所有附属文件
2. **迁移**所有遗留引用到新的 `com.habitrain.core.*` API
3. **修复**由此暴露的渲染、HUD 和任务逻辑 Bug
4. **迁移** companion 模组到新 API
5. 以上完成后，再进行 ModMenu 配置界面重写

---

## 2. 旧包删除清单

### 2.1 Java 源文件（33 个）

```
src/main/java/com/habitrain/taskapi/
├── HabiTrainTaskAPI.java                        # 旧主入口
├── api/
│   ├── HabiTaskCategory.java                    # 旧枚举 → 由 TaskCategory 常量替代
│   ├── HabiTaskDefinition.java                  # 旧定义 → 已由 TaskDefinition 替代
│   ├── HabiTaskInstance.java                    # 旧实例 → 已由 TaskInstance 替代
│   ├── HabiTaskRegistry.java                    # 旧注册中心 → 已由 TaskRegistry 替代
│   └── EffectOwnershipTracker.java              # 旧追踪器 → 已由 core.misc 版替代
├── impl/
│   ├── HabiTaskManager.java                     → 已由 TaskManager 替代
│   ├── TaskEnumHelper.java                      → 已由 core.game.sre 版替代
│   ├── config/HabiConfigManager.java            → 已由 ConfigManager 替代
│   ├── config/HabiTaskConfigEntry.java          → 已由 TaskConfigEntry 替代
│   └── mixin/ (5 个 mixin)                      → 已由 core 版替代
├── client/
│   ├── HabiTrainTaskAPIClient.java              → 已由 HabiTrainCoreClient 替代
│   ├── ActiveCustomTaskCache.java               → 已由 ActiveTaskCache 替代
│   ├── CustomTaskStore.java                     → 废弃
│   ├── gui/ (5 个 Screen 文件)                  → 已由 core.gui 版替代
│   └── mixin/ (5 个 mixin)                     → 已由 core.client.mixin 版替代
└── network/
    ├── ActiveCustomTaskPayload.java             → 已由 ActiveTaskPayload 替代
    ├── ConfigUpdateC2SPayload.java              → 已由 ConfigUpdatePayload 替代
    ├── ShaderConfigSyncS2CPayload.java          → 已由 ShaderConfigPayload 替代
    ├── ShaderPackInfoC2SPayload.java            → 已由 ShaderInfoPayload 替代
    └── TaskConfigSyncPayload.java               → 已由 TaskConfigPayload 替代
```

### 2.2 Mixin 配置文件（2 个）

```
src/main/resources/habitrain_taskapi.mixins.json        # 旧服务端 mixin
src/main/resources/habitrain_taskapi.client.mixins.json # 旧客户端 mixin
```

### 2.3 其他

`habitrain_taskapi` 旧 `fabric.mod.json` 已在首次重构时被 `habitrain_core` 版本覆盖，不需额外操作。删除上述文件后 JAR 中仅剩 `habitrain_core` 一个模组 ID。

---

## 3. 新包引用修复

### 3.1 HabiTaskCategory → TaskCategory 常量替代

`HabiTaskCategory` 枚举被删除后，由 `TaskCategory` 类提供等效的静态常量：

```java
public class TaskCategory {
    // 替代 HabiTaskCategory.MURDER / REPAIR / ALL / CUSTOM
    public static final TaskCategory MURDER = new TaskCategory("sre:murder", "谋杀模式", "sre:base");
    public static final TaskCategory REPAIR = new TaskCategory("sre:repair", "修机模式", "sre:base");
    public static final TaskCategory ALL     = new TaskCategory("sre:all", "通用任务", "sre:base");
    public static final TaskCategory CUSTOM  = new TaskCategory("sre:custom", "自定义任务", "sre:base");
}
```

### 3.2 TaskDefinition 改动

| 项 | 旧 | 新 |
|----|----|----|
| 字段 | `HabiTaskCategory originalCategory` | `TaskCategory category` |
| Builder 方法 | `.originalCategory(HabiTaskCategory)` | `.category(TaskCategory)` |
| Getter | `getOriginalCategory()` | `getCategory()` |
| `gameModeId` 字段 | 保留 | 保留 |
| `customCategory` 字段 | 保留 | 保留 |

### 3.3 受影响文件清单

| 文件 | 改动内容 |
|------|---------|
| `core/game/sre/mixin/GenerateTaskMixin.java` | `HabiConfigManager` → `ConfigManager` |
| | `HabiTaskConfigEntry` → `TaskConfigEntry` |
| | `HabiTaskCategory` → `TaskCategory` |
| | `getEffectiveWeight()` 从旧 Config 改为新 Config |
| `core/task/TaskManager.java` | `HabiTaskCategory` → `TaskCategory` |
| | `getCurrentGameModeCategory()` 返回类型变更 |
| | `getAvailableTasks()` 使用新 category 匹配 |
| `core/api/TaskDefinition.java` | `originalCategory(HabiTaskCategory)` → `category(TaskCategory)` |
| | `getOriginalCategory()` → `getCategory()` |
| | Builder 默认值从 `HabiTaskCategory.ALL` 改为 `TaskCategory.ALL` |
| `core/client/mixin/HudCustomTaskMixin.java` | 移除 `CustomTaskStore` 引用，改用 `ActiveTaskCache` |
| `core/game/sre/SREGameModeBase.java` | `HabiTaskCategory` → `TaskCategory` |
| `core/client/gui/ConfigScreen.java` | `HabiTaskCategory` → `TaskCategory`（配合后续 ModMenu 重写） |

---

## 4. Bug 修复方案

### 4.1 Bug 1: test_grass 方块一直高亮

**根因分析：**

1. 旧 `CustomTaskBlockRendererMixin` 和新版同时注入，双重渲染
2. 旁观/创造模式下渲染所有 `blockTypeId >= 12` 的自定义方块
3. `TaskManager.activeCustomTasks` 中的已完成任务未被清理，导致任务状态始终为"活跃"

**修复：**

a) **删除旧 renderer**（旧包删除自然解决）

b) **任务完成后清理活跃状态** — 在 `TaskManager.handleTaskCompletion()` 末尾加入：

```java
// 任务完成 → 清理活跃任务
removeActiveTask(player.getUUID());
```

c) **游戏结束时清空所有活跃任务** — 在 `SREGameModeBase.onCleanup()` 或 `SREGameModeBase.onEnd()` 中加入：

```java
// 遍历所有玩家，清空活跃 DLC 任务
TaskManager.getInstance().clearAllActiveTasks();
```

d) **旁观/创造模式只渲染当前 GameMode 对应的方块** — 在 `renderAllCustomTaskBlocks()` 中过滤：如当前无活跃 GameMode（大厅阶段），仅渲染类型为 ALL 的任务方块（即 11 号类型以下的 SRE 原版方块），不渲染任何 DLC 自定义方块（type >= 12）。

### 4.2 Bug 2: 左上角 HUD 不显示 DLC 任务

**根因分析：**

`TaskInstance` 实现了 SRE 的 `TrainTask` 接口，通过 `generateTaskInternal()` 返回后被 SRE 加入 `tasks` 映射。但：
- `getName()` 返回的是 `taskId`（"test_grass"）而非可读名
- 旧 `HudCustomTaskMixin` 从 NBT sync 读取的 `customName` 未正确传递到 SRE HUD
- `ActiveTaskCache` 虽然存储了活跃任务 ID，但与 SRE 的 HUD 渲染是两条路径

**修复：**

a) **`TaskInstance.getName()`** → 返回 `definition.getDisplayName()` 而非 `definition.getTaskId()`

b) **`HudCustomTaskMixin`**（新版，在 `core.client.mixin` 中）：
- 从 NBT 同步数据中提取 `customId` 和 `customName`
- 存入 `ActiveTaskCache`（复用现有机制）
- 同时确保 SRE 的 `readFromSyncNbt()` 流程不受干扰

### 4.3 Bug 3: 配置/配置错误的游戏模式映射

**根因分析：**

`GenerateTaskMixin` 中的 `getCurrentGameModeCategory()` 使用旧枚举判断当前游戏模式，无法识别新 GameMode。

**修复：**

```java
// 旧: 仅返回 HabiTaskCategory.MURDER/REPAIR
// 新: 查询 GameModeRegistry.getActiveForLevel()，返回对应的 TaskCategory
public TaskCategory getCurrentGameModeCategory(Player player) {
    if (player == null || player.level() == null) return TaskCategory.ALL;
    try {
        SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(player.level());
        if (gameWorld == null || gameWorld.getGameMode() == null) return TaskCategory.ALL;
        String modeId = gameWorld.getGameMode().identifier.toString();
        if (modeId.contains("repair")) return TaskCategory.REPAIR;
        return TaskCategory.MURDER;
    } catch (Exception e) {
        return TaskCategory.ALL;
    }
}
```

---

## 5. Companion 模组迁移

### `HabiTrainMoreTasks.java`

| 位置 | 旧代码 | 新代码 |
|------|--------|--------|
| 第 80 行 (test_grass) | `.originalCategory(HabiTaskCategory.MURDER)` | `.category(TaskCategory.MURDER)` |
| 第 125 行 (pet_cat) | `.originalCategory(HabiTaskCategory.MURDER)` | `.category(TaskCategory.MURDER)` |
| 其他任务 | 同上 | 同上 |
| Import | `import com.habitrain.taskapi.api.HabiTaskCategory;` | `import com.habitrain.core.api.TaskCategory;` |

---

## 6. 槟榔模组

无需改动。已正确引用 `com.habitrain.core.misc.EffectOwnershipTracker` 和 `"habitrain_core": "*"`。

---

## 7. 实施顺序

| # | 阶段 | 内容 |
|---|------|-------|
| 1 | 删除旧包 | 删除所有 `com.habitrain.taskapi.*` 源文件和旧 mixin JSON |
| 2 | 新包引用修复 | 修改 TaskCategory 常量、TaskDefinition、TaskManager、GenerateTaskMixin、SREGameModeBase、HudCustomTaskMixin |
| 3 | Bug 修复 | 渲染器修复 + 任务完成清理 + HUD 修复 |
| 4 | 编译验证 | `./gradlew clean build` 确认可编译 |
| 5 | Companion 迁移 | 更新 HabiTrainMoreTasks 到新 API |
| 6 | 整体构建 | 构建两个模组，验证 JAR 中不再包含旧包 |
| 7 | ModMenu 重写 | 单独阶段，后续设计 |

---

## 8. 不包含的范围

- ❌ 本设计**不包含** ModMenu 配置界面的重写（此为后续阶段）
- ❌ 本设计**不包含** GameMode API 本身的修改
- ❌ 本设计**不包含**对 SRE 本体的修改
- ❌ 本设计**不包含**槟榔模组的任何改动
