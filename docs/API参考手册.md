# 哈比列车核心 API 参考手册

> HabiTrain Core 2.0.2 · Role Extension API 2.0 · Minecraft 1.21.1 · Fabric · Java 21
> 公开根包：`com.habitrain.core.api`
> 文档日期：2026-08-18

本手册用于快速查接口和契约。角色扩展的端到端示例、限制和排障见 [角色扩展 API v2 使用教程](角色扩展API-v2使用教程.md)。

## 1. 公开边界与生命周期

| 边界 | 说明 |
|---|---|
| `com.habitrain.core.api` | 公开 API 根包 |
| `com.habitrain.core.api.role` | 兼容稳定的角色覆盖 v1 |
| `com.habitrain.core.api.role.v2` | 完整角色扩展 v2（preview / experimental） |
| 其他 `com.habitrain.core.*` | 内部实现，除文档明确要求的客户端触发桥外不要依赖 |

任务、模式和 v1 覆盖在初始化期注册，并在服务端启动阶段冻结。v2 通用与客户端声明分别通过 Fabric entrypoint 注册：

```text
habitrain:role_extensions
habitrain:role_client_extensions
```

v2 provider 的一次 `register(...)` 是事务：抛异常时该 provider 整批回滚。所有 ID、entryKey 和翻译键应稳定且非本地化。

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
| `register(modId, modeId, mode)` | 注册键为 `modId:modeId` |
| `start(fullId, level)` | 每个世界仅一个主动模式 |
| `stop(level, result)` | `onEnd` 后 finally `onCleanup` |
| `tickAll(server)` | 只 tick 主动表中的模式 |
| `getActiveForLevel(level)` | 主动表优先，否则检查被动 `isActive` |

`WinResult` 提供 `singleWinner`、`noWinner`、`forceEnd`。

## 4. 投票与道具

### 4.1 `OptionVoteApi`

```java
boolean start(ServerLevel level, String voteId,
              List<VoteOption> options, int durationSeconds,
              Consumer<VoteResult> onResolved);
boolean cast(ServerLevel level, ServerPlayer voter, @Nullable String optionId);
boolean isActive(ServerLevel level);
void cancel(ServerLevel level);
```

### 4.2 `ModeMapVoteApi`

`start(level)`、`start(level, config)`、`cancel(level)`、`isRunning(level)`、`getSnapshot(level)`。

`ModeMapVoteConfig` 可覆盖模式/地图投票时长和候选 ID；`ModeMapVoteSnapshot` 暴露阶段、已选模式、已选地图和剩余秒数。

### 4.3 `ItemReclaimHelper`

`tagGrantedItem` 标记物品，`matchesGrant` 检查，`reclaim` 从玩家物品栏移除，`reclaimForTask` 先运行任务回收回调再按 full ID 回收。

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

广播类事件需要按语义选择作用域。`GLOBAL_WHILE_ENABLED` 只有配置 `allowGlobalHooks=true` 才应使用。

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

`assign(player, role, options)`、`transform(player, role, cause)`、`remove(player, cause)`、`current(player)`、`history(player)`。变更由事务处理 alias、旧角色清理、映射、历史、初始化、hooks 和同步。

### 8.3 `RoleStateApi`

注册返回 `RoleStateKey<T>`；运行时使用 `get/set/reset`。状态 scope：PLAYER/WORLD/ROUND；sync：NONE/OWNER/OWNER_AND_TRACKING/ALL/SERVER_ONLY；reset cause：ROLE_LOST/ROLE_ASSIGNED/ROUND_END/ROUND_START/MANUAL。

WORLD/PERMANENT persistence 或非 NONE sync 必须提供 `Codec<T>`。声明 `dataVersion > 1` 时迁移链必须从 v1 连续覆盖到当前版本。

### 8.4 `RoleActionApi` / `RoleActionClientApi`

服务端：`spec/specs/specsFor`、`dispatch/receiveC2S`、`sendTo`。客户端：`send`、`add/removePushListener`；结果按 `(actionId, sequence)` 关联，并处理 timeout/disconnect。

动作方向：C2S/S2C/BIDIRECTIONAL。目标 codec：NONE、PLAYER_UUID、BLOCK_POS、ENTITY_ID。只有 PLAYER_UUID 支持平台级 alive/distance/line-of-sight 条件。

平台在 handler 前执行 provider/entry、握手、方向、大小、速率、序列、当前角色、存活、冷却和目标验证。

### 8.5 `RoleCapabilityApi`

查询 `voices/chats`，用 `status/supports` 检查适配器，用 `evaluateVoice/evaluateChat` 评估策略。VOICE 由 voicechat 适配器消费；chat `muteSend` 生效，`muteReceive` 当前 experimental，不能依赖逐接收者过滤。

### 8.6 `RoleDiagnostics`

`report()`、`entries()`、`aliases()`、`snapshotInfo()`。用于查看 disabled/conflict/invalid/pending，不要从有效目录猜诊断状态。

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
- 对局中修改：编译为 pending，下一局边界激活。
- 当前 round snapshot 对局中不变。

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

## 11. 关键红线

1. 不用 `TMMRoles.registerRole()` 注册 v2 ADD/REPLACE；让 Core 管理一次性编译与可见性。
2. 不直接遍历 `TMMRoles.ROLES`；使用 `RoleCatalogApi`。
3. 不直接修改角色 Map 转职；使用 `RoleChangeApi`。
4. 不为可由 hooks 表达的行为注册永久全局监听器。
5. 不直接调用只读 `registrar()` 或客户端全局写形方法。
6. 不在 action handler 里重新解析已声明的结构化目标；使用 `RoleActionContext.target()`。
7. 不把 v2 preview 能力宣传为已经完成真实双端验收。
8. 不把 `muteReceive`、非 NAMEPLATE 名称渲染或完整视觉 HUD 当成稳定消费能力。

## 12. 类族索引

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

接口变更时，应同时更新本手册、`README.md`、`docs/使用教程.md`、角色扩展 v2 教程以及工作区角色扩展 skill。
