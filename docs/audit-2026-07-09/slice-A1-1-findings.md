# 切片 A1-1 耦合/架构专项审计发现（静态网 / 单例）

- 日期：2026-07-09
- 范围：全仓横切（`com.habitrain.core`），聚焦 static Map / 单例 / per-level 静态状态网
- 方法：Grep 收集 `static.*Map`、`INSTANCE`、`getInstance`，逐文件读源码核对清理路径与可达性
- 独立性：未参考仓库内任何既有审计/计划/报告，全部基于源码事实

## 文件覆盖确认表

| 文件（相对 `com/habitrain/core`） | 是否读取 | 主要核查点 |
|---|---|---|
| HabiTrainCore.java | 是 | SERVER_STOPPING 清理覆盖范围（208-220） |
| task/GameLifecycleHandler.java | 是 | handleGameEnd 清理清单（唯一清理点） |
| task/TaskManager.java | 是 | 单例 + dlcTaskCounts/clearAllActiveTasks |
| config/ConfigManager.java | 是 | 单例 DCL、无 SERVER_STOPPING 重置 |
| api/GameModeRegistry.java | 是 | REGISTRY/ACTIVE_MODES/frozen 静态网 |
| game/blackout/BlackoutMode.java | 是 | 命名空间、onCleanup 清理 |
| game/blackout/BlackoutRoleManager.java | 是 | INSTANCES per-level + disabledVigilanteRoles 全局静态 |
| game/blackout/BlackoutTimerSystem.java | 是 | instances per-level HashMap |
| game/blackout/BlackoutSheriffVoteManager.java | 是 | STATES + 死逻辑（startVote 不可达） |
| game/blackout/BlackoutExileVoteManager.java | 是 | STATES per-level（已覆盖清理） |
| game/blackout/BlackoutPoliceHireService.java | 是 | STATES per-level |
| game/blackout/BlackoutShopService.java | 是 | ROLE_SHOPS 全局 + PURCHASES per-level |
| game/blackout/BlackoutHornVoteHandler.java | 是 | confirmWindows UUID-keyed（无 SERVER_STOPPING 清理） |
| game/blackout/task/*Handler.java（AddCoal/FurnaceExplosion/MaintainPower/RestorePower/RepairWiring/Sabotage/BlackoutEat/BlackoutDrink） | grep+抽样 | activeStates UUID-keyed，仅 handleGameEnd 清理 |
| task/SlownessReapplyManager.java | 是 | activeEntries per-level，仅 handleGameEnd 清理 |
| betel/BetelQuestState.java | 是 | 单例（非 volatile）+ playerData |
| task/BackpackQuestState.java | 是 | 单例（volatile DCL）+ completedPlayers |
| misc/EffectOwnershipTracker.java | 是 | ownership 全局 UUID→effect→source，死方法 clearPlayer/forceClean |
| betel/BetelLeafHandler.java | grep | activeHarvests UUID-keyed |
| task/BackpackSearchHandler.java | grep | activeSearches UUID-keyed |
| client/InstinctColorHelper.java | 是 | overrideColors 静态 Map，getOverrideColors 泄露 |
| game/sre/SREGameModeBase.java | 是 | LOBBY_GROUP/pendingVoiceJoins 静态可变 |
| game/sre/CustomTaskBlockCache.java | grep | BLOCK_TYPE_IDS/BLOCK_AT_POS（按扫描清理） |
| task/TaskPoolBuilder.java | grep | CACHE（invalidateAll） |
| ModTickHandler.java | 是 | tick 入口 |

## 静态状态网清单（per-level Map）

- `GameModeRegistry.ACTIVE_MODES` — HashMap，SERVER_STOPPING 经 `GameModeRegistry.stop` 清理（活跃 level）✅
- `BlackoutRoleManager.INSTANCES` — ConcurrentHashMap，`clear(level)` 覆盖 ✅
- `BlackoutTimerSystem.instances` — HashMap，`reset(level)` 覆盖 ✅
- `BlackoutSheriffVoteManager.STATES` — HashMap，`reset(level)` 覆盖 ✅
- `BlackoutExileVoteManager.STATES` — ConcurrentMap，`reset(level)` 覆盖 ✅
- `BlackoutShopService.PURCHASES` — ConcurrentHashMap，`resetRound(level)` 覆盖 ✅
- `BlackoutPoliceHireService.STATES` — ConcurrentMap，`cleanup(level)` 覆盖 ✅
- `SlownessReapplyManager.activeEntries` — ConcurrentHashMap，**仅 handleGameEnd 清理，SERVER_STOPPING 未覆盖** ❌
- 上述 per-level Map 外层大多为 ConcurrentHashMap，但内层 state 容器多为 HashMap/HashSet（RoleState.roles/factions/sheriffs、VoteState.votesByVoter/candidateOrder 等），均为 server thread 单线程访问，无线程安全实质风险，但对外暴露 `getRoleHistory`/`getAllAlive` 等返回拷贝，已规避外部修改。

## 静态状态网清单（UUID / 全局 Map，非 per-level）

以下仅在 `GameLifecycleHandler.handleGameEnd`（游戏活跃→非活跃下降沿）清理，SERVER_STOPPING 不清理：
- `SlownessReapplyManager.activeEntries`（per-level 但走 handleGameEnd）
- `BetelLeafHandler.activeHarvests`
- `BackpackSearchHandler.activeSearches`
- `AddCoalHandler.activeStates`、`FurnaceExplosionHandler.activeStates/pendingExplosions`、`MaintainPowerHandler.activeStates`、`RestorePowerHandler.activeStates`
- `BlackoutEatHandler.eatingTracked`、`BlackoutDrinkHandler.drinkingTracked`
- `BlackoutHornVoteHandler.confirmWindows`
- `EffectOwnershipTracker.ownership`（甚至 handleGameEnd 也不清，仅 release 精细释放）
- `BetelQuestState` 实例 `playerData` / `revealUsedThisRound`
- `BackpackQuestState.completedPlayers`
- `SREGameModeBase.pendingVoiceJoins`、`LOBBY_GROUP`
- `TaskManager` 实例 `activeCustomTasks` / `activeFakeTasks` / `blackoutNextDailyPool`（clearAllActiveTasks，仅 SREGameModeBase 288/294 调用，非 SERVER_STOPPING）

## 单例清单

- `ConfigManager` — volatile DCL，无 SERVER_STOPPING 重置（设计如此，配置跨会话复用，load 重新覆盖）✅
- `TaskManager` — volatile DCL ✅
- `BetelQuestState` — **非 volatile** 懒加载单例（见 S3）
- `BackpackQuestState` — volatile DCL ✅

## 上帝类量化（行数）

- `CustomTaskBlockRendererMixin` 466 行 / 22 static+private 字段
- `HabiTrainCore` 442 行（主入口，注册 + 生命周期 + 网络接收集中）
- `GenerateTaskMixin` 435 行 / 16 方法
- `TaskTabScreen` 406 行、`ShaderWhitelistScreen` 389 行（客户端 GUI）
- `BlackoutRoleManager` 297 行、`SREGameModeBase` 296 行
- 注：这些类偏大但职责相对内聚；未达“上帝类”级严重耦合，作为架构背景记录，不单列为 finding。

## findings 摘要

见 StructuredOutput。主要结论：
1. SERVER_STOPPING 清理覆盖不全（S1）：对局中途停服会残留大量 UUID-keyed 静态 Map 到下一会话。
2. Sheriff 投票系统整体死逻辑（S1）：startVote 不可达 → castVote/tickSecond/resolve/sync 系列全部失效。
3. clearDlcTaskCounts、EffectOwnershipTracker.clearPlayer/forceClean 为死方法（S2），且 ownership 无清理路径。
4. 命名空间不一致（S3）、API 泄露内部 Map（S3）、单例非 volatile（S3）。