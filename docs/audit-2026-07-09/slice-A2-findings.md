# Slice A2 — 死逻辑交叉验证专项 留档

- 范围：全仓横切（`D:\Backup\mc mod\哈比列车api\src\main\java\com\habitrain\core`）
- 方法：对第一波各切片标出的候选死代码，用 Grep 全量搜索调用方/读取方，按调用图事实分类
- 日期：2026-07-09
- 约束：独立审计，不参考任何既有审计/计划/报告；未修改任何源码

## 文件覆盖确认表

| 候选来源 | 文件 | 结论 |
|---|---|---|
| S1 | api/GameModeLifecycle.java | 确认死（死类型） |
| S2 | task/TaskManager.java | 确认死（getAvailableTasks 零调用，与 TaskPoolBuilder.getPool 重复） |
| S2 | task/SlownessReapplyManager.java | 确认死（unregister(2参)/clearAll(ResourceKey) 零调用） |
| S4 | config/TaskConfigEntry.java | 确认死（getEffectiveGoldReward/getEffectiveEmotionReward/getEffectiveRefreshWeight 零调用） |
| S4 | config/ConfigStore.java | 确认死（calculateCurrentBoost 零调用） |
| S5 | network/ShaderConfigPayload.java | 确认缺陷（decode count 无上限；非死代码，payload 有 send 调用） |
| S5 | network/CustomTaskBlockPayload.java | 确认缺陷（decode entryCount/setCount 无上限；非死代码） |
| S5 | network/BlackoutSheriffVotePayload.java | 确认缺陷（decode candidates size 无上限；非死代码） |
| S6 | game/sre/SRETrainTaskWrapper.java | 确认死（toNbt() 零调用） |
| S6 | game/sre/TaskEnumHelper.java | 确认死（isCustomTaskSupported() 零调用） |
| S6 | game/sre/mixin/MinigameRewardMixin.java | 确认死（captured 字段仅写不读，HEAD 注入仅为填这些死字段） |
| S7 | game/blackout/BlackoutSheriffVoteManager.java | 部分确认死（startVote/tickSecond/resolve/buildCandidates 死；reset/castVote/onPlayerJoined/onPlayerRemoved 仍活） |
| S8 | game/blackout/task/BlackoutEatHandler.java | 确认死（register() 空体；eatingTracked 仅 remove/clear，永不写入） |
| S8 | game/blackout/task/BlackoutDrinkHandler.java | 同上，确认死 |
| S9 | client/mixin/CustomTaskBlockRendererMixin.java | 确认死（invalidateGameRunningCache 零调用） |
| S10 | client/gui/GlobalSettingsScreen.java | 确认死（整类零引用） |
| S10 | client/gui/config/SharedGuiKit.java | 确认死（drawPanel/drawStatusPill 零调用；fontWidth 参数未用） |
| S10 | client/gui/LiveConfigAccess.java | 确认死（isRemoteLocked 零调用） |
| S10 | client/gui/ShaderWhitelistScreen.java | 确认死分支（334-337 空 if，注释“不处理”） |
| S11 | client/gui/BlackoutSheriffVoteState.java | 确认死（getTotalSeconds/getTimerText 零调用） |
| S11 | client/gui/BlackoutWelcomeRenderer.java | 确认死（getRoleName 零调用） |
| S11 | client/gui/BlackoutHudOverlay.java | 确认死（setVisible 零调用） |

## 确认死的发现

### A2-001 GameModeLifecycle 枚举全仓无任何引用（死类型）
- file: api/GameModeLifecycle.java
- line: 7
- dimension: 死逻辑/死代码
- severity: S2
- evidence: `grep GameModeLifecycle` 仅命中定义行 `public enum GameModeLifecycle {`（line 7），全仓零引用，无反射/字符串引用痕迹。
- impact: 类型完全不可达，编译期保留但运行期无任何消费方，维护负担与误导性命名。
- direction: 整类型移除或确认是否为预留 API 后保留并加注释。

### A2-002 TaskManager.getAvailableTasks 死方法且与 TaskPoolBuilder 重复
- file: task/TaskManager.java
- line: 125
- dimension: 死逻辑/死代码
- severity: S2
- evidence: `getAvailableTasks` 仅在 line 125 定义；`grep` 外部仅命中 GenerateTaskMixin 的 `SREPlayerTaskComponent.Task.getAvailableTasksList()`（同名不同类）。实际可用路径为 `TaskPoolBuilder.getPool`（GenerateTaskMixin line 233 调用）。
- impact: 双重任务池构建逻辑并存，逻辑分叉与重复实现，维护时易改一处忘另一处。
- direction: 移除死方法或统一到单一任务池构建路径。

### A2-003 SlownessReapplyManager.unregister(2参)/clearAll(ResourceKey) 死代码
- file: task/SlownessReapplyManager.java
- line: 49
- dimension: 死逻辑/死代码
- severity: S3
- evidence: `.unregister(` 全仓零调用；`.clearAll(` 命中均为无参 `clearAll()`（line 67），1参 `clearAll(ResourceKey)`（line 63）零调用。`register/registerTickHandler/unregisterAllLevels/clearAll()` 均有调用方。
- impact: 单点移除/按 dimension 清理的细粒度 API 无人使用，仅留 unregisterAllLevels（全量）被调用。
- direction: 删除未被调用的两个重载。

### A2-004 TaskConfigEntry 三 getEffective* 方法死代码
- file: config/TaskConfigEntry.java
- line: 86
- dimension: 死逻辑/死代码
- severity: S2
- evidence: `getEffectiveGoldReward|getEffectiveEmotionReward|getEffectiveRefreshWeight` 仅命中定义行（86/90/94），全树零调用。
- impact: “有效值计算”抽象未被任何消费方使用，可能与 ConfigManager 直接读取原始值路径并存，导致语义二义。
- direction: 确认是否有计划接入，否则删除。

### A2-005 ConfigStore.calculateCurrentBoost 死方法
- file: config/ConfigStore.java
- line: 259
- dimension: 死逻辑/死代码
- severity: S2
- evidence: `calculateCurrentBoost` 仅命中定义行（line 259），全仓零调用。
- impact: boost 计算逻辑保留但无消费方，维护期可能误以为生效。
- direction: 删除或接入实际消费方。

### A2-006 SRETrainTaskWrapper.toNbt() 死代码
- file: game/sre/SRETrainTaskWrapper.java
- line: 59
- dimension: 死逻辑/死代码
- severity: S2
- evidence: `toNbt` 仅命中本类定义（line 59）与 `TaskInstance.toNbt`（line 117，被本类内部调用）。本类 `toNbt` 零外部调用方。
- impact: 持久化路径未被使用，包装类序列化逻辑残留。
- direction: 删除或接入存档路径。

### A2-007 TaskEnumHelper.isCustomTaskSupported 死代码
- file: game/sre/TaskEnumHelper.java
- line: 30
- dimension: 死逻辑/死代码
- severity: S2
- evidence: `isCustomTaskSupported` 仅命中定义行（line 30），全仓零调用。
- impact: 功能开关抽象未被消费，可能导致自定义任务支持判定走另一路径。
- direction: 删除或接入开关判定。

### A2-008 MinigameRewardMixin captured 字段与 HEAD 注入死代码
- file: game/sre/mixin/MinigameRewardMixin.java
- line: 22
- dimension: 死逻辑/死代码
- severity: S2
- evidence: `habitrain$capturedMinigameId`/`habitrain$capturedPlayer` 仅在 line 48-49（HEAD 注入）被写入，全仓从未被读取；RETURN 注入 `applyHabiRewards`（line 57-86）直接使用其入参 `minigameId`/`player`，不读 captured 字段。
- impact: HEAD 注入与两个字段是纯死代码，徒增 mixin 注入开销与可读性噪声。
- direction: 删除 HEAD 注入与两个字段。

### A2-009 BlackoutSheriffVoteManager 投票启动/解析子图死代码
- file: game/blackout/BlackoutSheriffVoteManager.java
- line: 78
- dimension: 死逻辑/死代码
- severity: S1
- evidence: `tickSecond`/`startVote`/`isVoteOpen`/`syncToPlayer`/`syncToAll`/`resolve`/`buildCandidates` 等方法全仓零外部调用。`tickSecond` 从未被 BlackoutTickCoordinator 调用（后者仅调用 `BlackoutExileVoteManager.tickSecond`、`BlackoutTimerSystem.tickSecond` 等，未调 Sheriff 版）。`startVote` private 且零调用。类内注释（line 83-85）明确写 “Auto-vote disabled... The startVote and sheriff resolve logic are retained for potential future use but never triggered automatically.” 仍活路径仅剩 `reset`/`castVote`/`onPlayerJoined`/`onPlayerRemoved`。
- impact: 整套投票启动、计时递减、解析结算逻辑不可达；`castVote` 仍接收投票但永不触发结算（因无 tickSecond），形成功能性死循环——投票可投但永不出结果。属确定性功能缺失。
- direction: 确认是否废弃警长投票；若废弃则连 castVote/payload/client 屏一并清理，否则接回 tickSecond 调度。

### A2-010 BlackoutEatHandler/BlackoutDrinkHandler 全套死代码
- file: game/blackout/task/BlackoutEatHandler.java
- line: 7
- dimension: 死逻辑/死代码
- severity: S2
- evidence: `register()` 空体（line 11-12）；`eatingTracked` map 仅被 `clearState`/`clearAll` remove/clear，无任何 put/写入。`BlackoutEatTask`/`BlackoutDrinkTask` 仅在 onRemove 回调中调用 `clearState`，从未将玩家加入 map。BlackoutDrinkHandler 同构。
- impact: 进食/饮水进度追踪功能实际未生效，追踪 map 永远空；clear 调用无副作用，属完整死功能。
- direction: 删除两个 handler 或补全写入逻辑。

### A2-011 CustomTaskBlockRendererMixin.invalidateGameRunningCache 死代码
- file: client/mixin/CustomTaskBlockRendererMixin.java
- line: 172
- dimension: 死逻辑/死代码
- severity: S3
- evidence: `invalidateGameRunningCache` 仅命中定义行（line 173），全仓零调用。注释称“在 SRE 游戏开始/结束事件时调用”但无对应事件订阅。
- impact: 缓存失效入口缺失，导致游戏开始/结束切换后最长 500ms 渲染用旧 running 值（line 167 缓存窗口）。
- direction: 接入游戏开始/结束事件调用，或删除该方法与对应注释。

### A2-012 GlobalSettingsScreen 整类死代码
- file: client/gui/GlobalSettingsScreen.java
- line: 26
- dimension: 死逻辑/死代码
- severity: S2
- evidence: `GlobalSettingsScreen` 仅命中本类定义行（line 26、46 构造），全仓零引用。无任何 open/构造调用。
- impact: 整屏 UI 不可达，已被其他设置入口取代（如 GlobalTabScreen）。
- direction: 删除整类。

### A2-013 SharedGuiKit.drawPanel/drawStatusPill 死方法
- file: client/gui/config/SharedGuiKit.java
- line: 31
- dimension: 死逻辑/死代码
- severity: S3
- evidence: `drawPanel`/`drawStatusPill` 仅命中各自定义行（line 31、45），全仓零调用。`drawStatusPill` 参数 `fontWidth` 在方法体内未使用（width 硬编码 48，line 46）。
- impact: 未用绘制工具残留，fontWidth 未用参数误导调用者以为宽度可配。
- direction: 删除两个方法。

### A2-014 LiveConfigAccess.isRemoteLocked 死方法
- file: client/gui/LiveConfigAccess.java
- line: 30
- dimension: 死逻辑/死代码
- severity: S3
- evidence: `isRemoteLocked` 仅命中定义行（line 30），全仓零调用。
- impact: 远端锁定判定未被消费，配置锁定语义可能未生效或走他路。
- direction: 删除或接入锁定 UI 判定。

### A2-015 ShaderWhitelistScreen mouseClicked 空 if 死分支
- file: client/gui/ShaderWhitelistScreen.java
- line: 334
- dimension: 死逻辑/死代码
- severity: S3
- evidence: line 335-337 `if (addBox.isFocused() && button == 0) { /* 如果点击了添加按钮外部，但不处理 */ }` 空体，注释明示“不处理”。
- impact: 无行为分支，仅占位与噪声。
- direction: 删除空分支。

### A2-016 BlackoutSheriffVoteState.getTotalSeconds/getTimerText 死方法
- file: client/gui/BlackoutSheriffVoteState.java
- line: 49
- dimension: 死逻辑/死代码
- severity: S3
- evidence: `getTotalSeconds`（line 49）/`getTimerText`（line 81）零外部调用（屏幕使用 `getRemainingSeconds`，line 70）。
- impact: 未用渲染辅助残留。
- direction: 删除两方法。

### A2-017 BlackoutWelcomeRenderer.getRoleName 死方法
- file: client/gui/BlackoutWelcomeRenderer.java
- line: 33
- dimension: 死逻辑/死代码
- severity: S3
- evidence: `getRoleName` 仅命中定义行（line 33），全仓零调用。
- impact: 角色名解析未被消费。
- direction: 删除。

### A2-018 BlackoutHudOverlay.setVisible 死方法
- file: client/gui/BlackoutHudOverlay.java
- line: 39
- dimension: 死逻辑/死代码
- severity: S3
- evidence: `setVisible` 仅命中定义行（line 39），全仓零调用。
- impact: HUD 显隐外部开关不可达。
- direction: 删除或接入键位/配置。

## 确认缺陷（解码边界，非死代码）

### A2-019 ShaderConfigPayload decode count 无上限
- file: network/ShaderConfigPayload.java
- line: 54
- dimension: 性能/健壮性（解码边界）
- severity: S2
- evidence: decode line 54 `int count = buf.readInt();` 后直接 `for (i<count)` 分配（line 56-62），仅对单串长度做 `Math.min(len, MAX_STRING_LENGTH)`（line 58），未对 count 设上限。该 payload 为 S2C（register playS2C，line 87），客户端解码。
- impact: 异常/恶意服务端发 count=Integer.MAX_VALUE 可致客户端 OOM 或长时间空循环。受信服务端场景下风险低但缺保护为事实。
- direction: 对 count 设合理上限并越界 break/抛异常。

### A2-020 CustomTaskBlockPayload decode entryCount/setCount 无上限
- file: network/CustomTaskBlockPayload.java
- line: 37
- dimension: 性能/健壮性（解码边界）
- severity: S2
- evidence: decode line 37 `entryCount = buf.readInt()` 仅做 `<0 →0`（line 38），无上限；line 45 `setCount` 同（line 46）。嵌套循环可 O(entryCount×setCount)。S2C payload。
- impact: 异常服务端可放大客户端分配。受信场景风险低。
- direction: 对两级 count 设上限。

### A2-021 BlackoutSheriffVotePayload/BlackoutVotePayload decode candidates size 无上限
- file: network/BlackoutSheriffVotePayload.java
- line: 39
- dimension: 性能/健壮性（解码边界）
- severity: S2
- evidence: readPlayers line 40 `int size = buf.readVarInt()` 后 `new ArrayList<>(size)`（line 41）+ 循环，无 size 上限。BlackoutVotePayload 同构（line 35-）。均 S2C。
- impact: 异常服务端可致客户端大预分配。
- direction: 对 size 设上限。

## 误报剔除（仍有调用方，不判死）

- BlackoutSheriffVotePayload：有 send/broadcastToAll 调用（SheriffVoteBroadcaster line 61、BlackoutSyncManager line 57、BlackoutSheriffVoteManager line 131/314），非“仅注册未发送”。
- BlackoutVotePayload：有 broadcastToAll 调用（BlackoutExileVoteManager line 235），非“仅注册未发送”。
- ShaderConfigPayload：有 sendToPlayer/broadcastToAll（HabiTrainCore line 236/281），非“仅注册未发送”。
- CustomTaskBlockPayload：有 sendToPlayer/broadcastToAll（HabiTrainCore line 235、MapScannerMixin line 173），非“仅注册未发送”。
- BlackoutSheriffVoteManager（整体）：非整类死代码——reset/castVote/onPlayerJoined/onPlayerRemoved 仍被调用；仅投票启动/解析子图死（见 A2-009）。
- SlownessReapplyManager（整体）：register/registerTickHandler/unregisterAllLevels/clearAll() 仍活；仅 unregister(2参)/clearAll(ResourceKey) 死（见 A2-003）。