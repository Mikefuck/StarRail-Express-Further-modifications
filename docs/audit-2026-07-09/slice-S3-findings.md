# S3 切片审计留档 — betel 子系统

审计日期: 2026-07-09
审计员: 独立审计 agent（从零读源码，未参考仓库内任何既有报告/计划）
切片范围: `com.habitrain.core.betel` 全部 6 个文件

## 文件覆盖确认表

| 文件 | 已读 | 说明 |
|---|---|---|
| betel/BetelFoodRestriction.java | 是 | 47 行，全读 |
| betel/BetelLeafHandler.java | 是 | 270 行，全读 |
| betel/BetelQuestDefinition.java | 是 | 49 行，全读 |
| betel/BetelQuestState.java | 是 | 124 行，全读 |
| betel/BetelTickEngine.java | 是 | 341 行，全读 |
| betel/BetelWithdrawal.java | 是 | 31 行，全读 |

为确认调用关系，额外读了：ModTickHandler.java、game/blackout/task/BlackoutBetelQuestTask.java、misc/EffectOwnershipTracker.java、HabiTrainCore.java（相关行），并 grep 了 lastKnownLastEatTime / clearHechengTianxiaData / setFoodRestriction / hasActiveHarvest / isGameActive 等符号在全模块的引用情况。

## findings

### S3-001 — tickPlayer 每热路径重复 registry/component 查找与对象分配（性能 S1）

- file: betel/BetelTickEngine.java
- line: 33-199 (tickPlayer 整体), 78, 175-177, 250-270
- dimension: 性能
- severity: S1
- title: tickPlayer 每 tick 对每玩家重复同步查找 registry/组件，且 isGameActive 每世界每 tick 也查组件
- evidence:
  - `tickPlayer` 由 `ModTickHandler.tickMoreMods` 在 `hasActiveGame` 为真时对 `server.getPlayerList().getPlayers()` 每名玩家每 tick 调用一次（ModTickHandler.java:48-49）。
  - 每次调用都做：`SREGameWorldComponent.KEY.get(player.level())`（line 45）、`BetelNutEntityComponents.ADDICTION.get(player)`（line 73）、`BetelNutConfig.get()`（line 78）、`addiction.getAddictionStage()`/`getWithdrawalSeverity()`/`getWithdrawalValue()`/`getLastEatTime()` 多次（line 81/118-119/175-177）、`player.level().getGameTime()` 多次（line 94/137）。
  - 检测到吃槟榔时还会 `BuiltInRegistries.ITEM.get(...)`（giveBetelNutResidue line 274）与 `BuiltInRegistries.MOB_EFFECT.getHolder(...)`（applyNoellesrolesEffect line 262）—— registry 查找本可缓存。
  - `executeReveal`（line 299）每次揭晓 `new Random()`，而非复用静态 Random / ThreadLocalRandom。
  - `isGameActive`（line 201-210）对每个 `server.getAllLevels()` 每 tick 调用一次（ModTickHandler.java:30），每次都 `SREGameWorldComponent.KEY.get(world)` 同步组件查找，且其 catch 块为空（见 S3-004）。
- impact: 对局活跃时每 tick × 每玩家 × 多次 registry/组件查找，玩家数量大时为可量化的服务器 tick 开销；`new Random()` 在揭晓路径每揭晓一次分配一次对象（低频，但属不必要分配）。
- direction: 组件引用在 tick 内取一次复用；registry 常量（noellesroles 效果 holder、residue item）启动期缓存为字段；isGameActive 复用已查出的 gameWorld 而非每世界独立再查；Random 用 ThreadLocalRandom 或静态实例。

### S3-002 — BetelLeafHandler activeHarvests 在同一 tick 被两条路径双重遍历（性能 S2）

- file: betel/BetelLeafHandler.java
- line: 47-53, 58-74, 119-185
- dimension: 性能
- severity: S2
- title: applyHarvestSlowness 与 tickHarvests 都每 tick 全量遍历 activeHarvests，存在 O(世界×任务) 冗余
- evidence:
  - `BetelLeafHandler.register()` 注册了 `ServerTickEvents.END_SERVER_TICK` 回调 `applyHarvestSlowness`（line 47-52），其内 `for (ServerLevel world : server.getAllLevels()) applyHarvestSlowness(world)`（line 49），而 `applyHarvestSlowness` 又 `for (HarvestTask task : activeHarvests.values())`（line 60）—— 即每 tick 遍历「所有世界 × 所有任务」。
  - 同时 `ModTickHandler.tickMoreMods` 又对每个 `server.getAllLevels()` 调用 `BetelLeafHandler.tickHarvests(world)`（ModTickHandler.java:29），`tickHarvests` 内部也 `Iterator` 遍历整个 `activeHarvests`（line 123-185），按 `task.worldKey.equals(world.dimension())` 过滤（line 131）。
  - 两条路径在同一 END_SERVER_TICK 内都对全部任务做完整遍历再过滤世界。
- impact: activeHarvests 非空时，每 tick 对同一 map 做两次全量遍历（一次跨世界外层 + 全任务内层，一次每世界 + 全任务），任务数与世界数耦合放大；正常游玩下活跃采集任务通常很少，影响有限，但属结构性冗余。
- direction: 把 activeHarvests 按世界维度分桶，或合并两条遍历为一次；统一由 ModTickHandler 驱动，移除 BetelLeafHandler 自身注册的 END_SERVER_TICK 回调，避免双注册。

### S3-003 — 死方法 clearHechengTianxiaData（死逻辑 S2）

- file: betel/BetelTickEngine.java
- line: 212-219
- dimension: 死逻辑
- severity: S2
- title: clearHechengTianxiaData 公共方法全模块零调用方
- evidence: `grep clearHechengTianxiaData` 仅命中其自身定义（line 212）与内部 `addiction.clearHechengTianxiaData(player)`（line 215，那是外部 betel.nut 组件的同名方法，非本方法递归）。本 `BetelTickEngine.clearHechengTianxiaData` 在整个 `src/main/java` 内无任何调用方。
- impact: 公共 API 形同死代码，意图「清除合成天下槟榔数据」的功能在主流程中无触发点；维护者可能误以为存在清理入口。
- direction: 确认是否应有调用方（游戏结束/重置链路），若需要则在对应生命周期接入，否则删除。

### S3-004 — isGameActive 与 clearAddictionForPlayer 中的空 catch 吞异常（死逻辑 S2）

- file: betel/BetelTickEngine.java
- line: 201-210, 244-245
- dimension: 死逻辑
- severity: S2
- title: 两处空 catch 静默吞掉组件异常，无日志无降级标记
- evidence:
  - `isGameActive`（line 201-210）：`try { Object gameComponent = SREGameWorldComponent.KEY.get(world); ... } catch (Exception e) {}`，catch 体完全空，直接 return false。
  - `clearAddictionForPlayer`（line 234-245）：`try { BetelNutAddictionComponent addiction = BetelNutEntityComponents.ADDICTION.get(player); addiction.clearAddiction(player); } catch (Exception e) {}`，catch 体空。
- impact: SRE/betel-nut 组件在异常态（mod 缺失、组件未注入、NPE）时，isGameActive 静默返回 false 导致整条 tick 流程提前短路（ModTickHandler 据 hasActiveGame 决定是否 tickPlayer），但日志层无任何痕迹，排障困难；clearAddictionForPlayer 失败时玩家成瘾状态未清除却无日志，跨局状态可能残留。
- direction: 至少 catch 内加 warn/error 日志（参考 line 75 的做法）；isGameActive 失败的语义需明确（是视为非活跃还是兜底活跃）。

### S3-005 — 多个公共 API 方法零调用方（死逻辑 S2）

- file: betel/BetelQuestState.java, betel/BetelLeafHandler.java
- line: BetelQuestState.java:51-53, BetelLeafHandler.java:187-189/194-196/201-204
- dimension: 死逻辑
- severity: S2
- title: setFoodRestriction / hasActiveHarvest / hasActiveHarvestInWorld / clearAllHarvests（部分）零或近零调用
- evidence:
  - `BetelQuestState.setFoodRestriction`（line 51-53）：grep 全模块仅命中定义本身；实际设置食物限制是 `BetelTickEngine` 直接写 `data.hasFoodRestriction = true`（BetelTickEngine.java:158），从不走此 setter。
  - `BetelLeafHandler.hasActiveHarvest`（line 194-196）与 `hasActiveHarvestInWorld`（line 201-204）：grep 全模块仅命中定义本身，零调用。
  - `clearAllHarvests`（line 187-189）：仅 GameLifecycleHandler.java:93 调用一处，属于有效；不并入本条。
- impact: 公共方法对外暴露但内部未使用，API 表面大于实际行为；`setFoodRestriction` 与字段直写并存，破坏了「状态经封装方法修改」的约定，维护者难判断哪条路径为权威。
- direction: 删除未用查询方法或接入调用方；食物限制状态统一走 setter，移除 BetelTickEngine 中对 `data.hasFoodRestriction` 的直写。

### S3-006 — lastKnownLastEatTime 字段只写不读（死逻辑 S3）

- file: betel/BetelQuestState.java, betel/BetelTickEngine.java
- line: BetelQuestState.java:114, BetelTickEngine.java:171-173
- dimension: 死逻辑
- severity: S3
- title: PlayerBetelData.lastKnownLastEatTime 写入后从未被读取
- evidence: `BetelTickEngine.tickPlayer` line 171-173 `if (currentLastEatTime > 0 && currentLastEatTime != data.lastKnownLastEatTime) { data.lastKnownLastEatTime = currentLastEatTime; }` 仅赋值；grep `lastKnownLastEatTime` 全模块无任何读取点。其语义与已被读取的 `lastDetectedEatTime`（line 83-89）高度重叠。
- impact: 字段无效果，徒增 PlayerBetelData 状态密度与认知负担，疑似历史逻辑残留。
- direction: 删除该字段及其赋值分支，或确认其设计意图后接入读取。

### S3-007 — BetelQuestState 单例懒加载非线程安全（耦合 S2）

- file: betel/BetelQuestState.java
- line: 23-28, 11-21
- dimension: 耦合
- severity: S2
- title: getInstance 双重检查缺失，静态可变单例在并发下可重复构造
- evidence: `private static BetelQuestState instance;`（line 11）；`init()` 直接 `instance = new BetelQuestState()`（line 20）无同步；`getInstance()`（line 23-28）`if (instance == null) instance = new BetelQuestState();` 无 volatile / 无同步块。`playerData` 为普通 HashMap（line 15），`computeIfAbsent` 在并发下不安全。
- impact: Fabric 1.21.1 服务器主 tick 为单线程，常态下安全；但 FabricLoader.getInstance() 调用路径（getCurrentServer line 76-84）、可能的 mod 初始化线程或并行流场景下，单例与 HashMap 的非同步访问存在数据竞争风险，可能导致 PlayerBetelData 丢失或重复构建。
- direction: 单例用 holder/双重检查锁 + volatile；或保证 init() 在服务器启动前确定性执行后getInstance 直接返回（当前 HabiTrainCore 已在初始化期调用 init()，可考虑移除 getInstance 内的懒加载兜底以消除歧义）。playerData 若有并发访问需换 ConcurrentHashMap。

### S3-008 — PlayerBetelData 状态字段密度高且布尔语义重叠（标识 S2）

- file: betel/BetelQuestState.java
- line: 108-123
- dimension: 标识
- severity: S2
- title: PlayerBetelData 含 14 个状态字段，多个布尔跟踪重叠的生命周期/触发态
- evidence: `PlayerBetelData`（line 108-123）字段：`hasBetelQuestBeenAssigned`、`lastDiagnosticStage`、`hasBeenProcessed`、`wasGameNotRunning`、`wasSpectating`、`lastKnownLastEatTime`、`lastDetectedEatTime`、`hasEatenBetelNut`、`betelNutsEatenThisGame`、`ownLastEatGameTime`、`darknessAppliedThisTrigger`、`hasHeavyAddiction`、`ateBetelNutToRelieve`、`hasFoodRestriction`。其中 `hasEatenBetelNut`/`ateBetelNutToRelieve`/`hasHeavyAddiction`/`darknessAppliedThisTrigger` 均为布尔状态标志且语义部分重叠（都围绕「吃槟榔—成瘾—戒断—效果已施加」），无注释说明各自边界；`hasBeenProcessed`/`wasGameNotRunning`/`wasSpectating` 三者都是「上一 tick 边界状态」的快照标志。
- impact: 14 字段密度高，布尔命名相近语义重叠，新人难判断哪个为权威；`ateBetelNutToRelieve` 在 tickPlayer 中被反复置位/清零（line 92/166/196）跨越两条路径，易引入状态机 bug。
- direction: 将重叠布尔收敛为显式状态枚举（如成瘾阶段/效果施加状态机），并补字段级注释说明每个标志的写入点与清零点。

### S3-009 — 成瘾/戒断阈值与时长全为魔法数字（标识 S3）

- file: betel/BetelTickEngine.java, betel/BetelLeafHandler.java
- line: BetelTickEngine.java:126-138/157/178/182/265, BetelLeafHandler.java:35/95/159/70/107
- dimension: 标识
- severity: S3
- title: 阈值 80/60/40/20、戒断 600、食物限制 Stage3、数量上限 5、效果时长 100/200/600 等均硬编码
- evidence:
  - BetelTickEngine.java:126-132 `ownValue >= 80/60/40/20` 阶梯；line 138 `>= 600`；line 157 `>= 3`；line 178 `Math.max(1, Math.min(25, config.maxWithdrawalValue))`（25 为魔法上限）；line 182 `new MobEffectInstance(DARKNESS, 600, 0...)`；line 265 `200`。
  - BetelLeafHandler.java:35 `HARVEST_TICKS = 60`；line 95/159 `>= 5`（槟榔数量上限，重复出现未提常量）；line 70/107 `HARVEST_TICKS + 10`（缓冲魔法数 10）。
  - BetelWithdrawal.java:15/17 `100`。
- impact: 阈值散落各处无单一来源，平衡调整需多处改动且易漏改；`config.enableAddictionSystem` 开启时本应依赖 config，但自有追踪分支仍用硬编码阶梯，两套阈值并存语义不清。
- direction: 集中为命名常量或 BetelNutConfig 字段；尤其成瘾阶段阈值与戒断 tick 阈值应来自配置以保证两套追踪一致。

### S3-010 — betel 被 blackout.task 单向依赖且 BetelQuestState 暴露为公共可变单例（耦合 S2）

- file: betel/BetelQuestState.java, game/blackout/task/BlackoutBetelQuestTask.java
- line: BlackoutBetelQuestTask.java:4/28-32, BetelQuestState.java:11/23/95
- dimension: 耦合
- severity: S2
- title: blackout 任务子系统直接依赖 betel 内部状态单例，betel 状态被外部包可写
- evidence: `BlackoutBetelQuestTask`（game.blackout.task 包）import `com.habitrain.core.betel.BetelQuestState`（line 4）并调用 `markQuestAssigned`/`resetEatenStatus`/`hasPlayerEatenBetelNut`（line 28-32），即 blackout 子系统单向依赖 betel 子系统。`BetelQuestState` 为公共可变单例（line 11/23），`getInstance()` 可被任意包获取并 `setRevealUsed(true)`/`resetAll()`（line 104-106/95），且 `PlayerBetelData` 字段包级可见（无 private），外部同包虽不可见但通过 getter/setter 暴露。
- impact: betel 与 blackout 形成跨包耦合，且 betel 的对局状态对外完全可写，任意外部模块可调用 resetAll()/setRevealUsed 影响对局；blackout 任务复用 betel 状态机也意味着两子系统状态生命周期需同步重置，GameLifecycleHandler.resetAll（task/GameLifecycleHandler.java:92）承担此责任，一旦遗漏即跨局污染。
- direction: 收敛 BetelQuestState 公共表面（resetAll/setRevealUsed 不应对外开放）；考虑由 betel 提供显式「任务复用」接口而非让 blackout 直接操作内部状态字段。

### S3-011 — getCurrentServer 中局部变量遮蔽静态字段 instance（标识 S3）

- file: betel/BetelQuestState.java
- line: 76-84
- dimension: 标识
- severity: S3
- title: 局部 `var instance` 与静态字段 `instance` 同名遮蔽，易误读
- evidence: line 78 `var instance = net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance();` 局部变量名为 `instance`，与类静态字段 `private static BetelQuestState instance;`（line 11）同名。虽作用域不冲突，但同名降低可读性。
- impact: 仅可读性问题，无功能 bug。
- direction: 局部变量改名为 `gameInstance`/`rawInstance` 以避免与单例字段同名。

### S3-012 — BetelLeafHandler 块查找的 blockChecked/betelLeafBlock 非同步且无失效机制（耦合 S3）

- file: betel/BetelLeafHandler.java
- line: 40-41, 206-221
- dimension: 耦合
- severity: S3
- title: 静态可变 betelLeafBlock/blockChecked 缓存无同步、无重载机制
- evidence: `private static Block betelLeafBlock = null;` 与 `private static boolean blockChecked = false;`（line 40-41）在 `isBetelLeafBlock` 内懒加载并置 `blockChecked = true`（line 218）后永久缓存。查找依赖 `BuiltInRegistries.BLOCK.get`（line 209），若首次查找时 betel-nut-mod 尚未完成注册（加载顺序问题），会永久判定为「方块未找到」且永不重试。
- impact: mod 加载顺序敏感：若首次 isBetelLeafBlock 调用发生在 betel-nut-mod 注册完成前，槟榔叶采集将永久失效且无自愈。非线程安全字段在并行场景下也存在可见性问题。
- direction: 延后首次查找时机（如改为服务器启动后惰性 + 可重试），或显式在合适生命周期事件中预热查找并校验非 AIR。