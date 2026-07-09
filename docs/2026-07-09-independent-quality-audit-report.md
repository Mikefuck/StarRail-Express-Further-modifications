# 哈比列车 API 独立全量代码审计报告

## 1. 元信息
- 审计日期：2026-07-09
- 审计对象：Minecraft Fabric mod「哈比列车api」（mod id `habitrain_core`，1.21.1 / Java 21）
- 审计范围：`src/main/java/com/habitrain/core/` 全量 159 个 Java 文件（~18.7k 行）
- 基线 commit：`9f92434`
- 独立性声明：本审计为全新独立第二意见。执行 agent 全程仅基于源代码事实判断，**未参考**仓库内任何既有审计/计划/报告（docs/superpowers/ 下一切）。每个 agent 从零读源码。
- 禁止访问：全程未访问 `D:\Backup\mc mod\backup\`。
- 方法：MECE 正交切片（11 个，每文件恰好归一片）+ 2 专项兜底（A1 耦合/架构、A2 死逻辑交叉验证）。
- 性质：本报告只发现并报告问题，不修复。

## 2. 执行摘要
- 切片 agent 返回数：11 / 11
- 专项 agent 返回数：3 / 3
- 发现总数：150
- S0 阻断：0
- S1 严重：20
- S2 中等：80
- S3 低：50

### Top 风险（S0 + 高优 S1）
1. [S1][死逻辑] S1-007 com/habitrain/core/BuiltinTaskRegistrar.java:194-233 — look_my_eyes onTick 每 tick AABB+getEntitiesOfClass 实体扫描与对象分配
2. [S1][死逻辑] S1-001 com/habitrain/core/ModTickHandler.java:26-35, 44 — anyGameActive 与 hasActiveGame 双布尔始终同值，冗余变量
3. [S1][死逻辑] S1-002 com/habitrain/core/api/GameModeLifecycle.java:7-17 — GameModeLifecycle 枚举全仓无任何引用，死类型
4. [S1][死逻辑] S2-001 task/TaskManager.java:51 — dlcTaskCounts 跨局不清理，计数单调累积破坏分配平衡
5. [S1][性能] S3-001 betel/BetelTickEngine.java:33-199 — tickPlayer 每 tick 对每玩家重复同步查找 registry/组件，isGameActive 每世界每 tick 也查组件，且揭晓路径 new Random()
6. [S1][性能] S6-001 com/habitrain/core/game/sre/mixin/SREPlayerTaskComponentMixin.java:127 — 每 tick 每玩家 new PerPlayerTaskTicker 热路径分配
7. [S1][性能] S6-002 com/habitrain/core/game/sre/TaskWeightCurves.java:77-96 — shouldIncludeOriginalTasks 每次任务生成重建全量 Set
8. [S1][死逻辑] S7-001 game/blackout/BlackoutSheriffVoteManager.java:78-169 — BlackoutSheriffVoteManager 整套警长投票功能为死代码
9. [S1][死逻辑] S7-002 game/blackout/BlackoutSheriffResolver.java:21 — BlackoutSheriffResolver.applyVoteResult 永不调用，警长投票结算链断裂
10. [S1][死逻辑] S8-001 game/blackout/task/SupplyTaskSyncHelper.java:38 — syncCompletion 链式递归致时间影响被重复施加 + O(N²) 扫描
11. [S1][性能] S8-002 game/blackout/task/BlackoutLookMyEyesTask.java:30 — BlackoutLookMyEyesTask.onTick 每 tick 遍历全服玩家做向量计算
12. [S1][性能] S9-001 com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java:358 — 每帧每块分配 Color 对象（渲染热路径）
13. [S1][死逻辑] S9-002 com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java:172 — invalidateGameRunningCache 永不调用（死代码）
14. [S1][耦合] S9-003 com/habitrain/core/client/mixin/FixTaskRendererMixin.java:26 — 12 个 client mixin 全部 required=true，任一 SRE 目标缺失即阻断客户端启动
15. [S1][死逻辑] S10-002 client/gui/config/MinigameEditScreen.java:296 — MinigameEditScreen saveBtn/resetBtn 位置在 super.render 之后才设置

## 3. 四维统计表

| 维度 | S0 | S1 | S2 | S3 | 小计 |
|------|----|----|----|----|------|
| 性能 | 0 | 6 | 11 | 6 | 23 |
| 死逻辑 | 0 | 10 | 42 | 14 | 66 |
| 标识 | 0 | 0 | 4 | 22 | 26 |
| 耦合 | 0 | 4 | 23 | 8 | 35 |

## 4. 切片发现明细

### 切片 S1
覆盖文件 13 个。发现 9 条。

#### S1-007 [S1][死逻辑] look_my_eyes onTick 每 tick AABB+getEntitiesOfClass 实体扫描与对象分配
- **文件**：com/habitrain/core/BuiltinTaskRegistrar.java:194-233
- **证据**：look_my_eyes 任务 onTick 每服务端 tick 调 serverLevel.getEntitiesOfClass(ServerPlayer.class, new AABB(eye±3), p->p!=serverPlayer&&p.isAlive()) 并对 nearby 逐个 getEyePosition/normalize/dot；maxProgress 上限 60，任务存活期间持续执行。
- **影响**：被分配该任务的玩家每 tick 触发一次实体查询与多个 Vec3 分配；多玩家并发分配时叠加为 O(玩家数) 每 tick 开销，属热路径可量化劣化。
- **方向**：look_my_eyes onTick 增加节流（如每 N tick 检测一次）或先用距离/朝向初筛减少 getEntitiesOfClass 调用

#### S1-001 [S1][死逻辑] anyGameActive 与 hasActiveGame 双布尔始终同值，冗余变量
- **文件**：com/habitrain/core/ModTickHandler.java:26-35, 44
- **证据**：tickMoreModules 内 anyGameActive 与 hasActiveGame 在同一 if(BetelTickEngine.isGameActive(world)) 分支里同时置 true，值恒等；anyGameActive 喂给 GameLifecycleHandler.tickGameEndCheck，hasActiveGame 用于提前 return。
- **影响**：两变量语义重叠，后续维护若只改其一会引入隐性不一致；每 tick 执行的确定性冗余分支。
- **方向**：合并为单一布尔并取语义清晰命名

#### S1-002 [S1][死逻辑] GameModeLifecycle 枚举全仓无任何引用，死类型
- **文件**：com/habitrain/core/api/GameModeLifecycle.java:7-17
- **证据**：全仓 Grep GameModeLifecycle 仅命中本文件定义；PRE_START/START/TICK/.../CLEANUP 无任何 switch 或引用，GameMode 接口直接用 default 方法钩子。
- **影响**：api 包导出公开类型，DLC 可见却永不被框架调用，类文档声称“用于框架内部调度”构成误导性 API。
- **方向**：删除该枚举或改为包私有并更正文档

#### S1-003 [S2][死逻辑] grantedItems/addGrantedItem/getGrantedItems 写入从不被读取，回收走 NBT 标签
- **文件**：com/habitrain/core/api/TaskInstance.java:34, 49-53
- **证据**：grantedItems 经 addGrantedItem 写入（BuiltinTaskRegistrar:175、BlackoutSearchBackpackTask:57）但全仓 getGrantedItems() 仅定义行；ItemReclaimHelper.reclaim 实际靠 NBT habitrain_grant 标签扫描背包，不读该列表；addGrantedItem 还做 stack.copy()。
- **影响**：维护者会误以为回收依赖此清单；实为死数据，徒增 TaskInstance 内存与每次 onComplete 一次 copy 分配。
- **方向**：移除 grantedItems 链路或让回收真正消费该清单，二选一

#### S1-004 [S2][死逻辑] getElapsedTicks 与 getCustomTaskId 无调用方
- **文件**：com/habitrain/core/api/TaskInstance.java:44, 115
- **证据**：全仓 Grep getElapsedTicks 仅命中定义行；getCustomTaskId() 仅定义行与 SRETrainTaskWrapper 覆盖行（覆盖里用的是 instance.getFullId() 而非 instance.getCustomTaskId()）。
- **影响**：公开 API 暴露无消费方方法；elapsedTicks 字段仍被 tick 累加与 toNbt 序列化但 getter 无人读，半死代码。
- **方向**：评估删除 getter 或澄清预期调用方

#### S1-005 [S2][死逻辑] 两处空 catch 吞异常（shop.balance、conn.setGroup）
- **文件**：com/habitrain/core/HabiTrainCore.java:331-334, 397, 403
- **证据**：L331-334 try{ var shop=SREPlayerShopComponent.KEY.get(player); if(shop!=null) balance=shop.balance; } catch(Exception ignored){}; L394-397 与 L399-403 conn.setGroup 两处 catch(Exception ignored){}。
- **影响**：组件缺失/连接异常被吞，电话 GUI 余额显示 0 误导玩家、临时群组静默失败计数不符；问题难定位。
- **方向**：至少 log.warn 记录非预期异常，不应静默忽略

#### S1-006 [S2][性能] getActiveForLevel fallback 每次新建流遍历全部注册模式
- **文件**：com/habitrain/core/api/GameModeRegistry.java:126-134
- **证据**：getActiveForLevel 在 ACTIVE_MODES 未命中时走 REGISTRY.values().stream().filter(m->m.isActive(level)).findFirst()，每次调用新建流并调用所有注册模式 isActive(level)。
- **影响**：事件路径每次调用分配流对象并触发各模式 isActive 实现；模式数与调用频率增长时累计开销。
- **方向**：缓存被动激活结果或限定 fallback 只在显式标记 passive 的模式上检查

#### S1-008 [S3][标识] roleType 魔法数字 4/5、双节棍冷却 1000/200 未命名常量化
- **文件**：com/habitrain/core/LootHelper.java:31, 48, 77
- **证据**：if(roleType==4)/else if(roleType==5) 直接比较裸数字；双节棍冷却 (roleType==4)?1000:200 亦为裸字面量，无命名常量说明 4/5 各代表什么角色。
- **影响**：角色码语义不可读，跨文件改动易错且与角色枚举无对齐。
- **方向**：用命名常量或枚举替换 4/5 与冷却阈值

#### S1-009 [S3][耦合] 主入口类 442 行职责密度过高（配置/网络/命令/生命周期/语音/槟榔/音效集中）
- **文件**：com/habitrain/core/HabiTrainCore.java:66-442
- **证据**：单类 442 行内承担配置、3 个 GameMode 注册、16 个网络包注册、命令注册、6 个生命周期回调、4 个 C2S 接收器、音效注册、槟榔初始化、内置/停电任务注册；C2S 接收器内联耦合 BlackoutPoliceHireService/ExileVoteManager/SREPlayerShopComponent 等多个实现包。
- **影响**：单一职责违反，任一子系统改动都改本类，测试与替换困难，跨实现包耦合集中。
- **方向**：按子系统拆出 NetworkHandler/CommandRegistrar/LifecycleWiring 等协作类，主类仅做编排


### 切片 S2
覆盖文件 7 个。发现 10 条。

#### S2-001 [S1][死逻辑] dlcTaskCounts 跨局不清理，计数单调累积破坏分配平衡
- **文件**：task/TaskManager.java:51
- **证据**：dlcTaskCounts(行51)仅被 incrementDlcTaskCount(行58)单调累加；唯一清理 clearDlcTaskCounts(行63)全仓库无调用者；游戏结束清理 clearAllActiveTasks(行88)只清 activeCustomTasks/activeFakeTasks/blackoutNextDailyPool，不清 dlcTaskCounts。
- **影响**：getDlcTaskCount(行53)被 GenerateTaskMixin:307 用于 DLC 任务分配去重/次数限制；计数永不归零→跨局累积→任务分配多样性/平衡随对局数劣化，属确定性跨局状态泄漏。
- **方向**：在游戏结束路径接入 dlcTaskCounts 清理，或将 clearDlcTaskCounts 绑定到生命周期回调；先确认计数语义为“本局”还是“全局会话”再决定清理点。

#### S2-002 [S2][死逻辑] TaskManager.getAvailableTasks 死代码且与 TaskPoolBuilder 重复
- **文件**：task/TaskManager.java:125
- **证据**：getAvailableTasks(String, TaskCategory)(行125)全仓库仅定义处命中、无任何调用者；其过滤逻辑与 TaskPoolBuilder.getAvailableDlcTasks(行40-99)+isTaskMapEnabled 重复但已不同步。
- **影响**：重复实现易分叉（mapFilterMode、category 判定与 TaskPoolBuilder 不一致）；维护负担与误用风险。
- **方向**：确认是否遗留入口；无调用方则移除，并将池/过滤逻辑统一到单一实现。

#### S2-003 [S2][性能] TaskPoolBuilder.CACHE 无按 mode 失效与游戏结束清理，invalidate(String) 死代码
- **文件**：task/TaskPoolBuilder.java:25
- **证据**：CACHE(行25)仅在 invalidateAll()(行137)被调用；invalidate(String modeId)(行141)全仓库无调用者；getPool 用三元组(modeId,mapName,categoryId)为 key(行23/34)，无按 mode 选择性失效、无容量上限、无游戏结束失效。
- **影响**：随 mode/mapName 组合增多缓存只增不减，旧 key 残留为内存泄漏；invalidate(String) 形同虚设，按 mode 失效能力缺失。
- **方向**：接入游戏结束/模式切换的按 mode 失效，评估容量上限或弱引用；移除未用的 invalidate(String) 或将其接入失效路径。

#### S2-004 [S3][死逻辑] SlownessReapplyManager.unregister 与 clearAll(ResourceKey) 死代码
- **文件**：task/SlownessReapplyManager.java:49
- **证据**：unregister(ResourceKey, UUID)(行49)与 clearAll(ResourceKey)(行63)全仓库无调用者；所有清理处均用 unregisterAllLevels 或无参 clearAll()。
- **影响**：误导维护者以为存在按维度精细清理路径；增大 API 表面。
- **方向**：移除无调用者方法，或接入按维度失效逻辑。

#### S2-005 [S3][死逻辑] GameLifecycleHandler.register() 为空挂（仅日志，不注册回调）
- **文件**：task/GameLifecycleHandler.java:27
- **证据**：register()(行27)仅 LOGGER.info，不注册任何回调；实际 tick 由 ModTickHandler.register() 调 GameLifecycleHandler.tickGameEndCheck(行39)。HabiTrainCore.java:439 调用此空方法。
- **影响**：误导读者以为生命周期已接好；方法名 register 与实际空挂不符。
- **方向**：移除空 register() 或在其中真正注册 tick 回调；改名以反映实际职责。

#### S2-006 [S2][耦合] GameLifecycleHandler 直接依赖 game.blackout.task 具体类
- **文件**：task/GameLifecycleHandler.java:100
- **证据**：handleGameEnd 硬编码清理 com.habitrain.core.game.blackout.task.AddCoalHandler/RepairWiringHandler/MaintainPowerHandler/FurnaceExplosionHandler/SabotageWiringHandler/BlackoutEatHandler/BlackoutDrinkHandler.clearAll()(行100-108)。task 包反向依赖 game.blackout.task 具体类。
- **影响**：通用 task 包被 blackout 实现细节污染；新增 blackout 任务需改动通用 GameLifecycleHandler；违反包依赖方向(task→game.blackout)。
- **方向**：让各 handler 自注册游戏结束清理回调（观察者/注册表），GameLifecycleHandler 只触发统一清理事件，不逐个硬编码。

#### S2-007 [S2][耦合] TaskManager 直接依赖 io.wifi.starrailexpress.cca 具体组件并改其 public 字段
- **文件**：task/TaskManager.java:6
- **证据**：import io.wifi.starrailexpress.cca.AreasWorldComponent/SREGameRoundEndComponent/SREGameWorldComponent/SREPlayerTaskComponent(行6-9)；triggerDirectWin 直接写 roundEnd.CustomWinnerID、roundEnd.CustomWinnerPlayers.add(行190-192)并拼 modId+"_"+taskId+"_win"；getCurrentMapName/getCurrentGameModeCategory 直接读 SRE 组件 public 字段。
- **影响**：core 强耦合外部 SRE 模组具体类与内部字段；SRE 字段名/契约变更将直接破坏 core；实现细节经具体组件泄露。
- **方向**：经 core 自有抽象访问地图名/模式/胜利，避免直接持 SRE 具体类型与写其 public 字段；用命名常量替换 _win 拼接。

#### S2-008 [S3][标识] triggerDirectWin 硬编码 _win 后缀魔法字符串
- **文件**：task/TaskManager.java:190
- **证据**：roundEnd.CustomWinnerID = modId + "_" + taskId + "_win"(行190-191)用裸字符串 _win 拼接决定胜利标识。
- **影响**：标识契约散落、无单一真相源，命名空间/分隔符变更难追踪。
- **方向**：提取为命名常量或由 GameMode/任务定义提供 winner id 规约。

#### S2-009 [S3][性能] BackpackSearchHandler 超时分支重复 getPlayer 查找 + 实际不可达 else 分支
- **文件**：task/BackpackSearchHandler.java:65
- **证据**：超时分支内 server.getPlayerList().getPlayer(uuid) 在行66与行83两次调用(同 uuid)；行157 if(world instanceof ServerLevel sl && sl.getServer()!=null) 的 else(行160 world.getGameTime())在 player instanceof ServerPlayer 已成立的服务端侧实际不可达。
- **影响**：超时清理时多一次 O(玩家数) 列表查找；else 分支为死代码，混用 world.getGameTime() 与主世界 gameTime 与注释意图相悖。
- **方向**：复用一次 getPlayer 查找结果；移除或修正 else 分支，统一时间源。

#### S2-010 [S3][死逻辑] BackpackQuestState.init() 与 getInstance() DCL 并存，冗余初始化
- **文件**：task/BackpackQuestState.java:22
- **证据**：init()(行22)在 HabiTrainCore.java:434 调用直接 new 实例；getInstance()(行26)又有 DCL 双检懒加载，两套初始化路径并存。
- **影响**：冗余路径增加混淆(实例来源不唯一)；init 非 volatile 写、DCL 兜底，语义可对齐但易误改。
- **方向**：择一初始化方式(启动期 init 或纯 DCL)，不要两套并存。


### 切片 S3
覆盖文件 6 个。发现 12 条。

#### S3-001 [S1][性能] tickPlayer 每 tick 对每玩家重复同步查找 registry/组件，isGameActive 每世界每 tick 也查组件，且揭晓路径 new Random()
- **文件**：betel/BetelTickEngine.java:33-199
- **证据**：tickPlayer 由 ModTickHandler.tickMoreMods 在 hasActiveGame 为真时对 server.getPlayerList().getPlayers() 每名玩家每 tick 调用一次（ModTickHandler.java:48-49）。每次调用做：SREGameWorldComponent.KEY.get(player.level())(L45)、BetelNutEntityComponents.ADDICTION.get(player)(L73)、BetelNutConfig.get()(L78)、getAddictionStage/getWithdrawalSeverity/getWithdrawalValue/getLastEatTime 多次(L81/118-119/175-177)、player.level().getGameTime() 多次(L94/137)。检测到吃槟榔时还 BuiltInRegistries.ITEM.get(L274) 与 getHolder(L262)。executeReveal(L299) 每次 new Random()。isGameActive(L201-210) 对每个 server.getAllLevels() 每 tick 一次(ModTickHandler.java:30) 都同步组件查找。
- **影响**：对局活跃时每 tick × 每玩家 × 多次 registry/组件查找，玩家数量大时为可量化服务器 tick 开销；new Random() 每揭晓一次分配一次对象（低频不必要分配）。
- **方向**：tick 内组件引用取一次复用；registry 常量(noellesroles 效果 holder、residue item)启动期缓存为字段；isGameActive 复用已查出的 gameWorld 而非每世界独立再查；Random 用 ThreadLocalRandom 或静态实例。

#### S3-002 [S2][性能] applyHarvestSlowness 与 tickHarvests 都每 tick 全量遍历 activeHarvests，存在 O(世界×任务) 冗余
- **文件**：betel/BetelLeafHandler.java:47-74, 119-185
- **证据**：BetelLeafHandler.register() 注册 END_SERVER_TICK 回调 applyHarvestSlowness(L47-52)，内层 for (HarvestTask task : activeHarvests.values())(L60)，外层 for (ServerLevel world : server.getAllLevels())(L49)。同时 ModTickHandler.tickMoreMods 对每个 getAllLevels() 调用 tickHarvests(world)(ModTickHandler.java:29)，tickHarvests 内 Iterator 遍历整个 activeHarvests(L123-185) 再按 task.worldKey.equals 过滤(L131)。两条路径同 tick 内都对全量任务做完整遍历。
- **影响**：activeHarvests 非空时每 tick 对同一 map 两次全量遍历(一次跨世界外层+全任务内层,一次每世界+全任务)，任务数与世界数耦合放大；常态下采集任务少影响有限，属结构性冗余。
- **方向**：activeHarvests 按世界维度分桶，或合并两条遍历为一次；统一由 ModTickHandler 驱动，移除 BetelLeafHandler 自身注册的 END_SERVER_TICK 回调，避免双注册。

#### S3-003 [S2][死逻辑] clearHechengTianxiaData 公共方法全模块零调用方
- **文件**：betel/BetelTickEngine.java:212-219
- **证据**：grep clearHechengTianxiaData 仅命中其自身定义(L212)与内部 addiction.clearHechengTianxiaData(player)(L215,那是外部 betel.nut 组件同名方法)。本方法在整个 src/main/java 内无任何调用方。
- **影响**：公共 API 形同死代码，意图「清除合成天下槟榔数据」的功能在主流程无触发点；维护者可能误以为存在清理入口。
- **方向**：确认是否应有调用方(游戏结束/重置链路),若需要则接入,否则删除。

#### S3-004 [S2][死逻辑] isGameActive 与 clearAddictionForPlayer 中两处空 catch 静默吞异常，无日志无降级标记
- **文件**：betel/BetelTickEngine.java:201-210, 244-245
- **证据**：isGameActive(L201-210): try{...}catch(Exception e){} catch 体完全空,直接 return false。clearAddictionForPlayer(L234-245): try{...}catch(Exception e){} catch 体空。
- **影响**：SRE/betel-nut 组件异常态时 isGameActive 静默返回 false 使整条 tick 流程提前短路(ModTickHandler 据 hasActiveGame 决定是否 tickPlayer)但日志无任何痕迹,排障困难;clearAddictionForPlayer 失败时玩家成瘾未清除却无日志,跨局状态可能残留。
- **方向**：catch 内至少加 warn/error 日志(参考 L75 做法);isGameActive 失败语义需明确(视为非活跃还是兜底活跃)。

#### S3-005 [S2][死逻辑] setFoodRestriction / hasActiveHarvest / hasActiveHarvestInWorld 公共方法零调用方
- **文件**：betel/BetelQuestState.java:51-53
- **证据**：BetelQuestState.setFoodRestriction(L51-53) grep 全模块仅命中定义本身;实际设置食物限制是 BetelTickEngine 直接写 data.hasFoodRestriction = true(BetelTickEngine.java:158),从不走此 setter。hasActiveHarvest(L194-196)与 hasActiveHarvestInWorld(L201-204) grep 全模块仅命中定义本身,零调用。clearAllHarvests(L187-189) 仅 GameLifecycleHandler.java:93 调用一处,有效,不并入本条。
- **影响**：公共方法对外暴露但内部未使用,API 表面大于实际行为;setFoodRestriction 与字段直写并存,破坏「状态经封装方法修改」约定,维护者难判断哪条路径为权威。
- **方向**：删除未用查询方法或接入调用方;食物限制状态统一走 setter,移除 BetelTickEngine 中对 data.hasFoodRestriction 的直写。

#### S3-006 [S3][死逻辑] PlayerBetelData.lastKnownLastEatTime 只写不读
- **文件**：betel/BetelQuestState.java:114
- **证据**：BetelTickEngine.tickPlayer L171-173 仅赋值 data.lastKnownLastEatTime = currentLastEatTime;grep lastKnownLastEatTime 全模块无任何读取点。其语义与已被读取的 lastDetectedEatTime(L83-89)高度重叠。
- **影响**：字段无效果,徒增 PlayerBetelData 状态密度与认知负担,疑似历史逻辑残留。
- **方向**：删除该字段及其赋值分支,或确认其设计意图后接入读取。

#### S3-007 [S2][耦合] getInstance 单例懒加载非线程安全，playerData 为普通 HashMap
- **文件**：betel/BetelQuestState.java:23-28, 11-21
- **证据**：private static BetelQuestState instance;(L11);init() 直接 instance = new BetelQuestState()(L20) 无同步;getInstance()(L23-28) if(instance==null) instance=new BetelQuestState() 无 volatile/无同步块。playerData 为普通 HashMap(L15),computeIfAbsent 在并发下不安全。
- **影响**：Fabric 服务器主 tick 单线程常态安全;但 FabricLoader.getInstance() 调用路径(getCurrentServer L76-84)、可能 mod 初始化线程或并行流场景下,单例与 HashMap 非同步访问存在数据竞争,可能 PlayerBetelData 丢失或重复构建。
- **方向**：单例用 holder/双重检查锁+volatile;或保证 init() 在服务器启动前确定性执行后 getInstance 直接返回(当前 HabiTrainCore 已在初始化期调用 init,可移除 getInstance 内懒加载兜底消除歧义)。playerData 若有并发访问需换 ConcurrentHashMap。

#### S3-008 [S2][标识] PlayerBetelData 14 状态字段密度高，多个布尔跟踪重叠生命周期/触发态
- **文件**：betel/BetelQuestState.java:108-123
- **证据**：PlayerBetelData(L108-123)含 14 字段:hasBetelQuestBeenAssigned/lastDiagnosticStage/hasBeenProcessed/wasGameNotRunning/wasSpectating/lastKnownLastEatTime/lastDetectedEatTime/hasEatenBetelNut/betelNutsEatenThisGame/ownLastEatGameTime/darknessAppliedThisTrigger/hasHeavyAddiction/ateBetelNutToRelieve/hasFoodRestriction。hasEatenBetelNut/ateBetelNutToRelieve/hasHeavyAddiction/darknessAppliedThisTrigger 均围绕「吃槟榔-成瘾-戒断-效果已施加」语义部分重叠且无注释;hasBeenProcessed/wasGameNotRunning/wasSpectating 均为「上一 tick 边界状态」快照。
- **影响**：14 字段密度高,布尔命名相近语义重叠,难判断哪个为权威;ateBetelNutToRelieve 在 tickPlayer 中反复置位/清零(L92/166/196)跨越两条路径,易引入状态机 bug。
- **方向**：将重叠布尔收敛为显式状态枚举(如成瘾阶段/效果施加状态机),并补字段级注释说明每个标志的写入点与清零点。

#### S3-009 [S3][标识] 成瘾/戒断阈值与效果时长全为魔法数字散落多处
- **文件**：betel/BetelTickEngine.java:126-138, 157, 178, 182, 265
- **证据**：BetelTickEngine.java:126-132 ownValue>=80/60/40/20 阶梯;L138 >=600;L157 >=3;L178 Math.max(1,Math.min(25,config.maxWithdrawalValue))(25 魔法上限);L182 new MobEffectInstance(DARKNESS,600,...);L265 200。BetelLeafHandler.java:35 HARVEST_TICKS=60;L95/159 >=5(数量上限重复出现未提常量);L70/107 HARVEST_TICKS+10(缓冲魔法数 10)。BetelWithdrawal.java:15/17 100。
- **影响**：阈值散落各处无单一来源,平衡调整需多处改动易漏改;config.enableAddictionSystem 开启时本应依赖 config,但自有追踪分支仍用硬编码阶梯,两套阈值并存语义不清。
- **方向**：集中为命名常量或 BetelNutConfig 字段;尤其成瘾阶段阈值与戒断 tick 阈值应来自配置以保证两套追踪一致。

#### S3-010 [S2][耦合] betel 被 blackout.task 单向依赖，BetelQuestState 暴露为公共可变单例且对外可写
- **文件**：betel/BetelQuestState.java:11, 23, 95, 104-106
- **证据**：BlackoutBetelQuestTask(game.blackout.task 包)import com.habitrain.core.betel.BetelQuestState(L4)并调用 markQuestAssigned/resetEatenStatus/hasPlayerEatenBetelNut(L28-32)。BetelQuestState 为公共可变单例(L11/23),getInstance() 可被任意包获取并 setRevealUsed(true)/resetAll()(L104-106/95);PlayerBetelData 字段包级可见无 private。
- **影响**：betel 与 blackout 跨包耦合,且 betel 对局状态对外完全可写,任意外部模块可调用 resetAll()/setRevealUsed 影响对局;blackout 复用 betel 状态机意味着两子系统状态生命周期需同步重置(GameLifecycleHandler.resetAll task/GameLifecycleHandler.java:92),一旦遗漏即跨局污染。
- **方向**：收敛 BetelQuestState 公共表面(resetAll/setRevealUsed 不应对外开放);考虑由 betel 提供显式「任务复用」接口而非让 blackout 直接操作内部状态字段。

#### S3-011 [S3][标识] getCurrentServer 中局部 var instance 遮蔽静态字段 instance
- **文件**：betel/BetelQuestState.java:76-84
- **证据**：L78 var instance = net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance(); 局部变量名为 instance,与类静态字段 private static BetelQuestState instance;(L11)同名。作用域不冲突但同名降低可读性。
- **影响**：仅可读性问题,无功能 bug。
- **方向**：局部变量改名为 gameInstance/rawInstance 以避免与单例字段同名。

#### S3-012 [S3][耦合] 静态可变 betelLeafBlock/blockChecked 缓存无同步、无失效/重试机制
- **文件**：betel/BetelLeafHandler.java:40-41, 206-221
- **证据**：private static Block betelLeafBlock=null; 与 private static boolean blockChecked=false;(L40-41) 在 isBetelLeafBlock 内懒加载并置 blockChecked=true(L218)后永久缓存。查找依赖 BuiltInRegistries.BLOCK.get(L209),若首次查找时 betel-nut-mod 尚未完成注册(加载顺序问题),会永久判定「方块未找到」且永不重试。
- **影响**：mod 加载顺序敏感:若首次 isBetelLeafBlock 调用发生在 betel-nut-mod 注册完成前,槟榔叶采集永久失效且无自愈。非线程安全字段在并行场景下也存在可见性问题。
- **方向**：延后首次查找时机(如改为服务器启动后惰性 + 可重试),或显式在合适生命周期事件中预热查找并校验非 AIR。


### 切片 S4
覆盖文件 8 个。发现 10 条。

#### S4-001 [S2][死逻辑] TaskConfigEntry.getEffectiveGoldReward/getEffectiveEmotionReward/getEffectiveRefreshWeight 为死方法，全树零调用
- **文件**：com/habitrain/core/config/TaskConfigEntry.java:86
- **证据**：三个方法定义存在且签名为 getEffectiveXxx(TaskDefinition def)，全 src/main/java grep 仅命中定义行，零调用方。getEffectiveGoldReward 体为 goldReward>=0?goldReward:-1，getEffectiveEmotionReward 同构，refreshWeight 才回退 def.getWeight()。
- **影响**：统一“配置覆盖 vs 回退默认值”的逻辑实际从未生效；调用方（TaskManager、mixins 等）必然各自直接读 public 字段再自行判负，绕过封装。未来加新字段易再次各自实现。
- **方向**：A2 跨包核对消费路径后，要么删除这三个死方法，要么把现有直接读 public 字段的调用方迁到这三个方法统一取值口径。

#### S4-002 [S2][死逻辑] @Deprecated(forRemoval=true) disabledMaps 仍被 fromJson 解析填充但从不参与决策也不序列化
- **文件**：com/habitrain/core/config/TaskConfigEntry.java:22
- **证据**：字段 @Deprecated(forRemoval=true) public List<String> disabledMaps(22-23)；fromJson 读 'disabledMaps' 填充该字段并 warn(67-76)，但 isAllowedOnMap 只看 enabledMaps+mapFilterMode，toJson(37-52) 不写出。grep 全树确认无过滤逻辑消费 disabledMaps。
- **影响**：字段持续被反序列化进内存却永不使用，每次加载旧配置打 warn 噪音；forRemoval=true 却仍被解析，违背废弃语义；用户“禁用地图”旧配置静默失效，仅靠 warn 引导。
- **方向**：既 forRemoval 且无消费，fromJson 阶段只打迁移 warn、不再填充字段；或彻底移除该字段与解析分支。

#### S4-003 [S2][死逻辑] ConfigStore.calculateCurrentBoost 为死方法
- **文件**：com/habitrain/core/config/ConfigStore.java:259
- **证据**：包级方法 float calculateCurrentBoost(ConfigRepository) 调 TaskBalancer.calcBoost(...countDlcTasks(), countOriginalTasks())，与 public getDlcWeightBoost 计算相同但去日志。全树 grep 仅命中定义行，零调用方。
- **影响**：死代码且与 getDlcWeightBoost 重复实现，改 calcBoost 调用点易漏改这份。
- **方向**：删除该方法（无人引用）。

#### S4-004 [S2][耦合] client.gui/网络/混入等跨包直接依赖 ConfigManager 单例具体类，无接口隔离
- **文件**：com/habitrain/core/config/ConfigManager.java:33
- **证据**：ConfigManager 为静态单例(volatile INSTANCE+双检锁 15-42)。grep 显示 client.gui.GlobalSettingsScreen/ShaderWhitelistScreen/config.*TabScreen/MinigameEditScreen/TaskSaveController 及 client.mixin/client.cache/network/game.blackout/game.sre.mixin 全部直接 ConfigManager.getInstance().getXxx()，直接耦合 ConfigManager 与 config 包内部具体类 public 字段。
- **影响**：API 泄露实现：单例扩散到客户端/网络/混入层，无法替换或 mock 配置源；GUI 直接读具体类 public 字段，配置内部结构变更波及整个客户端层。
- **方向**：抽取只读配置查询接口，GUI/网络层依赖接口而非 ConfigManager 具体类；逐步收敛单例访问点。

#### S4-005 [S2][性能] ConfigStore.buildJsonRoot 每次 save 全量遍历 TaskRegistry/QuestMinigames 并构造 JSON，每次配置变更触发
- **文件**：com/habitrain/core/config/ConfigStore.java:159
- **证据**：buildJsonRoot(159-202) 对 TaskRegistry.getAll() 全量遍历，每任务哈希查表+构造完整 JsonObject(含 enabledMaps 数组)，minigames 段同样 safeGetAllMinigames() 全量遍历。ConfigManager 几乎每个 setter 末尾 store.save(repository)，save 内 buildJsonRoot(true) 还额外调 getDlcWeightBoost(含 calcBoost+LOGGER.info)。
- **影响**：非每 tick 热路径，但 GUI 改一个值即全量重写磁盘+全量 JSON 构造+全量 registry 遍历+一次 boost 计算+info 日志；批量编辑(如 TaskTabScreen 循环 setTaskConfig)放大为 N 次全量 save。
- **方向**：区分“内存更新”与“落盘”，提供批量提交入口；save 支持增量/脏标记而非每次全量 build。

#### S4-006 [S3][标识] 多个 Logger 复用同名 "ConfigManager"，中英混用
- **文件**：com/habitrain/core/config/ConfigStore.java:21
- **证据**：ConfigStore(21)、ConfigSync(11)、MinigameEnforcement(12) 三者 getLogger 名都传 'ConfigManager'；TaskConfigEntry(17) 传 'TaskConfigEntry'。日志中英文混用(如 '全局设置: DLC目标占比=...' 与 'applyMinigameEnforcement 失败，SRE 可能未安装')。
- **影响**：ConfigStore/ConfigSync/MinigameEnforcement 日志全显示为 ConfigManager logger，排障无法区分来源。
- **方向**：各类用自身类名作为 logger name；日志语种统一。

#### S4-007 [S3][死逻辑] ConfigManager.getGameModeConfig 转发全树无调用方，底层 computeIfAbsent 含写副作用
- **文件**：com/habitrain/core/config/ConfigManager.java:83
- **证据**：ConfigRepository.getGameModeConfig 用 gameModeConfigs.computeIfAbsent(gameModeId, GameModeConfigScope::new)，读即创建。ConfigManager.getGameModeConfig 仅转发，grep 全树仅命中本转发与底层定义，无外部调用方(实际仅用 getAllGameModeConfigs 做序列化)。
- **影响**：读方法隐含写副作用(无配置也建空 scope 留存内存)，当前无人用属潜在陷阱；未来被调用会在 repository 累积空 GameModeConfigScope 且不落盘。
- **方向**：删除未用的 getGameModeConfig 转发；底层 getGameModeConfig 改为无副作用 get，创建显式走 getOrCreate。

#### S4-008 [S3][标识] TaskConfigEntry/MinigameConfigEntry 用 -1 作“未设置”哨兵，魔法值散落多处
- **文件**：com/habitrain/core/config/TaskConfigEntry.java:27
- **证据**：goldReward=-1/emotionReward=-1f/refreshWeight=-1f 用 -1 作“未配置”哨兵，toJson 用 >=0 判断写出，getEffectiveXxx 用 >=0 判断回退。0 是合法奖励值却与 -1 哨兵边界冲突，魔法数字散落多处。MinigameConfigEntry 同构复制。
- **影响**：0 奖励与“未配置”需靠 -1 区分，易误判；规则在两文件重复，可维护性差。
- **方向**：用 Optional/包装类型或显式 hasXxx 标志位替代 -1 哨兵；或集中“有效值”判定到一个常量方法。

#### S4-009 [S3][死逻辑] ConfigRepository.setTaskConfig/putTaskConfig、setMinigameConfig/putMinigameConfig 实现完全相同
- **文件**：com/habitrain/core/config/ConfigRepository.java:22
- **证据**：setTaskConfig 与 putTaskConfig 体均为 map.put(fullId, entry)(22-28)；setMinigameConfig 与 putMinigameConfig 同构(93-99)。ConfigManager 层 set* 带 save、put* 不带 save，语义差异只在 ConfigManager 层，Repository 层两方法体完全重复。
- **影响**：命名暗示语义差异(set vs put)但 Repository 层无差别，易混淆；维护时需同步改两份。
- **方向**：Repository 层合并为单一 put，ConfigManager 层用“是否 save”参数或两个方法名表达语义。

#### S4-010 [S3][耦合] ConfigStore/MinigameEnforcement 直接依赖 SRE DLC 具体类与字段名，无抽象隔离
- **文件**：com/habitrain/core/config/ConfigStore.java:10
- **证据**：ConfigStore import io.wifi.starrailexpress.content.minigame.QuestMinigame/QuestMinigames，safeGetAllMinigames 直接调 QuestMinigames.getAll()(catch Throwable 兜底)。MinigameEnforcement import 同类+io.wifi.starrailexpress.cca.AreasWorldComponent，直接读写 areas.minigameQuestEnabled/availableMinigameIds/mapName/areas.sync()。
- **影响**：config 包对 SRE DLC 具体类与具体字段名强耦合，SRE 升级改字段名直接崩；catch Throwable 掩盖结构性变化。专属检查点“对外部 DLC 强耦合”成立。
- **方向**：经 SRE 提供的稳定接口/SPI 访问小游戏与区域组件，避免 config 包直接依赖 DLC 具体类字段名。


### 切片 S5
覆盖文件 18 个。发现 7 条。

#### S5-001 [S2][性能] BlackoutVotePayload 每秒无变化门控全量广播（与 SheriffVoteBroadcaster 不一致）
- **文件**：network/BlackoutVotePayload.java:35
- **证据**：BlackoutExileVoteManager.tickSecond 在 state.active 期间每秒调用 broadcastState，每次新建 HashMap counts/nameCache + List<Entry> 并 broadcastToAll 发全体；对照 SheriffVoteBroadcaster.java:19-24 用 computeHash+lastPayloadHash 做内容去重门控，放逐路径无此门控。
- **影响**：停电模式放逐投票期间（VOTE_DURATION_SECONDS），服务端每秒分配多个临时集合并向所有在线玩家串行广播，即使票数/名单未变也照发。玩家多时每秒产生 O(玩家数) 名字查询与 N×M 写包，属可量化但有限的每秒劣化（非每 tick）。
- **方向**：为放逐投票路径引入与 SheriffVoteBroadcaster 等价的内容快照/哈希门控，状态未变化时跳过广播与集合重建。

#### S5-002 [S2][死逻辑] ShaderConfigPayload 解码缺少 count 上限与 len 负值保护
- **文件**：network/ShaderConfigPayload.java:54
- **证据**：decode 第54行 count=buf.readInt() 无上限；第57行 len=buf.readInt() 后 len=Math.min(len,MAX_STRING_LENGTH) 未先判负，Math.min(负数,65536)=负数，第59行 new byte[len] 触发 NegativeArraySizeException。对照 TaskConfigPayload.java:51-58,66-68 对 count/len 做 <0||>MAX 校验抛 DecoderException。
- **影响**：S2C 包，正常服务端可信，但损坏/异常包会让客户端 decode 以 NPE/NegativeArray 路径崩溃而非受控丢弃；count 无上限意味着异常服务端可触发 OOM。
- **方向**：对 count 与每个 len 增加范围校验（参考 TaskConfigPayload 的 MAX_* 常量 + DecoderException 模式）。

#### S5-003 [S2][死逻辑] CustomTaskBlockPayload 解码缺少 entryCount/setCount 上限
- **文件**：network/CustomTaskBlockPayload.java:37
- **证据**：decode 第37行 entryCount=buf.readInt() 只挡负数 if(entryCount<0)entryCount=0 无上限；第45行 setCount 同样只挡负数。每 entry 读 3 int 坐标 + setCount 个 int typeId。对照 TaskConfigPayload 对 size 做 MAX_ENTRIES/MAX_MAPS_PER_ENTRY 上限。MapScannerMixin.java:173 broadcastToAll 会把全量 snapshot 发给所有玩家放大风险。
- **影响**：S2C 包，异常/恶意服务端发送超大 entryCount 或 setCount 时，客户端按 size 循环 new HashMap/HashSet+readInt，可触发内存放大与 OOM。
- **方向**：为 entryCount 与 setCount 增加 MAX 上限校验，超限抛 DecoderException。

#### S5-004 [S2][死逻辑] BlackoutSheriffVotePayload / BlackoutVotePayload 解码候选列表 size 无上限
- **文件**：network/BlackoutSheriffVotePayload.java:39
- **证据**：两处 int size=buf.readVarInt() 后直接 new ArrayList<>(size) 按循环读 Entry，size 无上限。readVarInt 最大可达约 2^31，new ArrayList<>(2_000_000_000) 触发 OOM/预分配失败。
- **影响**：S2C 包，异常/恶意服务端发送超大 size 时客户端 OOM。同切片定长 S2C 包（Timer/PhoneOpen）无此问题。
- **方向**：对候选列表 size 增加合理上限校验，超限抛 DecoderException 或截断。

#### S5-005 [S2][标识] habitrain_taskapi 资源目录孤立 + 命名空间混用
- **文件**：src/main/resources/assets/habitrain_taskapi/lang/zh_cn.json:1
- **证据**：fabric.mod.json:3 id=habitrain_core，icon 引用 assets/habitrain_core/icon.png，所有 payload 用 habitrain_core 命名空间；但存在 assets/habitrain_taskapi/(icon.png+lang/zh_cn.json+lang/en_us.json)，lang 内容是 assets/habitrain_core/lang 的子集。habitrain_taskapi 非已注册 mod id，该目录不自动加载。下游 client/gui/TaskEditScreen.java:192 用 "habitrain_taskapi".equals(def.getModId()) 判定内置，而内置任务用 HabiTrainCore.MOD_ID(habitrain_core) 注册(BuiltinTaskRegistrar.java:47等)，判定恒 false。
- **影响**：habitrain_taskapi 命名空间在源码作为标识出现但与实际 mod id(habitrain_core) 不一致，资源树死资源永不加载、lang 重复维护；TaskEditScreen 内置判定恒为 false 致所有内置任务被错标为 [外部/DLC任务]。
- **方向**：统一命名空间为 habitrain_core，删除孤立 assets/habitrain_taskapi/ 目录，并将 TaskEditScreen 内置判定改为 HabiTrainCore.MOD_ID（或常量）。

#### S5-006 [S3][标识] BlackoutAnnouncePayload 显示串上限魔法数字 32767
- **文件**：network/BlackoutAnnouncePayload.java:21
- **证据**：roleName/subtitle/goal 三段显示文本统一硬编码上限 32767（约 32KB/串，三段合计近 96KB 单包），32767 是 MC 旧式 chat 串上限魔法数字，与该包实际承载短显示文本语义不符，且未以命名常量表达。
- **影响**：单包理论体积偏大；魔法数字散落无语义命名影响可维护性。无功能错误。
- **方向**：将 32767 替换为按实际显示文本语义设定的命名常量（如 ROLE_NAME_MAX 等），收紧到合理上限。

#### S5-007 [S3][标识] BlackoutVotePayload/BlackoutVoteCastPayload 的 purpose 以字面量散落比较、无枚举常量
- **文件**：network/BlackoutVotePayload.java:36
- **证据**：两包 purpose 字段都用 32 字符上限。服务端 receiver 用 "EXILE".equals(payload.purpose())(HabiTrainCore.java:357) 做路由；S2C BlackoutVotePayload 客户端只判 "EXILE".equals(HabiTrainCoreClient.java:276)。purpose 作为枚举语义值却以裸 String+字面量比较在收发两端各写一次，无单一枚举/常量定义。
- **影响**：新增投票类型时需在收发两端多处同步字面量，漏改会导致路由缺失/静默丢弃。当前仅 EXILE，无即时错误。
- **方向**：将 purpose 提取为枚举或常量集合，收发两端共用，避免字面量散落。


### 切片 S6
覆盖文件 23 个。发现 15 条。

#### S6-001 [S1][性能] 每 tick 每玩家 new PerPlayerTaskTicker 热路径分配
- **文件**：com/habitrain/core/game/sre/mixin/SREPlayerTaskComponentMixin.java:127
- **证据**：habitrain$onServerTick 注入 SREPlayerTaskComponent.serverTick HEAD，末尾 `new PerPlayerTaskTicker(player).tick();`。serverTick 由 SRE 每玩家组件每 tick (20 TPS) 调用。
- **影响**：N 个玩家对局期间每秒产生 20*N 个一次性 PerPlayerTaskTicker 对象（含字段与 Logger 引用），加重 young-gen GC 压力；高人数长对局下可量化劣化。
- **方向**：让 ticker 成为按玩家复用的实例（字段缓存/WeakHashMap by player），或将其逻辑内联为 static 方法接收 player 参数，避免每 tick 分配。

#### S6-002 [S1][性能] shouldIncludeOriginalTasks 每次任务生成重建全量 Set
- **文件**：com/habitrain/core/game/sre/TaskWeightCurves.java:77-96
- **证据**：在 GenerateTaskMixin.addOriginalTasks (L136) 调用，处于 generateTaskInternal 热路径。L86 先 `new ArrayList<>(TaskRegistry.getAll())` 拷贝整个注册表，再 `.stream().map(...).collect(Collectors.toSet())` 构建 Set，最后 L90-94 遍历 builtinSreTaskIds 调 `contains`。
- **影响**：每个玩家每次任务刷新都做一次全注册表拷贝 + 流式 Set 构建 O(注册表大小)，多玩家高频刷新时累加；Set 仅用于 contains 查询，存在更轻量手段。
- **方向**：activeMode.filterAvailableTasks 的结果可直接遍历 builtinSreTaskIds 求交集，或缓存 activeMode→允许集；避免每次重建完整 Set。

#### S6-003 [S2][耦合] 静态可变状态网 / 单例扩散
- **文件**：com/habitrain/core/game/sre/SREGameModeBase.java:32-43
- **证据**：5 个 static 可变字段：LOBBY_GROUP(L32)、pendingVoiceJoins(L34)、pendingGameEndGroupJoin(L38)、builtinTasksRegistered(L41)、sreEventsRegistered(L43)；并对外暴露多个 static 方法，构成跨实例全局状态网。
- **影响**：所有 SREGameModeBase 子类共享同一份全局状态，多对局/多世界并发时状态串扰；生命周期与类绑定而非与对局绑定，难以测试与重置。
- **方向**：将语音群组/事件注册状态收敛到一个显式单例服务对象（注入式），按 MinecraftServer 生命周期管理，而非散布于类静态字段。

#### S6-004 [S2][死逻辑] getType 在 CUSTOM 不支持时回退 SLEEP，与原版任务槽冲突
- **文件**：com/habitrain/core/game/sre/SRETrainTaskWrapper.java:51-57
- **证据**：getType() 在 typeOverride==null 且 TaskEnumHelper.getCustom()==null 时返回 SLEEP。注释 L22-25 说明 typeOverride 仅为杀手假任务避免与 CUSTOM 冲突；正常 DLC 任务走无 override 构造，在旧版 SRE 上落到 SLEEP 槽。
- **影响**：在不支持 CUSTOM 的 SRE 版本上，DLC 任务被映射到原版 SLEEP 枚举槽，可能覆盖/与玩家正常睡觉任务混淆，破坏任务槽位语义。
- **方向**：不支持 CUSTOM 时让 getType 表达“无可用槽”而非默认 SLEEP；或要求上层保证仅在 isCustomTaskSupported 时包装 DLC 任务。

#### S6-005 [S2][死逻辑] SRETrainTaskWrapper.toNbt() 为死代码
- **文件**：com/habitrain/core/game/sre/SRETrainTaskWrapper.java:59-64
- **证据**：全仓 grep `toNbt()` 仅命中 TaskInstance.toNbt 与 wrapper 自身定义；无任何外部调用 SRETrainTaskWrapper.toNbt()。
- **影响**：永不调用方法，维护负担与误读风险（读者以为 NBT 序列化经此路径）。
- **方向**：确认无外部使用后删除，或明确标注其调用方。

#### S6-006 [S2][死逻辑] TaskEnumHelper.isCustomTaskSupported 为死代码
- **文件**：com/habitrain/core/game/sre/TaskEnumHelper.java:30-32
- **证据**：全仓 grep `isCustomTaskSupported` 仅命中其定义处，无调用点。
- **影响**：公共静态方法暴露但无人使用，API 表面积虚增。
- **方向**：删除或纳入降级判定路径（如 S6-004 的回退逻辑可改用此方法）。

#### S6-007 [S2][死逻辑] MinigameRewardMixin 捕获字段与 HEAD 注入为死代码
- **文件**：com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java:22-23
- **证据**：字段 habitrain$capturedMinigameId/habiTrain$capturedPlayer 仅在 L48-49 被赋值，全类无读取；RETURN 注入 applyHabiRewards (L57) 使用方法形参而非捕获字段。
- **影响**：HEAD 捕获注入与其字段无作用，徒增每次小游戏完成一次字段写与注入开销，误导维护者以为存在跨注入状态传递。
- **方向**：删除捕获字段与 HEAD 注入，仅保留 RETURN 注入。

#### S6-008 [S2][死逻辑] habiTrain$overrideTokenReward 为空透传 ModifyArg
- **文件**：com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java:25-37
- **证据**：@ModifyArg 方法体 `return originalReward;` 直接回传原值未做修改。注释 L36 说明“默认不替换”。
- **影响**：无副作用的 ModifyArg 仍参与 mixin 编织，属冗余注入痕迹（疑似历史 fix 残留——近期 commit “stop zeroing minigame token rewards by default” 移除归零逻辑但注入骨架留下）。
- **方向**：若确无替换需求则移除该 ModifyArg 注入；若保留为扩展点则加注释明确意图。

#### S6-009 [S2][标识] currentIsFakeTask 冗余赋值与 isParallelCall 语义错位
- **文件**：com/habitrain/core/game/sre/FactionFilter.java:27-45
- **证据**：L27 `boolean isParallelCall = hasActiveTasks;` 变量名“并行调用”但取值是“玩家已有活跃任务”。L30 currentIsFakeTask=false 初值，L45 else 分支再次 currentIsFakeTask=false（与初值相同，冗余）。
- **影响**：命名与真实语义不符，易误读；冗余赋值掩盖分支意图。
- **方向**：重命名 isParallelCall 以反映其推导来源（如 hasExistingTask），或修正其语义；移除 L45 冗余赋值。

#### S6-010 [S2][耦合] 强 @Shadow 耦合 SRE DLC 内部字段
- **文件**：com/habitrain/core/game/sre/mixin/SREPlayerTaskComponentMixin.java:29-44
- **证据**：SREPlayerTaskComponentMixin @Shadow：player(L30)、parallelTaskGenerated(L33)、playerMoodComponent(L36)、tasks(L39)、generateParallelTask()(L42)。GenerateTaskMixin @Shadow：player(L63)、tasks(L64)、timesGotten(L65)、playerMoodComponent(L66)、getDisabledTasks()(L69)、getEnabledSceneTasks()(L74)、createTaskInstance()(L80)。
- **影响**：API mod 对外部 DLC (StarRailExpress) 的私有/包级内部实现深度依赖；DLC 重命名/改签名/改字段可见性任一项即破坏本 mod，升级面脆弱。
- **方向**：评估可经 SRE 公共 API 替代的 @Shadow，收敛到接口契约；对必须 Shadow 的内部字段集中到一处并标注版本兼容矩阵。

#### S6-011 [S3][死逻辑] 重复 Javadoc 注释块
- **文件**：com/habitrain/core/game/sre/SREGameModeBase.java:158-165
- **证据**：L158-161 与 L162-165 是两段几乎相同的 `/** 检查服务器上当前是否有任何 SRE 对局正在运行 ... */` Javadoc，连续出现在 isAnySreGameRunning 之前。
- **影响**：注释痕迹冗余，易让维护者误以为有两个方法。
- **方向**：删除重复的一段注释。

#### S6-012 [S3][死逻辑] isAnySreGameRunning/isAnySreGameStartingOrRunning 静默吞异常
- **文件**：com/habitrain/core/game/sre/SREGameModeBase.java:166-196
- **证据**：两方法对 SREGameWorldComponent.KEY.get(level) 包裹 try{...}catch(Exception ignored){}（L173, L193）。
- **影响**：若 DLC 组件访问异常，方法静默返回 false，可能让本应排队/不排队的玩家误判，且无日志便于排查。
- **方向**：至少 debug 级日志记录异常，或明确注释为何吞异常。

#### S6-013 [S3][性能] eat/drink typeId 二次全量遍历注册表
- **文件**：com/habitrain/core/game/sre/mixin/MapScannerMixin.java:64-122
- **证据**：L64 已遍历 TaskRegistry.getAll() 构建 blockToTypeIds；L119-122 再次遍历同一注册表仅为取 blackout_eat/blackout_drink 的 blockTypeId。
- **影响**：扫描路径（非每 tick，但每次地图扫描/加载）做两次全注册表遍历；可合并减少常数开销。
- **方向**：在首循环 (L64-91) 同时记录 eat/drink typeId，删除 L119-122 的第二次遍历。

#### S6-014 [S3][标识] SREWeatherController 静态可变状态 + 降雨参数魔法数字
- **文件**：com/habitrain/core/game/sre/SREWeatherController.java:16-22
- **证据**：forcedRainByLowPlayers(L16)、tickCounter(L17) 静态可变；魔法数字 CHECK_INTERVAL=20(L19)、MIN_PLAYERS=8(L20)、RAIN_DURATION_TICKS=20*60*10(L21)、CLEAR_DURATION_TICKS=20*60(L22) 硬编码无配置入口。
- **影响**：阈值/时长不可配置，调参需改代码重编译；静态状态使多世界/多对局场景难以独立控制降雨。
- **方向**：将阈值与时长抽为可配置项；状态按 level 维度而非全局静态管理。

#### S6-015 [S3][标识] 任务 ID 字符串硬编码重复
- **文件**：com/habitrain/core/game/sre/mixin/BlackoutEatMixin.java:32-47
- **证据**：“habitrain_core:blackout_eat”、“habitrain_core:blackout_drink” 在 BlackoutEatMixin L32/L39 与 BlackoutDrinkItemMixin L47 重复硬编码字符串字面量，未与已有 BLACKOUT_* 常量复用。
- **影响**：任务 ID 字符串散落多处，改名需多处同步修改，易遗漏。
- **方向**：提取为共享常量，统一引用。


### 切片 S7
覆盖文件 20 个。发现 11 条。

#### S7-001 [S1][死逻辑] BlackoutSheriffVoteManager 整套警长投票功能为死代码
- **文件**：game/blackout/BlackoutSheriffVoteManager.java:78-169
- **证据**：startVote 为 private 且全仓库无调用方；state.active 只能由 startVote 置 true 故永为 false。tickSecond(L78) 从未被 coordinator 调用（tick coordinator L68 只调 BlackoutExileVoteManager.tickSecond）。castVote(L98) 入口 if(state==null||!state.active) return false 恒返回 false。
- **影响**：客户端 BlackoutSheriffVoteCastPayload 接收器(HabiTrainCore.java:308-316)把玩家投票转给 castVote，但 castVote 恒返回 false，票被静默丢弃；警长由投票选出 + 左轮奖励 + 200 金奖励链路永不触发。功能缺失但 payload 仍注册，属无效路径。
- **方向**：决定彻底移除该 manager+resolver+broadcaster+payload，或重新接通触发入口（电话雇佣已替代则应删除残留）。

#### S7-002 [S1][死逻辑] BlackoutSheriffResolver.applyVoteResult 永不调用，警长投票结算链断裂
- **文件**：game/blackout/BlackoutSheriffResolver.java:21
- **证据**：applyVoteResult 全仓库无调用方（grep 仅命中定义与类型引用）。BlackoutMode 持有 sheriffResolver 字段(BlackoutMode.java:43)并注入 tickCoordinator，但 tickCoordinator 从未调用 sheriffResolver 任何方法。
- **影响**：即便 S7-001 被接通，结算结果也无消费者；sheriffResolver 为死对象，左轮发放/警察转职/200 金奖励逻辑悬空。
- **方向**：与 S7-001 一并处理；保留则接通 resolver 调用，否则删除。

#### S7-003 [S2][死逻辑] BlackoutTimerSystem.onTimeWarning 回调恒为空，60 秒倒计时警告永不触发
- **文件**：game/blackout/BlackoutTimerSystem.java:72-75,48
- **证据**：BlackoutMode.onPreStart 传入 () -> {} 作为 timeWarningCb(BlackoutMode.java:87)。tickSecond 在 totalTimeRemaining<=60 时 s.onTimeWarning.run()(L74)，但回调体为空。
- **影响**：剩余 60 秒时无任何客户端提示，玩家不知时间将尽；字段名与注释暗示应有警告广播，实际不产生效果。
- **方向**：删除 onTimeWarning 字段与对应分支，或在 onPreStart 传入真实广播回调。

#### S7-004 [S2][死逻辑] BlackoutTickCoordinator.onSreGameStarted/onSreGameEnded 未被调用
- **文件**：game/blackout/BlackoutTickCoordinator.java:25-31
- **证据**：onSreGameStarted(ServerLevel) 与 onSreGameEnded(ServerLevel) 全仓库无调用方（grep 仅命中定义）。它们只设置 cachedSreActive，而 tick() 内部已自行每 20 tick 重新探测 cachedSreActive，故这两个方法不影响行为。
- **影响**：死方法，维护者可能误以为有事件驱动入口；实际状态完全由 tick 轮询驱动。
- **方向**：删除这两个未用方法。

#### S7-005 [S2][死逻辑] BlackoutRoleManager 多个 public 方法无外部调用方
- **文件**：game/blackout/BlackoutRoleManager.java:65,158,190,200
- **证据**：getRoleId(L65)、getAllSheriffs(L158)、getRandomGoodNonSheriff(Random)(L190 单参仅转发)、getRandomGoodNonSheriff(Random,UUID)(L200) 全仓库无调用方；电话雇佣实际用 getRandomHireTarget(L220)。
- **影响**：公共 API 表面膨胀，含未用候选选择逻辑（含分支与 null 处理），增加误用风险与维护负担。
- **方向**：删除未用 public 方法或降为 private；getRandomGoodNonSheriff 与 getRandomHireTarget 语义重叠需厘清保留哪一个。

#### S7-006 [S2][死逻辑] SheriffVoteBroadcaster.resetCache 及 BlackoutTimerSystem 多个 getter 未被引用
- **文件**：game/blackout/BlackoutTimerSystem.java:206-211
- **证据**：SheriffVoteBroadcaster.resetCache(L64) 无调用方。isTransientBlackoutActive(L206)、isInMaintenance(L207)、getInitialBlackoutCD(L210)、getInitialMaintenanceDuration(L211) 全仓库无外部调用方（grep 仅命中定义）。
- **影响**：死代码；SheriffVoteBroadcaster 整体仅供 S7-001 死代码使用，随之一并成为死类。
- **方向**：随 S7-001 一并清理；未用 getter 删除。

#### S7-007 [S2][耦合] BlackoutMode 静态可变状态 lastWinningFaction + 上帝类编排倾向
- **文件**：game/blackout/BlackoutMode.java:47,41-45
- **证据**：private static volatile BlackoutRoleManager.Faction lastWinningFaction(L47) 为跨实例静态可变状态，由实例方法写、静态 getLastWinningFaction() 读。BlackoutMode 同时持有 syncManager/victoryChecker/sheriffResolver/tickCoordinator 四个协调器字段并直接编排其 onPreStart/onEnd/onCleanup(L75-175)。
- **影响**：静态可变状态在多 level/多对局并发时存在被覆盖风险；协调器编排逻辑集中导致 BlackoutMode 职责过载。
- **方向**：评估 lastWinningFaction 是否应随对局状态对象化而非 static；将生命周期编排拆出独立 controller。

#### S7-008 [S2][耦合] 三处重复的 street_phone/horn 方块静态缓存
- **文件**：game/blackout/BlackoutPhoneHandler.java:24-31
- **证据**：BlackoutPhoneHandler.cachedStreetPhone 与 BlackoutOverlayTypes.cachedStreetPhone 是同一 yuushya:street_phone 方块的两份独立静态缓存，逻辑相同（null/AIR 时重新查 BuiltInRegistries）。BlackoutHornVoteHandler.cachedHorn 同模式缓存 trainmurdermystery:horn。
- **影响**：三份独立静态可变缓存，同一方块 ID 多处缓存易出现不一致/失效认知；命名空间硬编码散落多处。
- **方向**：统一到 BlackoutOverlayTypes 或一个 BlockCache 工具，避免重复静态缓存。

#### S7-009 [S3][标识] transientBlackoutTicks 命名为 ticks 实为秒，语义混淆
- **文件**：game/blackout/BlackoutTimerSystem.java:23,44,80-84,134
- **证据**：TRANSIENT_TICKS=140(L23) 与 transientBlackoutTicks(L44) 命名为 ticks，但唯一递减点在 tickSecond(L80)，该方法每秒调用一次，故实际持续 140 秒而非 140 tick；广播称短暂停电(L135)。
- **影响**：字段名暗示 tick 单位会误导维护者按 20/tick 推算时长；魔法数字 140 无单位语义注释。
- **方向**：重命名为 transientBlackoutSeconds 或在常量上加单位注释。

#### S7-010 [S3][标识] BlackoutShopCatalog *_KEY 常量仅在本类 record 构造内引用
- **文件**：game/blackout/BlackoutShopCatalog.java:8,13,38,52,67,82,97
- **证据**：REVOLVER_KEY/HANDCUFFS_KEY/KILLER_REVOLVER_KEY/ACID_BUCKET_KEY/KNIFE_KEY/LOCKPICK_KEY/PSYCHO_MODE_KEY 各 public static final String 仅在同文件 record 构造(L19,29,43,...)中被引用，外部全用 .key() record 访问器。
- **影响**：暴露的 KEY 常量无外部消费者，公共表面冗余；中英混用（KEY 英文、NAME 中文）。
- **方向**：KEY 常量降为 private 或直接内联到 record 构造。

#### S7-011 [S3][性能] forceAssignRestorePowerToAllGood 对每名玩家重复 getPlayerList.getPlayer
- **文件**：game/blackout/BlackoutVictoryChecker.java:122-166
- **证据**：forceAssignRestorePowerToAllGood 循环内对每个 uuid 两次调用 level.getServer().getPlayerList().getPlayer(uuid)（L129 与 L145）。
- **影响**：触发于停电阶段转换（非每 tick 热路径），玩家数 N 时多 N 次 O(1) 查找，影响轻微。
- **方向**：复用同一 ServerPlayer 引用。


### 切片 S8
覆盖文件 24 个。发现 9 条。

#### S8-001 [S1][死逻辑] syncCompletion 链式递归致时间影响被重复施加 + O(N²) 扫描
- **文件**：game/blackout/task/SupplyTaskSyncHelper.java:38
- **证据**：syncCompletion 对每个未完成同任务 GOOD 玩家执行 setFulfilled(true) 后调用 task.getDefinition().onComplete(other, task)（56-58行）。repair_wiring/maintain_power/add_coal 的 onComplete 内部再次调用 SupplyTaskSyncHelper.syncCompletion（RepairWiringTask:54, MaintainPowerTask:37, AddCoalTask:65）。被同步玩家的 onComplete 还调用 BlackoutTaskHelper.applyTimeImpact(level, ...)（MaintainPowerTask:34 等）。无任何去重守卫（对比 RestorePowerTask:42 用 isRestoreCompleted 早返回）。
- **影响**：N 个 GOOD 玩家同时做同一供电池任务时，第一名完成级联触发其余 N-1 名 onComplete，每名 onComplete 都对全局共享计时器再施加一次 delta（maintain_power +80s × N = 5 人 +400s），严重破坏供电平衡；递归链每层遍历全存活玩家总开销 O(N²)。
- **方向**：在 syncCompletion/onComplete 路径引入'时间影响只由原始完成者施加一次'的去重守卫（类比 RestorePowerTask 的 isRestoreCompleted 守卫），将同步完成与时间影响施加解耦

#### S8-002 [S1][性能] BlackoutLookMyEyesTask.onTick 每 tick 遍历全服玩家做向量计算
- **文件**：game/blackout/task/BlackoutLookMyEyesTask.java:30
- **证据**：onTick 每 tick 执行 for (ServerPlayer otherPlayer : serverPlayer.serverLevel().players()) 遍历全服玩家，对每个 otherPlayer 计算 toOther.length()/normalize()/dot()（39-52行）再用 distance>3.0 过滤，无 AABB 预过滤、无计数节流。对比 BuiltinTaskRegistrar.java:199-208 同名任务使用 getEntitiesOfClass(ServerPlayer.class, searchBox, ...) 做 3 米 AABB 预过滤。
- **影响**：持有该任务的每名玩家每 tick 遍历全服玩家做向量运算；M 名同时持有则每 tick O(M×N) 向量运算+多个 Vec3 分配。热路径确定性触发，是 BuiltinTaskRegistrar.look_my_eyes 的劣化复制。
- **方向**：改用 AABB 范围查询（getEntitiesOfClass 配 3 米盒）替代全服遍历，与 BuiltinTaskRegistrar.look_my_eyes 对齐

#### S8-003 [S2][性能] AddCoalTask.onTick 每 tick 全背包线性扫描
- **文件**：game/blackout/task/AddCoalTask.java:46
- **证据**：onTick 在 progress==GENERATOR_PHASE 时每 tick 调用 hasPlayerCoal(player)（48行），该方法 for(i=0;i<getContainerSize();i++) 遍历整个背包检查 stack.is(Items.COAL)（81-89行），最多 ~41 槽。
- **影响**：玩家处于 GENERATOR_PHASE（持煤炭走向发电机期间，可能持续数十秒）时每 tick 全背包线性扫描，热路径每 tick 迭代。
- **方向**：将'是否仍持有煤炭'改为事件驱动（监听背包变更或仅在右键发电机时校验），避免每 tick 扫背包

#### S8-004 [S2][死逻辑] BlackoutEatHandler/BlackoutDrinkHandler 全套死代码（空 register + 永不写入的 Map）
- **文件**：game/blackout/task/BlackoutEatHandler.java:7
- **证据**：register() 方法体为空（EatHandler:11-13, DrinkHandler:11-13）；eatingTracked/drinkingTracked 静态 Map 仅在 clearState/clearAll 中 remove/clear，全仓 grep 无任何 put/写入口。进食/喝水完成实际由 game/sre/mixin/BlackoutEatMixin.java:32-44 与 BlackoutDrinkItemMixin 直接 setProgress 完成。两个 Task.onRemove 仍调用 BlackoutEatHandler.clearState/BlackoutDrinkHandler.clearState（BlackoutEatTask:33, BlackoutDrinkTask:33）。
- **影响**：两个 Handler 类、两个静态 Map、clearState/clearAll/register 全为无效代码；GameLifecycleHandler 与 Task.onRemove 的调用也无任何效果，徒增维护者理解成本（误以为存在状态追踪）。
- **方向**：删除 BlackoutEatHandler/BlackoutDrinkHandler 两个类，移除各 Task.onRemove 与 GameLifecycleHandler 中对它们的调用；如未来确需状态追踪再恢复

#### S8-005 [S2][死逻辑] BlackoutTaskHelper.advanceOnLook（及 resolveTargets）永不被调用
- **文件**：game/blackout/task/BlackoutTaskHelper.java:106
- **证据**：advanceOnLook(Player, TaskInstance)（106-142行）全仓 grep 无任何调用点；私有 resolveTargets（144-166行）仅供 advanceOnLook 使用。BlackoutPetCatTask.onTick（BlackoutPetCatTask:38-73）内联了自己的 raytrace 逻辑，未用此 helper。
- **影响**：无效方法+无效私有 helper 留存于共享工具类，误导维护者以为存在统一注视推进机制。
- **方向**：删除 advanceOnLook 与 resolveTargets；如需统一注视检测再在调用方显式引用

#### S8-006 [S3][死逻辑] MaintainPowerHandler.tickCheck 空桩 + MaintainPowerTask.onTick 空转
- **文件**：game/blackout/task/MaintainPowerHandler.java:93
- **证据**：MaintainPowerHandler.tickCheck(Player, TaskInstance) 方法体为空（93-94行），MaintainPowerTask.onTick 仍调用该空桩（MaintainPowerTask:29）。
- **影响**：每 tick 触发一次空方法调用无任何作用，徒增阅读歧义（看似有 tick 检查实则无）。
- **方向**：删除空 tickCheck 与 MaintainPowerTask 的 onTick 钩子，或彻底移除该 onTick 注册

#### S8-007 [S3][标识] AddCoalHandler 阶段0 发煤延迟到缓慢结束，与 Javadoc 不一致
- **文件**：game/blackout/task/AddCoalHandler.java:67
- **证据**：类 Javadoc 称'阶段0：右键煤炭块 → 给缓慢III(6秒) + 发放 1 个煤炭 → 进入阶段1'（35-36行），但实际 onUseBlock 阶段0 分支仅 giveSlow + 设 slowUntilTick（130-140行），发煤与推进 GENERATOR_PHASE 发生在 END_SERVER_TICK 的 slowUntilTick<=tick 分支（71-84行，6 秒后）。
- **影响**：玩家右键煤炭块后 6 秒内背包无煤炭，与文档承诺不符，GUI/任务提示可能误导。
- **方向**：校准文档与实现一致，或将发煤提前到右键瞬间并明确语义

#### S8-008 [S3][标识] SREBlackoutGameMode 构造硬编码魔法数字 10/1
- **文件**：game/blackout/sre/SREBlackoutGameMode.java:38
- **证据**：super(MODE_ID, 10, 1) 中 10（最小玩家数）与 1（疑似杀手数下限）为裸字面量，无命名常量或注释解释含义。
- **影响**：魔法数字降低可读性，含义需读父类 SREMurderGameMode 才能推断。
- **方向**：抽取为具名常量并注释语义，与父类 SREMurderGameMode 形参对齐

#### S8-009 [S3][耦合] RestorePowerHandler.restoreCompleted 为跨局共享静态布尔
- **文件**：game/blackout/task/RestorePowerHandler.java:32
- **证据**：private static boolean restoreCompleted 为类级静态字段（32行），依赖 BlackoutVictoryChecker.resetCompleted()（BlackoutVictoryChecker:112）在局末复位。
- **影响**：多局/多维度并行或复位路径遗漏时状态会跨局泄漏，影响后续对局恢复供电判定。
- **方向**：将'是否已恢复供电'收敛进按 level 隔离的状态对象（如 BlackoutTimerSystem 的 TimerState），消除跨局静态状态


### 切片 S9
覆盖文件 19 个。发现 12 条。

#### S9-001 [S1][性能] 每帧每块分配 Color 对象（渲染热路径）
- **文件**：com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java:358
- **证据**：renderConstantOverlaysIfBlackout(343-364) 在 for 循环内对每个街机电话方块每帧执行 renderCustomOverlay(context, pos, new java.awt.Color(0xFFFFD700, true), 5.0f)（358），未复用常量 Color；该方法在生存+停电分支(264)与旁观分支(232)每帧各调用一次。
- **影响**：停电模式进行中、地图存在多个 street_phone 方块时，每帧为每个该类方块分配一个 java.awt.Color 对象，进入渲染热路径，方块越多 GC 压力越大。
- **方向**：将金色 Color 提升为 static final 常量复用，避免每帧分配。

#### S9-002 [S1][死逻辑] invalidateGameRunningCache 永不调用（死代码）
- **文件**：com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java:172
- **证据**：private static void invalidateGameRunningCache()（172-175）定义并注释"在 SRE 游戏开始/结束事件时调用"，但全仓库 grep 仅命中定义处，无任何调用点；isGameRunning 缓存仅靠 500ms TTL 自然过期（167-168）。
- **影响**：游戏开始/结束瞬间的缓存状态最多滞后 500ms，注释承诺的强制失效不会发生；同时为误导性死代码。
- **方向**：要么在游戏开始/结束事件处接线调用该方法，要么删除该死方法与其注释承诺。

#### S9-003 [S1][耦合] 12 个 client mixin 全部 required=true，任一 SRE 目标缺失即阻断客户端启动
- **文件**：com/habitrain/core/client/mixin/FixTaskRendererMixin.java:26
- **证据**：resources/habitrain_core.client.mixins.json 设 "required": true；12 个 client mixin 几乎全部 @Mixin 外部模组类（SRE SubtitleHUD/TimeRenderer/SREClient/PlayerBodyEntity/LimitedInventoryScreen/InstinctRenderer、noellesroles TaskBlockOverlayRenderer、FixTaskRendererMixin 两内部类 targets）。FixTaskRendererMixin(22-29) 与 StarRailExpressTitleScreenMixin 大量 @Shadow 私有字段，任一字段/类被上游重命名即应用失败。仅 PlayerBodyEntityMixin 显式 required:false 缓解。
- **影响**：SRE/noellesroles 升级重命名任一目标类或字段时，required:true 下整个客户端 mixin 阶段失败，客户端启动崩溃且无降级路径。
- **方向**：对脆弱目标（@Shadow 私有字段、内部类 targets）评估 require=0 降级，或将 mixins.json required 改 false 以允许非关键 mixin 失败降级。

#### S9-004 [S2][性能] 渲染热路径 keySet 多次迭代 + Block 缓存未命中回退 getBlockState
- **文件**：com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java:293
- **证据**：主循环(293-303)与旁观循环(438-449)对 CustomTaskBlockCache.keySet() 迭代，每位置 get(pos)+getBlockAt(pos)，未命中时 level.getBlockState(pos).getBlock()；renderConstantOverlaysIfBlackout 在生存主流程被调用两次(250、264)重复迭代同一 keySet(352)。
- **影响**：缓存方块数较多时每帧多次遍历 keySet + 每位置 Block 查询；renderConstantOverlaysIfBlackout 在生存路径重复调用造成双倍遍历。
- **方向**：合并常量透视与任务透视为单次 keySet 迭代；评估缓存命中 Block 缺失时是否仍需 getBlockState 回退。

#### S9-005 [S2][死逻辑] blockTypeId==12 守卫与 <12 守卫等价冗余
- **文件**：com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java:251
- **证据**：生存分支 251-253 与多人分支 270-272 均为 if (blockTypeId < 12) return; if (blockTypeId == 12) return; —— <12 与 ==12 串联等价于 <=12，第二守卫不增加新信息。
- **影响**：冗余分支降低可读性，维护者易误以为 12 有特殊语义（实际与 0-11 同处理）。
- **方向**：合并为 if (blockTypeId <= 12) return; 并补注释说明 12 排除原因。

#### S9-006 [S2][标识] 硬编码魔法数字 12/18 位置常量
- **文件**：com/habitrain/core/client/mixin/SubtitleHUDPrefixFixMixin.java:26
- **证据**：new SubtitleEntry(normalizedMain, subText, durationTicks, 12, 18, color, typewriter, screenPosition)（26-28）中 12、18 为位置/偏移常量，无命名无注释硬编码入构造。
- **影响**：维护者无法判断 12/18 含义，与已有参数 screenPosition 语义混淆；SRE 升级 SubtitleEntry 构造签名变更时难以快速定位。
- **方向**：提取为命名常量（如 SUBTITLE_OFFSET_X/Y）并注释含义。

#### S9-007 [S2][耦合] getOverrideColors 直接返回可变内部 Map（API 泄露实现）
- **文件**：com/habitrain/core/client/InstinctColorHelper.java:24
- **证据**：getOverrideColors()（24-26）直接 return overrideColors; 暴露内部可变 HashMap；rebuildOverrides()（38）clear() 同一 Map，InstinctColorMixin(66) 在每帧 render 重定向内读取该 Map。
- **影响**：API 泄露内部可变状态，调用方可在不知情下修改缓存；当前调用顺序（HEAD rebuild 后 redirect）规避并发，但未来新增读取方可能踩到 rebuild 中途状态。
- **方向**：返回不可变视图/拷贝，或改为提供 getOverride(int type) 查询方法。

#### S9-008 [S2][耦合] HabiTrainCoreClient 上帝类职责密度与静态可变状态网
- **文件**：com/habitrain/core/client/HabiTrainCoreClient.java:58
- **证据**：onInitializeClient(58-290) 单方法内注册 9 个 S2C receiver、JOIN/DISCONNECT、tick 监测、save 回调、快捷键、HUD、4 个 blackout receiver、SRE 游戏结束事件、商店 bootstrap，并持有 5 个 static 可变字段(lastSentShaderPack/monitoringShaderPack/shaderMonitorTick/cachedIrisClass)；光影反射检测(301-330)内联。
- **影响**：单一类横跨网络/配置同步/光影监测/HUD/按键/商店初始化多职责，静态可变状态网跨 JOIN/DISCONNECT/tick 多处读写，难测试难维护。
- **方向**：拆分为多个注册器（NetworkReceivers/ShaderMonitor/HudRegistrar 等），静态状态收敛到专门 holder 类。

#### S9-009 [S2][耦合] ActiveTaskCache 双写路径无协调（NBT 同步与 payload 同步）
- **文件**：com/habitrain/core/client/mixin/HudCustomTaskMixin.java:20
- **证据**：ActiveTaskCache 有两处写入：①ActiveTaskPayload receiver(HabiTrainCoreClient 89 setActiveTask)，②HudCustomTaskMixin 拦截 SRE 同步 NBT(30 setActiveTask)；HudCustomTaskMixin 遇无 tasks 列表时早返回不清缓存(23-24)，ActiveTaskPayload clear 分支(84-85)才清。
- **影响**：同步 NBT 部分同步(无 tasks 键)时缓存保留旧值；两写入路径时序未定义，极端情况下 stale activeTaskFullId 导致渲染错误方块描边。
- **方向**：明确单一权威写入源，统一部分同步时是否清缓存的语义。

#### S9-010 [S3][标识] 原始类型 List @Shadow 字段
- **文件**：com/habitrain/core/client/mixin/StarRailExpressTitleScreenMixin.java:39
- **证据**：@Shadow private List menuEntries;（39）使用无泛型原始 List，注释写 List<MenuEntry> 说明语义但未在类型上表达。
- **影响**：失去类型安全，menuEntries.get(i) 返回 Object 需反射操作(setEntryPos 反射 setInt)，与 @Shadow 访问字段风格不一致。
- **方向**：若 SRE 上游字段有泛型则对齐；否则维持但补强注释说明。

#### S9-011 [S3][标识] getOpenVoteKey 返回未初始化时 null，调用方需判空
- **文件**：com/habitrain/core/client/BlackoutKeyHandler.java:17
- **证据**：openVoteKey 静态字段初始 null（15），getOpenVoteKey()(17-19) 直接返回；BlackoutHudOverlay(116) 在渲染期调用，若 register() 未执行则返回 null，调用方需自行判空。
- **影响**：调用顺序假设隐式(register 必须先于 HUD 使用)，缺 null 防御可能 NPE；实际初始化时序保证 register 先于 render，风险低。
- **方向**：在 getOpenVoteKey 文档注明前置条件，或返回不可变哨兵。

#### S9-012 [S3][性能] detectCurrentShaderPack 每 30 秒反射 getMethod 未缓存 Method 对象
- **文件**：com/habitrain/core/client/HabiTrainCoreClient.java:313
- **证据**：cachedIrisClass(56) 缓存了 Class，但每次调用仍 irisClass.getMethod("getIrisConfig") / irisConfig.getClass().getMethod("areShadersEnabled") / getMethod("getShaderPackName") 三次反射查找；频率每 600 tick(168 行,约 30 秒)一次。
- **影响**：非热路径，30 秒一次反射查找开销极小，属轻微。
- **方向**：可缓存 Method 对象；收益有限，低优先。


### 切片 S10
覆盖文件 15 个。发现 19 条。

#### S10-002 [S1][死逻辑] MinigameEditScreen saveBtn/resetBtn 位置在 super.render 之后才设置
- **文件**：client/gui/config/MinigameEditScreen.java:296
- **证据**：行 296 super.render 在 renderSection 之后；行 300-301 saveBtn.setX(width/2-110)/resetBtn.setX(...) 在 super.render 之后执行。saveBtn/resetBtn 经 addRenderableWidget（行140/157）注册由 super.render 绘制，故首帧用 init 时 -10000,-10000 位置绘制，后续每帧绘制上一帧刚 setX/Y 的坐标。
- **影响**：保存/重置按钮首帧画在屏外；点击命中框（super.mouseClicked 行315）与可见位置错一帧，窗口缩放时错位明显
- **方向**：把 saveBtn/resetBtn 的 setX/setY/setWidth 移到 super.render 之前

#### S10-007 [S1][性能] TaskTabScreen 渲染每帧对每任务查 ConfigManager
- **文件**：client/gui/config/TaskTabScreen.java:205
- **证据**：render 每帧：侧栏每 section 调 countEnabled（行205）内部对每 task 调 ConfigManager.getInstance().getTaskConfig（行308）；drawTaskRow 每行再调 getTaskConfig（行256）。同一屏 N 个任务每帧约 2N 次 ConfigManager 查询。
- **影响**：任务数多时（停电拆 good/bad+多分类）每帧重复 Map 查询，GUI 打开期间持续 O(N) registry 查询
- **方向**：render 一次性把当前可见 section 的 TaskConfigEntry 快照成 Map 传入，避免逐行再查

#### S10-001 [S2][死逻辑] GlobalSettingsScreen 整类死代码，功能已被 GlobalTabScreen 取代
- **文件**：client/gui/GlobalSettingsScreen.java:26
- **证据**：全仓 grep GlobalSettingsScreen 仅命中该类自身（行26、46），无 new GlobalSettingsScreen 调用点；功能已被 config/GlobalTabScreen.java（同套 MIN/MAX/STEP/DEFAULT 常量+滑块，行24-26、92-130）取代。ModMenuIntegration 仅指向 ConfigRootScreen::new。
- **影响**：282 行旧实现随构建打包却永不实例化，维护者误改旧屏以为生效
- **方向**：确认无外部入口后整体移除，或补调用方并注释与 GlobalTabScreen 关系

#### S10-003 [S2][死逻辑] MinigameEditScreen mouseClicked 内恒等式滚动 no-op
- **文件**：client/gui/config/MinigameEditScreen.java:320
- **证据**：行320 scrollOffset = Mth.clamp(scrollOffset + (my - contentTop) * 0, 0, maxScroll); 其中 (my-contentTop)*0 恒为0，clamp 退化为不变自身，不随鼠标移动改变滚动。
- **影响**：意图点击内容区跳转滚动实际不产生滚动，历史调试残留死逻辑误导维护
- **方向**：删除恒等式或改为按鼠标位置正确计算目标 scrollOffset

#### S10-004 [S2][死逻辑] SharedGuiKit.drawStatusPill 死方法且 fontWidth 参数未用
- **文件**：client/gui/config/SharedGuiKit.java:45
- **证据**：全仓 grep drawStatusPill( 仅命中定义（行45），无调用点；签名中 fontWidth 参数从未被读取，行52 注释明确'文字需在外部完成'但无外部调用方据此绘制。
- **影响**：死方法+无意义参数，状态药丸实际由各 Tab 内联 g.fill+g.drawString（TaskTabScreen 行280-281 等）重复实现
- **方向**：移除 drawStatusPill 或补全为含文字居中的真正复用方法并由各 Tab 改用

#### S10-005 [S2][死逻辑] SharedGuiKit.drawPanel 死方法
- **文件**：client/gui/config/SharedGuiKit.java:31
- **证据**：全仓 grep drawPanel( 仅命中定义（行31），无调用点；各 Tab 均内联 g.fill 绘制面板/分隔线。
- **影响**：死方法随构建保留，误导维护者以为面板绘制有统一入口
- **方向**：移除或改为各 Tab 真正复用

#### S10-006 [S2][死逻辑] LiveConfigAccess.isRemoteLocked 死方法
- **文件**：client/gui/LiveConfigAccess.java:30
- **证据**：全仓 grep isRemoteLocked 仅命中定义（行30），无调用点；权限判定全部走 canEditRemoteConfigs（ConfigRootScreen 行44 等）。
- **影响**：未使用的判定分支随构建保留，与 canEditRemoteConfigs 语义重叠易误用
- **方向**：移除该死方法，统一用 canEditRemoteConfigs

#### S10-008 [S2][性能] MinigameTabScreen 渲染每帧对每小游戏查 ConfigManager
- **文件**：client/gui/config/MinigameTabScreen.java:102
- **证据**：render 主循环每帧对每个过滤后小游戏调 ConfigManager.getInstance().getMinigameConfig(mg.id())（行102）。
- **影响**：小游戏数量增长时每帧重复 Map 查询，随列表规模线性劣化
- **方向**：同 S10-007，预取快照

#### S10-009 [S2][死逻辑] ShaderWhitelistScreen mouseClicked 回车处理为空 if 死分支
- **文件**：client/gui/ShaderWhitelistScreen.java:334
- **证据**：行334-337 if (addBox.isFocused() && button == 0) { // 如果点击了添加按钮外部，但不处理 } 条件成立方法体为空注释，不产生动作，随后 return false。
- **影响**：空分支误导维护者以为有失焦逻辑，实际 addBox 失焦靠点击其它 widget
- **方向**：移除空分支或补全失焦逻辑

#### S10-015 [S2][耦合] TaskTabScreen/MinigameTabScreen/MinigameEditScreen/GlobalTabScreen 直接依赖 ConfigManager 具体类而非经 LiveConfigAccess
- **文件**：client/gui/config/TaskTabScreen.java:10
- **证据**：四个 Tab 直接 import ConfigManager 并到处 ConfigManager.getInstance().getTaskConfig/setTaskConfig/getMinigameConfig 等（TaskTabScreen 行256/308/390；MinigameTabScreen 行102/221；MinigameEditScreen 行137/155/192；GlobalTabScreen 行46-48/69-70/81），而非经 LiveConfigAccess。LiveConfigAccess 仅管权限。
- **影响**：GUI 直接耦合 config 包具体类与单例，未来改 ConfigManager 接口或加缓存需四处改，违背 GUI 经 LiveConfigAccess 访问配置的设计意图
- **方向**：将配置读写集中到 LiveConfigAccess（或新增只读快照/写入门面），Tab 不再直引 ConfigManager

#### S10-016 [S2][耦合] MinigameEditScreen 表单逻辑重复未复用 TaskSaveController/TaskColorPicker/TaskMapFilterEditor
- **文件**：client/gui/config/MinigameEditScreen.java:195
- **证据**：MinigameEditScreen 自带 commitFields（行195-211，与 TaskSaveController.syncFields 行21-40 同逻辑）、自带 colorBtn+colorIndex 循环（行73-79/179-181，与 TaskColorPicker.cycleColor 重复）、自带 mapFilterBtn 循环（行120-126，与 TaskMapFilterEditor 重复）。TaskSaveController 已抽离但小游戏编辑未复用。
- **影响**：两套等价表单逻辑并存，数值解析/重置默认值/颜色循环任一改动需双改，复用边界未贯彻
- **方向**：MinigameEditScreen 改用 TaskSaveController/TaskColorPicker/TaskMapFilterEditor，或抽共享 MinigameSaveController 与 Task 系对齐

#### S10-017 [S2][死逻辑] MinigameEditScreen commitFields 未捕获非数字异常，与 TaskSaveController 不一致
- **文件**：client/gui/config/MinigameEditScreen.java:198
- **证据**：行198/200/202 直接 Integer.parseInt/Float.parseFloat 无 try/catch，而 setFilter 正则（行100/108/116）允许 -?\d* / -?\d*\.?\d*，可匹配 '-'、'-.' 等非法数，parse 抛异常。对照 TaskSaveController.parseNumFields（行27-40）每字段都有 try/catch。
- **影响**：用户在小游戏金币/情绪/权重框输入 '-' 或 '-.' 后点保存，抛 NumberFormatException 中断 commit，后续字段未写、配置停留旧值
- **方向**：对齐 TaskSaveController 的逐字段 try/catch，或收紧 setFilter 正则至完整数格式

#### S10-018 [S2][性能] ConfigRootScreen.init 每次重建三个子 Tab 含全量任务/小游戏列表
- **文件**：client/gui/config/ConfigRootScreen.java:48
- **证据**：init 每次被调用都 new TaskTabScreen（行51），其构造即 rebuildSections（行65）遍历 TaskRegistry.getAll() 全量分组排序（行79-95）；MinigameTabScreen 构造即 loadMinigames 调 QuestMinigames.getAll()（行53/59）。
- **影响**：每次 init 重建并重新拉全量注册表，窗口缩放/TaskEditScreen reset 时重复 O(N) 分组排序
- **方向**：子 Tab 改为按需懒建或 init 时复用已建实例仅重算布局

#### S10-010 [S3][死逻辑] ShaderWhitelistScreen 未使用 import HabiTrainCore
- **文件**：client/gui/ShaderWhitelistScreen.java:3
- **证据**：行3 import com.habitrain.core.HabiTrainCore; 全文件 grep HabiTrainCore 仅该 import 行，类体内无引用。
- **影响**：无用 import 噪声
- **方向**：移除无用 import

#### S10-011 [S3][死逻辑] ConfigRootScreen font()/isEditable() 访问器无调用
- **文件**：client/gui/config/ConfigRootScreen.java:184
- **证据**：行184 font() 与行185 isEditable() 全仓 grep 仅命中定义；子 Tab 各自持 font/editable 字段（TaskTabScreen 行40-41 等）未走 root 访问器。
- **影响**：两个公开访问器无调用者，API 表面冗余
- **方向**：移除未用访问器或让子 Tab 改用其统一访问以减少状态重复

#### S10-012 [S3][标识] TaskTabScreen/MinigameTabScreen 滚动 clamp 上限硬编码 10000
- **文件**：client/gui/config/TaskTabScreen.java:359
- **证据**：Mth.clamp(...,0,10000) 上限 10000 为魔法数字，而 render 内已算真实 maxSidebarScroll/maxContentScroll（TaskTabScreen 行214/250；MinigameTabScreen 行116）但未传给交互方法。
- **影响**：滚动拖拽/滚轮上限与实际内容高度脱节，极端高内容时 clamp 偏松，魔法数字降低可维护性
- **方向**：交互方法接收或缓存真实 maxScroll，去除 10000 字面量

#### S10-013 [S3][标识] 多处颜色/坐标魔法数字散落未走 SharedGuiKit 常量
- **文件**：client/gui/config/GlobalTabScreen.java:110
- **证据**：SharedGuiKit 已定义 BG_ROW/TEXT_PRIMARY 等，但多处仍直接写 0xFF1B3A2A/0xFF3A1B1B（启用绿/停用红底，MinigameTabScreen 行153、TaskTabScreen 行273/280）、0xFF222B36（编辑钮底 行158/285）、0xAAFF5555 系列滑块填充（GlobalTabScreen 行110-113）。
- **影响**：同一语义色在多文件硬编码，改色需多处同步，与 SharedGuiKit 常量并存易漂移
- **方向**：把启用/停用/编辑底色等高频语义色纳入 SharedGuiKit，各处改引常量

#### S10-014 [S3][性能] GlobalTabScreen 控件在 render 内懒构建且每帧 setX/Y/Width
- **文件**：client/gui/config/GlobalTabScreen.java:52
- **证据**：sheriffField/shaderBtn/mgToggleBtn/sheriffApplyBtn 在 render 内 if(x==null) 懒构建（行52-85），随后每帧 setX/setY/setWidth（行140-143/153/166）；这些控件未 addRenderableWidget 注册（GlobalTabScreen 非 Screen），靠每帧手动 render。
- **影响**：每帧重复 setX/Y/Width 冗余，控件生命周期与 Screen init 解耦无统一清理，可读性差
- **方向**：把一次性构建移到 init 期，render 仅定位

#### S10-019 [S3][标识] TaskColorPicker.cycleColor 颜色未匹配时跳 color(0)，colorBtn 文本更新分散在 render
- **文件**：client/gui/TaskColorPicker.java:98
- **证据**：行98-110 循环找当前色命中则切下一色 onSave.run() return；未命中走行108 color(0) onSave.run()。colorBtn 文本更新路径分散在 render（行62-63 重算 getColorIndex+COLOR_NAMES）而非事件回调。
- **影响**：行为可工作但状态更新路径分散，状态字段密度高易遗漏一致性
- **方向**：把 colorBtn message 更新收敛到 cycleColor 内或单一 render 路径


### 切片 S11
覆盖文件 7 个。发现 19 条。

#### S11-002 [S2][死逻辑] totalDuration 仅单调增长，跨对局不收敛导致进度条比例失真
- **文件**：client/gui/BlackoutHudOverlay.java:26-28
- **证据**：第26-28行 `if (total > totalDuration) totalDuration = total;` 仅在更大时更新，永不下调；reset 把 totalDuration 设回 300。filledW/markerX/warningX 全部基于 totalDuration 计算（第74、87、91行）
- **影响**：跨对局若总时长不同（如 600 回到 300），totalDuration 仍保留 600，进度条 elapsed/totalDuration 比例失真，标记位与警告位错位
- **方向**：每次 updateTime 直接以服务端 total 重置 totalDuration，而非仅取大值

#### S11-003 [S2][死逻辑] cachedEndTimeTick 用 0 作 sentinel 与“未设置”语义混用
- **文件**：client/gui/BlackoutHudOverlay.java:22,86-89
- **证据**：cachedEndTimeTick 默认 0；第86行用 `getLocalCountdown() > 0` 作守卫决定是否画 marker，getLocalCountdown 在 cachedEndTimeTick=0 时返回 0
- **影响**：0 值 sentinel 与“未设置”语义混用；若 cachedEndTimeTick 被设成过去时刻会画错标记
- **方向**：用单独 boolean 或 long sentinel（如 -1）显式表示未设置，与倒计时计算解耦

#### S11-004 [S2][死逻辑] maxSelections 字段写入但无读取，toggleSelection 仅支持单选
- **文件**：client/gui/BlackoutVoteState.java:14,28,60-66
- **证据**：第14行声明 maxSelections，第28行写入，全仓 grep 无 getMaxSelections 或任何读取；toggleSelection（第60-66行）只支持单选单 UUID
- **影响**：服务端下发的多选能力被静默丢弃，UI 只能单选，maxSelections>1 场景功能缺失或与设计不符
- **方向**：若设计就是单选则移除字段与 payload 字段；若需多选则 toggleSelection 按 maxSelections 限制

#### S11-005 [S3][死逻辑] getTotalSeconds/getTimerText 为死方法
- **文件**：client/gui/BlackoutSheriffVoteState.java:49,81
- **证据**：BlackoutVoteState.java:52 getTotalSeconds 定义；BlackoutSheriffVoteState.java:49 getTotalSeconds、81 getTimerText 定义。grep 全仓仅命中定义处
- **影响**：死方法/字段维护噪音
- **方向**：移除未被调用的 getter 与对应字段，或在 UI 中实际使用

#### S11-006 [S2][死逻辑] startWelcome 参数 killers/targets 未使用
- **文件**：client/gui/BlackoutWelcomeRenderer.java:24
- **证据**：第24行签名含 killers、targets；方法体第25-28行仅赋值 roleName/subtitle/goal/welcomeTime，两个参数完全未引用。HabiTrainCoreClient.java:263-265 仍传 payload.killerCount()/targetCount()
- **影响**：调用方传递的杀手/目标数信息被丢弃；若设计要求显示人数则功能缺失
- **方向**：要么使用参数渲染人数，要么删除参数与调用端多余传参

#### S11-007 [S3][死逻辑] getRoleName 死方法
- **文件**：client/gui/BlackoutWelcomeRenderer.java:33
- **证据**：第33-35行定义；grep 全仓仅命中定义处，无调用
- **影响**：死方法维护噪音
- **方向**：移除或在别处实际使用

#### S11-008 [S3][死逻辑] setVisible 死方法
- **文件**：client/gui/BlackoutHudOverlay.java:39
- **证据**：第39行定义 setVisible；grep `setVisible(` 仅命中定义，showHud 实际由 updateTime/reset 控制（第33、52行）
- **影响**：死方法；外部若以为可通过它控制可见性但实际无人调用，易误导
- **方向**：移除 setVisible 或改由实际触发点调用

#### S11-001 [S2][性能] render 每帧多次调用 getLocalCountdown 重复访问 level/getGameTime
- **文件**：client/gui/BlackoutHudOverlay.java:86-89,106-109
- **证据**：render 每帧第86、87、106、108行各调一次 getLocalCountdown()，共4次；每次 getLocalCountdown 取 Minecraft.getInstance().level + level.getGameTime()（第42-45行）
- **影响**：HUD overlay 每帧渲染（热路径），每帧 4 次重复 level/游戏时间查找，可避免的重复查找
- **方向**：在 render 入口或条件块内取一次本地值缓存为局部变量复用

#### S11-012 [S2][性能] Screen render 每帧为每行新建多个 Component 对象
- **文件**：client/gui/BlackoutVoteScreen.java:63-70,102-111
- **证据**：两 Screen render 每帧 Component.literal(title/description/timer/“票数:”+votes/“✓”/selectedSlot 等)（VoteScreen 第64、70、104、109、113行；SheriffVoteScreen 第65、72、106、113行），每候选行每帧多个 Component
- **影响**：投票期间 Screen 持续渲染，每帧 O(N) Component 分配，增加 GC 压力，候选多时尤甚
- **方向**：对静态文本（标题/描述/表头）缓存为字段，行内动态文本按需缓存

#### S11-011 [S2][死逻辑] Screen tick 不本地递减 remainingSeconds，倒计时显示依赖服务端推送频率
- **文件**：client/gui/BlackoutVoteScreen.java:40-44
- **证据**：两 Screen tick 仅在 !isActive 时关闭（VoteScreen 第41-43、SheriffVoteScreen 第42-44行），不本地递减；UI 显示的 Ns 完全依赖服务端 payload 周期推送（HabiTrainCoreClient.java:270-273/228-235）
- **影响**：倒计时显示精度依赖网络推送频率；推送稀疏时用户看到的剩余时间卡住/跳变
- **方向**：客户端本地基于 tick 递减 remainingSeconds，服务端推送做校准

#### S11-014 [S2][死逻辑] toggleSelection 选满时静默替换第一票，被替换旧票未发撤回
- **文件**：client/gui/BlackoutSheriffVoteState.java:73-78
- **证据**：第73-78行 size < sheriffCount 时 add，否则 set(0, targetId) 替换第一票；SheriffVoteScreen.java:137-144 toggle 后用 indexOf(targetId)>=0 判断是否发 cast，被替换掉的第一票此刻不在列表中，不发送撤回(slotIndex=-1)
- **影响**：选满后改选，被替换掉的旧目标票在客户端被静默移除但未向服务端发撤回，客户端与服务端投票状态不一致
- **方向**：替换场景需对被替换的旧目标发送撤回 payload，或服务端按整批覆盖

#### S11-016 [S2][死逻辑] statusText 无失败/成功回写路径，停留在正在请求
- **文件**：client/gui/BlackoutPhoneHireScreen.java:20,85,141-143
- **证据**：点击后 statusText=“正在请求...”（第85行）btn.active=false；updateState 第35行清 statusText；全仓 grep 无对 statusText 的成功/失败赋值；render 第141行显示
- **影响**：玩家点击后只看到“正在请求...”，成功/失败均无明确反馈；服务端不推送新 state 时 statusText 不会被清，停在“正在请求...”
- **方向**：增加服务端聘请结果 payload 回执，据结果设置成功/失败 statusText

#### S11-015 [S2][死逻辑] lockCountdownTicks 本地倒数与服务端不同步可能误启用按钮
- **文件**：client/gui/BlackoutPhoneHireScreen.java:23,29,48-55,100-109
- **证据**：构造时 lockCountdownTicks = unlocked?0:remainingLockSeconds*20（第29行）；tick 递减到 0 即视为解锁（第102-108行）；canHime 用 `!state.unlocked() && lockCountdownTicks > 0` 判定（第48-55行）。注释承认服务端二次校验
- **影响**：本地与服务端时钟不一致时按钮可点但请求必失败，且失败无 UI 反馈（statusText 停在“正在请求...”）
- **方向**：失败需有服务端回执驱动 statusText 更新；本地倒数只作乐观估计，禁用态以服务端为准

#### S11-018 [S3][耦合] blackoutModeActive 成为 HUD 与多个 client mixin 间共享的全局静态 flag
- **文件**：client/gui/BlackoutHudOverlay.java:18,36-37
- **证据**：blackoutModeActive 静态字段由 setBlackoutModeActive 写（HabiTrainCoreClient.java:221），由 BlackoutTimeRendererMixin.java:32、CustomTaskBlockRendererMixin.java:349 读取
- **影响**：HUD overlay 类承担客户端全局 blackout 状态职责，职责蔓延到 mixin，单一职责边界模糊
- **方向**：把 blackoutModeActive 移到专门 client state holder，HUD 只负责读+渲染

#### S11-009 [S3][标识] HUD 大量坐标/颜色/时长/phase 魔法数字散落
- **文件**：client/gui/BlackoutHudOverlay.java:15,17,20,21,65-68,70-71,77,82,88,91-92,100,104,107
- **证据**：硬编码 300（第15、20、49、54行）、60（TIME_WARNING_SECONDS）、220/11/2（第65-68行）、颜色 0x332A3642/0x88262E38/0xFF596573/0xFFFFD84B/0xFF4AC06A/0xFFFF6A6A/0xFFFFFF00、phase==2（第107行魔法 phase）
- **影响**：可维护性差，phase 数字与中英混用（“对局剩余时间”/“停电中”/“剩余供电时间”）散落代码
- **方向**：提取颜色/尺寸常量，phase 用枚举或命名常量

#### S11-013 [S3][性能] update 用 stream removeIf 清理选中，每次推送 O(sel×cand)
- **文件**：client/gui/BlackoutSheriffVoteState.java:29
- **证据**：第29行 `selectedTargetIds.removeIf(id -> candidates.stream().noneMatch(entry -> entry.playerId().equals(id)))`，对每个 selected id 遍历整个 candidates 流
- **影响**：每次 S2C 推送 O(sel × cand) 比较；量级小但每推送都跑
- **方向**：先把 candidates 的 playerId 收集成 Set 再 removeIf 查询，O(sel+cand)

#### S11-019 [S3][标识] 取消投票发 null UUID 表示弃票，语义靠注释隐式约定
- **文件**：client/gui/BlackoutVoteScreen.java:133-138
- **证据**：第137行 `PayloadSenders.sendVoteCast(getPurpose(), null)` 以 null UUID 表示弃票，注释“发送 null UUID 表示弃票”。语义在客户端靠注释承载
- **影响**：弃票协议语义散在客户端注释，易与服务端实现漂移
- **方向**：用显式方法名 sendVoteRevoke 或专用 payload 表达弃票意图

#### S11-017 [S3][耦合] Screen 直接静态耦合 BlackoutVoteState/BlackoutSheriffVoteState 具体类
- **文件**：client/gui/BlackoutVoteScreen.java:41,72,80,134
- **证据**：Screen 直接静态调用 BlackoutVoteState.isActive()/getCandidates()/isSelected()/getPurpose（第41,72,80,134行）与 BlackoutSheriffVoteState 同理（第42,74,82,137行）
- **影响**：Screen 不可复用/测试隔离；State 改签名直接影响 Screen
- **方向**：长期可经构造注入 state 视图接口；短期可接受

#### S11-010 [S2][耦合] BlackoutWelcomeRenderer 全静态可变状态，无实例隔离
- **文件**：client/gui/BlackoutWelcomeRenderer.java:17-21
- **证据**：roleName/subtitle/goal/welcomeTime 全 static mutable（第18-21行），类全静态无实例意义；与 BlackoutHudOverlay/BlackoutVoteState 同构静态可变单例网
- **影响**：全局静态状态无法测试隔离、无法多对局并行；与客户端 mixin 共享静态 flag 形成隐式耦合网
- **方向**：长期可考虑实例化状态对象经客户端上下文传递；短期至少集中到单一 client state holder


### 专项 A1-1 / A1-2（耦合/架构）
#### A1-1-001 [S1][耦合] SERVER_STOPPING 清理覆盖不全：对局中途停服残留大量 UUID-keyed 静态 Map
- **文件**：HabiTrainCore.java:208
- **证据**：HabiTrainCore.java:208-220 SERVER_STOPPING 仅遍历 level 清理 BlackoutRoleManager/BlackoutTimerSystem/BlackoutSheriffVoteManager/BlackoutShopService/BlackoutPoliceHireService/BlackoutExileVoteManager 这 6 个 per-level manager。SlownessReapplyManager.activeEntries、BetelLeafHandler.activeHarvests、BackpackSearchHandler.activeSearches、AddCoalHandler.activeStates、FurnaceExplosionHandler.activeStates/pendingExplosions、MaintainPowerHandler.activeStates、RestorePowerHandler.activeStates、BlackoutEatHandler.eatingTracked、BlackoutDrinkHandler.drinkingTracked、BlackoutHornVoteHandler.confirmWindows、EffectOwnershipTracker.ownership、TaskManager 实例 activeCustomTasks/activeFakeTasks/blackoutNextDailyPool、BetelQuestState.playerData、BackpackQuestState.completedPlayers、SREGameModeBase.pendingVoiceJoins 这些静态 Map 仅由 GameLifecycleHandler.handleGameEnd（task/GameLifecycleHandler.java:51-112）清理。handleGameEnd 由 tickGameEndCheck 的下降沿（wasGameActive && !anyGameActive，行 41-42）触发，仅在游戏活跃→非活跃的 tick 边界运行。注释（HabiTrainCore.java:204-207）声称 SERVER_STOPPING 遍历 level 清理以弥补 fabric-api 无 UNLOAD 事件，但实际只覆盖了 6 个 per-level manager，未覆盖上述 UUID-keyed 状态。
- **影响**：单机集成服务器停服时 JVM 不退出、static 字段不重置。若在游戏进行中停服（/stop、退出单人存档），handleGameEnd 不会因下降沿触发（tick 循环直接终止），这些 UUID-keyed 静态 Map 残留到下一局会话，导致下一局玩家被错误判定为已添煤/已翻找/已吃槟榔/已被 claim 效果等，状态泄漏影响功能正确性。
- **方向**：在 SERVER_STOPPING 闭环中补齐这些 manager 的 clearAll 调用（与 handleGameEnd 对齐），或改为在 GameMode.onCleanup 统一释放；确保无论对局是否走完 handleGameEnd，停服时都清理一次。

#### A1-1-002 [S1][死逻辑] Sheriff 投票系统整体死逻辑：startVote 不可达导致 castVote/tickSecond/resolve/sync 系列全部失效
- **文件**：game/blackout/BlackoutSheriffVoteManager.java:143
- **证据**：BlackoutSheriffVoteManager.java:143 private static void startVote 是唯一将 state.active 置 true 的入口（行 145），但全仓 grep `BlackoutSheriffVoteManager\.` 仅命中 reset/castVote/onPlayerRemoved/onPlayerJoined，无 startVote 调用；行 83-85 注释自承 'startVote and sheriff resolve logic are retained for potential future use but never triggered automatically'。由此 state.active 恒为 false：castVote（行 100）因 `!state.active` 永远 return false；tickSecond（行 87）因 `!state.active` 永远 return Optional.empty()，resolve（行 171）不可达；syncToPlayer(128)/syncToAll(137)/isVoteOpen(123) 全仓无调用方，为死方法。
- **影响**：整个 sheriff 投票系统（客户端 BlackoutSheriffVoteScreen/BlackoutSheriffVoteState 与服务端 BlackoutSheriffVotePayload/BlackoutSheriffVoteCastPayload）在功能上不可达：玩家按绑定键打开投票页面、castVote 永远被拒绝、tickSecond 永不结算，BlackoutSheriffResolver.applyVoteResult 永不触发。死代码占该类约一半方法。
- **方向**：若 sheriff 投票确已废弃，移除 startVote/resolve/syncToPlayer/syncToAll/isVoteOpen 及相关广播与 payload；若仍需启用，恢复 startVote 的调用入口并补状态重置。

#### A1-1-003 [S2][死逻辑] TaskManager.clearDlcTaskCounts 为死方法，dlcTaskCounts 永不按玩家/对局清理
- **文件**：task/TaskManager.java:63
- **证据**：task/TaskManager.java:63 public void clearDlcTaskCounts(UUID) 全仓 grep 仅命中定义处，无任何调用方。TaskManager.java:88 clearAllActiveTasks() 清理 activeCustomTasks/activeFakeTasks/blackoutNextDailyPool 但不含 dlcTaskCounts；dlcTaskCounts（行 51）仅在 incrementDlcTaskCount（GenerateTaskMixin.java:396 调用）累加，getDlcTaskCount（GenerateTaskMixin.java:307 读取）用于按已分配次数降权 DLC 任务。
- **影响**：dlcTaskCounts 既无对局级清理也无玩家级清理（clearDlcTaskCounts 是死方法），计数跨对局、跨玩家离线持续累积，可能使 DLC 任务权重在长会话中被过度压低；同时保留了一个永不调用的 public API 形成误导性接口。
- **方向**：明确 dlcTaskCounts 的生命周期语义（按对局重置 or 跨会话累积），若应重置则在 clearAllActiveTasks 或 GameMode.onCleanup 中清；移除 clearDlcTaskCounts 或接入调用方。

#### A1-1-004 [S2][死逻辑] EffectOwnershipTracker.clearPlayer/forceClean 为死方法且 ownership 静态 Map 无清理路径
- **文件**：misc/EffectOwnershipTracker.java:119
- **证据**：misc/EffectOwnershipTracker.java:119 public static void forceClean、行 133 public static void clearPlayer 全仓 grep 仅命中定义，无任何调用方。ownership（行 45）的清理仅依赖 release 的引用计数精细释放（GameLifecycleHandler.handleGameEnd 对 betel_quest 逐效果 release，行 60-71），玩家断线（HabiTrainCore.java:249-265 DISCONNECT）与 SERVER_STOPPING 均不调用 clearPlayer。
- **影响**：若玩家在游戏中途退出而 release 未触发（或非 betel_quest 来源未被 release），其 ownership 条目永久残留在静态 Map，跨会话累积（集成服务器），并使后续 isClaimedBy/getSources 对同 UUID 返回过期结果，可能影响效果冲突判定。
- **方向**：在玩家断线或停服清理路径接入 clearPlayer/forceClean，避免 ownership 跨会话累积；若确无需要则移除这两个 public 方法。

#### A1-1-005 [S3][标识] BlackoutMode 模式 id 与任务 Category 命名空间不一致（habitrains vs habitrain）
- **文件**：game/blackout/BlackoutMode.java:24
- **证据**：BlackoutMode.java:24 MODE_ID = "habitrains:blackout"（复数 habitrains），而行 27-30 BLACKOUT_GOOD/BAD 的 TaskCategory namespace 为 "habitrain:blackout_good"/"habitrain:blackout_bad"（单数 habitrain）；BlackoutExileVoteManager.java:32 的 death reason 用 "habitrain_core"，TaskManager.isOriginalTaskDisabled 用 "habitrain_core:" 前缀（行 146）。模组内部出现 habitrains / habitrain / habitrain_core 三种 namespace 前缀混用。
- **影响**：命名空间不一致使按 id 索引/过滤的代码（如任务全 id 比对、death reason 命名空间匹配）存在隐性错配风险，降低可维护性，排查问题时易混淆。
- **方向**：统一命名空间（确认 mod id 究竟是 habitrains 还是 habitrain），使 MODE_ID 与 TaskCategory namespace 一致。

#### A1-1-006 [S3][耦合] InstinctColorHelper.getOverrideColors 直接返回内部可变静态 Map
- **文件**：client/InstinctColorHelper.java:24
- **证据**：client/InstinctColorHelper.java:24 public static Map<Integer, Color> getOverrideColors() 直接返回内部 static final Map overrideColors（行 13）的引用，未包装为不可变视图。当前唯一调用方 InstinctColorMixin.java:66 仅 get() 读取，未修改，但 API 仍对外暴露可变内部状态。
- **影响**：任何调用方可直接 put/clear 改写全局静态颜色表，破坏单例缓存不变式；当前虽未触发，但构成 API 泄露实现的隐患。
- **方向**：getOverrideColors 返回不可变视图或防御拷贝；调用方改为只读使用。

#### A1-1-007 [S3][耦合] BetelQuestState 单例字段 instance 非 volatile，与 BackpackQuestState 实现风格不一致
- **文件**：betel/BetelQuestState.java:11
- **证据**：betel/BetelQuestState.java:11 private static BetelQuestState instance; 无 volatile 修饰，getInstance（行 23-28）为非双重检查的懒加载（仅 `if (instance == null) instance = new BetelQuestState()`，无同步）。对比同目录 task/BackpackQuestState.java:15 使用 `private static volatile ... instance` + synchronized DCL，两处单例实现风格不一致。
- **影响**：BetelQuestState.init()（HabiTrainCore.java:433 在 mod init 阶段调用）虽会预创建，但若任何客户端/非主线程路径在 init 之前访问 getInstance，存在发布不安全的可见性问题；与项目内 BackpackQuestState 的规范实现不一致，易被后续维护者误用。
- **方向**：声明为 volatile 并采用与 BackpackQuestState 一致的 DCL，或在初始化阶段一次性创建且不再懒加载。

#### A1-2-001 [S1][耦合] mixin 配置 required:true + 大量 @Shadow 外部 mod 私有字段，外部 mod 重构即崩溃
- **文件**：src/main/resources/habitrain_core.mixins.json:2
- **证据**：src/main/resources/habitrain_core.mixins.json line2 "required":true；line5-18 列出 12 个服务端 mixin，其中 GenerateTaskMixin/SREPlayerTaskComponentMixin/RoleMethodDispatcherMixin/MinigameTaskAssignmentMixin/BlackoutShopMixin 全部 @Shadow SRE 私有/包级字段或方法（如 GenerateTaskMixin line63-82 @Shadow player/tasks/timesGotten/playerMoodComponent/getDisabledTasks/getEnabledSceneTasks/createTaskInstance）。对应字段全部属于外部 mod io.wifi.starrailexpress。
- **影响**：SRE 任何一次重构重命名 SREPlayerTaskComponent 字段（player/tasks/timesGotten/playerMoodComponent/getDisabledTasks 等）或移除其私有方法，mixin 在启动期应用失败，因 required:true 直接导致游戏无法启动（崩溃），而非运行期降级。这是对外部 DLC 实现细节的强耦合，升级风险高且不可控。
- **方向**：将对外部 mod 的私有字段 @Shadow 收敛为受版本约束的 SPI 接口或改用 @Accessor/@Invoker，或在 mixin json 为高风险目标单独配置 required=false 并提供缺失降级；建立外部 mod 版本兼容矩阵随依赖升级回归。

#### A1-2-002 [S1][耦合] 客户端 mixin json 同样 required:true，@Shadow noellesroles/exmo SRE 私有字段，外部升级即客户端崩溃
- **文件**：src/main/resources/habitrain_core.client.mixins.json:2
- **证据**：habitrain_core.client.mixins.json line2 "required":true，line6-17 含 CustomTaskBlockRendererMixin（@Shadow noellesroles TaskBlockOverlayRenderer private static getCombinedAABB, line69-72）、FixTaskRendererMixin（@Shadow SRE 内部类 io.wifi.starrailexpress.client.gui.HudMoodRenderer$TaskRenderer / MoodRenderer$TaskRenderer 的 private Component text, line32-33）、StarRailExpressTitleScreenMixin（@Shadow net.exmo.sre StarRailExpressTitleScreen 的 7 个私有字段 line36-45）。其中 FixTaskRendererMixin 还在 line26-29 用字符串 targets 锁定两个内部渲染器嵌套类。
- **影响**：noellesroles 重命名 getCombinedAABB、SRE 重命名/移除 HudMoodRenderer$TaskRenderer / MoodRenderer$TaskRenderer 内部类或其 text 字段、exmo 改 StarRailExpressTitleScreen 字段名，客户端启动即崩溃。内部类（$TaskRenderer）尤其脆弱：SRE 任意重构拆分渲染器都会同时命中两个 target 失效。
- **方向**：统一 require/required 策略；对脆弱 target 提供缺失检测与启动期降级，或解耦为事件/Hook 而非反射+@Shadow 组合。

#### A1-2-003 [S2][耦合] 客户端 Mixin 直读服务端 TaskManager 单例内部 Map，跨 client↔server 域直接依赖具体实现类
- **文件**：src/main/java/com/habitrain/core/client/mixin/FixTaskRendererMixin.java:65
- **证据**：FixTaskRendererMixin line5 import com.habitrain.core.task.TaskManager；line65 TaskManager.getInstance().getFakeTask(self.getUUID())。CustomTaskBlockRendererMixin line11 import com.habitrain.core.task.TaskManager；line239-240 mgr.getActiveTask(instance.player.getUUID())。TaskManager 为服务端单例（TaskManager.java line24-37 双检锁 INSTANCE），内部 activeCustomTasks/activeFakeTasks 为服务端运行态（line41,47）。
- **影响**：客户端渲染路径直读服务端单例内部 Map：在专用服务端联机时 TaskManager.INSTANCE 在客户端 JVM 是独立且为空，逻辑无效但不致崩；在单机集成服务器下两边共享同一单例，客户端渲染线程读服务端主线程写的 ConcurrentHashMap，存在跨线程可见性与语义混用，且使客户端渲染强依赖服务端实现类，客户端/服务端职责边界被打破。
- **方向**：客户端渲染只应依赖客户端缓存（ActiveTaskCache）或新增只读视图 API，不应直读服务端单例的内部 Map；为单机/联机提供统一抽象。

#### A1-2-004 [S2][耦合] HabiTrainCoreClient 上帝类：注册 + 状态重置 + 光影反射监测 + HUD 多职责混杂，含静态可变状态
- **文件**：src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java:58
- **证据**：HabiTrainCoreClient.java 332 行单类承担：S2C 接收器注册（line67-283，含 TaskConfig/ActiveTask/CustomTaskBlock/Shader/FullConfig/Timer/SheriffVote/PhoneOpen/Announce/Vote 等 10+ payload）、光影包反射检测（detectCurrentShaderPack line301-330）、tick 轮询监测（line160-176）、配置保存回调（line181-196）、快捷键/HUD 注册（line203-209）、HUD 状态重置（JOIN/DISCONNECT line128-157，OnGameFinishedClient line250-258）。类内静态可变状态 line51-56（lastSentShaderPack/monitoringShaderPack/shaderMonitorTick/cachedIrisClass）。
- **影响**：单文件职责密度过高，网络注册/光影监测/HUD/按键/状态重置混杂；静态字段跨越 JOIN/DISCONNECT 生命周期，单机模式退出对局后这些 static 不重置可能跨局残留；任一职责变更都需改这一文件，维护成本高。
- **方向**：拆分客户端初始化为按职责的独立注册器（网络接收器、HUD、按键、光影监测），state 字段下沉到各自管理类，HabiTrainCoreClient 仅做装配。

#### A1-2-005 [S2][耦合] CustomTaskBlockRendererMixin 上帝类：渲染+缓存+背包扫描+游戏态缓存+常量渲染多职责
- **文件**：src/main/java/com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java:65
- **证据**：CustomTaskBlockRendererMixin.java 466 行单类承担：RenderType 缓存（line79-100）、自定义渲染（line109-137）、游戏运行态缓存查询（isGameRunning line148-170 + volatile 静态 gameRunningCacheExpireMs/gameRunningCachedValue line148-149）、颜色映射构建缓存（buildTypeColorMap line183-207）、主渲染入口（line219-334）、常量透视方块渲染（line343-364）、添煤/熔炉阶段背包扫描缓存（hasPlayerCoal/hasPlayerRedstoneTorch line379-416 + 4 个静态字段 line371-374）、旁观模式渲染（line426-465）。
- **影响**：渲染热路径与多类缓存/背包扫描/阶段判定混在同一 mixin；类内静态可变状态（缓存过期时间、颜色版本、背包缓存）跨帧共享且无同步，多场景切换时缓存失效逻辑分散；466 行单类难以测试与演进，任一缓存策略改动牵动全类。
- **方向**：按渲染/缓存/阶段检测拆分，背包扫描下沉为独立服务；游戏运行态查询走事件失效而非自管理过期。

#### A1-2-006 [S2][耦合] HabiTrainCore 上帝类：入口装配+命令+生命周期+语音群组+betel 初始化多职责
- **文件**：src/main/java/com/habitrain/core/HabiTrainCore.java:66
- **证据**：HabiTrainCore.java 442 行单类承担：配置加载与 GameMode 注册（line80-92）、全部网络包注册（line94-108）、命令注册（registerCommands line152-192，含 /instantgroup 与 /habi_api）、生命周期事件（registerLifecycleEvents line193-362，含 SERVER_STARTED/SERVER_STOPPING/JOIN/DISCONNECT/4 个 C2S 接收器）、/instantgroup 语音群组逻辑（executeInstantGroup line364-413，直接调用 de.maxhenkel.voicechat API）、音效注册（line418-423）、betel 系统初始化（initBetelSystem line425-440）。
- **影响**：主入口类同时持有命令、生命周期分发、语音群组业务逻辑、betel 子系统装配，职责密度过高；registerLifecycleEvents 内联了大量本应属于 BlackoutManager/VoiceGroupService 的逻辑，导致 HabiTrainCore 与 voicechat/SRE 多个外部包直接耦合，任一子系统变更都需改主类。
- **方向**：将命令、生命周期事件分发、语音群组逻辑、betel 初始化拆为独立类，HabiTrainCore 仅保留入口装配。

#### A1-2-007 [S2][耦合] GenerateTaskMixin 上帝类：权重池构建+加权选择+停电轮换+DLC 跟踪多职责
- **文件**：src/main/java/com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java:37
- **证据**：GenerateTaskMixin.java 435 行单类承担：原版任务权重计算 addOriginalTasks（line132-220，含心情曲线权重调整）、DLC 任务池构建与过滤 addDlcTasks（line222-323，含自适应平衡 autoBoost、停电轮换池过滤）、加权选择 weightedSelect（line329-368）、任务实例化 instantiateTask（line370-381）、DLC 任务创建与跟踪 createAndTrackDlcTask（line389-426，含假任务槽、停电轮换标志切换）、权重读取 getEffectiveWeight（line428-434）。
- **影响**：任务池构建、权重曲线、轮换策略、任务跟踪全部塞进一个 mixin，职责密度极高；该 mixin 同时强耦合 SRE 私有字段与 blackout 业务规则，任一策略调整都需改这一注入类，且难以单元测试（依赖 SRE 实例字段）。
- **方向**：抽取独立的任务池构建/加权选择/轮换策略类，mixin 仅做注入入口与委派。

#### A1-2-008 [S2][耦合] betel↔blackout.task 跨子系统直接依赖具体类：停电任务直调 betel 状态类
- **文件**：src/main/java/com/habitrain/core/game/blackout/task/BlackoutBetelQuestTask.java:4
- **证据**：BlackoutBetelQuestTask.java line4 import com.habitrain.core.betel.BetelQuestState；line28-32 调用 BetelQuestState.markQuestAssigned/resetEatenStatus/hasPlayerEatenBetelNut。停电模式任务（blackout.task 包）直接依赖 betel 子系统的静态状态类。betel 包并不反向依赖 blackout.task（无反向 import），但 blackout.task 依赖 betel 形成单向但跨子系统的具体类耦合。
- **影响**：停电任务实现细节与槟榔子系统内部状态强绑定；若 betel 状态 API 变更（如 BetelQuestState 方法签名/语义调整），该任务静默失效（完成判定 hasPlayerEatenBetelNut 返回错误值导致任务永远无法完成或误完成），且编译期不报错因 betel 是本仓内但跨子系统的具体类。
- **方向**：通过 GameMode SPI 或事件让 betel 作为可被 blackout 使用的可选能力，停电任务不直接 import betel 内部状态类；或下沉共享状态到独立中性模块。

#### A1-2-009 [S2][耦合] BlackoutMode.onStart 直读 SRE 静态模式表与 GameUtils，sre↔blackout 双向耦合
- **文件**：src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java:98
- **证据**：BlackoutMode.onStart（line98-110）直接 import 并调用 io.wifi.starrailexpress.api.SREGameModes、cca.SREGameWorldComponent、game.GameUtils、game.GameConstants（line9-12 import），用 SREGameModes.GAME_MODES.get(blackoutModeId) 读取 SRE 静态模式表并 GameUtils.startGame 启动 SRE 对局。而 SRE 侧 mixin（GenerateTaskMixin/SREPlayerTaskComponentMixin）反向 import game.blackout.*（BlackoutMode/BlackoutRoleManager/BlackoutTimerSystem）。
- **影响**：sre↔blackout 双向直接依赖具体类：BlackoutMode 直读 SRE 内部静态模式表 GAME_MODES（实现细节），SRE mixin 又直读 blackout 静态管理器；任一侧重构（如 SREGameModes 静态表结构变更、BlackoutRoleManager API 调整）都会连锁失效，形成循环引用域。
- **方向**：以 SRE 提供的抽象 GameMode 启动接口或事件替代直读 SREGameModes 静态表与 GameUtils.startGame；明确 blackout 与 SRE 的契约边界。

#### A1-2-010 [S2][耦合] NunchuckCooldownMixin/MinigameRewardMixin 字符串 target 锁定 SRE 内部包类，无 require 缓解
- **文件**：src/main/java/com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java:17
- **证据**：MinigameRewardMixin.java line17 @Mixin(targets="io.wifi.starrailexpress.cca.SREPlayerMinigameTaskComponent", remap=false)；MinigameTaskAssignmentMixin.java line18 同样 target 字符串；NunchuckCooldownMixin.java line14 @Mixin(targets="io.wifi.starrailexpress.network.original.NunchuckHitPayload")。三个 mixin 用完全限定字符串 target 锁定 SRE 内部 cca/network.original 包内类，且 mixin json required:true（habitrain_core.mixins.json line2,11-13）。MinigameTaskAssignmentMixin 还 @Shadow 其 targetMinigameId/getPlayer（line23-25）。
- **影响**：字符串 target 对 SRE 包内类（cca.SREPlayerMinigameTaskComponent、network.original.NunchuckHitPayload）的锁定无 require 缓解；SRE 重命名这些内部类（network.original 暗示属内部包）即启动崩溃。MinigameRewardMixin 与 MinigameTaskAssignmentMixin 混入同一目标类且都 required:true，任一目标类缺失两个 mixin 同时失败。
- **方向**：评估为 SREPlayerMinigameTaskComponent 等字符串 target 单独配置 required=false 或加 @Pseudo，并提供运行期缺失降级；统一字符串 target 的降级策略。


## 5. 死逻辑交叉验证结果（A2）

候选死代码数：22

### 确认死代码表
- GameModeLifecycle.java 全类型 — grepCount=1 — 确认死
- TaskManager.getAvailableTasks (line 125) — grepCount=1 — 确认死
- SlownessReapplyManager.unregister(2参, line 49) / clearAll(ResourceKey, line 63) — grepCount=0 — 确认死
- TaskConfigEntry.getEffectiveGoldReward/getEffectiveEmotionReward/getEffectiveRefreshWeight (line 86-94) — grepCount=3 — 确认死
- ConfigStore.calculateCurrentBoost (line 259) — grepCount=1 — 确认死
- SRETrainTaskWrapper.toNbt() (line 59) — grepCount=1 — 确认死
- TaskEnumHelper.isCustomTaskSupported (line 30) — grepCount=1 — 确认死
- MinigameRewardMixin captured 字段 + HEAD 注入 (line 22-23/44-50) — grepCount=4 — 确认死
- BlackoutSheriffVoteManager 投票启动/解析子图 (tickSecond/startVote/resolve 等 line 78-169) — grepCount=0 — 确认死
- BlackoutEatHandler/BlackoutDrinkHandler 全套 (register 空+map 永不写入) — grepCount=6 — 确认死
- CustomTaskBlockRendererMixin.invalidateGameRunningCache (line 172) — grepCount=1 — 确认死
- GlobalSettingsScreen 整类 (line 26) — grepCount=2 — 确认死
- SharedGuiKit.drawPanel (line 31) / drawStatusPill (line 45) — grepCount=2 — 确认死
- LiveConfigAccess.isRemoteLocked (line 30) — grepCount=1 — 确认死
- ShaderWhitelistScreen mouseClicked 空 if 死分支 (line 334) — grepCount=1 — 确认死
- BlackoutSheriffVoteState.getTotalSeconds (line 49) / getTimerText (line 81) — grepCount=2 — 确认死
- BlackoutWelcomeRenderer.getRoleName (line 33) — grepCount=1 — 确认死
- BlackoutHudOverlay.setVisible (line 39) — grepCount=1 — 确认死
- ShaderConfigPayload decode count 无上限 (line 54) — 非死代码，payload 有 send 调用 — grepCount=4 — 仅定义未读
- CustomTaskBlockPayload decode entryCount/setCount 无上限 (line 37) — 非死代码 — grepCount=5 — 仅定义未读
- BlackoutSheriffVotePayload/BlackoutVotePayload decode size 无上限 (line 39) — 非死代码 — grepCount=8 — 仅定义未读

### 误报剔除（仍有跨包调用方）
- BlackoutSheriffVotePayload — 有 broadcastToAll/send 调用 (SheriffVoteBroadcaster:61, BlackoutSyncManager:57, BlackoutSheriffVoteManager:131/314)，非'仅注册未发送'
- BlackoutVotePayload — 有 broadcastToAll 调用 (BlackoutExileVoteManager:235)，非'仅注册未发送'
- ShaderConfigPayload — 有 sendToPlayer/broadcastToAll (HabiTrainCore:236/281)，非'仅注册未发送'
- CustomTaskBlockPayload — 有 sendToPlayer/broadcastToAll (HabiTrainCore:235, MapScannerMixin:173)，非'仅注册未发送'
- BlackoutSheriffVoteManager 整类 — reset/castVote/onPlayerJoined/onPlayerRemoved 仍被调用，仅投票启动/解析子图死
- SlownessReapplyManager 整类 — register/registerTickHandler/unregisterAllLevels/clearAll() 仍活，仅 unregister(2参)/clearAll(ResourceKey) 死

## 6. 全仓问题索引（按严重度排序）
1. [S1][死逻辑] S1-007 com/habitrain/core/BuiltinTaskRegistrar.java:194-233 — look_my_eyes onTick 每 tick AABB+getEntitiesOfClass 实体扫描与对象分配
2. [S1][死逻辑] S1-001 com/habitrain/core/ModTickHandler.java:26-35, 44 — anyGameActive 与 hasActiveGame 双布尔始终同值，冗余变量
3. [S1][死逻辑] S1-002 com/habitrain/core/api/GameModeLifecycle.java:7-17 — GameModeLifecycle 枚举全仓无任何引用，死类型
4. [S1][死逻辑] S2-001 task/TaskManager.java:51 — dlcTaskCounts 跨局不清理，计数单调累积破坏分配平衡
5. [S1][性能] S3-001 betel/BetelTickEngine.java:33-199 — tickPlayer 每 tick 对每玩家重复同步查找 registry/组件，isGameActive 每世界每 tick 也查组件，且揭晓路径 new Random()
6. [S1][性能] S6-001 com/habitrain/core/game/sre/mixin/SREPlayerTaskComponentMixin.java:127 — 每 tick 每玩家 new PerPlayerTaskTicker 热路径分配
7. [S1][性能] S6-002 com/habitrain/core/game/sre/TaskWeightCurves.java:77-96 — shouldIncludeOriginalTasks 每次任务生成重建全量 Set
8. [S1][死逻辑] S7-001 game/blackout/BlackoutSheriffVoteManager.java:78-169 — BlackoutSheriffVoteManager 整套警长投票功能为死代码
9. [S1][死逻辑] S7-002 game/blackout/BlackoutSheriffResolver.java:21 — BlackoutSheriffResolver.applyVoteResult 永不调用，警长投票结算链断裂
10. [S1][死逻辑] S8-001 game/blackout/task/SupplyTaskSyncHelper.java:38 — syncCompletion 链式递归致时间影响被重复施加 + O(N²) 扫描
11. [S1][性能] S8-002 game/blackout/task/BlackoutLookMyEyesTask.java:30 — BlackoutLookMyEyesTask.onTick 每 tick 遍历全服玩家做向量计算
12. [S1][性能] S9-001 com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java:358 — 每帧每块分配 Color 对象（渲染热路径）
13. [S1][死逻辑] S9-002 com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java:172 — invalidateGameRunningCache 永不调用（死代码）
14. [S1][耦合] S9-003 com/habitrain/core/client/mixin/FixTaskRendererMixin.java:26 — 12 个 client mixin 全部 required=true，任一 SRE 目标缺失即阻断客户端启动
15. [S1][死逻辑] S10-002 client/gui/config/MinigameEditScreen.java:296 — MinigameEditScreen saveBtn/resetBtn 位置在 super.render 之后才设置
16. [S1][性能] S10-007 client/gui/config/TaskTabScreen.java:205 — TaskTabScreen 渲染每帧对每任务查 ConfigManager
17. [S1][耦合] A1-1-001 HabiTrainCore.java:208 — SERVER_STOPPING 清理覆盖不全：对局中途停服残留大量 UUID-keyed 静态 Map
18. [S1][死逻辑] A1-1-002 game/blackout/BlackoutSheriffVoteManager.java:143 — Sheriff 投票系统整体死逻辑：startVote 不可达导致 castVote/tickSecond/resolve/sync 系列全部失效
19. [S1][耦合] A1-2-001 src/main/resources/habitrain_core.mixins.json:2 — mixin 配置 required:true + 大量 @Shadow 外部 mod 私有字段，外部 mod 重构即崩溃
20. [S1][耦合] A1-2-002 src/main/resources/habitrain_core.client.mixins.json:2 — 客户端 mixin json 同样 required:true，@Shadow noellesroles/exmo SRE 私有字段，外部升级即客户端崩溃
21. [S2][死逻辑] S1-003 com/habitrain/core/api/TaskInstance.java:34, 49-53 — grantedItems/addGrantedItem/getGrantedItems 写入从不被读取，回收走 NBT 标签
22. [S2][死逻辑] S1-004 com/habitrain/core/api/TaskInstance.java:44, 115 — getElapsedTicks 与 getCustomTaskId 无调用方
23. [S2][死逻辑] S1-005 com/habitrain/core/HabiTrainCore.java:331-334, 397, 403 — 两处空 catch 吞异常（shop.balance、conn.setGroup）
24. [S2][性能] S1-006 com/habitrain/core/api/GameModeRegistry.java:126-134 — getActiveForLevel fallback 每次新建流遍历全部注册模式
25. [S2][死逻辑] S2-002 task/TaskManager.java:125 — TaskManager.getAvailableTasks 死代码且与 TaskPoolBuilder 重复
26. [S2][性能] S2-003 task/TaskPoolBuilder.java:25 — TaskPoolBuilder.CACHE 无按 mode 失效与游戏结束清理，invalidate(String) 死代码
27. [S2][耦合] S2-006 task/GameLifecycleHandler.java:100 — GameLifecycleHandler 直接依赖 game.blackout.task 具体类
28. [S2][耦合] S2-007 task/TaskManager.java:6 — TaskManager 直接依赖 io.wifi.starrailexpress.cca 具体组件并改其 public 字段
29. [S2][性能] S3-002 betel/BetelLeafHandler.java:47-74, 119-185 — applyHarvestSlowness 与 tickHarvests 都每 tick 全量遍历 activeHarvests，存在 O(世界×任务) 冗余
30. [S2][死逻辑] S3-003 betel/BetelTickEngine.java:212-219 — clearHechengTianxiaData 公共方法全模块零调用方
31. [S2][死逻辑] S3-004 betel/BetelTickEngine.java:201-210, 244-245 — isGameActive 与 clearAddictionForPlayer 中两处空 catch 静默吞异常，无日志无降级标记
32. [S2][死逻辑] S3-005 betel/BetelQuestState.java:51-53 — setFoodRestriction / hasActiveHarvest / hasActiveHarvestInWorld 公共方法零调用方
33. [S2][耦合] S3-007 betel/BetelQuestState.java:23-28, 11-21 — getInstance 单例懒加载非线程安全，playerData 为普通 HashMap
34. [S2][标识] S3-008 betel/BetelQuestState.java:108-123 — PlayerBetelData 14 状态字段密度高，多个布尔跟踪重叠生命周期/触发态
35. [S2][耦合] S3-010 betel/BetelQuestState.java:11, 23, 95, 104-106 — betel 被 blackout.task 单向依赖，BetelQuestState 暴露为公共可变单例且对外可写
36. [S2][死逻辑] S4-001 com/habitrain/core/config/TaskConfigEntry.java:86 — TaskConfigEntry.getEffectiveGoldReward/getEffectiveEmotionReward/getEffectiveRefreshWeight 为死方法，全树零调用
37. [S2][死逻辑] S4-002 com/habitrain/core/config/TaskConfigEntry.java:22 — @Deprecated(forRemoval=true) disabledMaps 仍被 fromJson 解析填充但从不参与决策也不序列化
38. [S2][死逻辑] S4-003 com/habitrain/core/config/ConfigStore.java:259 — ConfigStore.calculateCurrentBoost 为死方法
39. [S2][耦合] S4-004 com/habitrain/core/config/ConfigManager.java:33 — client.gui/网络/混入等跨包直接依赖 ConfigManager 单例具体类，无接口隔离
40. [S2][性能] S4-005 com/habitrain/core/config/ConfigStore.java:159 — ConfigStore.buildJsonRoot 每次 save 全量遍历 TaskRegistry/QuestMinigames 并构造 JSON，每次配置变更触发
41. [S2][性能] S5-001 network/BlackoutVotePayload.java:35 — BlackoutVotePayload 每秒无变化门控全量广播（与 SheriffVoteBroadcaster 不一致）
42. [S2][死逻辑] S5-002 network/ShaderConfigPayload.java:54 — ShaderConfigPayload 解码缺少 count 上限与 len 负值保护
43. [S2][死逻辑] S5-003 network/CustomTaskBlockPayload.java:37 — CustomTaskBlockPayload 解码缺少 entryCount/setCount 上限
44. [S2][死逻辑] S5-004 network/BlackoutSheriffVotePayload.java:39 — BlackoutSheriffVotePayload / BlackoutVotePayload 解码候选列表 size 无上限
45. [S2][标识] S5-005 src/main/resources/assets/habitrain_taskapi/lang/zh_cn.json:1 — habitrain_taskapi 资源目录孤立 + 命名空间混用
46. [S2][耦合] S6-003 com/habitrain/core/game/sre/SREGameModeBase.java:32-43 — 静态可变状态网 / 单例扩散
47. [S2][死逻辑] S6-004 com/habitrain/core/game/sre/SRETrainTaskWrapper.java:51-57 — getType 在 CUSTOM 不支持时回退 SLEEP，与原版任务槽冲突
48. [S2][死逻辑] S6-005 com/habitrain/core/game/sre/SRETrainTaskWrapper.java:59-64 — SRETrainTaskWrapper.toNbt() 为死代码
49. [S2][死逻辑] S6-006 com/habitrain/core/game/sre/TaskEnumHelper.java:30-32 — TaskEnumHelper.isCustomTaskSupported 为死代码
50. [S2][死逻辑] S6-007 com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java:22-23 — MinigameRewardMixin 捕获字段与 HEAD 注入为死代码
51. [S2][死逻辑] S6-008 com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java:25-37 — habiTrain$overrideTokenReward 为空透传 ModifyArg
52. [S2][标识] S6-009 com/habitrain/core/game/sre/FactionFilter.java:27-45 — currentIsFakeTask 冗余赋值与 isParallelCall 语义错位
53. [S2][耦合] S6-010 com/habitrain/core/game/sre/mixin/SREPlayerTaskComponentMixin.java:29-44 — 强 @Shadow 耦合 SRE DLC 内部字段
54. [S2][死逻辑] S7-003 game/blackout/BlackoutTimerSystem.java:72-75,48 — BlackoutTimerSystem.onTimeWarning 回调恒为空，60 秒倒计时警告永不触发
55. [S2][死逻辑] S7-004 game/blackout/BlackoutTickCoordinator.java:25-31 — BlackoutTickCoordinator.onSreGameStarted/onSreGameEnded 未被调用
56. [S2][死逻辑] S7-005 game/blackout/BlackoutRoleManager.java:65,158,190,200 — BlackoutRoleManager 多个 public 方法无外部调用方
57. [S2][死逻辑] S7-006 game/blackout/BlackoutTimerSystem.java:206-211 — SheriffVoteBroadcaster.resetCache 及 BlackoutTimerSystem 多个 getter 未被引用
58. [S2][耦合] S7-007 game/blackout/BlackoutMode.java:47,41-45 — BlackoutMode 静态可变状态 lastWinningFaction + 上帝类编排倾向
59. [S2][耦合] S7-008 game/blackout/BlackoutPhoneHandler.java:24-31 — 三处重复的 street_phone/horn 方块静态缓存
60. [S2][性能] S8-003 game/blackout/task/AddCoalTask.java:46 — AddCoalTask.onTick 每 tick 全背包线性扫描
61. [S2][死逻辑] S8-004 game/blackout/task/BlackoutEatHandler.java:7 — BlackoutEatHandler/BlackoutDrinkHandler 全套死代码（空 register + 永不写入的 Map）
62. [S2][死逻辑] S8-005 game/blackout/task/BlackoutTaskHelper.java:106 — BlackoutTaskHelper.advanceOnLook（及 resolveTargets）永不被调用
63. [S2][性能] S9-004 com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java:293 — 渲染热路径 keySet 多次迭代 + Block 缓存未命中回退 getBlockState
64. [S2][死逻辑] S9-005 com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java:251 — blockTypeId==12 守卫与 <12 守卫等价冗余
65. [S2][标识] S9-006 com/habitrain/core/client/mixin/SubtitleHUDPrefixFixMixin.java:26 — 硬编码魔法数字 12/18 位置常量
66. [S2][耦合] S9-007 com/habitrain/core/client/InstinctColorHelper.java:24 — getOverrideColors 直接返回可变内部 Map（API 泄露实现）
67. [S2][耦合] S9-008 com/habitrain/core/client/HabiTrainCoreClient.java:58 — HabiTrainCoreClient 上帝类职责密度与静态可变状态网
68. [S2][耦合] S9-009 com/habitrain/core/client/mixin/HudCustomTaskMixin.java:20 — ActiveTaskCache 双写路径无协调（NBT 同步与 payload 同步）
69. [S2][死逻辑] S10-001 client/gui/GlobalSettingsScreen.java:26 — GlobalSettingsScreen 整类死代码，功能已被 GlobalTabScreen 取代
70. [S2][死逻辑] S10-003 client/gui/config/MinigameEditScreen.java:320 — MinigameEditScreen mouseClicked 内恒等式滚动 no-op
71. [S2][死逻辑] S10-004 client/gui/config/SharedGuiKit.java:45 — SharedGuiKit.drawStatusPill 死方法且 fontWidth 参数未用
72. [S2][死逻辑] S10-005 client/gui/config/SharedGuiKit.java:31 — SharedGuiKit.drawPanel 死方法
73. [S2][死逻辑] S10-006 client/gui/LiveConfigAccess.java:30 — LiveConfigAccess.isRemoteLocked 死方法
74. [S2][性能] S10-008 client/gui/config/MinigameTabScreen.java:102 — MinigameTabScreen 渲染每帧对每小游戏查 ConfigManager
75. [S2][死逻辑] S10-009 client/gui/ShaderWhitelistScreen.java:334 — ShaderWhitelistScreen mouseClicked 回车处理为空 if 死分支
76. [S2][耦合] S10-015 client/gui/config/TaskTabScreen.java:10 — TaskTabScreen/MinigameTabScreen/MinigameEditScreen/GlobalTabScreen 直接依赖 ConfigManager 具体类而非经 LiveConfigAccess
77. [S2][耦合] S10-016 client/gui/config/MinigameEditScreen.java:195 — MinigameEditScreen 表单逻辑重复未复用 TaskSaveController/TaskColorPicker/TaskMapFilterEditor
78. [S2][死逻辑] S10-017 client/gui/config/MinigameEditScreen.java:198 — MinigameEditScreen commitFields 未捕获非数字异常，与 TaskSaveController 不一致
79. [S2][性能] S10-018 client/gui/config/ConfigRootScreen.java:48 — ConfigRootScreen.init 每次重建三个子 Tab 含全量任务/小游戏列表
80. [S2][死逻辑] S11-002 client/gui/BlackoutHudOverlay.java:26-28 — totalDuration 仅单调增长，跨对局不收敛导致进度条比例失真
81. [S2][死逻辑] S11-003 client/gui/BlackoutHudOverlay.java:22,86-89 — cachedEndTimeTick 用 0 作 sentinel 与“未设置”语义混用
82. [S2][死逻辑] S11-004 client/gui/BlackoutVoteState.java:14,28,60-66 — maxSelections 字段写入但无读取，toggleSelection 仅支持单选
83. [S2][死逻辑] S11-006 client/gui/BlackoutWelcomeRenderer.java:24 — startWelcome 参数 killers/targets 未使用
84. [S2][性能] S11-001 client/gui/BlackoutHudOverlay.java:86-89,106-109 — render 每帧多次调用 getLocalCountdown 重复访问 level/getGameTime
85. [S2][性能] S11-012 client/gui/BlackoutVoteScreen.java:63-70,102-111 — Screen render 每帧为每行新建多个 Component 对象
86. [S2][死逻辑] S11-011 client/gui/BlackoutVoteScreen.java:40-44 — Screen tick 不本地递减 remainingSeconds，倒计时显示依赖服务端推送频率
87. [S2][死逻辑] S11-014 client/gui/BlackoutSheriffVoteState.java:73-78 — toggleSelection 选满时静默替换第一票，被替换旧票未发撤回
88. [S2][死逻辑] S11-016 client/gui/BlackoutPhoneHireScreen.java:20,85,141-143 — statusText 无失败/成功回写路径，停留在正在请求
89. [S2][死逻辑] S11-015 client/gui/BlackoutPhoneHireScreen.java:23,29,48-55,100-109 — lockCountdownTicks 本地倒数与服务端不同步可能误启用按钮
90. [S2][耦合] S11-010 client/gui/BlackoutWelcomeRenderer.java:17-21 — BlackoutWelcomeRenderer 全静态可变状态，无实例隔离
91. [S2][死逻辑] A1-1-003 task/TaskManager.java:63 — TaskManager.clearDlcTaskCounts 为死方法，dlcTaskCounts 永不按玩家/对局清理
92. [S2][死逻辑] A1-1-004 misc/EffectOwnershipTracker.java:119 — EffectOwnershipTracker.clearPlayer/forceClean 为死方法且 ownership 静态 Map 无清理路径
93. [S2][耦合] A1-2-003 src/main/java/com/habitrain/core/client/mixin/FixTaskRendererMixin.java:65 — 客户端 Mixin 直读服务端 TaskManager 单例内部 Map，跨 client↔server 域直接依赖具体实现类
94. [S2][耦合] A1-2-004 src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java:58 — HabiTrainCoreClient 上帝类：注册 + 状态重置 + 光影反射监测 + HUD 多职责混杂，含静态可变状态
95. [S2][耦合] A1-2-005 src/main/java/com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java:65 — CustomTaskBlockRendererMixin 上帝类：渲染+缓存+背包扫描+游戏态缓存+常量渲染多职责
96. [S2][耦合] A1-2-006 src/main/java/com/habitrain/core/HabiTrainCore.java:66 — HabiTrainCore 上帝类：入口装配+命令+生命周期+语音群组+betel 初始化多职责
97. [S2][耦合] A1-2-007 src/main/java/com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java:37 — GenerateTaskMixin 上帝类：权重池构建+加权选择+停电轮换+DLC 跟踪多职责
98. [S2][耦合] A1-2-008 src/main/java/com/habitrain/core/game/blackout/task/BlackoutBetelQuestTask.java:4 — betel↔blackout.task 跨子系统直接依赖具体类：停电任务直调 betel 状态类
99. [S2][耦合] A1-2-009 src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java:98 — BlackoutMode.onStart 直读 SRE 静态模式表与 GameUtils，sre↔blackout 双向耦合
100. [S2][耦合] A1-2-010 src/main/java/com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java:17 — NunchuckCooldownMixin/MinigameRewardMixin 字符串 target 锁定 SRE 内部包类，无 require 缓解
101. [S3][标识] S1-008 com/habitrain/core/LootHelper.java:31, 48, 77 — roleType 魔法数字 4/5、双节棍冷却 1000/200 未命名常量化
102. [S3][耦合] S1-009 com/habitrain/core/HabiTrainCore.java:66-442 — 主入口类 442 行职责密度过高（配置/网络/命令/生命周期/语音/槟榔/音效集中）
103. [S3][死逻辑] S2-004 task/SlownessReapplyManager.java:49 — SlownessReapplyManager.unregister 与 clearAll(ResourceKey) 死代码
104. [S3][死逻辑] S2-005 task/GameLifecycleHandler.java:27 — GameLifecycleHandler.register() 为空挂（仅日志，不注册回调）
105. [S3][标识] S2-008 task/TaskManager.java:190 — triggerDirectWin 硬编码 _win 后缀魔法字符串
106. [S3][性能] S2-009 task/BackpackSearchHandler.java:65 — BackpackSearchHandler 超时分支重复 getPlayer 查找 + 实际不可达 else 分支
107. [S3][死逻辑] S2-010 task/BackpackQuestState.java:22 — BackpackQuestState.init() 与 getInstance() DCL 并存，冗余初始化
108. [S3][死逻辑] S3-006 betel/BetelQuestState.java:114 — PlayerBetelData.lastKnownLastEatTime 只写不读
109. [S3][标识] S3-009 betel/BetelTickEngine.java:126-138, 157, 178, 182, 265 — 成瘾/戒断阈值与效果时长全为魔法数字散落多处
110. [S3][标识] S3-011 betel/BetelQuestState.java:76-84 — getCurrentServer 中局部 var instance 遮蔽静态字段 instance
111. [S3][耦合] S3-012 betel/BetelLeafHandler.java:40-41, 206-221 — 静态可变 betelLeafBlock/blockChecked 缓存无同步、无失效/重试机制
112. [S3][标识] S4-006 com/habitrain/core/config/ConfigStore.java:21 — 多个 Logger 复用同名 "ConfigManager"，中英混用
113. [S3][死逻辑] S4-007 com/habitrain/core/config/ConfigManager.java:83 — ConfigManager.getGameModeConfig 转发全树无调用方，底层 computeIfAbsent 含写副作用
114. [S3][标识] S4-008 com/habitrain/core/config/TaskConfigEntry.java:27 — TaskConfigEntry/MinigameConfigEntry 用 -1 作“未设置”哨兵，魔法值散落多处
115. [S3][死逻辑] S4-009 com/habitrain/core/config/ConfigRepository.java:22 — ConfigRepository.setTaskConfig/putTaskConfig、setMinigameConfig/putMinigameConfig 实现完全相同
116. [S3][耦合] S4-010 com/habitrain/core/config/ConfigStore.java:10 — ConfigStore/MinigameEnforcement 直接依赖 SRE DLC 具体类与字段名，无抽象隔离
117. [S3][标识] S5-006 network/BlackoutAnnouncePayload.java:21 — BlackoutAnnouncePayload 显示串上限魔法数字 32767
118. [S3][标识] S5-007 network/BlackoutVotePayload.java:36 — BlackoutVotePayload/BlackoutVoteCastPayload 的 purpose 以字面量散落比较、无枚举常量
119. [S3][死逻辑] S6-011 com/habitrain/core/game/sre/SREGameModeBase.java:158-165 — 重复 Javadoc 注释块
120. [S3][死逻辑] S6-012 com/habitrain/core/game/sre/SREGameModeBase.java:166-196 — isAnySreGameRunning/isAnySreGameStartingOrRunning 静默吞异常
121. [S3][性能] S6-013 com/habitrain/core/game/sre/mixin/MapScannerMixin.java:64-122 — eat/drink typeId 二次全量遍历注册表
122. [S3][标识] S6-014 com/habitrain/core/game/sre/SREWeatherController.java:16-22 — SREWeatherController 静态可变状态 + 降雨参数魔法数字
123. [S3][标识] S6-015 com/habitrain/core/game/sre/mixin/BlackoutEatMixin.java:32-47 — 任务 ID 字符串硬编码重复
124. [S3][标识] S7-009 game/blackout/BlackoutTimerSystem.java:23,44,80-84,134 — transientBlackoutTicks 命名为 ticks 实为秒，语义混淆
125. [S3][标识] S7-010 game/blackout/BlackoutShopCatalog.java:8,13,38,52,67,82,97 — BlackoutShopCatalog *_KEY 常量仅在本类 record 构造内引用
126. [S3][性能] S7-011 game/blackout/BlackoutVictoryChecker.java:122-166 — forceAssignRestorePowerToAllGood 对每名玩家重复 getPlayerList.getPlayer
127. [S3][死逻辑] S8-006 game/blackout/task/MaintainPowerHandler.java:93 — MaintainPowerHandler.tickCheck 空桩 + MaintainPowerTask.onTick 空转
128. [S3][标识] S8-007 game/blackout/task/AddCoalHandler.java:67 — AddCoalHandler 阶段0 发煤延迟到缓慢结束，与 Javadoc 不一致
129. [S3][标识] S8-008 game/blackout/sre/SREBlackoutGameMode.java:38 — SREBlackoutGameMode 构造硬编码魔法数字 10/1
130. [S3][耦合] S8-009 game/blackout/task/RestorePowerHandler.java:32 — RestorePowerHandler.restoreCompleted 为跨局共享静态布尔
131. [S3][标识] S9-010 com/habitrain/core/client/mixin/StarRailExpressTitleScreenMixin.java:39 — 原始类型 List @Shadow 字段
132. [S3][标识] S9-011 com/habitrain/core/client/BlackoutKeyHandler.java:17 — getOpenVoteKey 返回未初始化时 null，调用方需判空
133. [S3][性能] S9-012 com/habitrain/core/client/HabiTrainCoreClient.java:313 — detectCurrentShaderPack 每 30 秒反射 getMethod 未缓存 Method 对象
134. [S3][死逻辑] S10-010 client/gui/ShaderWhitelistScreen.java:3 — ShaderWhitelistScreen 未使用 import HabiTrainCore
135. [S3][死逻辑] S10-011 client/gui/config/ConfigRootScreen.java:184 — ConfigRootScreen font()/isEditable() 访问器无调用
136. [S3][标识] S10-012 client/gui/config/TaskTabScreen.java:359 — TaskTabScreen/MinigameTabScreen 滚动 clamp 上限硬编码 10000
137. [S3][标识] S10-013 client/gui/config/GlobalTabScreen.java:110 — 多处颜色/坐标魔法数字散落未走 SharedGuiKit 常量
138. [S3][性能] S10-014 client/gui/config/GlobalTabScreen.java:52 — GlobalTabScreen 控件在 render 内懒构建且每帧 setX/Y/Width
139. [S3][标识] S10-019 client/gui/TaskColorPicker.java:98 — TaskColorPicker.cycleColor 颜色未匹配时跳 color(0)，colorBtn 文本更新分散在 render
140. [S3][死逻辑] S11-005 client/gui/BlackoutSheriffVoteState.java:49,81 — getTotalSeconds/getTimerText 为死方法
141. [S3][死逻辑] S11-007 client/gui/BlackoutWelcomeRenderer.java:33 — getRoleName 死方法
142. [S3][死逻辑] S11-008 client/gui/BlackoutHudOverlay.java:39 — setVisible 死方法
143. [S3][耦合] S11-018 client/gui/BlackoutHudOverlay.java:18,36-37 — blackoutModeActive 成为 HUD 与多个 client mixin 间共享的全局静态 flag
144. [S3][标识] S11-009 client/gui/BlackoutHudOverlay.java:15,17,20,21,65-68,70-71,77,82,88,91-92,100,104,107 — HUD 大量坐标/颜色/时长/phase 魔法数字散落
145. [S3][性能] S11-013 client/gui/BlackoutSheriffVoteState.java:29 — update 用 stream removeIf 清理选中，每次推送 O(sel×cand)
146. [S3][标识] S11-019 client/gui/BlackoutVoteScreen.java:133-138 — 取消投票发 null UUID 表示弃票，语义靠注释隐式约定
147. [S3][耦合] S11-017 client/gui/BlackoutVoteScreen.java:41,72,80,134 — Screen 直接静态耦合 BlackoutVoteState/BlackoutSheriffVoteState 具体类
148. [S3][标识] A1-1-005 game/blackout/BlackoutMode.java:24 — BlackoutMode 模式 id 与任务 Category 命名空间不一致（habitrains vs habitrain）
149. [S3][耦合] A1-1-006 client/InstinctColorHelper.java:24 — InstinctColorHelper.getOverrideColors 直接返回内部可变静态 Map
150. [S3][耦合] A1-1-007 betel/BetelQuestState.java:11 — BetelQuestState 单例字段 instance 非 volatile，与 BackpackQuestState 实现风格不一致

## 7. 附录：MECE 覆盖与 agent 编排

### 切片覆盖
- S1: 13 文件 — com/habitrain/core/HabiTrainCore.java, com/habitrain/core/ModTickHandler.java, com/habitrain/core/BuiltinTaskRegistrar.java, com/habitrain/core/LootHelper.java, com/habitrain/core/api/GameMode.java, com/habitrain/core/api/GameModeLifecycle.java, com/habitrain/core/api/GameModeRegistry.java, com/habitrain/core/api/ItemReclaimHelper.java, com/habitrain/core/api/TaskCategory.java, com/habitrain/core/api/TaskDefinition.java, com/habitrain/core/api/TaskInstance.java, com/habitrain/core/api/TaskRegistry.java, com/habitrain/core/api/WinResult.java
- S2: 7 文件 — task/TaskManager.java, task/TaskBalancer.java, task/TaskPoolBuilder.java, task/BackpackSearchHandler.java, task/BackpackQuestState.java, task/SlownessReapplyManager.java, task/GameLifecycleHandler.java
- S3: 6 文件 — betel/BetelFoodRestriction.java, betel/BetelLeafHandler.java, betel/BetelQuestDefinition.java, betel/BetelQuestState.java, betel/BetelTickEngine.java, betel/BetelWithdrawal.java
- S4: 8 文件 — com/habitrain/core/config/ConfigManager.java, com/habitrain/core/config/ConfigRepository.java, com/habitrain/core/config/ConfigStore.java, com/habitrain/core/config/ConfigSync.java, com/habitrain/core/config/GameModeConfigScope.java, com/habitrain/core/config/MinigameConfigEntry.java, com/habitrain/core/config/MinigameEnforcement.java, com/habitrain/core/config/TaskConfigEntry.java
- S5: 18 文件 — network/ShaderConfigPayload.java, network/ShaderInfoPayload.java, network/ConfigUpdatePayload.java, network/BlackoutSheriffVotePayload.java, network/BlackoutSheriffVoteCastPayload.java, network/ActiveTaskPayload.java, network/TaskConfigPayload.java, network/CustomTaskBlockPayload.java, network/BlackoutAnnouncePayload.java, network/BlackoutTimerPayload.java, network/FullConfigSyncPayload.java, network/BlackoutPhoneOpenPayload.java, network/BlackoutHirePolicePayload.java, network/BlackoutVotePayload.java, network/BlackoutVoteCastPayload.java, client/network/PayloadSenders.java, src/main/resources/fabric.mod.json, src/main/resources/assets/habitrain_taskapi/lang/zh_cn.json
- S6: 23 文件 — com/habitrain/core/game/AbstractGameMode.java, com/habitrain/core/game/sre/SREGameModeBase.java, com/habitrain/core/game/sre/SREMurderMode.java, com/habitrain/core/game/sre/SRERepairMode.java, com/habitrain/core/game/sre/SRETrainTaskWrapper.java, com/habitrain/core/game/sre/TaskEnumHelper.java, com/habitrain/core/game/sre/FactionFilter.java, com/habitrain/core/game/sre/TaskWeightCurves.java, com/habitrain/core/game/sre/PerPlayerTaskTicker.java, com/habitrain/core/game/sre/CustomTaskBlockCache.java, com/habitrain/core/game/sre/SREWeatherController.java, com/habitrain/core/game/sre/mixin/SREPlayerTaskComponentMixin.java, com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java, com/habitrain/core/game/sre/mixin/MinigameTaskAssignmentMixin.java, com/habitrain/core/game/sre/mixin/BlackoutEatMixin.java, com/habitrain/core/game/sre/mixin/BlackoutShopMixin.java, com/habitrain/core/game/sre/mixin/BlackoutCanEatMixin.java, com/habitrain/core/game/sre/mixin/BlackoutDrinkItemMixin.java, com/habitrain/core/game/sre/mixin/ExtraEffectRoleMixin.java, com/habitrain/core/game/sre/mixin/NunchuckCooldownMixin.java, com/habitrain/core/game/sre/mixin/RoleMethodDispatcherMixin.java, com/habitrain/core/game/sre/mixin/MapScannerMixin.java, com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java
- S7: 20 文件 — game/blackout/BlackoutMode.java, game/blackout/BlackoutTickCoordinator.java, game/blackout/BlackoutTimerSystem.java, game/blackout/BlackoutSyncManager.java, game/blackout/BlackoutVictoryChecker.java, game/blackout/BlackoutRoleManager.java, game/blackout/BlackoutSheriffVoteManager.java, game/blackout/BlackoutSheriffResolver.java, game/blackout/SheriffVoteBroadcaster.java, game/blackout/BlackoutExileVoteManager.java, game/blackout/BlackoutHornVoteHandler.java, game/blackout/BlackoutPhoneHandler.java, game/blackout/BlackoutDeathHandler.java, game/blackout/BlackoutPoliceHireService.java, game/blackout/BlackoutShopService.java, game/blackout/BlackoutShopDefinition.java, game/blackout/BlackoutShopCatalog.java, game/blackout/BlackoutPsychoModeShopEntry.java, game/blackout/BlackoutRoleShopEntry.java, game/blackout/BlackoutOverlayTypes.java
- S8: 24 文件 — game/blackout/task/AddCoalTask.java, game/blackout/task/AddCoalHandler.java, game/blackout/task/RepairWiringTask.java, game/blackout/task/RepairWiringHandler.java, game/blackout/task/SabotageWiringTask.java, game/blackout/task/SabotageWiringHandler.java, game/blackout/task/FurnaceExplosionTask.java, game/blackout/task/FurnaceExplosionHandler.java, game/blackout/task/MaintainPowerTask.java, game/blackout/task/MaintainPowerHandler.java, game/blackout/task/RestorePowerTask.java, game/blackout/task/RestorePowerHandler.java, game/blackout/task/SupplyTaskSyncHelper.java, game/blackout/task/BlackoutTaskHelper.java, game/blackout/task/BlackoutEatTask.java, game/blackout/task/BlackoutEatHandler.java, game/blackout/task/BlackoutDrinkTask.java, game/blackout/task/BlackoutDrinkHandler.java, game/blackout/task/BlackoutSearchBackpackTask.java, game/blackout/task/BlackoutBetelQuestTask.java, game/blackout/task/BlackoutPetCatTask.java, game/blackout/task/BlackoutBeAloneTask.java, game/blackout/task/BlackoutLookMyEyesTask.java, game/blackout/sre/SREBlackoutGameMode.java
- S9: 19 文件 — com/habitrain/core/client/HabiTrainCoreClient.java, com/habitrain/core/client/BlackoutKeyHandler.java, com/habitrain/core/client/InstinctColorHelper.java, com/habitrain/core/client/cache/ActiveTaskCache.java, com/habitrain/core/client/network/PayloadSenders.java, com/habitrain/core/client/util/ClientSubtitleNotifier.java, com/habitrain/core/client/util/TaskTextNormalizer.java, com/habitrain/core/client/mixin/SubtitleHUDPrefixFixMixin.java, com/habitrain/core/client/mixin/StarRailExpressTitleScreenMixin.java, com/habitrain/core/client/mixin/HudCustomTaskMixin.java, com/habitrain/core/client/mixin/InstinctCacheFixMixin.java, com/habitrain/core/client/mixin/BlackoutTimeRendererMixin.java, com/habitrain/core/client/mixin/BlackoutLimitedInventoryScreenMixin.java, com/habitrain/core/client/mixin/InstinctKillerTeamMixin.java, com/habitrain/core/client/mixin/InstinctSheriffGateMixin.java, com/habitrain/core/client/mixin/FixTaskRendererMixin.java, com/habitrain/core/client/mixin/PlayerBodyEntityMixin.java, com/habitrain/core/client/mixin/InstinctColorMixin.java, com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java
- S10: 15 文件 — client/gui/config/ConfigRootScreen.java, client/gui/config/MinigameTabScreen.java, client/gui/config/GlobalTabScreen.java, client/gui/config/MinigameEditScreen.java, client/gui/config/SharedGuiKit.java, client/gui/config/TaskTabScreen.java, client/gui/ModMenuIntegration.java, client/gui/LiveConfigAccess.java, client/gui/SharedGuiConstants.java, client/gui/GlobalSettingsScreen.java, client/gui/ShaderWhitelistScreen.java, client/gui/TaskSaveController.java, client/gui/TaskColorPicker.java, client/gui/TaskMapFilterEditor.java, client/gui/TaskEditScreen.java
- S11: 7 文件 — client/gui/BlackoutHudOverlay.java, client/gui/BlackoutWelcomeRenderer.java, client/gui/BlackoutVoteScreen.java, client/gui/BlackoutVoteState.java, client/gui/BlackoutSheriffVoteScreen.java, client/gui/BlackoutSheriffVoteState.java, client/gui/BlackoutPhoneHireScreen.java

### 专项
- A1-1 静态网/单例（全仓横切，覆盖文件 32）
- A1-2 上帝类/循环/@Shadow（全仓横切，覆盖文件 21）
- A2 死逻辑交叉验证（候选 22 条，确认死 21，误报剔除 6）

### 独立性自检
- [x] 各 agent prompt 仅含源代码事实，未引用旧报告
- [x] 未访问 backup 目录
- [x] 各切片含文件覆盖确认表
- [x] A2 对每条候选附 grepCount
