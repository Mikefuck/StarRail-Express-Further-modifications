# 切片 S2 — task 子系统 审计发现

审查日期：2026-07-09
范围：`com.habitrain.core.task` 包内 7 个文件（见下表）。仅基于源码事实，未参考仓库内任何既有审计/计划/报告。

## 文件覆盖确认表

| 文件（相对 src/main/java） | 已读 | 行数 | 备注 |
|---|---|---|---|
| task/TaskManager.java | ✅ | 1–201 | DCL 单例 + 多个可变 Map |
| task/TaskBalancer.java | ✅ | 1–23 | 纯计算 |
| task/TaskPoolBuilder.java | ✅ | 1–144 | 静态池缓存 |
| task/BackpackSearchHandler.java | ✅ | 1–182 | 右键回调 + tick 超时 |
| task/BackpackQuestState.java | ✅ | 1–59 | 单例 + HashSet |
| task/SlownessReapplyManager.java | ✅ | 1–70 | 静态 effect 重施加 |
| task/GameLifecycleHandler.java | ✅ | 1–113 | 游戏结束清理 |

## 专属检查点对照

- TaskManager DCL 单例锁粒度与可变状态：DCL 锁粒度为 `TaskManager.class`，可变状态为多个 `ConcurrentHashMap`。DCL 本身可接受；但 `dlcTaskCounts` 缺少游戏结束清理（见 S2-001）。
- TaskBalancer/TaskPoolBuilder 是否每 tick 重建池/排序：`TaskPoolBuilder` 用 `computeIfAbsent` 缓存，非每 tick 重建；但缓存无按 mode 失效、无游戏结束失效，存在无界增长（见 S2-004）。`TaskBalancer.calcBoost` 为纯函数，无问题。
- BackpackSearchHandler 扫描容器是否 O(n²)：否。仅检查被右键的单个方块（`isBackpackBlock(block)`），无容器遍历。
- BackpackQuestState 状态字段是否全部可达：`completedPlayers` 经 `markCompleted/hasCompleted/resetAll` 均可达。
- SlownessReapplyManager 是否仍被触发：是，`registerTickHandler` 在 `HabiTrainCore` 注册，多个 blackout handler 调用 `register/unregisterAllLevels/clearAll`。但 `unregister` 与 `clearAll(ResourceKey)` 无调用者（见 S2-005）。
- GameLifecycleHandler 回调是否空挂：`register()` 仅打日志、不注册任何 tick 回调，实际 tick 由 `ModTickHandler` 调用 `tickGameEndCheck`（见 S2-006）。
- task 包是否直接依赖 blackout 具体类：是，`GameLifecycleHandler` 直接引用 `game.blackout.task.*` 具体类（见 S2-007）；`TaskManager` 直接依赖 `io.wifi.starrailexpress.cca.*` 具体组件（见 S2-008）。

## 发现列表

### S2-001  dlcTaskCounts 跨局不清理，计数单调累积破坏分配平衡
- 文件：task/TaskManager.java
- 行：51, 58–65, 88
- 维度：死逻辑 / 性能
- 严重度：S1
- 证据：`dlcTaskCounts`（行 51）只被 `incrementDlcTaskCount`（行 58）单调累加；唯一清理方法 `clearDlcTaskCounts`（行 63）全仓库无调用者。游戏结束清理 `clearAllActiveTasks`（行 88）只清 `activeCustomTasks/activeFakeTasks/blackoutNextDailyPool`，不清 `dlcTaskCounts`。
- 影响：`getDlcTaskCount`（行 53）被 `GenerateTaskMixin:307` 用于 DLC 任务分配决策（去重/次数限制）。计数永不归零 → 跨局累积 → 任务分配多样性/平衡随对局数劣化，属确定性跨局状态泄漏。
- 方向：在游戏结束路径（`clearAllActiveTasks` 或 `GameLifecycleHandler.handleGameEnd`）中接入 `dlcTaskCounts` 清理；或让 `clearDlcTaskCounts` 被生命周期回调调用。确认计数语义是“本局”还是“全局会话”后决定清理点。

### S2-002  TaskManager.getAvailableTasks 死代码，与 TaskPoolBuilder 重复
- 文件：task/TaskManager.java
- 行：125–143
- 维度：死逻辑 / 耦合
- 严重度：S2
- 证据：`getAvailableTasks(String, TaskCategory)` 全仓库仅在本文件定义、无任何调用者（Grep 仅命中定义行）；其过滤逻辑与 `TaskPoolBuilder.getAvailableDlcTasks`（行 40–99）+ `isTaskMapEnabled` 重复。
- 影响：重复实现易分叉（mapFilterMode、category 判定已与 TaskPoolBuilder 不同步）；维护负担与误用风险。
- 方向：确认是否为遗留入口，若无调用方则移除；统一池/过滤逻辑到单一实现。

### S2-003  TaskPoolBuilder.CACHE 无按 mode 失效、无游戏结束清理；invalidate(String) 死代码
- 文件：task/TaskPoolBuilder.java
- 行：25, 35–38, 137–143
- 维度：性能 / 死逻辑
- 严重度：S2
- 证据：`CACHE`（行 25）仅在 `invalidateAll()`（行 137，全清）被调用，`invalidate(String modeId)`（行 141）全仓库无调用者。`getPool` 用 `(modeId, mapName, categoryId)` 三元组为 key（行 23/34），无按 mode 选择性失效、无 LRU/容量上限。
- 影响：随 mode/mapName 组合增多，缓存条目只增不减；游戏结束不主动失效，跨局复用可能返回基于上一局 mode/category 的池（mapName/mode 变化时新建 key，旧 key 残留为内存泄漏）。`invalidate(String)` 形同虚设。
- 方向：接入游戏结束/模式切换的按 mode 失效；评估是否需要容量上限或弱引用；移除未用的 `invalidate(String)` 或将其接入失效路径。

### S2-004  SlownessReapplyManager.unregister 与 clearAll(ResourceKey) 死代码
- 文件：task/SlownessReapplyManager.java
- 行：49–55, 63–65
- 维度：死逻辑
- 严重度：S3
- 证据：`unregister(ResourceKey, UUID)`（行 49）与 `clearAll(ResourceKey)`（行 63）全仓库无调用者；所有清理处均用 `unregisterAllLevels` 或 `clearAll()` 无参版。
- 影响：误导维护者以为存在按维度精细清理路径；增加 API 表面。
- 方向：移除无调用者方法，或接入按维度失效逻辑。

### S2-005  GameLifecycleHandler.register() 为空挂（仅日志）
- 文件：task/GameLifecycleHandler.java
- 行：27–29, 39–45
- 维度：死逻辑 / 标识
- 严重度：S3
- 证据：`register()`（行 27）仅 `LOGGER.info`，不注册任何回调；实际 tick 由 `ModTickHandler.register()` 调 `GameLifecycleHandler.tickGameEndCheck(...)`（行 39）。方法名 `register` 暗示自注册，但无副作用。
- 影响：误导——读者以为 `GameLifecycleHandler.register()` 已接好生命周期；`HabiTrainCore.java:439` 调用此空方法。
- 方向：移除空 `register()` 或在其中真正注册 tick 回调；改名以反映实际职责。

### S2-006  GameLifecycleHandler 直接依赖 game.blackout.task 具体类
- 文件：task/GameLifecycleHandler.java
- 行：100–108
- 维度：耦合 / 架构边界
- 严重度：S2
- 证据：`handleGameEnd` 硬编码清理 `com.habitrain.core.game.blackout.task.AddCoalHandler/RepairWiringHandler/MaintainPowerHandler/FurnaceExplosionHandler/SabotageWiringHandler/BlackoutEatHandler/BlackoutDrinkHandler.clearAll()`（行 100–108）。`task` 包（本应通用）反向依赖 `game.blackout.task` 具体类。
- 影响：通用 task 包被 blackout 实现细节污染；新增 blackout 任务需改动通用 `GameLifecycleHandler`；违反包依赖方向（task → game.blackout）。
- 方向：让各 handler 自注册游戏结束清理回调（观察者/注册表），`GameLifecycleHandler` 只触发统一清理事件，不逐个硬编码。

### S2-007  TaskManager 直接依赖 io.wifi.starrailexpress.cca 具体组件并改其 public 字段
- 文件：task/TaskManager.java
- 行：6–9, 97–105, 185–200
- 维度：耦合 / 架构边界
- 严重度：S2
- 证据：`import io.wifi.starrailexpress.cca.AreasWorldComponent/SREGameRoundEndComponent/SREGameWorldComponent/SREPlayerTaskComponent`（行 6–9）。`triggerDirectWin` 直接写 `roundEnd.CustomWinnerID`、`roundEnd.CustomWinnerPlayers.add(...)`（行 190–192）并拼 `modId + "_" + taskId + "_win"`（行 190–191）。`getCurrentMapName/getCurrentGameModeCategory` 直接读 SRE 组件 public 字段 `areas.mapName`、`gameWorld.getGameMode().identifier`。
- 影响：core 的 task 管理器强耦合外部 SRE 模组具体类与内部字段；SRE 字段名/契约变更将直接破坏 core；core 实现细节经具体组件泄露。
- 方向：经 core 自有抽象（GameMode/Component 适配层）访问地图名/模式/胜利，避免直接持 SRE 具体类型与写其 public 字段；用命名常量替换 `_win` 拼接。

### S2-008  triggerDirectWin 硬编码 _win 后缀魔法字符串
- 文件：task/TaskManager.java
- 行：190–192
- 维度：标识
- 严重度：S3
- 证据：`roundEnd.CustomWinnerID = instance.getDefinition().getModId() + "_" + ... + "_win";` 用裸字符串 `_win` 拼接决定胜利标识。
- 影响：标识契约散落、无单一真相源，命名空间/分隔符变更难追踪。
- 方向：提取为命名常量或由 GameMode/任务定义提供 winner id 规约。

### S2-009  BackpackSearchHandler 超时分支重复 getPlayer 查找 + 实际不可达 else 分支
- 文件：task/BackpackSearchHandler.java
- 行：65–91, 157–161
- 维度：性能 / 死逻辑
- 严重度：S3
- 证据：超时分支内 `server.getPlayerList().getPlayer(uuid)` 在行 66 与行 83 两次调用（同一 uuid）；行 157 `if (world instanceof ServerLevel sl && sl.getServer() != null)` 的 else（行 160 `world.getGameTime()`）在 `player instanceof ServerPlayer` 已成立的服务端侧实际不可达。
- 影响：超时清理时多一次 O(玩家数) 列表查找；else 分支为死代码，混用 `world.getGameTime()` 与主世界 gameTime 与注释意图相悖。
- 方向：复用一次 `getPlayer` 查找结果；移除或修正 else 分支，统一时间源。

### S2-010  BackpackQuestState.init() 与 getInstance() DCL 并存，冗余初始化
- 文件：task/BackpackQuestState.java
- 行：15, 22–35
- 维度：死逻辑
- 严重度：S3
- 证据：`init()`（行 22）在 `HabiTrainCore.java:434` 调用直接 new 实例；`getInstance()`（行 26）又有 DCL 双检懒加载。两套初始化路径并存。
- 影响：冗余路径增加混淆（实例来源不唯一）；`init` 非 volatile 写、DCL 兜底，语义可对齐但易误改。
- 方向：择一初始化方式（启动期 `init` 或纯 DCL），不要两套并存。

## 未报为问题的项（确认已查）
- BackpackSearchHandler：无容器 O(n²) 扫描，仅单方块判断。tick 超时遍历量与活跃搜索数线性，可接受。
- TaskBalancer.calcBoost：纯函数、有上下钳制，无问题。
- SlownessReapplyManager tick：空表提前返回，活跃时按表迭代重施加 effect，结构合理；`registered` 防重复注册 OK。
- BackpackQuestState.completedPlayers：`HashSet` 但访问均经单例静态方法，主线程使用，未发现并发问题。