# 哈比列车 API 独立审计修复设计

## 元信息

- 日期：2026-07-09
- 审计来源：`临时/2026-07-09-independent-quality-audit-report.md`（150 发现：S1=20, S2=80, S3=50）
- 基线 commit：`9f92434`
- 总文件：159 Java 文件，~18.7k 行
- 批次：7 批，每批独立构建 + 复制 JAR

## 总体策略

- **按模块分批**（用户选型）
- **先做 Batch 0 安全死代码清理**以降低后续噪音
- **上帝类拆分本次就做**，分别归属各模块批次
- **Config 接口抽取（Batch 5）在 Client GUI 批次（Batch 3）之前**，以满足依赖
- **HabiTrainCore 上帝拆分在最后（Batch 6）**，避免前面批次反复修改
- **每批构建验证**：`./gradlew clean build` + 复制 JAR 到 `临时\`

## 批次顺序

```
Batch 0 (死代码+轻量优化) ──→ 独立，先清障碍
    ↓
Batch 1 (Blackout 模块) ──→ 最大模块，含投票/BOSS 任务/HUD
Batch 2 (Betel 槟榔) ────→ 独立模块
Batch 5 (Config 配置) ──→ 先抽接口，供 Batch 3 使用
    ↓
Batch 4 (SRE+Network) ──→ 含 GenerateTaskMixin 上帝拆分
    ↓
Batch 3 (Client GUI+渲染) ──→ 依赖 Batch 5 接口 + 2 个上帝拆分
    ↓
Batch 6 (核心/架构) ──→ 含 HabiTrainCore 上帝拆分（最后做）
```

## 完整批次总览

| Batch | 模块 | 项数 | 核心改动 |
|-------|------|------|----------|
| **0** | 全仓死代码+轻量优化 | **62** | 删除 40+ 死代码/类/方法 + 提取常量/命名/日志 + 按钮位置修复 + 参数清理 |
| **1** | Blackout 模块 | **28** | syncCompletion 递归链修复、dlcTaskCounts、look_my_eyes AABB、Sheriff 死代码清理、HUD 全局 flag 收敛 |
| **2** | Betel 槟榔 | 9 | tickPlayer 性能、并发安全、状态收敛 |
| **5** | Config 配置 | 7 | ConfigManager 接口抽取（Batch 3 依赖项）、save 脏标记、SRE 耦合隔离 |
| **4** | SRE+Network | 20 | GenerateTaskMixin 拆分、mixin required 降级、Network Payload 上限 |
| **3** | Client GUI+渲染 | **18** | 2 个上帝类拆分、渲染性能、GUI 配置查询优化、表单复用 |
| **6** | 核心/架构 | 22 | HabiTrainCore 拆分、SERVER_STOPPING 清理、耦合修复 |
| **合计** | | **~166** | 全审计覆盖（150 发现全部映射完成） |

## Batch 0：死代码清理 + 轻量优化（~50 项）

**原则**：零功能影响。每项删除前 grep 确认无外部调用。

### A. 死代码删除（32 项）

| # | 文件路径 | 问题 | 操作 |
|---|---------|------|------|
| 1 | `api/GameModeLifecycle.java` | S1-002 死枚举，全仓 0 引用 | 删除整个文件 |
| 2 | `game/blackout/task/BlackoutEatHandler.java` | S8-004 空 register + Map 永不写入 | 删除整个文件 |
| 3 | `game/blackout/task/BlackoutDrinkHandler.java` | S8-004 同上 | 删除整个文件 |
| 4 | `client/gui/GlobalSettingsScreen.java` | S10-001 已被 GlobalTabScreen 取代 | 删除整个文件 |
| 5 | `config/ConfigStore.java:259` calculateCurrentBoost | S4-003 死方法 | 删除方法 |
| 6 | `config/ConfigRepository.java:22` set/put 重复 | S4-009 实现完全相同 | 合并为单一 put |
| 7 | `task/TaskManager.java:125` getAvailableTasks | S2-002 死方法 | 删除方法 |
| 8 | `task/SlownessReapplyManager.java:49` unregister(2参)/clearAll(ResourceKey) | S2-004 无调用方 | 删除 2 个方法 |
| 9 | `task/GameLifecycleHandler.java:27` register() | S2-005 空桩 | 删除方法 + HabiTrainCore 调用处 |
| 10 | `game/sre/SRETrainTaskWrapper.java:59` toNbt() | S6-005 死方法 | 删除方法 |
| 11 | `game/sre/TaskEnumHelper.java:30` isCustomTaskSupported | S6-006 死方法 | 删除方法 |
| 12 | `game/sre/mixin/MinigameRewardMixin.java:22-23` 捕获字段+HEAD 注入 | S6-007 捕获了不用 | 删除 2 个 @Shadow 字段 + HEAD 注入，保留 RETURN |
| 13 | `game/sre/mixin/MinigameRewardMixin.java:25` overrideTokenReward ModifyArg | S6-008 空透传残留 | 删除 ModifyArg 注入 |
| 14 | `config/TaskConfigEntry.java:86` getEffectiveGoldReward 等 3 方法 | S4-001 死方法 | 删除 3 个方法 |
| 15 | `config/TaskConfigEntry.java:22` disabledMaps 字段 | S4-002 forRemoval=true 仍被解析 | fromJson 只打迁移 warn、不再填充字段 |
| 16 | `game/blackout/BlackoutTimerSystem.java:72` onTimeWarning | S7-003 回调恒为空 | 删除 onTimeWarning 字段 + tickSecond 调用分支 |
| 17 | `game/blackout/BlackoutTickCoordinator.java:25` onSreGameStarted/Ended | S7-004 无调用方 | 删除 2 个方法 |
| 18 | `game/blackout/BlackoutRoleManager.java:65,158,190,200` | S7-005 多个死方法 | 删除未用方法 |
| 19 | `game/blackout/SheriffVoteBroadcaster.java:64` resetCache | S7-006 死方法 | 删除方法 |
| 20 | `game/blackout/BlackoutTimerSystem.java:206` 多个 getter | S7-006 死 getter | 删除未用 getter |
| 21 | `game/blackout/task/BlackoutTaskHelper.java:106` advanceOnLook/resolveTargets | S8-005 无调用方 | 删除 2 个方法 |
| 22 | `game/blackout/task/MaintainPowerHandler.java:93` tickCheck | S8-006 空桩 | 删除 tickCheck + MaintainPowerTask.onTick 调用 |
| 23 | `game/sre/SREGameModeBase.java:158` 重复 Javadoc | S6-011 连续两段相同 | 删除重复段 |
| 24 | `game/sre/FactionFilter.java:45` 冗余赋值 | S6-009 else 赋初值 = 初值 | 移除冗余赋值 |
| 25 | `client/gui/config/SharedGuiKit.java:31` drawPanel | S10-005 死方法 | 删除方法 |
| 26 | `client/gui/config/SharedGuiKit.java:45` drawStatusPill | S10-004 死方法 + fontWidth 未用参数 | 删除方法 |
| 27 | `client/gui/LiveConfigAccess.java:30` isRemoteLocked | S10-006 死方法 | 删除方法 |
| 28 | `client/gui/ShaderWhitelistScreen.java:334` 空 if 分支 | S10-009 分支体为空 | 删除空分支 |
| 29 | `client/gui/BlackoutSheriffVoteState.java:49,81` getTotalSeconds/getTimerText | S11-005 死方法 | 删除 2 个方法 |
| 30 | `client/gui/BlackoutWelcomeRenderer.java:33` getRoleName | S11-007 死方法 | 删除方法 |
| 31 | `client/gui/BlackoutHudOverlay.java:39` setVisible | S11-008 死方法 | 删除方法 |
| 32 | `client/gui/ShaderWhitelistScreen.java:3` 无用 import | S10-010 | 删除 import |
| 33 | `client/gui/config/ConfigRootScreen.java:184` font()/isEditable() | S10-011 无调用 | 删除 2 个访问器 |
| 34 | `client/gui/BlackoutVoteState.java:14` maxSelections | S11-004 无读取 | 删除字段与 payload 对应字段 |
| 35 | `game/blackout/task/BlackoutEatTask.java:33` + `BlackoutDrinkTask.java:33` | 调用了死类 | 移除 onRemove 中调用 |
| 36 | `task/GameLifecycleHandler.java` 中对 Eat/DrinkHandler 的调用 | 同上 | 移除清理调用 |
| 37 | `client/gui/config/MinigameEditScreen.java:320` 滚动恒等式 | S10-003 no-op 分支 | 删除死分支 |
| 38 | `betel/BetelTickEngine.java:212` clearHechengTianxiaData | S3-003 死方法 | 删除方法 |
| 39 | `betel/BetelQuestState.java:51` setFoodRestriction | S3-005 无调用方 | 删除方法 |
| 40 | `betel/BetelQuestState.java:194,201` hasActiveHarvest/hasActiveHarvestInWorld | S3-005 无调用方 | 删除 2 个方法 |
| 41 | `betel/BetelQuestState.java:114` lastKnownLastEatTime | S3-006 只写不读 | 删除字段和赋值分支 |
| 42 | `config/ConfigManager.java:83` getGameModeConfig 转发 | S4-007 无调用+副作用 | 删除转发；底层改为 getOrCreate 风格 |

### B. 轻量优化（19 项）

| # | 文件 | 问题 | 操作 |
|---|------|------|------|
| 43 | `BlackoutHudOverlay.java:26` totalDuration | S11-002 只增不减 | 改为直接以服务端 total 重置 |
| 44 | `BlackoutHudOverlay.java:22` cachedEndTimeTick sentinel | S11-003 0 与未设置混用 | 改用 -1 sentinel |
| 45 | `CustomTaskBlockRendererMixin.java:251` 冗余守卫 | S9-005 <12 和 ==12 串联 | 合并为 <=12 |
| 46 | `SubtitleHUDPrefixFixMixin.java:26` 12/18 | S9-006 魔法数字 | 提取 SUBTITLE_OFFSET_X/Y 常量 |
| 47 | `MapScannerMixin.java:64-122` 二次遍历 | S6-013 两次全表遍历 | 合并到首循环 |
| 48 | `BlackoutVictoryChecker.java:122-166` 重复 getPlayer | S7-011 | 复用已查到引用 |
| 49 | `BlackoutPhoneHandler/OverlayTypes/HornVoteHandler` 三处重复缓存 | S7-008 | 统一到 BlockCache 工具 |
| 50 | `BetelQuestState.java:76` 局部 var 遮蔽静态字段 | S3-011 | 局部变量改名 |
| 51 | `BetelTickEngine.java:126-138` + `LootHelper.java:31` 魔法数字 | S3-009/S1-008 | 提取命名常量 |
| 52 | 多文件 Logger 同名 "ConfigManager" | S4-006 | 各类用自身类名 |
| 53 | `BlackoutTimerSystem.java:23` transient 命名 | S7-009 ticks→seconds | 改名 + 注释单位 |
| 54 | `MinigameEditScreen.java:198` commitFields 缺 try/catch | S10-017 | 对齐 TaskSaveController |
| 55 | `BlackoutSheriffVoteState.java:29` removeIf | S11-013 性能 | 改为 Set.contains |
| 56 | `SREGameModeBase.java:173,193` 空 catch | S6-012 | 加 debug 日志 |
| 57 | `BetelQuestState.java:11` instance 非 volatile | A1-1-007 | 加 volatile（与 BackpackQuestState 对齐） |
| 58 | `BetelLeafHandler.java:40` 静态缓存无重试 | S3-012 | 延后首次查找或预热检查 |
| 59 | **`MinigameEditScreen.java:296` saveBtn/resetBtn 位置在 super.render 之后** | **S10-002 [S1]** | **把 setX/Y 移到 super.render 之前** |
| 60 | **`BlackoutWelcomeRenderer.java:24` startWelcome 参数 killers/targets 未使用** | **S11-006 [S2]** | **删除未用参数 + 调用端多余传参** |

## Batch 1：Blackout 模块（~26 项）

### A. 关键 Bug 修复

| # | 问题 | 文件 | 操作 |
|---|------|------|------|
| 1 | S8-001 syncCompletion 链式递归→时间影响重复+O(N²) | `SupplyTaskSyncHelper.java:38` | 引入去重守卫，解耦同步完成与时间影响 |
| 2 | S2-001 dlcTaskCounts 跨局不清理 | `TaskManager.java:51` | clearAllActiveTasks 中清理 |
| 3 | S11-014 toggleSelection 满额替换未发撤回 | `BlackoutSheriffVoteState.java:73` | 替换时对旧目标发撤回 payload |
| 4 | S11-016 statusText 停在"正在请求…" | `BlackoutPhoneHireScreen.java:85` | 增加服务端结果回执 |
| 5 | S11-015 lockCountdownTicks 与服务端不同步 | `BlackoutPhoneHireScreen.java:23` | 禁用态以服务端为准 |
| 6 | S11-011 tick 不本地递减 remainingSeconds | `BlackoutVoteScreen.java:40` | 本地基于 tick 递减 |

### B. 死代码删除

| # | 问题 | 文件 | 操作 |
|---|------|------|------|
| 7 | S7-001/S7-002/A1-1-002 Sheriff 投票整套死逻辑 | `BlackoutSheriffVoteManager.java` | 删除 startVote/resolve/syncToPlayer/syncToAll/isVoteOpen |
| 8 | 同上，SheriffResolver | `BlackoutSheriffResolver.java` | 删除 applyVoteResult 或整类 |
| 9 | 同上，SheriffVoteBroadcaster | `SheriffVoteBroadcaster.java` | 随系统清理 |

### C. 性能优化

| # | 问题 | 文件 | 操作 |
|---|------|------|------|
| 10 | S8-002 look_my_eyes 每 tick 遍历全服 | `BlackoutLookMyEyesTask.java:30` | 改用 AABB 范围查询 |
| 11 | S8-003 AddCoalTask 每 tick 全背包扫描 | `AddCoalTask.java:46` | 改为事件驱动 |
| 12 | S5-001 VotePayload 每秒无变化全量广播 | `BlackoutVotePayload.java:35` | 引入哈希门控 |
| 13 | S11-001 render 每帧 4 次 getLocalCountdown | `BlackoutHudOverlay.java:86` | 缓存局部变量 |
| 14 | S11-012 Screen 每帧每行 new Component | `BlackoutVoteScreen.java:63` | 静态文本缓存为字段 |

### E. 耦合修复 + 命名空间

| # | 问题 | 文件 | 操作 |
|---|------|------|------|
| 15 | S8-009 restoreCompleted 跨局共享静态布尔 | `RestorePowerHandler.java:32` | 收敛进按 level 隔离的对象 |
| 16 | S7-007 lastWinningFaction 跨实例静态 | `BlackoutMode.java:47` | 随对局对象化 |
| 17 | S9-009 ActiveTaskCache 双写路径 | `HudCustomTaskMixin.java:20` | 明确单一权威写入源 |
| 18 | S11-010 BlackoutWelcomeRenderer 全静态可变状态 | `BlackoutWelcomeRenderer.java:17-21` | 集中到单一 client state holder |
| 19 | S8-007 AddCoal Javadoc 与实现不一致 | `AddCoalHandler.java:67` | 校准文档或提前发煤 |
| 20 | S8-008 构造 10/1 魔法数字 | `SREBlackoutGameMode.java:38` | 提取具名常量 |
| 21 | S7-010 ShopCatalog KEY 常量 | `BlackoutShopCatalog.java` | 降为 private |
| 22 | S11-009 HUD 魔法数字 | `BlackoutHudOverlay.java` | 提取常量，phase 用枚举 |
| 23 | S11-018 blackoutModeActive 全局静态 flag | `BlackoutHudOverlay.java:18` | 移到专门 client state holder，HUD 只负责读+渲染 |
| 24 | S11-019 弃票 null UUID | `BlackoutVoteScreen.java:133` | 改用显式 sendVoteRevoke |
| 25 | S7-011 重复 getPlayer | `BlackoutVictoryChecker.java` | 复用引用（Batch 0 已列） |

## Batch 2：Betel 槟榔模块（~9 项）

| # | 问题 | 文件 | 操作 |
|---|------|------|------|
| 1 | S3-001 tickPlayer 每 tick 重复 registry/组件查找 + new Random() | `BetelTickEngine.java:33-199` | 组件引用取一次复用；registry 常量启动期缓存；ThreadLocalRandom 替代 |
| 2 | S3-002 applyHarvestSlowness+tickHarvests 双遍历 | `BetelLeafHandler.java:47-74,119-185` | activeHarvests 按世界分桶；移除自注册回调 |
| 3 | S3-004 空 catch 吞异常 | `BetelTickEngine.java:201-210,244-245` | catch 加 warn 日志 |
| 4 | S3-007 单例非线程安全 | `BetelQuestState.java:11-28` | volatile+DCL；playerData→ConcurrentHashMap |
| 5 | S3-010 BetelQuestState 暴露公共可变单例 | `BetelQuestState.java:11,95,104-106` | 收敛公有方法 |
| 6 | S3-008 PlayerBetelData 14 字段密度高 | `BetelQuestState.java:108-123` | 收敛为状态枚举 + 字段级注释 |
| 7 | S3-012 betelLeafBlock 缓存无重试 | `BetelLeafHandler.java:40` | 延后查找或预热检查 |
| 8 | A1-2-008 blackout.task 直调 BetelQuestState | `BlackoutBetelQuestTask.java:4` | 下沉或 SPI 解耦 |

## Batch 5：Config 配置模块（~7 项）

| # | 问题 | 文件 | 操作 |
|---|------|------|------|
| 1 | S4-004 ConfigManager 无接口隔离 | `ConfigManager.java:33` | **抽取 ConfigQueryService 接口**，GUI 经接口访问 |
| 2 | S4-005 buildJsonRoot 每次 save 全量遍历 | `ConfigStore.java:159` | 脏标记 + 批量提交 commit() 入口 |
| 3 | S4-010 ConfigStore/MinigameEnforcement 依赖 SRE 具体类 | `ConfigStore.java:10` | 经 SRE 稳定接口/SPI 访问 |
| 4 | S4-008 -1 哨兵 | `TaskConfigEntry.java:27` | OptionalInt 或显式 hasXxx 标志 |
| 5 | S2-003 TaskPoolBuilder.CACHE 无按 mode 失效 | `TaskPoolBuilder.java:25` | 接入游戏结束/模式切换失效 |
| 6 | S1-006 getActiveForLevel fallback 流遍历 | `GameModeRegistry.java:126-134` | 缓存被动激活结果 |

## Batch 4：SRE+Network（~21 项）

| # | 问题 | 文件 | 操作 |
|---|------|------|------|
| 1 | **A1-2-007** GenerateTaskMixin 上帝类 | `GenerateTaskMixin.java` | 抽取 4 个职责类：TaskWeightCalculator/DlcTaskPoolBuilder/TaskSelector/DlcTaskTracker |
| 2 | S6-001 每 tick 每玩家 new PerPlayerTaskTicker | `SREPlayerTaskComponentMixin.java:127` | 按玩家复用或改为 static 方法 |
| 3 | S6-002 shouldIncludeOriginalTasks 重建全量 Set | `TaskWeightCurves.java:77-96` | 缓存允许集 |
| 4 | S1-007 look_my_eyes onTick 每 tick AABB | `BuiltinTaskRegistrar.java:194-233` | 增加节流（每 N tick） |
| 5 | S6-004 getType CUSTOM→SLEEP 回退 | `SRETrainTaskWrapper.java:51` | 表达"无可用槽" |
| 6 | S6-009 isParallelCall 命名 | `FactionFilter.java:27` | 重命名 |
| 7 | S6-003 静态可变状态网 | `SREGameModeBase.java:32-43` | 收敛到显式服务对象 |
| 8 | S6-010 @Shadow 耦合 | `SREPlayerTaskComponentMixin.java` | 评估公共 API 替代 |
| 9 | A1-2-001/A1-2-002 mixin required=true | 2 个 mixins.json | 脆弱 target require=0 降级 |
| 10 | A1-2-010 字符串 target | `MinigameRewardMixin.java:17` | required=false 或 @Pseudo |
| 11 | A1-2-003 客户端→服务端 TaskManager 直读 | `FixTaskRendererMixin.java:65` | 改经 ActiveTaskCache |
| 12 | S6-014 SREWeatherController 静态状态 | `SREWeatherController.java:16` | 按 level 隔离 |
| 13 | S5-002 ShaderConfigPayload decode 无上限 | `ShaderConfigPayload.java:54` | 加 MAX + DecoderException |
| 14 | S5-003 CustomTaskBlockPayload 无上限 | `CustomTaskBlockPayload.java:37` | 加 MAX 上限 |
| 15 | S5-004 2 个 VotePayload size 无上限 | 2 Payload 文件 | 加合理上限 |
| 16 | S5-005 habitrain_taskapi 资源目录 | 资源目录 | 统一命名空间为 habitrain_core；删孤立目录 |
| 17 | S5-006 32767 魔法数字 | `BlackoutAnnouncePayload.java:21` | 替换为命名常量 |
| 18 | S5-007 purpose 散落字面量 | `BlackoutVotePayload.java:36` | 提取枚举/常量集合 |
| 19 | S6-015 任务 ID 硬编码重复 | `BlackoutEatMixin.java:32` | 提取共享常量 |
| 20 | S6-014 降雨参数魔法数字 | `SREWeatherController.java:19-22` | 抽为可配置项 |

## Batch 3：Client GUI + 渲染（~16 项）

| # | 问题 | 文件 | 操作 |
|---|------|------|------|
| 1 | **A1-2-005** CustomTaskBlockRendererMixin 上帝类 | 466 行 | 拆分出 GameRunningCache/TypeColorMapper/PhoneOverlayRenderer/BlockStageScanner/ViewModeDispatcher |
| 2 | **S9-008/A1-2-004** HabiTrainCoreClient 上帝类 | 332 行 | 拆分出 NetworkReceiverRegistrar/ShaderMonitor/ClientStateHolder/HudRegistrar/ClientLifecycleHandler |
| 3 | S9-001 每帧每块 new Color() | `CustomTaskBlockRendererMixin.java:358` | 提升为 static final |
| 4 | S9-004 keySet 多次迭代 | `CustomTaskBlockRendererMixin.java:293` | 合并为单次 |
| 5 | S10-007 TaskTabScreen 每帧查 ConfigManager | `TaskTabScreen.java:205` | 预取快照 Map（依赖 Batch 5 #1） |
| 6 | S10-008 MinigameTabScreen 每帧查 ConfigManager | `MinigameTabScreen.java:102` | 同上 |
| 7 | S10-018 ConfigRootScreen init 重建三个 Tab | `ConfigRootScreen.java:48` | 按需懒建或缓存实例 |
| 8 | S10-014 GlobalTabScreen render 内懒构建 | `GlobalTabScreen.java:52` | init 期构建，render 仅定位 |
| 9 | S10-019 TaskColorPicker 文本更新分散 | `TaskColorPicker.java:98` | 收敛到 cycleColor |
| 10 | S9-012 反射未缓存 Method | `HabiTrainCoreClient.java:313` | 缓存 3 个 Method 对象 |
| 11 | S10-013 颜色魔法数字 | 多处 Screen | 纳入 SharedGuiKit 常量 |
| 12 | S9-002 invalidateGameRunningCache 死代码 | `CustomTaskBlockRendererMixin.java:172` | 改为事件失效取代 TTL |
| 13 | S9-010 原始类型 List @Shadow | `StarRailExpressTitleScreenMixin.java:39` | 补泛型或注释 |
| 14 | S9-011 getOpenVoteKey 返回 null | `BlackoutKeyHandler.java:17` | 补文档或哨兵 |
| 15 | S10-012 滚动 clamp 10000 | `TaskTabScreen.java:359` | 传真实 maxScroll |
| 16 | S9-003 client mixin required=true | `habitrain_core.client.mixins.json` | 脆弱 target 降级 |
| 17 | **S10-016** MinigameEditScreen 未复用 TaskSaveController | `MinigameEditScreen.java:195` | 改用 TaskSaveController/TaskColorPicker/TaskMapFilterEditor |
| 18 | **S11-017** Screen 直调 BlackoutVoteState 具体类 | `BlackoutVoteScreen.java:41,72,80,134` | 长期：构造注入 state 视图接口；短期标注 TODO |

## Batch 6：核心/架构横切（~22 项）

| # | 问题 | 文件 | 操作 |
|---|------|------|------|
| 1 | **S1-009/A1-2-006** HabiTrainCore 上帝类 | 442 行 | 拆分出 CommandRegistrar/NetworkRegistrar/LifecycleEventsRegistrar/C2SReceiverRegistrar/VoiceGroupService |
| 2 | **A1-1-001** SERVER_STOPPING 清理覆盖不全 | `HabiTrainCore.java:208` | 补齐全部 manager clearAll 调用 |
| 3 | A1-1-003 clearDlcTaskCounts 死方法 | `TaskManager.java:63` | 接入清理或删除 |
| 4 | A1-1-004 EffectOwnershipTracker 死方法 + 无清路径 | `EffectOwnershipTracker.java:119` | 断线/停服时清理 |
| 5 | S1-001 anyGameActive/hasActiveGame 双布尔 | `ModTickHandler.java:26-35` | 合并为单一布尔 |
| 6 | S1-003 grantedItems 链路（写入不被读取） | `TaskInstance.java:34` | 删除 grantedItems 链路 |
| 7 | S1-004 getElapsedTicks/getCustomTaskId 无调用方 | `TaskInstance.java:44,115` | 删除 getter |
| 8 | S1-005 空 catch 吞异常 | `HabiTrainCore.java:331-334,397,403` | 加 warn 日志 |
| 9 | S2-010 BackpackQuestState init/DCL 并存 | `BackpackQuestState.java:22` | 择优一 |
| 10 | S2-006 GameLifecycleHandler → blackout 硬编码 | `task/GameLifecycleHandler.java:100` | 改为观察者模式 |
| 11 | S2-007 TaskManager → SRE 具体组件耦合 | `task/TaskManager.java:6` | 经 core 抽象访问 |
| 12 | A1-2-009 BlackoutMode.onStart 直读 SRE 静态表 | `BlackoutMode.java:98` | 经 SRE 抽象接口启动 |
| 13 | S2-008 _win 后缀魔法字符串 | `TaskManager.java:190` | 提取命名常量 |
| 14 | S2-009 getPlayer 重复查找 | `BackpackSearchHandler.java:65` | 复用引用 |
| 15 | A1-1-005 命名空间不一致 | `BlackoutMode.java:24` | 统一命名空间 |
| 16 | A1-1-006 getOverrideColors 返回可变 Map | `InstinctColorHelper.java:24` | 不可变视图 |

## 验证策略

### 每批次构建验证
```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew clean build
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/临时/"
```

### 每批快速检查
- Batch 0: 确认被删类/方法无残留 import/调用（grep）
- Batch 1-6: 构建通过 + JAR 复制成功

### 端到端验证（全部完成后）
- 启动集成服务器，验证模组加载无 mixin 错误
- 停电模式：电话雇佣正常、号角投票正常、倒计时显示正确
- 重槟榔任务：正常分配/完成/成瘾效果
- GUI 配置屏：打开/编辑/保存无异常
- 多局连续：无跨局状态泄漏（timer/task counts）
