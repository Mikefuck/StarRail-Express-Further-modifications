# habitrain_core 公开 API 层审核（mod_version 2.0.11）

审核范围：`src/main/java/com/habitrain/core/api/**`（225 个顶层类型）、`src/test/java/com/habitrain/core/api/**`、
顶层公开入口 `CoreApi` / `GameModeRegistry` / `TaskRegistry` / `ItemReclaimHelper` / `OptionVoteApi` /
`ModeMapVoteApi` / `GameModeIds` / `WinResult` / `VoteOption` / `VoteResult` / `GameStateProvider` /
`ModeMapVoteConfig` / `ModeMapVotePhase` / `ModeMapVoteSnapshot` / `TaskCategory` / `TaskDefinition` /
`TaskInstance`。
消费方样本：`哈比列车抽奖补齐`（`habitrain_lottery`）。未做任何修改。未访问 `backup/`。

**总体结论**：`api/` 包内的反向依赖在 2.0.11 已被**彻底切断**（0 处内部包引用）。
剩余风险集中在三处：(a) 守卫测试的覆盖面与"装配-次数/生命周期"门禁的**故障路径**；
(b) 若干**默认方法 / 空串 / 空 Optional 把"没数据"与"没实现/未装配"混为一谈**；
(c) 版本与能力协商是**硬编码**的，不与实际装配状态、`gradle.properties` 版本联动。

---

## 1. API 面清点（按子系统）

225 个顶层类型，分布：`role.v2.*` 127、`scene.*` 63、`spi` 16、`match` 7、`role`(v1) 13、
顶层 13、`menu` 1、`client.*` 9。

| 子系统 | 主门面（下游应调用的类） | 关键入口 | 备注 |
|---|---|---|---|
| **顶层 / 版本** | `api/CoreApi.java` | `apiVersion()`:34, `roleApiVersion()`:39, `supports(String)`:50, `capabilities()`:72 | 唯一的机器可读版本+能力入口 |
| **SPI 定位器** | `api/spi/CoreSpi.java` | 桥接访问器 `:202-236`，查询 `isSreGameBlocking`:240, `matchPhase`:265, `matchModeId`:278 | 只读；`install*` 是 core 自用 |
| **SPI 角色** | `api/spi/RoleSpi.java` | `catalog/change/…/state`:102-136；`override()`:138；`actionClientOrNull()`:143 | 未装配抛 `IllegalStateException`:219 |
| **角色扩展 v2 目录** | `api/role/v2/RoleCatalogApi.java` | `instance()`:22, `find`:40, `canonicalize`:46, `effectiveRoles(RoleQuery)`:62, `currentSnapshot`:104, `roundSnapshot`:112, `lastEndedSnapshot`:121, `restore`:130 | 抽奖补齐的主入口 |
| **角色变更 v2** | `api/role/v2/RoleChangeApi.java` | `assign`:37, `transform`:45/58, `remove`:68, `current`:76, `history`:79 | `current(player==null)` 返回 null |
| **角色强制队列** | `api/role/v2/RoleForceApi.java` | `queueExact`:32, `queueFaction`:38, `queueRoleType`:44, `isQueued`:46, `clear`:48 | 3 个 queue 返回 boolean 无失败原因 |
| **角色状态 v2** | `api/role/v2/state/RoleStateApi.java` | `spec`:44, `get/getOrNull` 三重载 `:64,80,89,96,104,107`, `set`:111/120/123, `reset`:133/136, `freeze`:141 | 抛/不抛两种策略已分立 |
| **角色动作 v2** | `api/role/v2/action/RoleActionApi.java` + `RoleActionClientApi.java` | 服务端 handler 注册；客户端 `instance()`:29 / `send`:57 | 客户端 `instance()` 非客户端环境抛 ISE:37 |
| **角色诊断 v2** | `api/role/v2/RoleDiagnostics.java` | `entries()`, `report()`, `aliases()` | 禁用/无效声明的唯一出口 |
| **角色 v1 覆盖** | `api/role/RoleOverrideApi.java` | `registerReplace`:37, `registerModify`:41, `roleId`:48, `getEffectiveEntries`:67, `isReplaced`:71, `getReplacement`:75 | 明确声明"当前稳定、未废弃" |
| **角色客户端扩展** | `api/role/v2/client/RoleClientExtensionApi.java` + `RoleClientExtensionRegistrar.java` | HUD/皮肤/屏幕/本能/名字渲染注册 | `@Environment(CLIENT)` |
| **比赛状态** | `api/match/MatchStateApi.java` | `phase(Level)`:20, `hasLeftLobby`:25, `modeId(ServerLevel)`:42 | 单点判定入口 |
| **比赛事件** | `api/match/MatchEvents.java` | `STARTED`:31, `ROUND_ENDED`:43 | 注册表模式也发（`:141/:172`） |
| **比赛结算** | `api/match/MatchSettlement.java` / `MatchWinKind` / `MatchWinFaction` | `winners()`:63, `participants()`:59, `factionOf`:73, `factionOfOrEmpty`:84 | 抽奖补齐用来发奖 |
| **游戏模式注册** | `api/GameModeRegistry.java` | `register`:54, `start`:104, `stop`:148/210, `tickAll`:219, `getActiveForLevel`:259, `hasExplicitActiveMode`:287, `isActiveInLevel`:306, `freeze`:325 | `GameMode` 回调接口在 `api/GameMode.java` |
| **任务引擎** | `api/TaskRegistry.java` | `register`:22/29, `getAll`:37, `getByGameMode`:51, `getByCategory`:65, `freeze`:78 | 派发池在内部 `task.TaskPoolBuilder`（不在 API 内） |
| **菜单门控（服务端）** | `api/menu/MenuGateApi.java` | `isBlocked`:27/37, `isEnabled`:53, `isAllowed`:66 | `api/MenuGateApi.java` 是 **@Deprecated 转发壳** |
| **菜单门控（客户端）** | `api/client/menu/MenuGateClientApi.java` | `isScreenAllowed`:29, `isServerDedicated`:34 | `@Environment(CLIENT)` |
| **投票（通用选项）** | `api/OptionVoteApi.java` | `start`:29/44, `cast`:59/66, `isActive`:73, `cancel`:84 | 保留 id `mode`/`map` 被拒:49 |
| **投票（模式→地图）** | `api/ModeMapVoteApi.java` | `start`:17/28, `lastFailure`:36, `cancel`:40, `isRunning`:44, `getSnapshot`:48 | 服务端主线程 only |
| **场景（服务端全能力）** | `api/scene/SceneApi.java` | 实例 CRUD `:225-461`、地图级 `:468-496`、资产 `:567-640`、捕获 `:652`、事件 `:669`、诊断 `:683` | 700 行的大门面 |
| **场景（轻量门面）** | `api/scene/SceneMotionApi.java` | `getSettings`:26, `getProfile`:33, `setProfile`:40, `isSceneActive`:50, `startScene`:59 | 与 `SceneApi` 同一实现的两层门面 |
| **场景（客户端）** | `api/scene/client/SceneClientApi.java` | `mapSceneState`:41, `instances`:61, `instancesInCurrentDimension`:66, `isMeshReady`:87, `manifest`:92, `requestResync`:107 | `@Environment(CLIENT)` |
| **场景兼容桥（服务端/客户端）** | `api/scene/compat/*`（6 类）与 `api/client/scene/compat/*`（7 类） | `SceneBlockCaptureAdapters.register`, `SceneBlockMeshAdapters.register` | 两套并行注册表 |
| **场景值对象** | `api/scene/model/*`（30 类）、`api/scene/asset/*`（5 类） | `SceneInstanceSpec.builder`, `SceneAssetCodec`, `SceneLimits` | 2.0.11 起值对象入住公开层 |
| **SPI 桥接接口** | `api/spi/*Bridge.java`（11 个） | `SreRuntimeBridge`, `TaskPoolCacheBridge`, `ExtraSlotReclaimBridge`, `Scene*Bridge`(6), `MenuGateBridge`, `MenuGateClientBridge`, `VoteBridge` | 下游**不应**直接调用 |
| **其他** | `api/ItemReclaimHelper.java`, `api/GameStateProvider.java`, `api/GameModeIds.java`, `api/WinResult.java`, `api/VoteResult.java`, `api/VoteOption.java`, `api/ModeMapVoteConfig.java` | `reclaim`:64, `canonical`:36, `isBlackout/isRepair/isMurder`:58/69/81 | |

**同名重复**：`api/MenuGateApi` 与 `api/menu/MenuGateApi` 是唯一的同名类型对（见 A-13）。

---

## 2. 边界完整性

### 2.1 `api/**` 内的内部包 import

```
检索式：import com\.habitrain\.core\.(betel|client|config|game|internal|misc|mixin|network|persist|role|scene|task|util|vote)\.
范围：src/main/java/com/habitrain/core/api/**  （225 文件）
结果：0 处匹配
```

同一检索式在**全文件文本**（不限 import 行，含 javadoc / 全限定引用）下同样为 **0 处**。
即 2.0.11 声称的"反向依赖全部经 `api/spi` 切断"在 `src/main/java/.../api` 内**成立，未发现违规**。

### 2.2 `ApiLayerDependencyGuardTest` 到底强制了什么

`src/test/java/com/habitrain/core/api/ApiLayerDependencyGuardTest.java`（175 行，3 个 `@Test`）：

1. `apiPackageNeverReferencesImplementationLayers()`:69 —— 遍历 `src/main/java/com/habitrain/core/api` 下所有 `*.java`，
   对每个文件的**全文**做 `containsReference(text, prefix)` 子串匹配，前缀取
   `FORBIDDEN_PREFIXES`:38-53（14 个包前缀：`betel./client./config./game./internal./misc./mixin./network./persist./role./scene./task./util./vote.`）
   加 `FORBIDDEN_ROOT_CLASSES`:56-66（9 个 core 根包实现入口类名，如
   `com.habitrain.core.HabiTrainCore`、`…NetworkRegistrar`、`…ModTickHandler`）。
   命中即加入 `violations`，最后 `assertTrue(violations.isEmpty())`。
   `containsReference`:134 要求前缀前一字符不是标识符字符且不是 `.`（排除 `api.scene.` 被误判为 `scene.`）。
2. `apiSpiPackageIsTheOnlyBridgeToImplementation()`:93 —— 断言 `api/spi` 下存在 14 个指定桥接文件，
   且 `spi/CoreLifecycleScope.java` **不存在**（A1 回归守卫）。
3. `apiPackageExposesNoPublicTestHooks()`:110 —— 用正则禁止 `api` 下出现
   `public static <T> unfreezeForTests/resetTickGuards/clearForTests(`。

**它没有强制的东西（关键）**：

- **不扫测试源码**。`locateApiSources()`:151 只解析 `src/main/java/.../api`。实测
  `src/test/java` 下有 **193 处**内部包 import，其中就在 `api` 测试包内：
  `api/ApiValueObjectTest.java:3 import com.habitrain.core.network.GameEndTransitionPayload`、
  `api/GameModeRegistryTest.java:3 import com.habitrain.core.internal.CoreLifecycleScope`、
  `api/role/v2/RoleCatalogApiTest.java:4-7 import com.habitrain.core.role.catalog.RoleCatalogImpl` 等。
  测试可以（也必须）这么做，但守卫测试因此**无法证明"api 语义上不需要实现层"**。
- **不查反射 / 字符串 / ServiceLoader**。`Class.forName("com.habitrain.core.…")`、
  `core.internal.CoreLifecycleScope` 拆段拼接（`CoreLifecycle.java:31` 就用了这个技巧）都能绕过文本匹配。
- **根类名单是硬编码白名单**。`:56-66` 只列了 9 个类；`com.habitrain.core` 根包**新增**任何实现入口
  （例如新的 Registrar）不会被发现，也无测试断言该名单与根包实际内容一致。
- **只查"引用"，不查"签名泄漏/诱导"**。`api/TaskDefinition.java` 的 Builder 公开
  `filterAvailableTasks(List<TaskDefinition>, player)`（见 `api/GameMode.java:67`）等 API 本身没问题，
  但 `api/TaskRegistry.java:47,62` 把 **javadoc 指向实现类** `TaskPoolBuilder.getPool(...)` /
  `isTaskAllowedForPool`——那是 `com.habitrain.core.task` 下的**下游不可编译访问**的类型。
  文档诱导 + 无守卫（该处因 javadoc 用短名 `{@code TaskPoolBuilder.getPool(...)}` 而非全限定名，
  且 `com.habitrain.core.task.` 前缀未出现在文本中，恰好未被文本匹配命中）。
- **不校验 `@Environment` 合规**（见 A-11），也不校验 `api.spi` 之外是否有新的桥接被偷加。

---

## 3. 契约质量（下游真实使用的面）

### 3.1 `api/spi/CoreSpi.java`
- 装配 `install*` 全部经 `install()`:157：`!CoreLifecycle.isActive()` → **warn + 静默 return**（`:158-161`）。
  对 core 自用可接受；但方法本身 `public`，第三方误调得到的是"什么都没发生"。
- 单次装配用 `AtomicBoolean`:79-90 + `compareAndSet`:162 → 重复装配 warn + ignore（`:163-165`）。
- 未装配时 `sceneInstances()`:202 等返回 **NOOP 默认实现**（`:55-63`），所有桥接的 `default` 方法
  都是安全空值（逐个核对 `SceneInstanceBridge.java` 全 30 个方法、`MenuGateBridge.java:15-35`、
  `VoteBridge.java:27-73`），因此不会 NPE。**但 `SreRuntimeBridge`/`TaskPoolCacheBridge`/
  `ExtraSlotReclaimBridge`/`RoleSpi.RoleOverrideBridge`/`RoleSpi.RoleActionClientBridge` 是抽象方法**，
  NOOP 实例调用会 `AbstractMethodError`；好在每个访问点都包了 `try { } catch (Throwable)`：
  `isSreGameBlocking`:244-249、`resolveActiveForPlayer`:256-262、`matchPhase`:269-275、
  `matchModeId`:282-288、`invalidateTaskPoolCacheAll`:296-300、`invalidateTaskPoolCache`:317-321、
  `reclaimExtraSlots`:333-338。**这是契约的关键前提**：任何新增桥接查询必须沿用 try/catch 包裹。
- **误导值**：`matchModeId(null/未装配/bridge 抛异常)` 一律返回 **`""`**（`:279,281,284,287`）。
  `resolveActiveForPlayer` 用 `Optional`（好），`matchModeId` 用空串（差）——两种风格并存。
- 错误策略不一致：`isSreGameBlocking` 异常时 **返回 true（fail-closed，拒绝开局）**:248，
  而同级查询 `matchPhase` 异常时返回 `UNKNOWN`（也是 fail-closed，见 3.3），
  `resolveActiveForPlayer`/`matchModeId` 异常时 fail-**open**（空）。同一个类里三种方向，文档未汇总。
- 线程契约：**未声明**。访问器是 `volatile` 读，装配只在 bootstrap 阶段写入，读侧任意线程安全；
  但类 javadoc 没有像 `GameModeRegistry`:24-36 / `OptionVoteApi`:16-17 那样写明。

### 3.2 `api/match/MatchStateApi.java`
- `phase(Level)`:20 直接委托；`level == null` → `MatchPhase.UNKNOWN`（经 `CoreSpi.matchPhase`:266）。
  客户端侧 `ClientLevel` 与未装配环境**都**得到 `UNKNOWN`，而 `UNKNOWN.hasLeftLobby() == true`，
  意味着**客户端调用者永远得到"已离开大厅"**。javadoc:34-37 已就此警告，但方法与 `GameMode.hasLeftLobby` 的
  直觉命名仍是下游误用高发点（抽奖补齐 `CardUseService.isGameRunning` 在 `player.getServer()==null` 分支
  正是走这条路，`:415`）。
- `modeId(ServerLevel)`:42 返回 `""` 有 **3 种含义**（大厅 / 读不到 / 未装配），javadoc:36 明确承认
  "空串仍不等于本局没有模式"。无 `Optional` 重载，无 `@Nullable`，下游无法区分。
- 无方法声明线程约束（实现走 `ServerLevel`，实际天然限主线程）。

### 3.3 `api/match/MatchPhase.java`
- `hasLeftLobby()`:24 = `this != INACTIVE`，`UNKNOWN` fail-closed（`:19-22` 已文档化）。
  **命名与语义反向**是最大歧义源：`UNKNOWN` 表示"假设已离开大厅"，与"未知"字面含义冲突。
- `fromStatusName`:28 对 `null`/空白返回 `UNKNOWN`，`Locale.ROOT` 已处理（`:33`），正确。

### 3.4 `api/GameModeRegistry.java`
- 类级线程契约出色（`:24-36`）：注册/冻结限启动期；`ACTIVE_MODES`/`PASSIVE_CACHE` 是并发容器，
  只读查询任意线程；`start`/`stop`/`tickAll` 仅主线程（复合操作非原子，已声明）。
- 空值处理**不一致**：
  - `start`:104 对 `level`/`fullId` 无判空 → `level.dimension()` NPE / 未注册抛 IAE（后者有文档 `:102`）。
  - `stop(ServerLevel, WinResult)`:148 对 `level` **无判空**（`level.dimension()` NPE）；
    对比 `hasExplicitActiveMode`:287 与 `isActiveInLevel`:306 **有**判空。
  - `stop(level, null)` 合法：`:200-202` 显式处理；但 `:168` 直接 `result.getReason()`，
    若 `WinResult.getWinners()` 非空而 `reason == null` 则日志打出 null（不抛）。
  - `register(modId, modeId, null)`:63 在 `mode.getDisplayName()` 处 NPE，未提前 `requireNonNull`。
  - `getActiveForLevel(ServerLevel)`:259 无判空，`get(null)` 在 `:68` 抛 `NullPointerException`
    （Map.get 对 null key 在 ConcurrentHashMap/LinkedHashMap 上均 NPE）。
- `start`:117-138 的失败回滚只覆盖 `onPreStart`/`onStart` 抛异常；`ACTIVE_MODES.put` 之后
  `CoreSpi.invalidateTaskPoolCache` 抛异常不会回滚（该调用已内部 catch，实际安全）。
- `tickAll`:235-240 用 `ACTIVE_MODES.get(levelKey) == mode` 做重入保护，正确。
- `getActiveForLevel` 被动模式多命中时取注册序第一（`:271-276`，已 warn，A4）。

### 3.5 `api/role/v2/RoleCatalogApi.java`
- **`currentSnapshot()`:104、`roundSnapshot()`:112、`lastEndedSnapshot()`:121 是 `default` 且返回
  `Optional.empty()`**。生产实现 `role/catalog/RoleCatalogImpl.java:319/324/329` 有覆盖，
  但任何替换实现（`RoleSpi.Slot` 可被覆盖，见 4.3）静默退化为"永远没有快照"，
  调用方**无法区分"没数据"与"没实现"**。抽奖补齐 `GrantRoleSnapshot.endedSnapshot()` 之所以
  在 `:40-43` 双重兜底 + `catch (Throwable)`，正是因为这条契约不可信。
- `effectiveRoles()`:52 亦为 default 转发到 `effectiveRoles(RoleQuery.generic())`，属良性默认。
- `resolveStored(String)`:71 未声明 `null` 入参行为（`RoleKey.tryParse` 那条路径返回 `Optional.empty()`，
  但 javadoc 未承诺）。
- `snapshot()`:92 返回 `RoleSnapshotId`（record，`RoleSnapshotId.version()` 为单调 long），
  这是唯一可用于"这局用的是哪一版目录"的凭据；javadoc:94-103 明确提示结算代码应优先
  `lastEndedSnapshot()`，否则会读到下一局 lobby 覆盖层——**这是下游最易踩的时序陷阱**。

### 3.6 `api/role/v2/RoleForceApi.java`
- `queueExact`/`queueFaction`/`queueRoleType`:32/38/44 返回 `boolean`，
  **javadoc 完全未说明 false 的原因**（未装配？非大厅？玩家已有卡？）。抽奖补齐在
  `CardUseService.java:156,174` 仅用 `isQueued()` 做前置校验，一旦 `queueExact` 返回 false 就没有可诊断信息。
- `queueRoleType:44` 的 `int roleTypeId` 只有 javadoc 里的 1–5 映射表，**无枚举、无校验宣告**；
  越界值的后果未文档化。
- `isQueued(@Nullable UUID)`:46 / `clear(@Nullable UUID)`:48 标了 `@Nullable`，行为（null → false / no-op）
  未在 javadoc 写明。`DefaultHolder`:21-26 未装配时经 `RoleSpi.force()` 抛
  `IllegalStateException`（`RoleSpi.java:219`），即**未装配 → 抛异常**，而非返回 false。

### 3.7 `api/role/v2/RoleQuery.java`
- 不可变、构建器风格，`build()`:223 无校验。**实测缺陷**：
  - `mapAbilities(SRERole.SpecialMapRoleMap...)`:157 与 `factions(RoleFaction...)`:162、
    `tags(String...)`:203 只做 `Collections.addAll`，**不判空也不过滤 null 元素**；
    传入 `null` 数组 → NPE；数组含 null → 集合含 null，后续过滤期才可能出问题。
  - `playerCount`:213 用**负数表示关闭**（javadoc:209-211），与 `-1` 默认值耦合；
    传 0 与传 -1 语义差别巨大但无校验。
- **废弃字段仍在公开面上**：`includeDisabled()`:84 与 `includeInvalid()`:94 已 `@Deprecated`，
  javadoc 明说"对目录过滤无语义"（`:75-82`、`:85-92`）；`:112-113` 的契约说明它们是
  "forward compatibility"占位。下游按旧文档传 `includeDisabled(true)` 会**什么都不发生**。
- **`side()` 是纯装饰**：`QuerySide` 被存下（`:36,:70`）但 javadoc:23-24 承认
  "currently resolves to the same shared role set for every value"。同样是"设了没用"。
- `ordering()`/`purpose()` 通过 `Objects.requireNonNull` 校验（`:143,:219`），正确。

### 3.8 `api/role/v2/EffectiveRoleProfile.java`
- 记录紧凑构造器 `:76-85` 对 `key`/`source`/`mood`/`specialMapRole` 判空，
  三个 `List` 字段用 `Objects.requireNonNullElse(..., List.of())` 兜底 → **null 容忍且不可变**，质量好。
- `from(RoleKey, SRERole, Source)`:122 只 `requireNonNull(role)`；`relationKeys`:178 对 null 集合返回 `List.of()`。
- **兼容构造器** `:92-119`（无 `opposingTwoWay`）默认 `true`——即"老调用方隐式得到双向对立关系"。
  javadoc:87-91 已说明，但对"重建 overlay"语义是**静默的语义默认**（`:163-176 toOverlay` 会把它写回）。
- **字段数 39**，位置式构造器 + 手写兼容重载：任何字段新增都必须同步 3 处（canonical ctor、
  compat ctor、`withOverlay`/`toOverlay`），易错。无 `builder`、无 `equals` 语义说明
  （record 自动 equals 含全部 39 字段，其中 `List` 字段按内容比较，可用但未文档化）。

### 3.9 `api/role/v2/RoleSnapshot.java`
- 不可变快照，构造器`:44` 判 `id`，`roles`/`aliases`/`replacedTargets` **未判空**
  （`:51-53` 直接 `new LinkedHashMap<>(roles)` → `roles == null` 抛 NPE；`aliases == null`、`replacedTargets == null` 同样）。
  简短构造器`:33` 也走同一条路。
- `canonicalize(ResourceLocation id)`:91 **不判空**，`aliases.containsKey(null)` 在 LinkedHashMap 上抛 NPE；
  随后 `RoleKey.of(cur == null ? id : cur)`:100 兜底 `cur == null` 但兜不住 `id == null`。
- `canonicalize` 有环保护（`:93-99` `seen`），正确。
- `get(ResourceLocation)`:114 标 `@Nullable`（与同文件 `find` 返回 `Optional` 并存，两种风格）。
- `find`:84 只查 `roles` 一次，**不跟随 replacement 重定向到 target 的宿主条目**（`canonicalize` 只跟 alias）。
  javadoc:83 声称 "following aliases and replacements" —— 与实现不完全一致（`isReplaced` 标记的是被隐藏的 target id）。
- `withoutRuntimeHandles()`:146 是历史归档的关键（剥离 `SRERole` live 句柄）；下游必须用
  `profile()` 而非 `role()`（`EffectiveRole.java:59-62` 已在注释中说明）。

### 3.10 `api/menu/MenuGateApi.java`
- `isBlocked(player)`:27：`player == null` → false；否则委托 `CoreSpi.menuGate().isBlocked(player)`（含专用服判定）。
- `isBlocked(player, server)`:37：**在进入桥接**之前**先自己判定 `server == null || !server.isDedicatedServer()` → false**
  （`:38-40`）。这条路径**绕过了桥接实现**，是 4.4 里"替换桥接后仍可绕过"的具体位置。
- `isEnabled()`:53 只在非专用服上被 `isBlocked` 忽略——javadoc:48-52 已说明"总开关 ≠ 本服启用"。
- `isAllowed()`:66 被明确警告**不是** `isBlocked` 的取反（`:60-64`）：专用服 + 门控关闭时
  未授权玩家同时得到 `isBlocked()==false` 与 `isAllowed()==false`，`null` 玩家"既不拦截也不允许"。
  文档质量高，但把这种"两个布尔都不是你想要的那个"暴露给下游本身就是设计脆弱点。

### 3.11 `api/client/menu/MenuGateClientApi.java`
- `isScreenAllowed()`:29 **fail-open**：桥接默认 `true`（`MenuGateClientBridge.java:19-21`），
  专用服 / 尚未收到 `MenuGatePayload` 时返回 `true`，javadoc:17-18 明说这是为了避免误锁页面。
  即客户端门控在"状态未到"窗口内是**不设防**的；真正的拦截必须在服务端（设计如此，但下游若只用
  客户端判定做安全边界就是漏洞）。
- `isServerDedicated()`:34 桥接默认 **`false`**（`MenuGateClientBridge.java:31-33`）。
  默认值方向与 `isScreenAllowed` 相反（一个 fail-open 一个 fail-closed），文档未解释这处不对称。
- `@Environment(CLIENT)`:18 已按 B23 从 `api.menu` 迁入 `api.client.menu`；但如 A-11，
  注解对"非入口点"无加载期强制力，专用服一旦触碰仍 `NoClassDefFoundError`（`:11-16` 已说明）。

---

## 4. 装配 / 安全门禁

### 4.1 `CoreSpi` javadoc 的三条声明逐条核对
| 声明 | 位置 | 结论 |
|---|---|---|
| 装配必须在 `CoreLifecycle` 作用域内 | `CoreSpi.java:46-47`, 实现 `:158-161` | **成立**（第三方调用 warn+ignore，桥接不被替换） |
| 每个桥接只能装配一次 | `:47`, 实现 `:162-165` | **成立**，但见 A-01（先置位后执行） |
| `clearForTests()` 包私有 | `:48`, 实现 `:342` | **成立**（`static void`，包私有），且被守卫测试 `:110-125` 回归保护 |

### 4.2 `CoreLifecycle` 是否真能阻止第三方 install
`api/spi/CoreLifecycle.java`：
- `installProbe(Class<?> owner, BooleanSupplier)`:55 校验 `owner.getName()` 等于编译期常量
  `"com.habitrain.core." + "internal.CoreLifecycleScope"`（`:31`，刻意拆段以免公开层出现实现包全前缀）。
  `Class.getName()` 返回**定义类**的名字，无法伪造 → 只有真正的 core 类能装配探针。`:58-61` 否则抛 ISE。
- `INSTALLED.compareAndSet`:62 保证只装配一次，重复抛 ISE。
- `isActive()`:39 只读；类中**没有** `run()` / 无任何可驱动作用域的入口 → 下游无法凭空"进入作用域"。
**结论**：`CoreLifecycle` 本身确实阻止了第三方装配与第三方驱动作用域。

### 4.3 残余绕行路径（按可利用性排序）
1. **`CoreSpi.install*` 的"先置位、后执行"**（`:157-167`）：`installed.compareAndSet(false,true)` 在
   `apply.run()` **之前**。若 core bootstrap 中第一次 `apply.run()` 抛异常（例如
   `SceneInstanceService.getInstance()` / `ConfigManager.getInstance()` 在专用服上初始化失败），
   该槽位**永久标记为已装配**、且 `isSceneFullyInstalled()`:184 会开始返回 true，而桥接仍是 NOOP 或半装配。
   后续无法补救（重装被 `:163` 拒绝）。**这是装配门禁最实际的故障路径**。
2. **`CoreLifecycleScope.isCoreCaller()` 只做类名前缀匹配**（`internal/CoreLifecycleScope.java:57-65`，
   `startsWith("com.habitrain.core.")`）。任何"类名以 `com.habitrain.core.` 开头"的类都能进入作用域。
   构造这样一个类需要下游把类放进 core 的包名空间（跨 mod 同名包，Fabric 不在加载期强制包归属），
   属于**依赖类加载器解析顺序的残余攻击面**，而非被明确封堵。注意这对公开层不可见——
   `api/spi` 侧的门禁（`CoreSpi`:158、`RoleSpi.Slot`:191）只检查 `isActive()`，前缀校验在 `internal` 层，
   且**不在** `ApiLayerDependencyGuardTest` 的扫描范围内（该测试只扫 `api/`）。
3. **`Slot` 的装配在 `RoleSpi` 中比 `CoreSpi` 更严**：`Slot.install`:189-199 在
   `!CoreLifecycle.isActive()` 时**抛 `IllegalStateException`**（而不是 warn+ignore），且已装配时抛异常。
   两个 SPI 类对同一门禁的失败策略不同（一个 warn、一个 throw），javadoc 未解释。
4. **`MenuGateApi.isBlocked(player, server)` 的专用服短路**（`api/menu/MenuGateApi.java:38-40`）
   在桥接**之外**判定 `server.isDedicatedServer()`。即使将来桥接被换成更严的实现，
   传一个 `isDedicatedServer()==false`（或 `null`）的 `MinecraftServer` 仍直接放行；
   这是唯一一个"公开层自带判定逻辑、与实现层判定可分离"的门禁方法。
5. `GameModeRegistry.stop(ServerLevel)`/`TaskRegistry.freeze()`/`resetLifecycle()` 在非作用域调用时
   **warn + 静默返回**（`GameModeRegistry.java:326-329,363-366`；`TaskRegistry.java:79-82,97-100`）：
   下游若误以为调用成功，会得到"注册表没冻结/模式没停"的静默失败。

---

## 5. API 版本与兼容协商

**已暴露的机制（存在，但都不完整）**：

| 机制 | 位置 | 下游可读性 |
|---|---|---|
| `CoreApi.API_VERSION = "2.1"` | `api/CoreApi.java:26` | 编译期常量 + `apiVersion()`:34 |
| `CoreApi.ROLE_API_VERSION = "2.0"` | `:29`, `roleApiVersion()`:39 | 与 `RoleExtensionApi.apiVersion()`、实现里 `RoleExtensionServiceImpl.API_VERSION = "2.0"`(`:45`) 三处独立 |
| 能力查询 `CoreApi.supports(String)`:50 / `capabilities()`:72 | 12 个硬编码键 | 见 A-09 |
| 角色 API 版本（provider 侧） | `RoleExtensionApi.apiVersion()`:48 | provider 在注册期可查 |
| `fabric.mod.json` custom 块 | `custom["habitrain_core:api_version"]="2.1"`、`custom["habitrain_core:role_api"]="2.0"` | **无公开 API 读取**；下游只能反射 FabricLoader 元数据 |
| 快照世代 | `RoleSnapshotId.version()`（record） | 描述"目录第几代"，不是 API 版本 |
| 场景资产/协议格式版本 | `SceneAssetCodec.FORMAT_VERSION_V1/V2/V3`:27-30、`SceneAssetDelta.PATCH_VERSION`:53、`SceneMotionSettings.CURRENT_SCHEMA_VERSION`:21 | 只覆盖场景资产 wire 格式，与 API 能力无关 |

**"下游如何检测 core 2.0.11 vs 更旧"**：
- 唯一**语义化**手段是 `CoreApi.apiVersion()`（返回 `"2.1"`）——2.0.11 引入 `2.1`，
  2.0.10 及以前没有 `CoreApi` 这个类，因此**老 core 上引用 `CoreApi` 会 `NoClassDefFoundError`**。
  下游要兼容旧版只能 `Class.forName("com.habitrain.core.api.CoreApi")` 包一层 try/catch，
  这一点**没有任何文档或 API 支持**（`CoreApi` javadoc:4-12 只讲了自己的存在意义，未给兼容配方）。
- `supports("scene.instances")` 等键**也不构成可靠握手**：它是静态 switch，不反映编译产物。

**缺口**：
1. **能力集与实际装配状态脱节**：`CoreApi.supports()`:54-68 返回编译期常量，**从不查询**
   `CoreSpi.isSceneFullyInstalled()`:184 / `isMenuGateInstalled()`:192 / `isVoteInstalled()`:196 /
   `isSreRuntimeInstalled()`:172 / `RoleSpi` 槽位。这些方法**已存在且公开**，却没被用于
   能力判定。未装配/半装配时 `supports("scene.instances")` 仍返回 `true`，下游按能力分支会走到
   NOOP 桥接上（静默空结果）。
2. **没有 feature→minVersion 映射**：`supports()` 只有 12 个粒度极粗的键，
   无法表达"`SceneApi.publishAsset` 需要 ≥2.1""`RoleCatalogApi.lastEndedSnapshot` 需要 ≥2.0"。
3. **没有任何握手字段随网络下发**：`RoleExtensionApi`/`RoleManifest` 体系里有 provider 清单握手
   （在 `role/config` 内部），但**公开 API 层没有暴露给下游的 core 版本握手**；
   下游无法在运行时确认"对面对局用的是哪个 core 的语义"。
4. **版本常量无一致性测试**：`CoreApi.API_VERSION`（`:26`）、`CoreApi.ROLE_API_VERSION`（`:29`）、
   `RoleExtensionServiceImpl.API_VERSION`（`role/extension/RoleExtensionServiceImpl.java:45`）、
   `fabric.mod.json` 的 `custom` 块、`gradle.properties` 的 `mod_version=2.0.11`（`:26`）之间
   **没有任何测试或构建步校验同步**（检索 `src/test` 全域：`CoreApi|apiVersion` 零命中）。
   当前 4 处恰好一致（2.1 / 2.0 / 2.0 / 2.1），但下次发版极易漂移——而 `CoreApi` 的
   javadoc:11-12 恰恰把"用 API_VERSION 而不要用 mod 版本"作为正式建议。
5. **`Javadoc` 与实现的版本声明可能不同步**：`CoreApi`:21-23 把 `2.1` 归因于 2.0.11 的 4 项变更，
   这是**人工维护的散列清单**，无守卫。

---

## 优先级发现表

| ID | 严重度 | 标题 | 证据（path:line） | 对下游的影响 | 建议修复 |
|---|---|---|---|---|---|
| A-01 | 高 | `CoreSpi.install*` 先置位后执行，首次装配失败即永久锁死为 NOOP/半装配 | `api/spi/CoreSpi.java:157-167`（置位 162，执行 166）；`isSceneFullyInstalled()`:184 | core bootstrap 中任一实现初始化抛异常 → 桥接永久为 NOOP，且 `isXxxInstalled()` 谎报已装配；下游所有场景/投票/门控调用静默空转，无重试可能 | `apply.run()` 成功后再 `compareAndSet`；失败时记 `error` 并允许重试；或把 `installed` 与 `healthy` 拆成两个状态 |
| A-02 | 高 | 分层守卫测试只扫 `src/main/java/.../api`，且根类名单硬编码——`api` 测试子树有 193 处内部包引用无人约束 | `src/test/.../ApiLayerDependencyGuardTest.java:69,151-152`；`FORBIDDEN_ROOT_CLASSES`:56-66；反例 `src/test/.../api/GameModeRegistryTest.java:3`、`api/role/v2/RoleCatalogApiTest.java:4-7`、`api/ApiValueObjectTest.java:3` | 守卫给出"公开层已隔离"的安全感，但(a) 新增 core 根包实现类不会被发现；(b) 反射/字符串引用完全绕过；(c) 无法证明 `api` 的契约不依赖实现层；下游升级时可能依赖到即将变动的内部形状 | 根类名单改为**扫描 `src/main/java/com/habitrain/core/*.java` 自动生成**并断言名单完整；增加反射/字符串前缀检查（`Class.forName`、`"com.habitrain.core."` 字面量）；在测试里显式区分"允许内部依赖的测试"与"api 纯契约测试" |
| A-03 | 高 | `CoreLifecycleScope.isCoreCaller()` 仅按类名前缀放行，是唯一的门禁驱动校验，且不在 `api` 守卫范围内 | `internal/CoreLifecycleScope.java:21,34-37,57-65`；门禁消费点 `api/spi/CoreSpi.java:158`、`api/spi/RoleSpi.java:191`、`api/GameModeRegistry.java:326/363`、`api/TaskRegistry.java:79/97` | 下游若在 `com.habitrain.core.*` 包内提供同名包类（Fabric 不在加载期强制包归属），即可 `run()` 进入生命周期作用域，进而 `TaskRegistry.register` 绕过冻结、`CoreSpi.install*` 替换桥接、伪造 `MatchStateApi.modeId()` 与 `GameModeRegistry.start` 的占用互斥 | 期望方向应由**类加载器身份**而非包名保证：例如在 `CoreLifecycle` 内存一个 bootstrap 期一次性 capability（`Object` token）由 `CoreLifecycleScope` 持有，`run(token, action)`；或至少把 `isCoreCaller()` 的包名前缀判定改为"owner class 的 CodeSource 是否等于 core 自身 jar" |
| A-04 | 中 | `RoleCatalogApi` 三个快照访问器是 `default` → 返回空 Optional，与"没有快照"不可区分 | `api/role/v2/RoleCatalogApi.java:104,112,121`；生产覆盖 `role/catalog/RoleCatalogImpl.java:319,324,329`；下游兜底 `哈比列车抽奖补齐 .../grant/GrantRoleSnapshot.java:39-43` | 替换/降级实现会让结算快照永久为空；下游只能靠 try/catch + 双重兜底猜，抽奖发奖身份可能取到 lobby 覆盖层 | 三方法改为**抽象方法**（编译期强制实现），删除 default；若必须兼容，则加 `boolean isSnapshotSupportAvailable()` 能力位 |
| A-05 | 中 | `MatchStateApi.modeId()` 用 `""` 同时表示大厅/读不到/未装配；`phase()` 在客户端恒为 `UNKNOWN`→`hasLeftLobby()==true` | `api/match/MatchStateApi.java:36-44`；`api/spi/CoreSpi.java:279,281,284,287`；`api/match/MatchPhase.java:24-26`；下游 `哈比列车抽奖补齐 .../card/CardUseService.java:415` | 按"有 modeId 就套用模式配置"直觉写会在大厅误用对局规则；客户端调用 `hasLeftLobby` 永远得到 true，可能误判"对局已开始" | 增加 `Optional<String> modeIdOrEmpty(ServerLevel)`；`modeId` 保留但 javadoc 标注 @Deprecated-for-ambiguity；把 `MatchPhase.fromLevel` 与 `hasLeftLobby` 的客户端语义在方法名上区分（如 `assumeOccupied()`） |
| A-06 | 中 | `TaskRegistry` 无类级线程契约，且 `getAll/getAllIds` 返回**活视图**（非快照），`getByGameMode(null)` NPE | `api/TaskRegistry.java:19,37,39,51-54`；对比已修好的 `api/GameModeRegistry.java:74-84,24-36` | 注册期（含下游 entrypoint）与查询并发时遍历活视图可能 CME；`getByGameMode(null)` 直接 NPE；下游无法从文档得知可否跨线程查询 | 改为 `List.copyOf`/`Set.copyOf` 快照并补类级线程契约段落；`register(String,String,Consumer)` 与 `getByGameMode` 加 `requireNonNull` |
| A-07 | 中 | `GameModeRegistry` 空值策略不一致：`start`/`stop`/`getActiveForLevel`/`get` 不判空，`hasExplicitActiveMode`/`isActiveInLevel` 判空 | `api/GameModeRegistry.java:104-105,148-150,259-260,67-69`（无判空）对比 `:287-289,306-312`（判空） | 下游在玩家还没进世界、或 `level` 来自已卸载维度时调用，得到 NPE 而非文档化的失败值；与同类中其他方法行为不一致 | 统一策略：所有接受 `ServerLevel` 的公开方法对 null 返回 `false`/`Optional.empty()` 或显式抛 IAE；`register` 对 `mode` 加 `requireNonNull` 并写入 javadoc |
| A-08 | 中 | `RoleQuery` 的 varargs 构建器不判空不过滤 null；两个 `@Deprecated` 字段与 `side()` 是"设了没语义"的公开面 | `api/role/v2/RoleQuery.java:157-160,162-165,203-206`（无校验）；`:75-94,109-114`（deprecated 无过滤语义）；`:23-24`（`side` 装饰性） | 下游按 javadoc 传 `includeDisabled(true)` / `side(...)` 期待过滤，实际**完全没有效果**，静默得到超集或同集；`factions(null)` NPE | 对 varargs 做 `Objects.requireNonNull` + 元素过滤；为无效语义的字段/方法加 `@Deprecated(forRemoval=true)` 并在返回集合处打一次性 WARN，或在 `RoleDiagnostics` 暴露"该维度未生效" |
| A-09 | 中 | `CoreApi.supports()` 是硬编码集合，不查询实际装配状态；无 feature→版本映射、无网络握手 | `api/CoreApi.java:50-69`（静态 switch）对比已公开但未使用的 `api/spi/CoreSpi.java:172,176,180,184,192,196` | 场景/投票/门控未装配或半装配时仍返回 `true`，下游按能力分支走进 NOOP 桥接，得到静默空结果且无从诊断 | `supports()` 对 `scene.*`/`vote.*`/`menu.*`/`match.*` 键改为组合 `CoreSpi.isXxxInstalled()`；新增 `Map<String,Integer> capabilityVersions()` 表达每项能力的最低 API 小版本 |
| A-10 | 中 | `SceneApi`/`SceneMotionApi` 的空值与错误信号不一致：`publishAsset` 吞掉异常原因，`SceneMotionApi.getAssetDescriptor` 可返回 null，多个查询无判空 | `api/scene/SceneApi.java:599-626`（catch 后仅 `return false`，无日志）、`:260-262`（无谓词判空）、`:293-295`、`:669-676`（listener 无判空）；`api/scene/SceneMotionApi.java:73-75`（可返回 null）对比 `api/scene/SceneApi.java:567-570`（有 EMPTY 兜底） | 资产发布失败（超限/格式错/磁盘错）在下游看来全是同一个 `false`，无法报错给服主；`SceneMotionApi.getAssetDescriptor` 的调用方按 `SceneApi.asset` 的约定写会 NPE | `publishAsset` 补 `LOGGER.warn` 并保留异常；新增 `AssetPublishResult`（状态枚举 + 原因）重载；`SceneMotionApi.getAssetDescriptor` 改为返回 `SceneAssetDescriptor.EMPTY`；公开方法统一判空 |
| A-11 | 中 | 客户端专用门禁靠 `@Environment` 注解 + 类移动，无加载期强制；`isScreenAllowed` fail-open、`isServerDedicated` 默认值方向相反 | `api/client/menu/MenuGateClientApi.java:11-18,29-36`；`api/spi/MenuGateClientBridge.java:19-21`（默认 true）对比 `:31-33`（默认 false）；同类问题 `api/scene/client/SceneClientApi.java:21,26`、`api/client/scene/compat/*`（7 类） | 专用服上一旦有任何代码路径触碰这些类（含经 `CoreSpi.menuGateClient()`），即 `NoClassDefFoundError`；客户端门控在 payload 到达前不设防，下游若把它当安全边界则可被绕过 | 把客户端门禁拆成"状态数据类（common 可加载）+ 客户端 UI 助手"两层；桥接默认值统一为 fail-closed 并文档化不对称原因；增加守卫测试禁止 `api` 下除白名单外的 `net.minecraft.client` 直接引用 |
| A-12 | 中 | `RoleSnapshot` 构造器与 `canonicalize` 不判空；`find` 的 javadoc 承诺强于实现 | `api/role/v2/RoleSnapshot.java:33-38,44-57,91-101,83-88` | 下游用 `new RoleSnapshot(null, …)` 或 `canonicalize(null)` 会得到 NPE 而非文档化异常；按 javadoc "following replacements" 写会漏掉被替换 target | 构造器加 `Objects.requireNonNull(roles/aliases/replacedTargets)`；`canonicalize` 显式 `requireNonNull(id)`；修正 `find` javadoc（只跟 alias，不跟随 replacement 宿主） |
| A-13 | 中 | 同名公开 API `api/MenuGateApi` 与 `api/menu/MenuGateApi` 并存，下游在用哪份靠运气 | `api/MenuGateApi.java:10-16,26,36,42,48`（@Deprecated 转发壳，声明"2.0.12 移除"）；实际消费者 `哈比列车抽奖补齐 .../meta/MenuGateServerBridge.java:3` 导入 `api.menu.MenuGateApi` | 同名两份使 IDE 自动导入/文档检索极易命中转发壳；一旦 2.0.12 真移除，未迁移者编译失败；两份签名相同使编译期无法察觉选错 | 现在就把 `api/MenuGateApi` 的所有方法加 `@Deprecated(forRemoval=true)`（已做）+ 在 `CoreApi.supports` 里不区分；最稳妥是**立即删除**转发壳并在 2.0.12 前只保留一份（下游已用 `api.menu`） |
| A-14 | 中 | 版本常量四处独立、无一致性测试；老 core 上引用 `CoreApi` 直接 `NoClassDefFoundError` 且无兼容配方 | `api/CoreApi.java:26,29`；`role/extension/RoleExtensionServiceImpl.java:45`；`src/main/resources/fabric.mod.json` custom `habitrain_core:api_version`/`role_api`；`gradle.properties:26` `mod_version=2.0.11`；`src/test` 全域无 `CoreApi\|apiVersion` 命中 | 发版时版本漂移不会被构建拦住，下游按 `apiVersion()` 做能力判断会失效；想同时支持 2.0.10 与 2.0.11 的下游没有官方检测方式 | 新增单测断言 `CoreApi.API_VERSION == RoleExtensionApi.apiVersion()` 一致、`ROLE_API_VERSION` 与 `RoleExtensionServiceImpl.API_VERSION` 一致，并把 `API_VERSION` 与 `fabric.mod.json` 的 custom 值对齐（可用 processResources 注入）；在 `CoreApi` javadoc 给出 `Class.forName` 探针片段 |
| A-15 | 低 | `GameModeIds.canonical(null/空白)` 返回 `MURDER`，"未知"被静默当成谋杀模式 | `api/GameModeIds.java:36-39`；调用面 `api/match/MatchStateApi.java:40`（javadoc 提到映射到 `BLACKOUT`） | 下游用 `canonical()` 做归一会把空输入判成谋杀局，用于发奖/统计时产生错误的模式归属 | `canonical` 对 null/空白返回 `null`（或新增 `canonicalOrNull`），把 MURDER 回退移到调用方；至少 javadoc 显式警告 |
| A-16 | 低 | `RoleSpi` 的 `Slot.install` 判空在空指针后仍抛"已装配"，且与 `CoreSpi` 门禁失败策略不一致（throw vs warn） | `api/spi/RoleSpi.java:189-199`（`Objects.requireNonNull` 在前 `:190`，`supplier != null` 判定 `:195`）；对比 `api/spi/CoreSpi.java:157-161`（warn+return） | 错误信息与实际原因不符，排查成本高；两个 SPI 对"未在作用域内装配"给出不同后果，下游/文档难以形成单一预期 | 修正判定顺序（`if (supplier != null) throw …alread…` 提到最前或改写为 `(installed)` 布尔）；统一或在 javadoc 中明确解释两种策略 |
| A-17 | 低 | 若干公开 getter 可返回 null 但未标 `@Nullable` | `api/WinResult.java:53`（`getReason()`）；`api/TaskRegistry.java:38`（`get(String)`）；`api/spi/SceneInstanceBridge.java:31`（`server()`）；`api/ModeMapVoteSnapshot`（经 `ModeMapVoteApi.getSnapshot`:48 已用 Optional 兜住，尚可） | 下游做静态检查或空安全分析时会漏掉这些点，运行期才 NPE | 补齐 `@Nullable` 并加 `package-info.java` 声明"`api` 默认非空，例外必须标注" |
| A-18 | 低 | `api/spi` 桥接接口风格分裂：多数是全 default 安全空值，少数是抽象方法（依赖调用点 try/catch） | 抽象：`SreRuntimeBridge.java:23,28,31,34`、`TaskPoolCacheBridge.java:11,14`、`ExtraSlotReclaimBridge.java:18`、`RoleSpi.java:141-166`；安全默认：`SceneInstanceBridge.java` 全 30 方法、`VoteBridge.java:27-73`、`MenuGateBridge.java:15-35` | 两个 NOOP 实例（`CoreSpi.java:55-63`）在未装配时对抽象方法调用会 `AbstractMethodError`；当前**仅因为**每个访问点都包了 `try/catch(Throwable)` 才安全，任何新增查询漏掉 try/catch 即崩 | 把 `SreRuntimeBridge`/`TaskPoolCacheBridge`/`ExtraSlotReclaimBridge` 改成 `default` 安全空值（与场景/投票桥一致），或抽出一个 `SafeNoop` 基类；并在 `CoreSpi` 类 javadoc 中把"新增查询必须 try/catch"写成硬性约定 |

### 附：确认无问题的点（供回归对照）
- `api/**` 内 0 处内部包 import（含 javadoc / 全限定引用），2.0.11 的边界修复**真实有效**。
- `CoreLifecycle.installProbe` 的 owner 类名校验 + 单次装配语义**无法被常规下游伪造**。
- `RoleStateApi` 的 `get`/`getOrNull` 抛与不抛两种策略已分立且各自文档化（`RoleStateApi.java:57-96`），
  是被审核过的正确范例。
- `RoleOverrideApi` 的 v1/v2 关系、`api/MenuGateApi` 的废弃声明、`ModeMapVoteApi.lastFailure`、
  `OptionVoteApi` 保留 id 拒绝（`:49`）等既有审核项均已落实。
- `ApiValueObjectTest` 覆盖了 `WinResult`/`VoteResult` 的防御性快照（`:22-52`）。
