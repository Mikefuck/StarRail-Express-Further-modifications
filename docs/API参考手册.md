# 哈比列车核心 API 参考手册

> HabiTrain Core 2.0.11 · Role Extension API 2.0 · Minecraft 1.21.1 · Fabric · Java 21
> 公开 API 语义版本：`2.1`（`CoreApi.apiVersion()`）· 角色扩展 API 语义版本：`2.0`（`CoreApi.roleApiVersion()`）
> 公开根包：`com.habitrain.core.api`
> 文档日期：2026-09-20

本手册用于快速查接口和契约。角色扩展的端到端示例、限制和排障见 [角色扩展 API v2 使用教程](角色扩展API-v2使用教程.md)。

## 1. 公开边界与生命周期

**唯一公开的 API 是 `com.habitrain.core.api.**`**，具体包含：

| 公开子包 | 说明 |
|---|---|
| `com.habitrain.core.api` | 顶层入口：`CoreApi`、任务、游戏模式、投票、`ItemReclaimHelper` 等 |
| `com.habitrain.core.api.spi` | 实现层桥接接口（`CoreSpi`、`CoreLifecycle`、各 `*Bridge`、`RoleSpi`） |
| `com.habitrain.core.api.scene` | 移动场景 v2 门面与公开值对象 |
| `com.habitrain.core.api.role` | 兼容稳定的角色覆盖 v1 |
| `com.habitrain.core.api.role.v2` | 完整角色扩展 v2（preview / experimental） |
| `com.habitrain.core.api.match` | 对局阶段、结算与事件 |
| `com.habitrain.core.api.menu` | 服务端 ModMenu 门控查询 |
| `com.habitrain.core.api.client` | 客户端专用查询（`api.client.menu`、`api.client.scene`） |

**其余一切都是内部实现，不要在编译期引用**：`com.habitrain.core.task`、`game`、`role`、`scene`、`config`、`client`、`vote`、`network`、`internal`、`misc`、`persist`、`util`、`betel`。这些包既不保证兼容，也可能在任意小版本重命名或删除；升级时若发现它们变动，属于内部重构而不是 API 破坏性变更。

> **文档历史遗留**：本文档与其他文档过去把若干实现类当作可编程 API 引用，例如 `CcaRoleStateStore`（§8.3）、`RoleOverrideTickApplier`（§10、使用教程 §2.7）、`ForcedRandomRoleChangePolicy`（§8.2、使用教程 §2.8、README 安全与运维防御）、`MenuGateService` / `MenuAccessGuard`（§3.4）、`TMMRoles` / `SRERole`（§8.1、§12）。**它们全部是内部实现（含上游 SRE 类型），只是说明其行为，任何下游代码都不得直接调用。**

任务、模式和 v1 覆盖在初始化期注册，并在服务端启动阶段冻结。v2 通用与客户端声明分别通过 Fabric entrypoint 注册：

```text
habitrain:role_extensions
habitrain:role_client_extensions
```

v2 provider 的一次 `register(...)` 是事务：抛异常时该 provider 整批回滚。所有 ID、entryKey 和翻译键应稳定且非本地化。

注册表的 `freeze()` 只能在 habitrain_core 自身 bootstrap 的生命周期作用域内生效：公开层只提供只读判定 `com.habitrain.core.api.spi.CoreLifecycle.isActive()`，驱动作用域的实现类已移到 `com.habitrain.core.internal.CoreLifecycleScope` 并移出公开 API（2.0.10 时它还是 `api.spi.CoreLifecycleScope`，下游可借它绕过冻结）。这是被强制执行的——`TaskRegistry` / `GameModeRegistry` 在作用域外调用 `freeze()` 不会生效。

## 2. 任务 API

### 2.1 `TaskCategory`

标准常量：`MURDER`、`REPAIR`、`ALL`、`CUSTOM`。自定义分类：

```java
new TaskCategory(String id, String displayName, String gameModeId)
```

相等性只比较 `id`。

### 2.2 `TaskRegistry`

| 方法 | 契约 |
|---|---|
| `register(TaskDefinition)` | 注册不可变定义；重复或冻结后抛异常 |
| `register(modId, taskId, Consumer<Builder>)` | Builder 便捷入口，返回定义 |
| `get(fullId)` | 不存在返回 `null` |
| `getAll()` / `getAllIds()` | 只读视图 |
| `getByGameMode(id)` | 按逻辑模式 ID 过滤 |
| `getByCategory(category)` | 匹配分类；`ALL` 定义会进入任意分类查询 |
| `freeze()` / `isFrozen()` | 冻结状态 |

### 2.3 `TaskDefinition.Builder`

| 类别 | 方法 |
|---|---|
| 展示/归属 | `displayName`、`category`、`customCategory`、`gameMode` |
| 权重/兼容 | `weight`、`blockTypeId`、`tags` |
| 扫描 | `scanBlocks`、`scanBlockIds`、`instinctColor(int)`、`instinctColor(r,g,b,a)` |
| 进度 | `timeLimit`、`canRepeat`、`shareProgress` |
| 时间影响 | `timeImpact(TimeAxis, deltaSeconds)` |
| 生命周期 | `onAssign`、`onComplete`、`onRemove`、`onFail`、`onReclaim` |
| 判定 | `completionChecker`、`canAssign`、`onTick`、`onProgressUpdate` |

颜色是 ARGB `int`，不要使用 `java.awt.Color`。

### 2.4 `TaskInstance`

常用方法：`get/setProgress`、`get/setMaxProgress`、`isFulfilled`、`isFailed`、`markFailed`、`tick`、`toNbt`、`fromNbt`。`setMaxProgress` 会钳制到至少 1。

## 3. 游戏模式 API

### 3.1 `GameMode`

必需方法：

```java
String getId();
String getDisplayName();
List<TaskCategory> getTaskCategories();
boolean isActive(ServerLevel level);
```

生命周期默认方法：`onPreStart`、`onStart`、`onTick`、`onPlayerJoin`、`onPlayerLeave`、`onTaskComplete`、`checkWinCondition`、`onEnd`、`onCleanup`。

任务拦截：`filterAvailableTasks`、`onTaskAssign`、`onTaskTick`、`onTaskProgressChange`、`overrideCompletionCheck`。

### 3.2 `GameModeRegistry`

| 方法 | 契约 |
|---|---|
| `register(modId, modeId, mode)` | 注册键为 `modId:modeId`，与 `GameMode.getId()` 不是同一字符串 |
| `start(fullId, level)` | 必须传注册键；每个世界仅一个主动模式 |
| `stop(level, result)` | `onEnd` 后 finally `onCleanup` |
| `tickAll(server)` | 只 tick 主动表中的模式 |
| `getActiveForLevel(level)` | 主动表优先，否则检查被动 `isActive` |

核心谋杀模式的注册键是 `habitrain_core:sre:murder`（`GameModeRegistry.register("habitrain_core", "sre:murder", ...)`），而 `GameMode.getId()` 是逻辑短 ID `sre:murder`。不要 `start(mode.getId())`。

`WinResult` 提供 `singleWinner`、`noWinner`、`forceEnd`。`singleWinner` 的 playerId 不可为 null；无人获胜用 `noWinner`。

### 3.3 `GameModeIds` / `MatchStateApi` / `MatchEvents`

| 入口 | 契约 |
|---|---|
| `GameModeIds.MURDER` / `REPAIR` | 当前内置模式短 id：`sre:murder`、`sre:repair` |
| `GameModeIds.BLACKOUT` | `habitrain:blackout`。**不再标 `@Deprecated`**：core 已不注册该模式、它不可玩，但 `canonical()` 与 `MatchSettlement.modeId()` 仍会产出这个规范 id（历史局与上游停电局），按「已废弃」避开它会漏判停电局 |
| `GameModeIds.canonical(raw)` | 把 registry 全 id、`sre:blackout`、类名猜测映射到短 id。大小写归一固定用 `Locale.ROOT`，不要再靠默认 Locale 判 `REPAIR` / `MURDER` |
| `MatchStateApi.phase(level)` | 对齐 SRE `GameStatus`（含 `INITIATING`）；读失败为 `UNKNOWN` |
| `MatchStateApi.hasLeftLobby(level)` | `phase != INACTIVE`（含 `UNKNOWN`，卡片应拒绝） |
| `MatchStateApi.modeId(level)` | 优先 `GameModeRegistry.getActiveForLevel`，否则 SRE `identifier`。**大厅返回空串**（此前会漏出残留的 `sre:murder`），因此它**不是**锁定判据：判定大厅请用 `MatchStateApi.phase(level)` |
| `MatchEvents.STARTED` / `ROUND_ENDED` | 对局开始 / 结算。抽奖发次只订 `ROUND_ENDED`，不要再读 RoundEnd CCA。2.0.11 起经 `GameModeRegistry.start/stop` 启动的对局也会触发；这类结算的 `winners()` / `participants()` 为空集，只能用于逐局重置，不能用于发奖。**监听器只能在 `onInitialize()` 里注册一次**（Fabric `Event` 无注销入口，写在 `SERVER_STARTED` 会随集成服反复启停累积） |
| `MatchSettlement` | 结算只读快照：`modeId()` / `winKind()` / `matchKey()` / `participants()` / `winners()` / `factions()`。`factionOf(uuid)` 查不到时兜底为 `PASSENGER`；要区分「查不到」与「真的是乘客」用 `factionOfOrEmpty(uuid)` → `Optional<MatchWinFaction>`。停电阵营与历史请从这里读，不要再找 `game.blackout.BlackoutRoleManager`（该内部类不存在） |
| `RoleForceApi` | 下局强制：`queueExact` / `queueFaction` / `queueRoleType` / `isQueued` / `clear`。不要调用 Harpy / `ForcePlayerTeam` |

`GameMode.isActive` **不能**用来判断大厅：大厅残留 mode 也会 true。

### 3.4 `MenuGateApi`

服务端（`api.menu`）：`MenuGateApi.isBlocked(player)`（专用服 + 门控开启 + 未授权 → true）。

> ⚠️ `MenuGateApi.isAllowed(player)` 是**名单查询**（该玩家是否在允许名单里），**不是门禁判据**，两者**不互为取反**：专用服 + 门控关闭时未授权玩家会同时得到 `isBlocked() == false` 与 `isAllowed() == false`。写 `if (isAllowed(p)) 放行 else 拒绝` 会把所有人拒掉——门禁判定只用 `isBlocked(...)`。

客户端（`api.client.menu`，客户端专用）：`MenuGateClientApi.isScreenAllowed()`，并可查 `MenuGateClientApi.isServerDedicated()`（服务端自报的专用服状态，与 `isScreenAllowed()` 同一份状态；客户端不再自行推断）。

顶层 `com.habitrain.core.api.MenuGateApi` 是**同名转发壳**，已标记 `@Deprecated(forRemoval = true, since = "2.0.11")`，请在新代码中直接使用 `com.habitrain.core.api.menu.MenuGateApi`（它将在 2.0.12 移除）。附属 mod 不要反射内部实现 `MenuGateService` / `MenuAccessGuard`。

## 4. 投票与道具

### 4.1 `OptionVoteApi`

```java
// 旧重载：标题固定为「投票」，描述为空串
boolean start(ServerLevel level, String voteId,
              List<VoteOption> options, int durationSeconds,
              Consumer<VoteResult> onResolved);

// 2.0.11 新增：可自定义标题与描述（null 回退「投票」/ 空串）
boolean start(ServerLevel level, String voteId, @Nullable String title,
              @Nullable String description, List<VoteOption> options,
              int durationSeconds, Consumer<VoteResult> onResolved);

boolean cast(ServerLevel level, UUID voter, @Nullable String optionId);
boolean isActive(ServerLevel level);
void cancel(ServerLevel level);
```

- 保留 voteId `"mode"` / `"map"` 由模式→地图编排器占用，公开入口会**直接拒绝**（返回 `false`），第三方用它们会意外继承维修锁图过滤并抢占唯一投票位。
- `options` 的 `id` 必须非空、长度 ≤ 64、互不重复；`durationSeconds >= 1`。任一项不满足时在改动任何状态**之前**就返回 `false`。
- **线程契约**：所有方法必须在服务端主线程调用；实现内部用非并发容器保存会话状态。C2S `option_vote_cast` 已加速率限制（此前是全仓唯一无限速的 C2S 处理器）。

`VoteResult` 是 5 元 record：`voteId`、`winnerId`、`tallies`、`randomPick`、`cancelled`。四参构造重载仍在（等价于 `cancelled = false`）。取消 / 重置路径会以 `VoteResult.cancelled(voteId)` 回调 `onResolved`（`winnerId == null`、票数为空、`cancelled == true`），不再丢弃回调；`isActive(...) == false` 无法区分「已结算」与「已取消」，判据请用 `cancelled`。

### 4.2 `ModeMapVoteApi`

`start(level)`、`start(level, config)`、`cancel(level)`、`isRunning(level)`、`getSnapshot(level)`、`lastFailure(level)`。

- `lastFailure(level)` 返回最近一次 `start(...)` 失败的**可读原因**（没有失败记录时为空串，每次 `start` 覆盖）。`start` 返回 `false` 共有 6 类原因（level 为空 / 配置未启用 / 已有投票 / 选项投票占用 / SRE 占用 / 模式占用 / 无候选），需要区分时读它。
- `ModeMapVoteSnapshot.phase()` 返回公开枚举 `com.habitrain.core.api.ModeMapVotePhase`：`IDLE | MODE_VOTING | MAP_VOTING | SWITCHING_MAP | STARTING_MODE`（2.0.10 及以前是裸 `String`）。枚举自带 `isVoting()` / `isIdle()` / `fromName(String)`（无法识别时回退 `IDLE`）。
- `ModeMapVoteConfig` 可覆盖模式/地图投票时长和候选 ID；快照还暴露已选模式、已选地图和剩余秒数。
- **线程契约**：所有方法必须在服务端主线程调用。

### 4.3 `ItemReclaimHelper`

`tagGrantedItem` 标记物品，`matchesGrant` 检查，`reclaim` 从玩家物品栏移除，`reclaimForTask` 先运行任务回收回调再按 full ID 回收。

### 4.4 `CoreApi`

```java
CoreApi.apiVersion();        // "2.1"
CoreApi.roleApiVersion();    // "2.0"
CoreApi.supports("scene.instances");   // 未知能力键 fail-closed 返回 false
CoreApi.capabilities();                // 全部已知能力键（只读 Set）
```

这是**机器可读**的版本 / 能力入口，替代「靠 mod 版本号猜能力」：同一个 API 大版本内只有语义变更才递增 `apiVersion()`，纯修 bug 不递增。已知能力键：`role.v2`、`role.v2.override`、`match.settlement`、`match.events`、`match.state`、`menu.gate`、`vote.option`、`vote.mode_map`、`scene.motion`、`scene.instances`、`task.registry`、`gamemode.registry`。

同一份信息也写进 `fabric.mod.json` 的 `custom` 段，供构建期 / 无代码检测：

```json
"custom": {
  "habitrain_core:api_version": "2.1",
  "habitrain_core:role_api": "2.0"
}
```


## 5. 角色覆盖 v1

包：`com.habitrain.core.api.role`。v1 仍是正式兼容 API，不弃用。

| 入口 | 用途 |
|---|---|
| `RoleOverrideApi.registerReplace` | 完全替换目标角色 |
| `RoleOverrideApi.registerModify` | 保留目标 ID/对象并打可撤销补丁 |
| `isReplaced` / `getReplacement` | 查询活动替换 |
| `isModified` / `getActiveModify` | 查询活动修改 |
| `getEffectiveEntries` | 查询所有条目及状态 |

MODIFY builder 支持 `namePatch`、`colorPatch`、`descriptionPatch`、`simpleDescriptionPatch`、`shopPatch`、`shopTransform`、`defaultItemsPatch`、`flagsPatch`、`spawnInfoPatch`、`managedSkillPatch`、`winConditionHook`、`roleBookAppendix/Appendices`。

`skillRegistrar` 仅保留兼容，使用它的条目会被判为 INVALID；新代码必须使用 `managedSkillPatch`。

## 6. 角色扩展 v2 注册端口

### 6.1 `RoleExtensionEntrypoint`

```java
void register(RoleExtensionRegistrar registrar);
default boolean requiresClient(); // 默认 false
```

只有回调参数 `registrar` 能写入注册表。`RoleExtensionApi.instance().registrar()` 的写方法全部抛异常。

### 6.2 `RoleExtensionRegistrar`

| 方法 | 操作 |
|---|---|
| `SRERole add(RoleDefinition)` | ADD 新角色 |
| `void modify(RolePatch)` | MODIFY 现有 canonical 角色 |
| `void replace(RoleReplacement)` | REPLACE 目标 |
| `void alias(RoleAlias)` | ALIAS 旧 ID |
| `hooks(role, hooks)` | HOLDER 作用域受管行为 |
| `hooks(role, scope, hooks)` | 显式作用域受管行为 |
| `<T> RoleStateKey<T> state(spec)` | 注册状态 schema |
| `RoleActionSpec action(spec)` | 注册受管动作 |
| `RoleVoicePolicy voice(policy)` | 注册语音策略 |
| `RoleChatPolicy chat(policy)` | 注册聊天策略 |

### 6.3 ADD：`RoleDefinition`

必填：`presentation`、`faction`、`spawn`、`compatibility`、`maxSprintTime >= 0`。

| Profile | 主要字段 |
|---|---|
| `RolePresentation` | ARGB color、mood、name/description/simple/objectives 翻译键、icon |
| `RoleFactionProfile` | innocent、killer、neutral、vigilante、neutralFor*、mafia |
| `RoleSpawnProfile` | defaultMax、enableChance、玩家数窗口 |
| `RoleCompatibilityProfile` | CCA componentKey、coin/revolver/random、otherMode、map、rotation、occupied count |
| `RoleInventoryProfile` | 初始 `ItemStack` |
| `RoleEconomyProfile` | 静态 shop 或每次打开调用的 `live(Supplier<List<ShopEntry>>)` |
| `RoleVisibilityProfile` | instinct、夜视、杀手队友可见 |
| `RoleRelationProfile` | occupation、opposing、related、opposingTwoWay |
| `RoleSkillSpec` | 稳定技能 ID + `RoleSkill.Definition` |
| `RoleBookContent` | 完整职业书文本页 |

`roleFactory(...)` 可在 staging 时构造自定义 `SRERole` 子类；返回角色必须使用定义的 canonical ID。

### 6.4 MODIFY：`RolePatch`

排序：`PatchPriority` → provider mod ID → `entryKey`。

| 补丁类型 | 操作 |
|---|---|
| `BooleanPatch` | `set` / `and` / `or` |
| `IntPatch` | `set` / `add` / `min` / `max` |
| `RoleKeyListPatch` | `append` / `remove` / `replaceAll` |
| `RoleSkillPatch` | `append` / `removeMatchingIds` / `replaceMatchingIds` / `replaceAll` |
| `RoleBookPatch` | `append` / `removeMatchingTitles` / `replaceAll` |

字段覆盖包括：颜色/动态颜色、mood、名称、详细/简短描述、初始物品、商店、最终商店 transform、胜利桥、flags、spawn、阵营、可见性、模式/轮换、关系、技能和职业书。

`RolePatch.build()` 至少要有一个字段操作。

### 6.5 REPLACE / ALIAS

`RoleReplacement` 的身份策略：

| 策略 | 契约 |
|---|---|
| `KEEP_CANONICAL_ID` | replacement 定义 ID 等于 target；保留 canonical ID，但不保留原 Java 对象 |
| `NEW_ID_WITH_ALIAS` | replacement 使用 provider 自有新 ID，旧 target 自动成为 alias |
| `PRESERVE_TARGET_ID` | `KEEP_CANONICAL_ID` 的弃用别名 |

必须保留原 Java 对象、CCA 或上游 `==` 比较时使用 MODIFY。

`RoleAlias(from,to)` 只迁移 ID，不改行为；alias 环、悬空目标和独占冲突会被诊断。

## 7. v2 行为端口

### 7.1 `RoleHooks`

| 分类 | 接口 | 入口 |
|---|---|---|
| 生命周期 | `RoleLifecycleHooks` | assigned/lost、game start/true start/end、roles confirm |
| 战斗 | `RoleCombatHooks` | death gate、killer gate、death/kill/body 回调 |
| 交互 | `RoleInteractionHooks` | use item/entity/block、attack entity/block、break block |
| 商店 | `RoleShopHooks` | allowBuy、onBuy、onAnyBuy |
| 任务 | `RoleTaskHooks` | onFinishQuest |
| 会议 | `RoleMeetingHooks` | meeting start/end、allowVoteOut |
| 胜利 | `RoleWinHooks` | allowGameEnd、evaluateWin、afterWinnersFinalized |
| Tick | `RoleTickHooks` | onServerTick、tickInterval |

`Decision` 合并顺序是 DENY > ALLOW > PASS。`RoleInteractionHooks` 第一个非 PASS 结果消费事件。

### 7.2 `RoleScope`

`HOLDER`、`KILLER`、`VICTIM`、`TARGET`、`ANY_ACTIVE_HOLDER`、`ROUND_PRESENT`、`GLOBAL_WHILE_ENABLED`。

广播类事件需要按语义选择作用域。`GLOBAL_WHILE_ENABLED` 注册时不会被拒绝，但运行时仍要求 `presentInRound && allowGlobalHooks`（round snapshot 的门控；无 snapshot 时读 live 配置）。不要把它当成「条目启用即无条件开火」。

### 7.3 `WinPatch`

`noChange`、`addWinners`、`removeWinners`、`replaceWinners`、`declareFaction`、`declareCustom`。结算锁定后只在 `afterWinnersFinalized` 做奖励/统计，不再修改赢家。

## 8. v2 运行时端口

### 8.1 `RoleCatalogApi`

| 方法 | 说明 |
|---|---|
| `find(RoleKey)` | 解析 alias/replacement 并查有效角色 |
| `canonicalize(ResourceLocation)` | 规范化任意 ID |
| `effectiveRoles()` | 当前有效可运行角色集合 |
| `effectiveRoles(RoleQuery)` | 过滤和排序 |
| `resolve(SRERole)` / `resolveStored(String)` | 解析上游对象或存档值 |
| `isActive/isAdded/isModified/isReplaced` | 状态查询 |
| `snapshot()` / `currentSnapshot()` | 当前快照 |
| `restore(snapshot,key)` | 从归档快照恢复纯数据视图 |

`RoleQuery` 可按 purpose、mode、map ability、faction、provider、tag、playerCount、ordering 过滤。`includeDisabled` / `includeInvalid` 已弃用且没有目录语义；使用 `RoleDiagnostics` 查声明行。

### 8.2 `RoleChangeApi`

`assign(player, role, options)`、`transform(player, role, cause)`、`transform(player, role, cause, options)`、`remove(player, cause)`、`current(player)`、`history(player)`。变更由事务处理 alias、旧角色清理、映射、历史、初始化、hooks 和同步。四参数 `transform(..., options)` 必须由实现覆盖，默认抛 `UnsupportedOperationException`，不会悄悄丢掉 options。

`OnGamePlayerRolesConfirm` / `RoleLifecycleHooks.onRolesConfirm` **可以**在开局确认阶段改写分配 Map（抽奖自选、职业卡、七宗罪互斥等）。这是**开局确认**，不是局中转职。局中转换必须走 `RoleChangeApi`。

强制随机转职必须使用 `RoleChangeCause.FORCED_RANDOM`。Core 会在事务写入前检查旧职业：Core 自有职业和普通、无组件、允许随机的 `NormalRole` 默认可转；未知上游的组件职业、自定义实现或禁止被其他职业随机的角色默认拒绝。只有完整审计旧职业的 CCA、药水效果、实体和全局状态清理后，才能加入内部实现类 `ForcedRandomRoleChangePolicy` 的内部安全名单。不要通过直接修改角色 Map  绕过保护。

### 8.3 `RoleStateApi`

注册返回 `RoleStateKey<T>`；运行时使用 `get(key, player)` / `set(key, player, value)` / `reset`。状态 scope：PLAYER/WORLD/ROUND；persistence：NONE/ROUND/WORLD/PERMANENT；sync：NONE/OWNER/OWNER_AND_TRACKING/ALL/SERVER_ONLY；reset cause：ROLE_LOST/ROLE_ASSIGNED/ROUND_END/ROUND_START/MANUAL。

生产存储：`Persistence.WORLD` / `PERMANENT` 经内部实现类 `CcaRoleStateStore` 写入 CCA（PLAYER → 玩家组件，WORLD → 世界组件）；`ROUND` / `NONE` 走内存。`StateScope.ROUND` 一律进局内内存袋，**ROUND+PERMANENT 也不会写入世界 NBT**。

WORLD/PERMANENT persistence 或非 NONE sync 必须提供 `Codec<T>`。声明 `dataVersion > 1` 时迁移链必须从 v1 连续覆盖到当前版本。

> ⚠️ **缺 codec 不是诊断行，是整批回滚**：`RoleStateSpec` / `RoleActionSpec` / `RolePatch` / `RoleDefinition` / `RoleReplacement` 等 spec 缺必填项时，`build()` 直接抛 `IllegalStateException`。异常会冒泡到 provider 的注册事务，**该 provider 本次声明的整批回滚**，`RoleDiagnostics` 里连一行 `INVALID` 都不会出现（诊断只读已提交条目）。旧文档写的「注册失败并报 INVALID」是错的。

### 8.4 `RoleActionApi` / `RoleActionClientApi`

服务端：`spec/specs/specsFor`、`dispatch/receiveC2S`、`sendTo`。客户端：`send`、`add/removePushListener`；结果按 `(actionId, sequence)` 关联，并处理 timeout/disconnect。`RoleActionClientApi.instance()` **只允许客户端**调用，main/server 入口调用会让专用服解析失败。`RoleActionClientApi.clear()`（断线 / 换世界时调用）只清空待处理请求，**保留已注册的 push 监听器**；推送回调仍会继续投递。

动作方向：C2S/S2C/BIDIRECTIONAL。目标 codec：NONE、PLAYER_UUID、BLOCK_POS、ENTITY_ID。

目标条件的适用范围（此处旧文档曾写反）：

| 声明项 | `PLAYER_UUID` | `BLOCK_POS` | `ENTITY_ID` / `NONE` |
|---|---|---|---|
| `maxDistance` | ✅ 生效 | ✅ 生效（距离由服务端强制） | ❌ `> 0` 时 `build()` 抛异常 |
| `requireTargetAlive` | ✅ 生效 | ❌ 声明即抛异常 | ❌ 声明即抛异常 |
| `requireLineOfSight` | ✅ 生效 | ❌ 声明即抛异常 | ❌ 声明即抛异常 |

平台在 handler 前执行 provider/entry、握手、方向、大小、速率、序列、当前角色、存活、冷却和目标验证。

### 8.5 `RoleCapabilityApi`

查询 `voices/chats`，用 `status/supports` 检查适配器，用 `evaluateVoice/evaluateChat` 评估策略。VOICE 由 voicechat 适配器消费；chat `muteSend` 生效，`muteReceive` 当前 experimental，不能依赖逐接收者过滤。

- `bindAdapter(key, status)` 是**公开、无归属的写入口**：没有 provider/entry 门控，任意 mod 谎报 `bindAdapter(VOICE, AVAILABLE)` 就能让 `supports(VOICE)` 说谎。适配器必须在自己的 entrypoint 里调用它，第三方**不要**用它来「探测」；注册表冻结后该入口应视为不可用入口（审核 R-20：`freeze()` 之后仍可写入，不要用它绕开注册期）。
- `setGroup(playerId, groupId)` 记录的隔离组是**本局临时编排**：玩家断线时清除该玩家的组，对局结束时清空全部组（审核 R-04）。旧实现从不清理，陈旧组会被注入后续每一轮与重连后的语音评估。

### 8.6 `RoleDiagnostics`

`report()`、`entries()`、`aliases()`、`snapshotInfo()`。用于查看 disabled/conflict/invalid/pending，不要从有效目录猜诊断状态。注意：`build()` 抛异常导致的**整批回滚不会留下 INVALID 行**（见 §8.3）。

### 8.7 `api.spi` 桥接包

`api.spi` 是**实现层与公开层之间唯一的桥**（2.0.11 起成为唯一入口）。桥接类型包括 `CoreSpi`、`CoreLifecycle`、`SceneInstanceBridge`、`SceneRuntimeBridge`、`SceneAssetBridge`、`SceneCaptureBridge`、`SceneConfigBridge`、`SceneClientBridge`、`MenuGateBridge`、`MenuGateClientBridge`、`VoteBridge`、`RoleSpi`（以及 `SreRuntimeBridge`、`TaskPoolCacheBridge`、`ExtraSlotReclaimBridge`）。

| 问题 | 约定 |
|---|---|
| **谁该实现** | 只有 habitrain_core 自己。实现在 core 初始化时由 `CoreSpi` 装配；装配入口带调用者限制，不是给下游用的扩展点 |
| **谁不该调** | 第三方 mod **只允许**读其中的查询助手（如 `CoreLifecycle.isActive()`），不得调用/替换/复装桥接实现，也不要把桥接当作「我可以提供实现」的 SPI 来用 |
| **失败语义** | 所有桥接都有**安全 no-op 默认实现**：未装配时查询返回中性与保守值（空集合、`false`、`null`/空 `Optional`），绝不抛异常。因此「桥未装配」表现为能力缺失而不是崩溃；`CoreLifecycle.isActive()` 未装配时恒为 `false` |
| **依赖方向** | `api.spi` 的实现类在 `internal` 包内；`api.**` 不反向依赖 `task` / `game` / `role` / `scene` 等实现包 |

## 9. v2 客户端端口

所有注册使用 `habitrain:role_client_extensions` 回调参数 `RoleClientExtensionRegistrar`：

| 方法 | 类型 |
|---|---|
| `hud(RoleHudSpec)` | 文本/徽章及基础 fallback HUD |
| `hudWidget(...)` | provider 自绘 HUD |
| `instinct(RoleInstinctRule)` | 直觉颜色/隐藏规则 |
| `skin(RoleSkinSpec)` | NORMAL/PSYCHO/DYNAMIC 皮肤资源 |
| `nameRender(RoleNameRenderRule)` | NAMEPLATE hide/color 已消费；其他 phase 预留 |
| `screen(RoleScreenSpec)` | PLAYER_PICK/CONFIRM/LIST 声明；由 provider 触发 stock screen |

`RoleClientExtensionApi` 是只读查询门面，旧写形方法会抛异常。不要让 common/server 类加载 `Minecraft` 客户端类型。

## 10. v2 配置、快照与命令
配置文件：`config/habitrain_role_v2.json`。门控顺序：全局 → provider → entry。

- 大厅修改：立即成为 lobby snapshot。
- 对局中 v2 配置修改：编译为 pending；下一局边界提升为 lobby 并激活。本局 gameplay（hooks、受管 action、HUD/直觉/皮肤）继续使用 round snapshot，不跟 pending。
- Mod Menu 与 `/habitrain roleapi snapshot` 诊断可以同时显示 pending 与 live。
- v1 flags/spawn/shop 的 live 写入由内部实现类 `RoleOverrideTickApplier` 在 round start 冻结（NEXT_ROUND）；局中 rebuild 不会立刻改当前对局的 flags/spawn/shop。
- 不要把「对局中修改绝不破坏当前对局」写成覆盖全部 API 的保证。
- 同一 `RoleKey` 双注册 v1+v2 MODIFY/REPLACE → Engine 标 v1 CONFLICT 并跳过（v1 胜负 hook / 商店 overlay 丢弃）。v1 不弃用。

常用命令：

```text
/habitrain roleapi providers
/habitrain roleapi list [effective|disabled|conflict|invalid|legacy|broken]
/habitrain roleapi inspect <role>
/habitrain roleapi trace <role> <field>
/habitrain roleapi aliases [role]
/habitrain roleapi snapshot
/habitrain roleapi hooks <role>
/habitrain roleapi actions
/habitrain roleapi capabilities
/habitrain roleapi perf
/habitrain roleapi archive
/habitrain roleapi state [player]
/habitrain roleapi config status
/habitrain roleapi config set provider <id> on|off
/habitrain roleapi config set entry <id> on|off
/habitrain roleapi config set allowGlobalHooks on|off
/habitrain roleapi config winner <target#field> <entryId>
/habitrain roleapi manifest
```

诊断/读取需要 OP 2，配置写入需要 OP 4。

## 11. 移动场景 API（`com.habitrain.core.api.scene`）

移动场景系统有两条通道：**地图级场景**（配置页/`sceneMotion` 配置，每图 1 主 + 最多 4 附加）
与 **API 实例**（外部 Mod 运行时注册，**数量无上限**、不落盘）。v2 门面 `SceneApi` 同时覆盖两者，
v1 的 `SceneMotionApi` 保留且行为不变。

### 11.1 `SceneApi`（服务端门面，`SceneApi.instance()`）

| 分组 | 方法 |
|---|---|
| 全局 | `config` `isGlobalEnabled` `setGlobalEnabled` `defaultMapKey` `lobbyMapKey` `isLobbyMapKey` `maxBackgroundsPerMap` |
| 资产键助手 | `normalizeMapKey` `primaryAssetKey` `backgroundAssetKey` `mapKeyFromAssetKey` `backgroundIdFromAssetKey` |
| 实例查询 | `instanceCount` `instanceCountIn` `instances` `instancesIn` `instancesOfOwner` `instancesWithTag` `instance` `hasInstance` |
| 实例生命周期 | `spawn` `upsert` `despawn` `despawnAllOfOwner` `despawnAllWithTag` `despawnMatching` `despawnAllIn` `despawnAll` `resync` `resyncDimension` `resyncAll` `modify` |
| 实时调参 | `setProfile` `editProfile` `setProfileEnabled` `setSpeed` `setDirection` `setDisplayOrigin` `setRotation` `setPhaseOffset` `setLoop` `setRenderDistance` `setTranslucent` `setMotionMode` `setOrbit` `setOrbitSettings` `editSound` `editShake` `setAssetKey` `setAnchor` `setPaused` `setTimeScale` `restart` `setStartGameTime` `setHeadStartSeconds` `setDurationTicks` `setDurationSeconds` `setPriority` `addTag` `setTags` `setVisibleToAll` `setVisibleTo` `setDimension` |
| 地图级场景 | `runtimeState` `isSceneActive` `startScene` `stopScene` `resolveContext` `registerContextResolver` |
| 配置读写 | `profile` `getOrCreateProfile` `setMapProfile` `editMapProfile` `profileMapKeys` `backgrounds` `backgroundProfile` `backgroundName` `putBackground` `removeBackground` |
| 资产 | `asset` `hasAsset` `assets` `deleteAsset` `publishAsset` `publishAssetData` `requestCapture` `cancelCapture` |
| 事件 | `addListener` `removeListener` |
| 诊断 | `diagnostics` `describeInstances` `snapshot` |

### 11.2 值对象与端口

| 类型 | 职责 |
|---|---|
| `SceneInstanceSpec` / `.Builder` | 实例描述：ID、owner、维度、assetKey、profile、时间轴（`startGameTime` / `headStartSeconds` / `timeScale` / `paused`）、`durationTicks`、`priority`、`tags`、`anchor`、可见性 |
| `SceneProfileBuilder`（`Orbit` / `Render` / `Sound` / `Shake`） | 运动参数全字段构建：源选区、原点、枢轴、方向、速度、旋转、相位、循环、直线/环绕、渲染距离与半透明、环境音、微震 |
| `SceneInstanceAnchor` | `world()` / `player(uuid, offset)` / `entity(entityId, offset)`，客户端每帧解析，失败回退静态原点 |
| `SceneSpawnResult.SceneSpawnStatus` | 嵌套枚举（不是顶层类型）：`OK` / `INVALID_SPEC` / `ALREADY_EXISTS` / `GLOBAL_DISABLED` / `SERVER_UNAVAILABLE` / `LEVEL_NOT_FOUND` |
| `SceneInstanceView` | 实例只读视图（身份、资产、参数、时间、生命周期、可见性、`elapsedSeconds`）。服务端 `SceneApi.instances()/instance()` 与客户端 `SceneClientApi.instances()/instance()` 现在都返回它，不再泄漏内部 `SceneInstance` |
| `SceneListener` | 服务端事件：实例增/改/删、地图级场景启停、资产发布、全量重同步 |
| `SceneClientApi`（`@Environment(CLIENT)`） | 客户端查询：`mapSceneState`、`instances*`、`isMeshReady`、`meshBytes`、`requestResync` |

`SceneListener.onInstanceRemoved(instance, reason)` 的 `reason` 只有四个取值：`despawn`（显式回收）、`expired`（存活期到）、`dimension_changed`（`setDimension` 迁移到另一维度）、`reset`（全量重置 / 停服清理）。**不存在 `replaced`**：同 ID 覆盖走 `onInstanceUpdated`，不会先触发移除（审核 S-01 / S-08）。

### 11.2.1 公开包迁移（2.0.11）

场景值对象整体迁入公开层，旧文档里的 `com.habitrain.core.scene.*` 包在 2.0.11 起一律**不要**再引用：

| 公开新位置 | 类型 |
|---|---|
| `com.habitrain.core.api.scene.model` | `SceneProfile`、`SceneBounds`、`SceneRenderSettings`、`SceneLoopSettings`、`SceneOrbitSettings`、`SceneRotation`、`SceneShakeSettings`、`SceneSoundSettings`、`SceneBackgroundConfig`、`SceneBackgroundKey`、`SceneInstance`、`SceneRuntimeState`、`SceneMotionMode`、`SceneLoopDistanceMode`、`SceneOrbitAxis`、`SceneOrbitCenterMode` 等 |
| `com.habitrain.core.api.scene.asset` | `SceneAssetDescriptor`、`SceneAssetCodec` |
| `com.habitrain.core.api.scene` | `SceneLimits`、`SceneMotionSettings`、`SceneContextResolver` |
| `com.habitrain.core.api.client.scene.compat` | `SceneMaterialKey`、`SceneBlockMeshAdapterRegistry` |
| `com.habitrain.core.api.scene.compat` | `SceneBlockCaptureAdapterRegistry` |

行为变更：`SceneMaterialKey.fromLayer(SceneMeshSet.Layer)` 已删除，改用 `SceneMaterialKey.fromLayerName(String layerName)`（`SceneMeshSet` 是内部实现类，公开层不再接受它的内部枚举）。

### 11.3 网络与同步契约

- S2C `habitrain_core:scene_instances`：`upserts` + `removals` + `clear` 的增量同步，**没有实例总数上限**；
  单包条目 ≤ 256、单实例 profile JSON ≤ 128 KiB（仅约束"包"，不约束"同时存在的场景数"）。
- C2S `habitrain_core:scene_instance_resync`：客户端在"没换会话但清空过本地状态"（对局结束）后请求全量重发；服务端有 1 秒冷却。
- 运动本身零流量：相位是 `gameTime` 的确定性函数。
- 实例只发给所属维度内、通过可见性过滤的玩家；JOIN/换维度/对局结束/全局开关重开都会全量重发。
- 客户端按 `assetHash` 共享 GPU 网格；显存配额只淘汰"当前无引用"的网格。

### 11.4 线程与持久化约束

- 写操作（含 `publishAsset`）必须在服务端主线程；读操作任意线程安全。
- API 实例是纯运行时状态：不写配置、不跨存档保存、服务器停止即清空；需要持久化请用地图级配置。

> 完整教程（含全部参数表、实战配方与排错表）见 **[移动场景 API 使用教程](移动场景API使用教程.md)**。

## 12. 关键红线

1. 不用内部实现类 `TMMRoles.registerRole()` 注册 v2 ADD/REPLACE；让 Core 管理一次性编译与可见性。
2. 不直接遍历内部实现 `TMMRoles.ROLES`；使用 `RoleCatalogApi`。
3. 不直接修改角色 Map 转职；局中转换使用 `RoleChangeApi`。`OnGamePlayerRolesConfirm` 只用于开局确认阶段的分配改写（抽奖自选 / 职业卡），不是局中 transform。
4. 不为可由 hooks 表达的行为注册永久全局监听器。
5. 不直接调用只读 `registrar()` 或客户端全局写形方法。
6. 不在 action handler 里重新解析已声明的结构化目标；使用 `RoleActionContext.target()`。
7. 不把 v2 preview 能力宣传为已经完成真实双端验收。
8. 不把 `muteReceive`、非 NAMEPLATE 名称渲染或完整视觉 HUD 当成稳定消费能力。
9. 不在服务端主线程之外调用 `SceneApi` 的写操作（注册表突变与发包都是同步的）。
10. 不把 API 实例当持久数据用；重启即清空，请在启动/开局时重新注册。
11. 只把 `com.habitrain.core.api.**` 当 API；`TMMRoles`、`SRERole`、`CcaRoleStateStore`、`RoleOverrideTickApplier`、`ForcedRandomRoleChangePolicy`、`MenuGateService`、`MenuAccessGuard` 等都是内部/上游实现，不要 import。
12. 不用 `MatchStateApi.modeId()` 判大厅/锁定（大厅是空串）；用 `MatchStateApi.phase(...)`。
13. 不用 `MenuGateApi.isAllowed(player)` 做门禁判断（它是名单查询，不是 `isBlocked` 的取反）。
14. `MatchEvents` 监听器只在 `onInitialize()` 注册一次（Fabric `Event` 无注销）。

## 13. 类族索引

| 类族 | 包 |
|---|---|
| 定义/补丁 | `role.v2.definition` |
| 受管行为 | `role.v2.behavior` |
| 状态 | `role.v2.state` |
| 动作 | `role.v2.action` |
| 客户端声明 | `role.v2.client` |
| 语音/聊天 | `role.v2.capability` |
| 技能补丁 | `role.v2.skill` |
| 职业书补丁 | `role.v2.book` |
| 实现层桥接 | `api.spi` |
| 对局阶段/结算/事件 | `api.match` |
| 服务端门控 | `api.menu` |
| 客户端查询 | `api.client.menu` / `api.client.scene` |
| 移动场景（公开） | `api.scene` / `api.scene.asset` / `api.scene.model` / `api.scene.compat` / `api.scene.client` |
| 移动场景（实现，不要引用） | `com.habitrain.core.scene.*`（`model` / `server` / `client` / `network` / `asset` / `compat` / `item`） |

## 14. 2.0.11 变更速查

| 变更 | 影响 |
|---|---|
| 新增 `com.habitrain.core.api.CoreApi` | 机器可读版本/能力查询：`apiVersion()` = `2.1`、`roleApiVersion()` = `2.0`、`supports(String)`、`capabilities()`；替代「按 mod 版本猜能力」 |
| `MenuGateClientApi` 迁到 `api.client.menu` | 客户端专用；新增 `isScreenAllowed()`、`isServerDedicated()`。旧位置 `api.menu.MenuGateClientApi` 不再存在 |
| 顶层 `api.MenuGateApi` 弃用 | `@Deprecated(forRemoval = true, since = "2.0.11")`，2.0.12 移除；用 `api.menu.MenuGateApi`。`isAllowed` 只是名单查询 |
| `ModeMapVoteSnapshot.phase()` 改枚举 | 返回 `ModeMapVotePhase`（`IDLE` / `MODE_VOTING` / `MAP_VOTING` / `SWITCHING_MAP` / `STARTING_MODE`），不再是 `String` |
| `OptionVoteApi.start` 新增标题/描述重载 | 旧重载仍在，标题默认「投票」；保留 voteId `"mode"` / `"map"` 被公开入口拒绝 |
| `VoteResult` 新增第 5 个分量 `cancelled` | 四参构造保留；取消 / 重置会以 `VoteResult.cancelled(voteId)` 回调 `onResolved` |
| `ModeMapVoteApi.lastFailure(level)` | 返回最近一次失败的可读原因 |
| 投票 API 线程契约 + C2S 限速 | 必须在服务端主线程调用；`option_vote_cast` 已限速 |
| `MatchEvents` 覆盖 `GameModeRegistry.start/stop` | 这类结算的 `winners()` / `participants()` 为空，只能用于逐局重置；监听器只注册一次 |
| `MatchStateApi.modeId()` 大厅返回空串 | 不再是锁定判据，用 `phase(...)` |
| `MatchSettlement.factionOfOrEmpty(UUID)` | 可区分「查不到」与「真的是乘客」；`factionOf` 仍兜底 `PASSENGER` |
| `GameModeIds.BLACKOUT` 恢复活跃语义 | 取消 `@Deprecated`；`canonical()` 固定用 `Locale.ROOT` |
| `api.spi.CoreLifecycleScope` 移出公开 API | 移到 `com.habitrain.core.internal.CoreLifecycleScope`；公开只读判定 `api.spi.CoreLifecycle.isActive()`；`freeze()` 只能在 core bootstrap 内生效并被强制执行 |
| `api.spi` 桥接补齐 | `SceneInstanceBridge`、`SceneRuntimeBridge`、`SceneAssetBridge`、`SceneCaptureBridge`、`SceneConfigBridge`、`SceneClientBridge`、`MenuGateBridge`、`MenuGateClientBridge`、`VoteBridge`、`RoleSpi`；见 §8.7 |
| 场景值对象迁入 `api.scene.*` | 见 §11.2.1；`SceneMaterialKey.fromLayer(SceneMeshSet.Layer)` → `fromLayerName(String)`；`SceneClientApi.instances()/instance()` 返回 `SceneInstanceView` |
| 场景监听器移除原因更正 | 只有 `despawn` / `expired` / `dimension_changed` / `reset`，**没有 `replaced`** |
| 角色动作目标条件更正 | `maxDistance` 对 `BLOCK_POS` 与 `PLAYER_UUID` 生效；`requireLineOfSight` / `requireTargetAlive` 仅 `PLAYER_UUID` |
| 缺 codec 的 spec 行为更正 | `build()` 抛 `IllegalStateException` 并回滚整个 provider 批次，不是 `INVALID` 诊断行 |
| `RoleCapabilityApi` / `RoleActionClientApi` 细节 | `bindAdapter` 是无归属公开写入口（冻结后不应再写）；`clear()` 保留 push 监听器；隔离组在断线与对局结束时清空 |
| 停电阵营与历史读取方式 | `com.habitrain.core.game.blackout.BlackoutRoleManager` 不存在；改读 `MatchSettlement.factions()` / `factionOfOrEmpty(...)` 与 `RoleCatalogApi` |

接口变更时，应同时更新本手册、`README.md`、`docs/使用教程.md`、`docs/移动场景API使用教程.md`、角色扩展 v2 教程以及工作区角色扩展 skill。
