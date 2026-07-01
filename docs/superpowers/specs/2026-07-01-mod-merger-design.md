# 模组合并设计：哈比列车更多修改 → 哈比列车核心

**日期**: 2026-07-01  
**状态**: 已批准  
**标签**: merger, refactor, migration, code-review

---

## 1. 动机

将 `哈比列车更多修改` (HabiTrain More Tasks) 的所有功能完整合并到 `哈比列车api` (HabiTrain Core) 项目内，消除跨模组依赖关系，统一项目结构、包命名、映射体系，并在合并过程中修复已发现的代码缺陷。

### 目标

1. 消除 `更多修改` 模组作为独立项目的存在，所有代码归入 Core
2. 将 Yarn 映射源码转为 Mojmap（与 Core 项目一致）
3. 修复已知 Bug（sounds.json 命名空间错误、任务注册 API 不一致）
4. 统一构建配置和资源文件

### 非目标

- 不改变原有功能逻辑（仅搬迁 + 转换 + 清理）
- 不改动任务完成条件、效果数值、交互流程
- 不做大规模重构（如抽取任务工厂模式）

---

## 2. 包结构

```
com.habitrain.core
├── HabiTrainCore.java                  ← 主入口，合并更多修改的初始化逻辑
├── betel/
│   ├── BetelQuestState.java            ← 槟榔任务状态管理 (原 Yarn，转 Mojmap)
│   ├── BetelQuestDefinition.java       ← 槟榔任务定义
│   └── BetelLeafHandler.java           ← 槟榔叶交互
└── task/
    ├── BackpackQuestState.java         ← 背包翻找状态
    ├── BackpackSearchHandler.java      ← 背包翻找交互
    └── GameLifecycleHandler.java       ← 游戏生命周期清理
└── game/blackout/task/
    ├── AddCoalTask.java                ← 添加煤炭（好人任务）
    ├── FurnaceExplosionTask.java       ← 熔炉爆炸（坏人任务）
    ├── MaintainPowerTask.java          ← 维护供电（好人任务）
    ├── RepairWiringTask.java           ← 维修线路（好人任务）
    └── SabotageWiringTask.java         ← 破坏线路（坏人任务）
```

### 删除的文件

| 原文件 | 原因 |
|--------|------|
| `BetelQuestMod.java` | 仅提供 MOD_ID/LOGGER 常量，合并后直接引用 `HabiTrainCore` 的常量 |
| `HabiTrainMoreTasks.java` | 初始化逻辑并入 `HabiTrainCore.onInitialize()` |

### 命名空间变更

- 原 `habitrain_more_tasks` → `habitrain_core`
- 任务 ID 变更：`habitrain_more_tasks:test_grass` → `habitrain_core:test_grass`
- 音效 ID 变更：`habitrain_more_tasks:betel_nut_eat` → `habitrain_core:betel_nut_eat`

---

## 3. 入口整合

将 `HabiTrainMoreTasks.onInitialize()` 的所有逻辑拆分到 `HabiTrainCore.onInitialize()` 中，按调用顺序组织为私有方法：

```java
// 在 HabiTrainCore.onInitialize() 末尾追加：
registerMoreTasks();           // 注册 4 个谋杀模式自定义任务
registerMoreSounds();          // 注册自定义音效
initBetelSystem();             // 槟榔系统初始化 + 食物限制
registerMoreTickHandlers();    // 服务端 Tick 事件
```

### 生命周期事件整合

| 事件 | 逻辑来源 | 注册位置 |
|------|----------|----------|
| 服务端 Tick | `HabiTrainMoreTasks` 中的 `ServerTickEvents.END_SERVER_TICK` | `HabiTrainCore.registerLifecycleEvents()` 内追加 |
| 食物限制 | `BetelQuestState.registerFoodRestriction()` 的 `UseItemCallback.EVENT` | `HabiTrainCore.registerLifecycleEvents()` 或初始化阶段 |
| 槟榔叶交互 | `BetelLeafHandler.register()` 的 `UseBlockCallback.EVENT` | `HabiTrainCore.onInitialize()` 内的 `initBetelSystem()` |
| 背包翻找 | `BackpackSearchHandler.register()` 的 `UseBlockCallback.EVENT` | `HabiTrainCore.onInitialize()` |
| 游戏生命周期 | `GameLifecycleHandler.register()` | `HabiTrainCore.onInitialize()` |

---

## 4. 映射转换清单

Yarn → Mojmap（按使用频率排序）：

| Yarn | Mojmap | 备注 |
|------|--------|------|
| `ServerPlayerEntity` | `ServerPlayer` | 最频繁，所有任务回调 |
| `PlayerEntity` | `Player` | 事件回调参数 |
| `player.getServerWorld()` | `player.serverLevel()` | |
| `player.getWorld()` | `player.level()` | |
| `ServerWorld` | `ServerLevel` | 世界对象 |
| `World` | `Level` | 世界接口 |
| `Text.literal()` | `Component.literal()` | 聊天/消息 |
| `ActionResult` | `InteractionResult` | 交互回调返回值 |
| `TypedActionResult` | `TypedActionResult<ItemStack>` | Mojmap 中仍存在但位置不同 |
| `UseBlockCallback` → `InteractionResult` | 不变（Fabric API 事件） | Fabric API 事件自身不变化 |
| `RegistryKey<World>` | `ResourceKey<Level>` | 世界标识键 |
| `Identifier.of()` | `ResourceLocation.fromNamespaceAndPath()` | 资源位置 |
| `Hand` | `InteractionHand` | 交互手参数 |
| `BlockHitResult` | 不变 | 无变化 |
| `StatusEffectInstance` | 不变 | 无变化 |
| `SoundCategory` | 不变 | 无变化 |
| `player.getRandom()` | 不变 | 无变化 |
| `player.getInventory()` | 不变 | 无变化 |
| `Registries.ITEM.get()` | 不变 | 注册表访问 |

---

## 5. Bug 修复

### 5.1 sounds.json 命名空间错误

**问题**：`sounds.json` 中 sound 路径使用 `test_more_tasks:` 命名空间，但代码实际注册使用 `habitrain_more_tasks:`。合并后应为 `habitrain_core:`。

**修复**：修改 `sounds.json` 中的路径引用为 `habitrain_core:`。

### 5.2 任务注册 API 不一致

**问题**：大多数任务使用 builder 模式直接返回（无 `.build()`），但 `MaintainPowerTask` 和 `RepairWiringTask` 以 `.build()` 结尾。

**修复**：移除 `.build()` 调用以统一风格。

### 5.3 缺少 look_my_eyes.ogg 音频文件

**问题**：`sounds.json` 中注册了 LOOK MY EYES 音效，但资源目录下缺少对应 `.ogg` 文件。

**修复**：在 `sounds.json` 中保留条目，但注释说明需要用户提供音频文件。

---

## 6. 构建配置

### build.gradle 追加

```groovy
dependencies {
    // ... 现有依赖 ...
    modImplementation files("libs/betel-nut-mod-4.0.1.jar")
}
```

### fabric.mod.json 追加

```json
"depends": {
    // ... 现有 ...
    "betel-nut-mod": "*"
}
```

---

## 7. 资源文件合并

| 来源资源 | 目标路径 | 处理方式 |
|----------|----------|----------|
| `assets/habitrain_more_tasks/sounds/*.ogg` | `assets/habitrain_core/sounds/` | 复制，同时删除原目录 |
| `assets/habitrain_more_tasks/sounds.json` | `assets/habitrain_core/sounds.json` | 合并到现有 sounds.json |
| `assets/habitrain_more_tasks/lang/*.json` | `assets/habitrain_core/lang/` | 追加翻译条目 |
| `fabric.mod.json` | - | 追加 betel-nut-mod 依赖 |

---

## 8. 验证要点

1. **构建验证** — `./gradlew clean build` 成功
2. **任务 ID 验证** — 所有任务在 `HabiTrainCore` 初始化日志中显示正确的 `habitrain_core:` 前缀
3. **音效验证** — 吃槟榔/翻找背包音效能正常播放（确认 sounds.json 路径正确）
4. **槟榔任务** — 采集槟榔叶 → 获得槟榔 → 吃槟榔 → 任务完成，全流程正常
5. **停电模式任务** — 添加煤炭/破坏线路/熔炉爆炸等任务在停电模式中正常触发
6. **食物限制** — Stage 3+ 限制非槟榔食物
7. **游戏结束清理** — 游戏结束时槟榔效果被清除，背包翻找状态重置

---

## 9. 文件变更清单

### 新增文件（搬迁 + 映射转换）
| 文件 | 说明 |
|------|------|
| `src/main/java/com/habitrain/core/betel/BetelQuestState.java` | 槟榔任务状态管理 |
| `src/main/java/com/habitrain/core/betel/BetelQuestDefinition.java` | 槟榔任务定义 |
| `src/main/java/com/habitrain/core/betel/BetelLeafHandler.java` | 槟榔叶交互 |
| `src/main/java/com/habitrain/core/task/BackpackQuestState.java` | 背包翻找状态 |
| `src/main/java/com/habitrain/core/task/BackpackSearchHandler.java` | 背包翻找交互 |
| `src/main/java/com/habitrain/core/task/GameLifecycleHandler.java` | 游戏生命周期清理 |
| `src/main/java/com/habitrain/core/game/blackout/task/AddCoalTask.java` | 停电任务 |
| `src/main/java/com/habitrain/core/game/blackout/task/FurnaceExplosionTask.java` | 停电任务 |
| `src/main/java/com/habitrain/core/game/blackout/task/MaintainPowerTask.java` | 停电任务 |
| `src/main/java/com/habitrain/core/game/blackout/task/RepairWiringTask.java` | 停电任务 |
| `src/main/java/com/habitrain/core/game/blackout/task/SabotageWiringTask.java` | 停电任务 |

### 修改文件
| 文件 | 变更 |
|------|------|
| `HabiTrainCore.java` | 追加更多模组的初始化逻辑 |
| `build.gradle` | 追加 betel-nut-mod 依赖 |
| `fabric.mod.json` | 追加 betel-nut-mod 依赖 |
| `assets/habitrain_core/sounds.json` | 合并音效条目 |

### 删除文件
| 文件 | 说明 |
|------|------|
| `HabiTrainMoreTasks.java` | 入口并入 Core |
| `BetelQuestMod.java` | 常量合并 |
