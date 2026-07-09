# S6 切片审计发现 (game.sre + sre.mixin)

审计日期: 2026-07-09
独立审计，仅基于源码事实，未参考仓库内任何既有审计/计划/报告。

## 文件覆盖确认表

| # | 文件 (相对 src/main/java) | 已读 |
|---|--------------------------|------|
| 1 | com/habitrain/core/game/AbstractGameMode.java | 是 |
| 2 | com/habitrain/core/game/sre/SREGameModeBase.java | 是 |
| 3 | com/habitrain/core/game/sre/SREMurderMode.java | 是 |
| 4 | com/habitrain/core/game/sre/SRERepairMode.java | 是 |
| 5 | com/habitrain/core/game/sre/SRETrainTaskWrapper.java | 是 |
| 6 | com/habitrain/core/game/sre/TaskEnumHelper.java | 是 |
| 7 | com/habitrain/core/game/sre/FactionFilter.java | 是 |
| 8 | com/habitrain/core/game/sre/TaskWeightCurves.java | 是 |
| 9 | com/habitrain/core/game/sre/PerPlayerTaskTicker.java | 是 |
| 10 | com/habitrain/core/game/sre/CustomTaskBlockCache.java | 是 |
| 11 | com/habitrain/core/game/sre/SREWeatherController.java | 是 |
| 12 | com/habitrain/core/game/sre/mixin/SREPlayerTaskComponentMixin.java | 是 |
| 13 | com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java | 是 |
| 14 | com/habitrain/core/game/sre/mixin/MinigameTaskAssignmentMixin.java | 是 |
| 15 | com/habitrain/core/game/sre/mixin/BlackoutEatMixin.java | 是 |
| 16 | com/habitrain/core/game/sre/mixin/BlackoutShopMixin.java | 是 |
| 17 | com/habitrain/core/game/sre/mixin/BlackoutCanEatMixin.java | 是 |
| 18 | com/habitrain/core/game/sre/mixin/BlackoutDrinkItemMixin.java | 是 |
| 19 | com/habitrain/core/game/sre/mixin/ExtraEffectRoleMixin.java | 是 |
| 20 | com/habitrain/core/game/sre/mixin/NunchuckCooldownMixin.java | 是 |
| 21 | com/habitrain/core/game/sre/mixin/RoleMethodDispatcherMixin.java | 是 |
| 22 | com/habitrain/core/game/sre/mixin/MapScannerMixin.java | 是 |
| 23 | com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java | 是 |

---

## 发现清单

### S6-001 — SREPlayerTaskComponentMixin: 每 tick 每玩家 new PerPlayerTaskTicker 热路径分配
- 文件: com/habitrain/core/game/sre/mixin/SREPlayerTaskComponentMixin.java
- 行: 127
- 维度: 性能
- 严重度: S1
- evidence: `habitrain$onServerTick` 注入 `SREPlayerTaskComponent.serverTick` HEAD，末尾 `new PerPlayerTaskTicker(player).tick();`。serverTick 由 SRE 每玩家组件每 tick (20 TPS) 调用。
- impact: N 个玩家对局期间每秒产生 20*N 个一次性 PerPlayerTaskTicker 对象（含其字段与 Logger 引用），加重 young-gen GC 压力；高人数长对局下可量化。
- direction: 让 ticker 成为按玩家复用的实例（字段缓存/WeakHashMap by player），或将其逻辑内联为 static 方法接收 player 参数，避免每 tick 分配。

### S6-002 — TaskWeightCurves.shouldIncludeOriginalTasks: 每次任务生成都重建全量 Set
- 文件: com/habitrain/core/game/sre/TaskWeightCurves.java
- 行: 77-96
- 维度: 性能
- 严重度: S1
- evidence: 该方法在 GenerateTaskMixin.addOriginalTasks (L136) 调用，处于 generateTaskInternal 热路径。L86 先 `new ArrayList<>(TaskRegistry.getAll())` 拷贝整个注册表，再 `.stream().map(...).collect(Collectors.toSet())` 构建一个 Set，最后 L90-94 遍历 builtinSreTaskIds 调 `contains`。
- impact: 每个玩家每次任务刷新都做一次全注册表拷贝 + 流式 Set 构建（O(注册表大小)），多玩家高频刷新时累加；且 Set 只为做 `contains` 查询，存在更轻量手段。
- direction: activeMode.filterAvailableTasks 的结果可直接遍历 builtinSreTaskIds 求交集，或缓存 activeMode→允许集；避免每次重建完整 Set。

### S6-003 — SREGameModeBase 静态可变状态网 / 单例扩散
- 文件: com/habitrain/core/game/sre/SREGameModeBase.java
- 行: 32-43
- 维度: 耦合
- 严重度: S2
- evidence: 类内含 5 个 static 可变字段：`LOBBY_GROUP`(L32)、`pendingVoiceJoins`(L34)、`pendingGameEndGroupJoin`(L38)、`builtinTasksRegistered`(L41)、`sreEventsRegistered`(L43)；并对外暴露 static 方法 queueLobbyGroupJoin/processPendingVoiceJoins/processGameEndGroupJoin/removePendingVoiceJoin/isAnySreGameRunning 等，构成跨实例全局状态网。
- impact: 所有 SREGameModeBase 子类共享同一份全局状态，多服务器实例或多对局并发时状态串扰风险；生命周期与类绑定而非与对局绑定，难以测试/重置。
- direction: 将语音群组/事件注册状态收敛到一个显式单例服务对象（注入式），按 MinecraftServer 生命周期管理，而非散布于类静态字段。

### S6-004 — SRETrainTaskWrapper.getType 在 CUSTOM 不支持时回退 SLEEP，与原版任务槽冲突
- 文件: com/habitrain/core/game/sre/SRETrainTaskWrapper.java
- 行: 51-57
- 维度: 死逻辑
- 严重度: S2
- evidence: getType() 在 typeOverride==null 且 TaskEnumHelper.getCustom()==null 时返回 `SREPlayerTaskComponent.Task.SLEEP`。注释 L22-25 说明 typeOverride 仅为杀手假任务避免与 CUSTOM 冲突而设；正常 DLC 任务走无 override 构造，在旧版 SRE 上会落到 SLEEP 槽。
- impact: 在不支持 CUSTOM 的 SRE 版本上，DLC 任务被映射到原版 SLEEP 枚举槽，可能覆盖/与玩家正常睡觉任务混淆，破坏任务槽位语义。
- direction: 不支持 CUSTOM 时应让 getType 表达"无可用槽"而非默认 SLEEP；或要求上层保证仅在 isCustomTaskSupported 时包装 DLC 任务。

### S6-005 — SRETrainTaskWrapper.toNbt() 为死代码
- 文件: com/habitrain/core/game/sre/SRETrainTaskWrapper.java
- 行: 59-64
- 维度: 死逻辑
- 严重度: S2
- evidence: 全仓 grep `toNbt()` 仅命中 TaskInstance.toNbt 与 wrapper 自身定义；无任何外部调用 SRETrainTaskWrapper.toNbt()。
- impact: 永不调用方法，维护负担与误读风险（读者以为 NBT 序列化经此路径）。
- direction: 确认无外部使用后删除，或明确其调用方并在注释中标注。

### S6-006 — TaskEnumHelper.isCustomTaskSupported 为死代码
- 文件: com/habitrain/core/game/sre/TaskEnumHelper.java
- 行: 30-32
- 维度: 死逻辑
- 严重度: S2
- evidence: 全仓 grep `isCustomTaskSupported` 仅命中其定义处，无调用点。
- impact: 公共静态方法暴露但无人使用，API 表面积虚增。
- direction: 删除或纳入降级判定路径（如 S6-004 的回退逻辑可改用此方法）。

### S6-007 — MinigameRewardMixin: 捕获字段与 HEAD 注入为死代码
- 文件: com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java
- 行: 22-23, 39-50
- 维度: 死逻辑
- 严重度: S2
- evidence: 字段 `habitrain$capturedMinigameId`/`habitrain$capturedPlayer` 仅在 L48-49 被赋值，全类无读取；RETURN 注入 `applyHabiRewards` (L57) 使用方法形参 `player`/`minigameId` 而非捕获字段。
- impact: HEAD 捕获注入与其字段无作用，徒增每完成小游戏一次的字段写与注入开销，且误导维护者以为存在跨注入状态传递。
- direction: 删除捕获字段与 HEAD 注入，仅保留 RETURN 注入。

### S6-008 — MinigameRewardMixin.habiTrain$overrideTokenReward 为空透传 ModifyArg
- 文件: com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java
- 行: 25-37
- 维度: 死逻辑
- 严重度: S2
- evidence: @ModifyArg 方法体 `return originalReward;` 直接回传原值，未做任何修改。注释 L36 说明"默认不替换"。
- impact: 一个无副作用的 ModifyArg 注入仍参与 mixin 编织，属冗余注入痕迹（疑似历史 fix 残留——见近期 commit "stop zeroing minigame token rewards by default"，原本归零逻辑已被移除但注入骨架留下）。
- direction: 若确无替换需求则移除该 ModifyArg 注入；若保留为扩展点则加注释明确意图。

### S6-009 — FactionFilter: currentIsFakeTask 冗余赋值与 isParallelCall 语义错位
- 文件: com/habitrain/core/game/sre/FactionFilter.java
- 行: 27, 30, 45
- 维度: 标识
- 严重度: S2
- evidence: L27 `boolean isParallelCall = hasActiveTasks;` — 变量名"并行调用"但取值是"玩家已有活跃任务"。L30 `currentIsFakeTask = false;` 初值，L45 else 分支再次 `currentIsFakeTask = false;`（与初值相同，冗余）。
- impact: 命名与真实语义不符（isParallelCall 实际含义是"本次是否为并行/假任务派发场景"由 tasks 非空推导），易误读；冗余赋值掩盖分支意图。
- direction: 重命名 isParallelCall 以反映其推导来源（如 hasExistingTask），或修正其语义；移除 L45 的冗余赋值。

### S6-010 — SREPlayerTaskComponentMixin / GenerateTaskMixin: 强 @Shadow 耦合 SRE DLC 内部字段
- 文件: com/habitrain/core/game/sre/mixin/SREPlayerTaskComponentMixin.java, com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java
- 行: SREPlayerTaskComponentMixin 29-44; GenerateTaskMixin 63-82
- 维度: 耦合
- 严重度: S2
- evidence: SREPlayerTaskComponentMixin @Shadow 字段/方法：`player`(L30)、`parallelTaskGenerated`(L33)、`playerMoodComponent`(L36)、`tasks`(L39)、`generateParallelTask()`(L42)。GenerateTaskMixin @Shadow：`player`(L63)、`tasks`(L64)、`timesGotten`(L65)、`playerMoodComponent`(L66)、`getDisabledTasks()`(L69)、`getEnabledSceneTasks()`(L74)、`createTaskInstance()`(L80)。
- impact: API mod 对外部 DLC (StarRailExpress) 的私有/包级内部实现深度依赖；DLC 重命名/改签名/改字段可见性任一项即破坏本 mod，升级面脆弱。
- direction: 评估可经 SRE 公共 API 替代的 @Shadow，收敛到接口契约；对必须 Shadow 的内部字段集中到一处并标注版本兼容矩阵。

### S6-011 — SREGameModeBase: 重复 Javadoc 注释块
- 文件: com/habitrain/core/game/sre/SREGameModeBase.java
- 行: 158-165
- 维度: 死逻辑
- 严重度: S3
- evidence: L158-161 与 L162-165 是两段几乎相同的 `/** 检查服务器上当前是否有任何 SRE 对局正在运行 ... */` Javadoc，连续出现在 isAnySreGameRunning 之前。
- impact: 注释痕迹冗余，易让维护者误以为有两个方法。
- direction: 删除重复的一段注释。

### S6-012 — SREGameModeBase.isAnySreGameRunning / isAnySreGameStartingOrRunning 吞异常
- 文件: com/hibitrain/core/game/sre/SREGameModeBase.java → 实际 com/habitrain/core/game/sre/SREGameModeBase.java
- 行: 168-176, 182-196
- 维度: 死逻辑
- 严重度: S3
- evidence: 两方法对 `SREGameWorldComponent.KEY.get(level)` 包裹 `try{...}catch(Exception ignored){}`，异常被静默吞掉（L173, L193 `catch (Exception ignored) {}`）。
- impact: 若 DLC 组件访问异常，方法静默返回 false，可能让本应排队/不排队的玩家误判，且无任何日志便于排查。
- direction: 至少 debug 级日志记录异常，或明确注释为何吞异常。

### S6-013 — MapScannerMixin: eat/drink typeId 二次全量遍历注册表
- 文件: com/habitrain/core/game/sre/mixin/MapScannerMixin.java
- 行: 64-91 与 119-122
- 维度: 性能
- 严重度: S3
- evidence: L64 已遍历 `TaskRegistry.getAll()` 构建 blockToTypeIds；L119-122 再次遍历同一注册表仅为取 blackout_eat/blackout_drink 的 blockTypeId。
- impact: 扫描路径（非每 tick，但每次地图扫描/加载）做两次全注册表遍历；可合并到首循环，减少常数开销。
- direction: 在首循环 (L64-91) 同时记录 eat/drink typeId，删除 L119-122 的第二次遍历。

### S6-014 — SREWeatherController.tick: 静态可变状态 + 降雨参数魔法数字
- 文件: com/habitrain/core/game/sre/SREWeatherController.java
- 行: 16-22, 32-74
- 维度: 标识 / 耦合
- 严重度: S3
- evidence: `forcedRainByLowPlayers`(L16)、`tickCounter`(L17) 静态可变；魔法数字 `CHECK_INTERVAL=20`(L19)、`MIN_PLAYERS=8`(L20)、`RAIN_DURATION_TICKS=20*60*10`(L21)、`CLEAR_DURATION_TICKS=20*60`(L22) 硬编码无配置入口。
- impact: 阈值/时长不可配置，调参需改代码重编译；静态状态使多世界/多对局场景难以独立控制降雨。
- direction: 将阈值与时长抽为可配置项；状态按 level 维度而非全局静态管理。

### S6-015 — BlackoutEatMixin / BlackoutDrinkItemMixin: 任务 ID 字符串硬编码重复
- 文件: com/habitrain/core/game/sre/mixin/BlackoutEatMixin.java, BlackoutDrinkItemMixin.java
- 行: BlackoutEatMixin 32,39; BlackoutDrinkItemMixin 47
- 维度: 标识
- 严重度: S3
- evidence: `"habitrain_core:blackout_eat"`、`"habitrain_core:blackout_drink"` 在 BlackoutEatMixin L32/L39 与 BlackoutDrinkItemMixin L47 重复硬编码字符串字面量，未与 GenerateTaskMixin.BLACKOUT_DAILY_TASK_IDS / TaskWeightCurves 常量复用。
- impact: 任务 ID 字符串散落多处，改名需多处同步修改，易遗漏。
- direction: 提取为共享常量（如 TaskWeightCurves 已有 BLACKOUT_SUPPLY/DAILY 常量模式），统一引用。