# 切片 S8 审计发现 — blackout.task + SREBlackoutGameMode

审查范围：
- `game/blackout/task/*.java`（23 个文件）
- `game/blackout/sre/SREBlackoutGameMode.java`

审查日期：2026-07-09
审查者：独立审计 agent（仅基于源码事实，未参考仓库内任何既有审计/计划/报告）

## 文件覆盖确认表

| 文件 | 已读 | 备注 |
|---|---|---|
| task/AddCoalTask.java | 是 | |
| task/AddCoalHandler.java | 是 | |
| task/RepairWiringTask.java | 是 | |
| task/RepairWiringHandler.java | 是 | |
| task/SabotageWiringTask.java | 是 | |
| task/SabotageWiringHandler.java | 是 | |
| task/FurnaceExplosionTask.java | 是 | |
| task/FurnaceExplosionHandler.java | 是 | |
| task/MaintainPowerTask.java | 是 | |
| task/MaintainPowerHandler.java | 是 | |
| task/RestorePowerTask.java | 是 | |
| task/RestorePowerHandler.java | 是 | |
| task/SupplyTaskSyncHelper.java | 是 | |
| task/BlackoutTaskHelper.java | 是 | |
| task/BlackoutEatTask.java | 是 | |
| task/BlackoutEatHandler.java | 是 | |
| task/BlackoutDrinkTask.java | 是 | |
| task/BlackoutDrinkHandler.java | 是 | |
| task/BlackoutSearchBackpackTask.java | 是 | |
| task/BlackoutBetelQuestTask.java | 是 | |
| task/BlackoutPetCatTask.java | 是 | |
| task/BlackoutBeAloneTask.java | 是 | |
| task/BlackoutLookMyEyesTask.java | 是 | |
| sre/SREBlackoutGameMode.java | 是 | |

辅助确认文件（用于交叉验证调用/注册关系，非本切片审查对象）：
- `task/SlownessReapplyManager.java`、`task/TaskManager.java`、`task/BackpackSearchHandler.java`
- `BuiltinTaskRegistrar.java`、`HabiTrainCore.java`、`game/sre/mixin/BlackoutEatMixin.java`
- `game/blackout/BlackoutVictoryChecker.java`

## 发现清单

### S8-001 SupplyTaskSyncHelper.syncCompletion 链式递归致时间影响被重复施加（S1，死逻辑/性能）
- file: `game/blackout/task/SupplyTaskSyncHelper.java`
- line: 38-68
- evidence: `syncCompletion` 对每个未完成同任务 GOOD 玩家执行 `task.setFulfilled(true)` 后调用 `task.getDefinition().onComplete(other, task)`（56-58 行）。repair_wiring/maintain_power/add_coal 的 onComplete 内部再次调用 `SupplyTaskSyncHelper.syncCompletion(...)`（RepairWiringTask:54, MaintainPowerTask:37, AddCoalTask:65）。被同步玩家的 onComplete 内还会调用 `BlackoutTaskHelper.applyTimeImpact(level, ...)`（MaintainPowerTask:34 等）。
- impact: 当 N 个 GOOD 玩家同时做同一供电池任务，第一名完成后会级联触发其余 N-1 名 onComplete，每名 onComplete 都对全局共享计时器再施加一次 delta（如 maintain_power +80s）。最终计时器被施加 N×delta（5 人同时做 maintain_power → +400s 而非 +80s），严重破坏供电平衡。与 RestorePowerTask 通过 `isRestoreCompleted()` 早返回守卫不同，本 helper 无任何去重守卫。同时递归链每层都遍历全存活玩家，总开销 O(N²)。
- direction: 在 syncCompletion/onComplete 路径引入"时间影响只由原始完成者施加一次"的去重守卫（类比 RestorePowerTask 的 isRestoreCompleted 守卫），并将同步完成与时间影响施加解耦。

### S8-002 BlackoutLookMyEyesTask.onTick 每 tick 遍历全服玩家（S1，性能）
- file: `game/blackout/task/BlackoutLookMyEyesTask.java`
- line: 30-58
- evidence: `onTick` 每 tick 执行 `for (ServerPlayer otherPlayer : serverPlayer.serverLevel().players())`，对每个 otherPlayer 计算 `toOther.length()`、`normalize()`、`dot()` 等向量运算，再用 `distance > 3.0` 过滤（39-45 行）。无 AABB 预过滤、无计数节流。
- impact: 持有该任务的每个玩家每 tick 都遍历全服所有玩家做向量计算；M 名玩家同时持有该任务则每 tick O(M×N) 向量运算 + 多个 Vec3 分配。同任务的 BuiltinTaskRegistrar.look_my_yes（BuiltinTaskRegistrar.java:199-208）使用 `getEntitiesOfClass(ServerPlayer.class, searchBox, ...)` 做 3 米 AABB 预过滤，本 blackout 版本是其劣化复制。热路径确定性触发。
- direction: 改用 AABB 范围查询（`getEntitiesOfClass` 配合 3 米盒）替代全服遍历，与 BuiltinTaskRegistrar.look_my__eyes 对齐。

### S8-003 AddCoalTask.onTick 每 tick 全背包线性扫描（S2，性能）
- file: `game/blackout/task/AddCoalTask.java`
- line: 46-52
- evidence: `onTick` 在 `progress == GENERATOR_PHASE` 时每 tick 调用 `hasPlayerCoal(player)`，该方法 `for (i=0; i<getContainerSize(); i++)` 遍历整个背包检查 `stack.is(Items.COAL)`（81-89 行），每 tick 扫描最多 ~41 槽。
- impact: 玩家处于 GENERATOR_PHASE（手持煤炭去右键发电机期间，可能持续数十秒）时每 tick 全背包线性扫描，热路径每 tick 分配/迭代。虽单次开销小，但属持续热路径。
- direction: 将"是否仍持有煤炭"改为事件驱动（监听背包变更或仅在右键发电机时校验），避免每 tick 扫背包。

### S8-004 BlackoutEatHandler / BlackoutDrinkHandler 全套死代码（S2，死逻辑）
- file: `game/blackout/task/BlackoutEatHandler.java`
- line: 7-21（同见 BlackoutDrinkHandler.java:7-21）
- evidence: 两个 Handler 的 `register()` 方法体为空（EatHandler:11-13, DrinkHandler:11-13）；静态 Map `eatingTracked`/`drinkingTracked` 仅在 `clearState`/`clearAll` 中被 `remove`/`clear`，仓库全量搜索无任何 `put`/写入口（grep `eatingTracked|drinkingTracked` 仅命中定义与 remove/clear）。进食/喝水任务完成实际由 `BlackoutEatMixin`（game/sre/mixin/BlackoutEatMixin.java:32-44）与 `BlackoutDrinkItemMixin` 直接 setProgress 完成，不经这两个 Handler。两个 Task 的 `onRemove` 仍调用 `BlackoutEatHandler.clearState`/`BlackoutDrinkHandler.clearState`（BlackoutEatTask:33, BlackoutDrinkTask:33）。
- impact: 两个 Handler 类、两个静态 Map、`clearState`/`clearAll`/`register` 全为无效代码；`GameLifecycleHandler` 与 Task 的 onRemove 调用 `clearAll`/`clearState` 也无任何效果。维护者可能误以为存在进食/喝水状态追踪逻辑，增加理解成本。
- direction: 删除 BlackoutEatHandler/BlackoutDrinkHandler 两个类，移除各 Task.onRemove 与 GameLifecycleHandler 中对它们的调用；如未来确需状态追踪再恢复。

### S8-005 BlackoutTaskHelper.advanceOnLook 永不被调用（S2，死逻辑）
- file: `game/blackout/task/BlackoutTaskHelper.java`
- line: 106-142（连同私有 `resolveTargets` 144-166）
- evidence: `advanceOnLook(Player, TaskInstance)` 全仓搜索无任何调用点（grep `advanceOnLook` 仅命中本定义）。`resolveTargets` 仅供 advanceOnLook 使用，亦为死代码。BlackoutPetCatTask.onTick（BlackoutPetCatTask:38-73）内联了自己的 raytrace 逻辑，未使用此 helper。
- direction: 删除 advanceOnLook 与 resolveTargets；如需统一注视检测再在调用方显式引用。

### S8-006 MaintainPowerHandler.tickCheck 空桩 + MaintainPowerTask.onTick 空转（S3，死逻辑）
- file: `game/blackout/task/MaintainPowerHandler.java`
- line: 93-94（同见 MaintainPowerTask.java:29）
- evidence: `MaintainPowerHandler.tickCheck(Player, TaskInstance)` 方法体为空（93-94 行），MaintainPowerTask.onTick 仍 `.onTick((player, task) -> MaintainPowerHandler.tickCheck(player, task))`（MaintainPowerTask:29）调用该空桩。
- impact: 每 tick 都触发一次空方法调用，无任何作用；徒增阅读歧义（看似有 tick 检查实则无）。
- direction: 删除空 tickCheck 与 MaintainPowerTask 的 onTick 钩子（或彻底移除该 onTick 注册）。

### S8-007 AddCoalHandler 阶段0 发煤延迟到缓慢结束，与文档不一致（S3，标识/文档）
- file: `game/blackout/task/AddCoalHandler.java`
- line: 67-84
- evidence: 类 Javadoc 声称"阶段0：右键煤炭块 → 给缓慢III(6秒) + 发放 1 个煤炭 → 进入阶段1"（35-36 行），但实际 onUseBlock 阶段0 分支仅 giveSlow + 设 slowUntilTick，并未当场发煤；发煤与推进到 GENERATOR_PHASE 发生在 END_SERVER_TICK 的 `state.slowUntilTick <= tick` 分支（71-84 行，6 秒后）。
- impact: 行为与文档描述不符；玩家右键煤炭块后 6 秒内背包无煤炭，期间 GUI/任务提示可能误导。
- direction: 校准文档与实现一致，或将发煤提前到右键瞬间并明确语义。

### S8-008 SREBlackoutGameMode 构造硬编码魔法数字（S3，标识）
- file: `game/blackout/sre/SREBlackoutGameMode.java`
- line: 38
- evidence: `super(MODE_ID, 10, 1);` 中 10（最小玩家数）与 1（疑似杀手数下限）为裸字面量，无命名常量或注释解释含义。
- direction: 抽取为具名常量并注释语义，与父类 SREMurderGameMode 形参对齐。

### S8-009 RestorePowerHandler.restoreCompleted 为跨局共享静态布尔（S3，耦合）
- file: `game/blackout/task/RestorePowerHandler.java`
- line: 32
- evidence: `private static boolean restoreCompleted` 是类级静态字段，依赖 `BlackoutVictoryChecker.resetCompleted()`（BlackoutVictoryChecker:112）在局末复位。若多局/多维度并行或复位路径遗漏，状态会跨局泄漏。
- direction: 将"是否已恢复供电"收敛进按 level 隔离的状态对象（如 BlackoutTimerSystem 的 TimerState），消除跨局静态状态。