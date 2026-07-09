# Slice S7 — game.blackout 核心审计发现

审计范围：`src/main/java/com/habitrain/core/game/blackout/*.java`（排除 task/、sre/ 子目录）
审计日期：2026-07-09
审计员：独立代码审计（从零读源码，未参考仓库内任何既有报告）

## 文件覆盖确认表

| 文件 | 已读 | 备注 |
|---|---|---|
| BlackoutMode.java | ✅ | 上帝类倾向 + 静态可变状态 |
| BlackoutTickCoordinator.java | ✅ | 含未调用回调方法 |
| BlackoutTimerSystem.java | ✅ | onTimeWarning 死回调 |
| BlackoutSyncManager.java | ✅ | — |
| BlackoutVictoryChecker.java | ✅ | 重复 getPlayerList 查找 |
| BlackoutRoleManager.java | ✅ | 多个未引用 public 方法 |
| BlackoutSheriffVoteManager.java | ✅ | 整套警长投票逻辑为死代码 |
| BlackoutSheriffResolver.java | ✅ | applyVoteResult 从未被调用 |
| SheriffVoteBroadcaster.java | ✅ | 仅供死代码使用 |
| BlackoutExileVoteManager.java | ✅ | — |
| BlackoutHornVoteHandler.java | ✅ | 重复方块缓存 |
| BlackoutPhoneHandler.java | ✅ | 重复方块缓存 |
| BlackoutDeathHandler.java | ✅ | — |
| BlackoutPoliceHireService.java | ✅ | — |
| BlackoutShopService.java | ✅ | ROLE_SHOPS 静态可变 |
| BlackoutShopDefinition.java | ✅ | record，OK |
| BlackoutShopCatalog.java | ✅ | 魔法数字 + 部分常量仅定义用 |
| BlackoutPsychoModeShopEntry.java | ✅ | OK |
| BlackoutRoleShopEntry.java | ✅ | OK |
| BlackoutOverlayTypes.java | ✅ | 重复方块缓存 |

## 发现清单（按严重度）

### S7-001 [S1][死逻辑] BlackoutSheriffVoteManager 整套警长投票功能为死代码
- file: game/blackout/BlackoutSheriffVoteManager.java
- line: 78-96, 143-169
- evidence: `startVote` 为 `private` 且全仓库无调用方（grep 仅命中定义）。`state.active` 只能由 `startVote` 置 true，故永为 false。`tickSecond`(L78) 从未被任何 coordinator 调用（tick coordinator L68 只调 `BlackoutExileVoteManager.tickSecond`）。`castVote`(L98) 入口 `if (state == null || !state.active) return false`(L100) 恒返回 false。
- impact: 客户端 `BlackoutSheriffVoteCastPayload` 接收器（HabiTrainCore.java:308-316）把玩家投票转给 `castVote`，但 `castVote` 恒返回 false，票被静默丢弃；`BlackoutSheriffResolver.applyVoteResult`（L21）也全仓库无调用方，警长由投票选出 + 左轮奖励 + 200 金奖励链路永不触发。功能缺失但对外仍注册了 payload，属“只注册不发送/无效路径”。
- direction: 决定彻底移除该 manager+resolver+broadcaster+payload，或重新接通触发入口（如电话雇佣已替代则应删除残留）。

### S7-002 [S1][死逻辑] BlackoutSheriffResolver.applyVoteResult 永不调用，警长投票结算链断裂
- file: game/blackout/BlackoutSheriffResolver.java
- line: 21
- evidence: `applyVoteResult` 全仓库无调用方（grep 仅命中定义与类型引用）。BlackoutMode 持有 `sheriffResolver` 字段（BlackoutMode.java:43）并注入 tickCoordinator，但 tickCoordinator 从未调用 `sheriffResolver` 任何方法。
- impact: 即便 S7-001 被接通，结算结果也无消费者；`sheriffResolver` 字段为死对象，残留左轮发放/警察转职/200 金奖励逻辑悬空。
- direction: 与 S7-001 一并处理；若保留警长投票则接通 resolver 调用，否则删除。

### S7-003 [S2][死逻辑] BlackoutTimerSystem.onTimeWarning 回调恒为空，60 秒倒计时警告永不触发
- file: game/blackout/BlackoutTimerSystem.java
- line: 72-75, 48
- evidence: `BlackoutMode.onPreStart` 传入 `() -> {}` 作为 timeWarningCb（BlackoutMode.java:87）。`tickSecond` 在 `totalTimeRemaining<=60` 时 `s.onTimeWarning.run()`（L74），但回调体为空。
- impact: 剩余 60 秒时无任何客户端提示，玩家不知时间将尽；代码注释与字段名暗示应有警告广播，实际不产生任何效果。
- direction: 要么删除 onTimeWarning 字段与对应分支，要么在 onPreStart 传入真实广播回调。

### S7-004 [S2][死逻辑] BlackoutTickCoordinator.onSreGameStarted/onSreGameEnded 未被调用
- file: game/blackout/BlackoutTickCoordinator.java
- line: 25-31
- evidence: `onSreGameStarted(ServerLevel)` 与 `onSreGameEnded(ServerLevel)` 全仓库无调用方（grep 仅命中定义）。它们只设置 `cachedSreActive`，而 `tick()` 内部已自行每 20 tick 重新探测 `cachedSreActive`，故这两个方法即便存在也不影响行为。
- impact: 死方法，维护者可能误以为有事件驱动入口；实际状态完全由 tick 轮询驱动。
- direction: 删除这两个未用方法。

### S7-005 [S2][死逻辑] BlackoutRoleManager 多个 public 方法无外部调用方
- file: game/blackout/BlackoutRoleManager.java
- line: 65,158,190,200
- evidence: `getRoleId`(L65)、`getAllSheriffs`(L158)、`getRandomGoodNonSheriff(Random)`(L190，单参仅转发)、`getRandomGoodNonSheriff(Random,UUID)`(L200) 全仓库无调用方（grep 仅命中定义；电话雇佣实际用的是 `getRandomHireTarget` L220）。
- impact: 公共 API 表面膨胀，含未使用候选选择逻辑（含其分支与 null 处理），增加误用风险与维护负担。
- direction: 删除未用 public 方法或降为 private；`getRandomGoodNonSheriff` 与 `getRandomHireTarget` 语义重叠，需厘清保留哪一个。

### S7-006 [S2][死逻辑] SheriffVoteBroadcaster.resetCache / BlackoutTimerSystem 多个 getter 未被引用
- file: game/blackout/SheriffVoteBroadcaster.java, game/blackout/BlackoutTimerSystem.java
- line: SheriffVoteBroadcaster.java:64；BlackoutTimerSystem.java:206-211
- evidence: `SheriffVoteBroadcaster.resetCache`(L64) 无调用方。`isTransientBlackoutActive`(L206)、`isInMaintenance`(L207)、`getInitialBlackoutCD`(L210)、`getInitialMaintenanceDuration`(L211) 全仓库无外部调用方（grep 仅命中定义）。
- impact: 死代码；且 SheriffVoteBroadcaster 整体仅供 S7-001 死代码使用，随之一并成为死类。
- direction: 随 S7-001 一并清理；未用 getter 删除。

### S7-007 [S2][耦合] BlackoutMode 静态可变状态 lastWinningFaction + 上帝类倾向
- file: game/blackout/BlackoutMode.java
- line: 47,41-45
- evidence: `private static volatile BlackoutRoleManager.Faction lastWinningFaction`(L47) 为跨实例静态可变状态，由实例方法 `setLastWinningFaction` 写、静态 `getLastWinningFaction()` 读（SREBlackoutGameMode 读）。BlackoutMode 同时持有 syncManager/victoryChecker/sheriffResolver/tickCoordinator 四个协调器字段并直接编排它们的 onPreStart/onEnd/onCleanup（L75-175），属编排型上帝类。
- impact: 静态可变状态在多 level/多对局并发时存在被覆盖风险（虽单服通常单对局，但 lastWinningFaction 作为 static 共享是隐患）；协调器编排逻辑集中导致 BlackoutMode 职责过载。
- direction: 评估 lastWinningFaction 是否应随对局状态对象化而非 static；将生命周期编排拆出独立 controller。

### S7-008 [S2][耦合] 三处重复的 street_phone/horn 方块静态缓存
- file: game/blackout/BlackoutPhoneHandler.java, game/blackout/BlackoutHornVoteHandler.java, game/blackout/BlackoutOverlayTypes.java
- line: BlackoutPhoneHandler.java:24-31；BlackoutHornVoteHandler.java:37-44；BlackoutOverlayTypes.java:17-26
- evidence: `BlackoutPhoneHandler.cachedStreetPhone` 与 `BlackoutOverlayTypes.cachedStreetPhone` 是同一 `yuushya:street_phone` 方块的两份独立静态缓存，逻辑相同（null/AIR 时重新查 BuiltInRegistries）。`BlackoutHornVoteHandler.cachedHorn` 同模式缓存 `trainmurdermystery:horn`。
- impact: 三份独立静态可变缓存，同一方块 ID 多处缓存易出现不一致/失效认知；命名空间硬编码散落多处。
- direction: 统一到 BlackoutOverlayTypes 或一个 BlockCache 工具，避免重复静态缓存。

### S7-009 [S3][标识] BlackoutTimerSystem 命名语义混淆：transientBlackoutTicks 实为“秒”却命名为 ticks
- file: game/blackout/BlackoutTimerSystem.java
- line: 23,44,80-84,134
- evidence: `TRANSIENT_TICKS=140`(L23) 与 `transientBlackoutTicks`(L44) 命名为“ticks”，但唯一递减点在 `tickSecond`(L80 `s.transientBlackoutTicks--`)，该方法每秒调用一次，故实际持续 140 秒而非 140 tick。注释/广播均称“短暂停电”(L135)。
- impact: 字段名暗示 tick 单位会误导维护者按 20/tick 推算时长；魔法数字 140 无注释说明其单位语义。
- direction: 重命名为 transientBlackoutSeconds 或在常量上加单位注释。

### S7-010 [S3][死逻辑/标识] BlackoutShopCatalog *_KEY 常量仅在本类 record 构造内引用
- file: game/blackout/BlackoutShopCatalog.java
- line: 8,13,38,52,67,82,97
- evidence: `REVOLVER_KEY/HANDCUFFS_KEY/KILLER_REVOLVER_KEY/ACID_BUCKET_KEY/KNIFE_KEY/LOCKPICK_KEY/PSYCHO_MODE_KEY` 各 `public static final String` 仅在同文件 record 构造（L19,29,43,...）中被引用，外部全用 `.key()` record 访问器（BlackoutShopService 通过 `BlackoutShopCatalog.REVOLVER.key()`）。
- impact: 暴露的 KEY 常量无外部消费者，公共表面冗余；中英混用（KEY 常量英文、NAME 中文）。
- direction: KEY 常量降为 private 或直接内联到 record 构造。

### S7-011 [S3][性能] BlackoutVictoryChecker.forceAssignRestorePowerToAllGood 对每名玩家重复 getPlayerList.getPlayer
- file: game/blackout/BlackoutVictoryChecker.java
- line: 122-166
- evidence: 循环内对每个 uuid 两次调用 `level.getServer().getPlayerList().getPlayer(uuid)`（L129 与 L145）。
- impact: 触发于停电阶段转换（非每 tick 热路径），玩家数 N 时多 N 次 O(1) 查找，影响轻微。
- direction: 复用同一 ServerPlayer 引用。

## 维度小结
- 性能：无热路径确定性劣化；S7-011 为非热路径轻微冗余。
- 死逻辑：S7-001/002（警长投票整套死链，最严重）、S7-003/004/005/006。
- 标识：S7-009（ticks 误名）、S7-010（冗余常量+中英混用）。
- 耦合：S7-007（static 可变状态+上帝类编排）、S7-008（三份重复方块缓存）。
- blackout 包未直接 import sre 子包具体类，无循环引用。