# 哈比列车完整角色扩展 API v2 使用教程

> 适用：HabiTrain Core 2.0.2、Role Extension API `2.0`、StarRailExpress 4.3.0、Minecraft 1.21.1、Fabric、Java 21
> 状态：v2 为 preview / experimental；v1 `RoleOverrideApi` 仍是正式兼容 API
> 最后更新：2026-08-18

本教程说明附属模组如何通过 HabiTrain Core 的完整角色扩展端口新增、修改、替换和迁移 SRE 角色，以及如何接入受管 hooks、状态、动作、客户端展示、语音/聊天、目录、转职、快照和诊断。

## 目录

1. [选择 v1 或 v2](#1-选择-v1-或-v2)
2. [依赖和两个 Fabric 入口](#2-依赖和两个-fabric-入口)
3. [注册规则与 ID](#3-注册规则与-id)
4. [ADD：新增角色](#4-add新增角色)
5. [MODIFY：可撤销修改](#5-modify可撤销修改)
6. [REPLACE 与 ALIAS](#6-replace-与-alias)
7. [受管行为 Hooks](#7-受管行为-hooks)
8. [受管状态 State](#8-受管状态-state)
9. [受管动作 Action](#9-受管动作-action)
10. [有效目录与转职](#10-有效目录与转职)
11. [客户端扩展](#11-客户端扩展)
12. [语音与聊天策略](#12-语音与聊天策略)
13. [配置、快照、握手与诊断](#13-配置快照握手与诊断)
14. [v1 迁移](#14-v1-迁移)
15. [完整骨架](#15-完整骨架)
16. [能力边界和验收](#16-能力边界和验收)

## 1. 选择 v1 或 v2

| 需求 | 选择 |
|---|---|
| 稳定替换或修改一个既有 SRE 角色 | v1 `RoleOverrideApi` |
| 保留原 ID、对象、CCA、上游事件与私有兼容路径 | MODIFY（v1 或 v2） |
| 新增全新角色 | v2 ADD |
| 多 provider 对同一字段有确定合并顺序 | v2 MODIFY |
| 新 ID 完整重建角色，并隐藏旧角色 | v2 REPLACE |
| 旧 ID / 存档迁移 | v2 ALIAS |
| 受管 hooks、state、action、client、voice/chat | v2 |

REPLACE 不保留原 Java 对象。只要上游代码依赖 `role == SOME_CONSTANT`、原 CCA component key、原类方法、原 HUD/职业书或现有事件处理器，就优先 MODIFY。

不要让 v1 与 v2 同时修改同一角色的同一职责。迁移应按一个完整条目完成并双端验证。

## 2. 依赖和两个 Fabric 入口

### 2.1 Gradle

```groovy
repositories {
    flatDir { dirs "libs" }
}

dependencies {
    modImplementation files("libs/habitrain_core-2.0.2.jar")
    modImplementation files("libs/star_rail_express-4.3.0-dev.jar")
}
```

### 2.2 `fabric.mod.json`

```json
{
  "schemaVersion": 1,
  "id": "example_mod",
  "environment": "*",
  "entrypoints": {
    "habitrain:role_extensions": [
      "com.example.roles.ExampleRoleProvider"
    ],
    "habitrain:role_client_extensions": [
      "com.example.roles.client.ExampleRoleClientProvider"
    ]
  },
  "depends": {
    "fabricloader": ">=0.18.2",
    "minecraft": "~1.21.1",
    "java": ">=21",
    "fabric-api": "*",
    "habitrain_core": ">=2.0.2",
    "starrailexpress": "*"
  }
}
```

| entrypoint | 加载端 | 注册内容 |
|---|---|---|
| `habitrain:role_extensions` | common | definition、patch、replacement、alias、hooks、state、action、voice/chat |
| `habitrain:role_client_extensions` | client | HUD、直觉、皮肤、名字渲染、屏幕 |

只有 entrypoint 回调传入的 provider-scoped registrar 可写：

```java
public final class ExampleRoleProvider implements RoleExtensionEntrypoint {
    @Override
    public void register(RoleExtensionRegistrar registrar) {
        // 所有 common 声明都在这里
    }

    @Override
    public boolean requiresClient() {
        return true; // 只有客户端扩展/资源对 provider 必不可少时才返回 true
    }
}
```

错误示例：

```java
// 只读兼容门面；任何注册方法都会抛异常
RoleExtensionApi.instance().registrar().add(definition);
```

## 3. 注册规则与 ID

1. 新角色、状态、动作、客户端声明的 namespace 必须是 provider 模组 ID。
2. 修改/替换目标必须使用真实 canonical ID。SRE 4.3.0 原版通常是 `starrailexpress:path`，不要猜成 `sre:path`。
3. 用稳定 `entryKey` 区分同一 provider 的多个补丁/替换。发布后不要随意更改，否则配置和冲突赢家会失联。
4. Provider 注册是事务：其中一个声明抛异常，整个 provider 回滚。
5. Core 在注册期结束后冻结；不要延迟到 `SERVER_STARTED` 再注册。
6. 不直接写 `TMMRoles.ROLES`，不直接 `TMMRoles.registerRole()`。

角色键：

```java
RoleKey role = RoleKey.of("example_mod", "plague_doctor");
RoleKey parsed = RoleKey.tryParse("example_mod:plague_doctor"); // 解析失败返回 null
ResourceLocation id = role.location();
```

## 4. ADD：新增角色

### 4.1 最小可用定义

```java
public static final RoleKey PLAGUE_DOCTOR =
        RoleKey.of("example_mod", "plague_doctor");

SRERole runtime = registrar.add(RoleDefinition.builder(PLAGUE_DOCTOR)
        .presentation(RolePresentation.builder()
                .color(0xFF7BB661)
                .moodType(SRERole.MoodType.REAL)
                .nameKey("role.example_mod.plague_doctor")
                .descriptionKey("role.example_mod.plague_doctor.description")
                .simpleDescriptionKey("role.example_mod.plague_doctor.simple")
                .objectivesKey("role.example_mod.plague_doctor.objectives")
                .icon("example_mod:textures/gui/role/plague_doctor.png")
                .build())
        .faction(RoleFactionProfile.builder()
                .innocent()
                .build())
        .spawn(RoleSpawnProfile.builder()
                .defaultMax(1)
                .enableChance(100)
                .needPlayerCount(6)
                .maxPlayerCount(0)
                .build())
        .compatibility(RoleCompatibilityProfile.builder()
                .canBeRandomed()
                .canSeeCoin()
                .occupiedRoleCount(1)
                .build())
        .maxSprintTime(20)
        .canSeeTime(true)
        .build());
```

必填：`presentation`、`faction`、`spawn`、`compatibility`、`maxSprintTime(>=0)`。

### 4.2 阵营

```java
RoleFactionProfile.builder().innocent().build();
RoleFactionProfile.builder().killer().build();
RoleFactionProfile.builder().neutral().neutralForKiller().build();
RoleFactionProfile.builder().vigilanteTeam().build();
RoleFactionProfile.builder().mafiaTeam().build();
```

`neutralForKiller/neutralForInnocent` 会按 SRE 契约使角色成为 neutral。`notNeutral*` 和 `notMafiaTeam` 用于明确覆盖构造器推导值。

### 4.3 初始物品、商店、关系、可见性

```java
.inventory(RoleInventoryProfile.builder()
        .item(new ItemStack(Items.IRON_SWORD))
        .items(new ItemStack(Items.BREAD, 2))
        .build())
.economy(RoleEconomyProfile.builder()
        .entry(new KillerKnifeShopEntry(100))
        .live(() -> buildFreshShop()) // 每次打开重建；不得返回可被外部长期修改的共享列表
        .build())
.visibility(RoleVisibilityProfile.builder()
        .canUseInstinct()
        .instinctNightVision()
        .canSeeTeammateKiller(false)
        .build())
.relations(RoleRelationProfile.builder()
        .occupation(RoleKey.of("starrailexpress", "civilian"))
        .opposing(RoleKey.of("starrailexpress", "killer"))
        .related(RoleKey.of("example_mod", "assistant"))
        .opposingTwoWay()
        .build())
```

有 CCA 的角色先注册 component type/entrypoint，再传 `.componentKey(MyComponent.KEY)`。

### 4.4 技能和职业书

```java
RoleSkill.Definition heal = RoleSkill.skill(
        ResourceLocation.fromNamespaceAndPath("example_mod", "heal"),
        "skill.example_mod.heal",
        context -> true)
        .cooldownSeconds(30)
        .showOnHud(true)
        .announceToSelf(true)
        .build();

RoleDefinition definition = RoleDefinition.builder(PLAGUE_DOCTOR)
        // 必填 profiles...
        .skill(RoleSkillSpec.of(heal))
        .book(RoleBookContent.of(
                RoleBookPage.of(
                        Component.translatable("book.example_mod.plague.title"),
                        Component.translatable("book.example_mod.plague.body"))))
        .maxSprintTime(20)
        .build();
```

### 4.5 自定义 `SRERole` 子类

需要 `CustomWinnerRole` 等子类时使用 `roleFactory`：

```java
.roleFactory(def -> new MyCustomRole(def.key().location(), /* 其余参数 */))
```

Factory 在 staging 中调用一次。返回对象的 ID 必须等于 definition key；Core 仍管理目录、快照、配置和 hooks。

## 5. MODIFY：可撤销修改

MODIFY 保留目标 canonical ID 和原对象，在快照激活时折叠补丁，关闭后恢复基线。

```java
RoleKey civilian = RoleKey.of("starrailexpress", "civilian");

registrar.modify(RolePatch.builder(civilian)
        .entryKey("armed_civilian")
        .priority(PatchPriority.NORMAL)
        .color(0xFF35C759)
        .namePatch((original, server) -> Component.literal("武装平民"))
        .descriptionPatch((original, baseline) ->
                Component.translatable("role.example_mod.armed_civilian.description"))
        .simpleDescriptionPatch((original, baseline) ->
                Component.translatable("role.example_mod.armed_civilian.simple"))
        .defaultItemsPatch((original, server) ->
                List.of(new ItemStack(Items.IRON_SWORD)))
        .shopTransform((original, server, resolved) -> {
            List<ShopEntry> out = new ArrayList<>(resolved);
            out.add(new KillerKnifeShopEntry(100));
            return out;
        })
        .innocent(RolePatch.BooleanPatch.set(true))
        .canUseKiller(RolePatch.BooleanPatch.set(true))
        .defaultMax(RolePatch.IntPatch.set(2))
        .needPlayerCount(RolePatch.IntPatch.max(6))
        .canUseInstinct(RolePatch.BooleanPatch.or(true))
        .skills(RoleSkillPatch.append(RoleSkillSpec.of(heal)))
        .book(RoleBookPatch.append(RoleBookPage.of(
                Component.literal("修改说明"),
                Component.literal("现在会获得铁剑和治疗技能。"))))
        .build());
```

### 5.1 合并操作

| 值 | 操作 |
|---|---|
| boolean | `set`、`and`、`or` |
| int | `set`、`add`、`min`、`max` |
| role list | `append`、`remove`、`replaceAll` |
| skill list | `append`、`removeMatchingIds`、`replaceMatchingIds`、`replaceAll` |
| book pages | `append`、`removeMatchingTitles`、`replaceAll` |

`RoleSkillPatch.append` 不允许和基线重复 ID。要覆盖某个既有技能：

```java
.skills(RoleSkillPatch.replaceMatchingIds(
        RoleSkillSpec.of(overriddenSkillWithSameId)))
```

职业书没有 page ID，匹配替换/删除以 title 的 `getString()` 为键。

### 5.2 动态补丁

v2 复用 v1 的动态 patch 接口：

```java
.colorProvider((original, server) -> 0xFFFFAA00)
.flagsPatch((original, server, out) -> out.canUseInstinct = true)
.spawnInfoPatch((original, server, out) -> out.defaultMax = 2)
.shopPatch((original, server) -> buildOwnedShop())
.shopTransform((original, server, resolved) -> transformFinalShop(resolved))
.winConditionHook(context -> null)
```

`shopPatch` 替换角色自身的早期商店源；`shopTransform` 接收 SRE 最终解析后的不可变列表，应先复制再修改。带 server 参数的展示/商店回调可能在物理客户端以 `server == null` 调用。

### 5.3 顺序和冲突

补丁顺序：

```text
PatchPriority(EARLY < NORMAL < LATE)
→ provider mod ID 字典序
→ entryKey 字典序
```

同优先级的独占标量 SET 冲突时不会静默覆盖。用 `/habitrain roleapi trace <role> <field>` 查看折叠过程，由管理员关闭条目或配置 winner。

## 6. REPLACE 与 ALIAS

### 6.1 保留 canonical ID

```java
RoleKey target = RoleKey.of("starrailexpress", "loose_end");

RoleDefinition rebuilt = RoleDefinition.builder(target)
        // 完整定义，key 必须等于 target
        .presentation(...)
        .faction(...)
        .spawn(...)
        .compatibility(...)
        .maxSprintTime(20)
        .build();

registrar.replace(RoleReplacement.builder(target, rebuilt)
        .identity(ReplacementIdentity.KEEP_CANONICAL_ID)
        .entryKey("rebuilt_loose_end")
        .build());
```

这保留 ID，不保留原对象。若上游做 `==`，仍可能不兼容。

### 6.2 使用新 ID 并自动 alias

```java
RoleDefinition replacement = RoleDefinition.builder("example_mod", "new_loose_end")
        // 完整定义...
        .maxSprintTime(20)
        .build();

registrar.replace(RoleReplacement.builder(target, replacement)
        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS)
        .entryKey("new_loose_end")
        .build());
```

旧 target 解析到 `example_mod:new_loose_end`。同一 target 只能有一个活动 replacement owner。

### 6.3 单独 ALIAS

```java
registrar.alias(RoleAlias.of(
        "old_example", "doctor",
        "example_mod", "plague_doctor"));
```

ALIAS 只影响查找、命令和存档解析，不修改行为。`to` 必须归 provider 所有；环、悬空和多个 provider 抢同一个 from 会进入诊断。

## 7. 受管行为 Hooks

Provider 不为标准角色行为自己注册永久全局事件。Core 注册全局监听器并按有效快照、角色和作用域分发。

```java
registrar.hooks(PLAGUE_DOCTOR, RoleScope.HOLDER, RoleHooks.builder()
        .lifecycle(new RoleLifecycleHooks() {
            @Override
            public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
                player.sendSystemMessage(Component.literal("你成为了瘟疫医生"));
            }
            @Override
            public void onLost(ServerPlayer player, RoleHookContext ctx) {
                // 清理由平台外创建的临时资源
            }
        })
        .combat(new RoleCombatHooks() {
            @Override
            public Decision allowDeath(ServerPlayer player,
                                       ResourceLocation reason,
                                       RoleHookContext ctx) {
                return Decision.PASS;
            }
            @Override
            public void onKill(ServerPlayer victim, ServerPlayer killer,
                               ResourceLocation reason, RoleHookContext ctx) {
            }
        })
        .tick(new RoleTickHooks() {
            @Override
            public int tickInterval() { return 20; }
            @Override
            public void onServerTick(MinecraftServer server, RoleHookContext ctx) {
                // 每秒一次，保持轻量
            }
        })
        .build());
```

### 7.1 Hook 分类

| 分类 | 方法 |
|---|---|
| lifecycle | `onAssigned`、`onLost`、`onGameStart`、`onGameTrueStart`、`onRolesConfirm`、`onGameEnd` |
| combat | `allowDeath`、`allowDeathByKiller`、`allowKillByKiller`、`onDeath`、`onAnyDeath`、`onKill`、`onDeathWithBody` |
| interaction | `useItem`、`useEntity`、`useBlock`、`attackEntity`、`attackBlock`、`breakBlock` |
| shop | `allowBuy`、`onBuy`、`onAnyBuy` |
| task | `onFinishQuest` |
| meeting | `onMeetingStart`、`onMeetingEnd`、`allowVoteOut` |
| win | `allowGameEnd`、`evaluateWin`、`afterWinnersFinalized` |
| tick | `onServerTick`、`tickInterval` |

`Decision` 折叠为 DENY > ALLOW > PASS。交互 hook 返回非 PASS 会消费事件。

### 7.2 Scope

| Scope | 语义 |
|---|---|
| `HOLDER` | 事件玩家持有该角色 |
| `KILLER` / `VICTIM` / `TARGET` | 对应事件侧持有该角色 |
| `ANY_ACTIVE_HOLDER` | 任一在线/存活 holder 存在 |
| `ROUND_PRESENT` | 角色存在于本局快照/历史 |
| `GLOBAL_WHILE_ENABLED` | 条目启用即运行，高风险 |

广播事件（any death、any buy、meeting、game start/end、tick、win）要显式选择 scope。GLOBAL 还受 `allowGlobalHooks` 配置门控。

### 7.3 胜利

```java
.win(new RoleWinHooks() {
    @Override
    public Decision allowGameEnd(ServerLevel level, String proposed,
                                 boolean loose, RoleHookContext ctx) {
        return Decision.PASS;
    }

    @Override
    public WinPatch evaluateWin(ServerLevel level, String proposed,
                                boolean loose, RoleHookContext ctx) {
        return WinPatch.addWinners(holderUuid);
    }

    @Override
    public void afterWinnersFinalized(ServerLevel level, WinOutcome outcome,
                                      RoleHookContext ctx) {
        // 只做统计/奖励，不再写赢家
    }
})
```

## 8. 受管状态 State

### 8.1 注册 schema

```java
public static RoleStateKey<Integer> SOULS;

SOULS = registrar.state(RoleStateSpec.of("example_mod", "souls", Integer.class)
        .role(PLAGUE_DOCTOR)
        .scope(StateScope.PLAYER)
        .persistence(Persistence.ROUND)
        .sync(SyncPolicy.OWNER_AND_TRACKING)
        .resetOn(ResetCause.ROLE_LOST, ResetCause.ROUND_END)
        .defaultValue(() -> 0)
        .codec(Codec.INT)
        .dataVersion(1)
        .maxSerializedBytes(64)
        .build());
```

需要 WORLD/PERMANENT persistence 或任何非 NONE sync 时，`codec(...)` 必填。生产环境使用 Core 的固定 CCA 容器存储；provider 不需要为每个状态注册新 component key。

### 8.2 读写

```java
RoleStateApi state = RoleStateApi.instance();

int souls = Objects.requireNonNullElse(state.get(SOULS, player), 0);
state.set(SOULS, player, souls + 1);
state.reset(player, PLAGUE_DOCTOR, ResetCause.MANUAL);
```

PLAYER scope 以玩家 UUID 为槽；WORLD/ROUND 使用玩家所在世界，或用三参数 overload 显式传 `worldKey`。Provider/entry 禁用时，已有值保留但不可访问：读返回默认、写和同步 no-op，重新启用后继续原生命周期。

### 8.3 数据迁移

```java
.dataVersion(3)
.migrate(1, old -> migrateV1ToV2(old))
.migrate(2, old -> migrateV2ToV3(old))
```

迁移链必须从 v1 连续到当前版本；缺链时不应破坏原始存储数据。

## 9. 受管动作 Action

Action 使用 Core 的复用 C2S/S2C 通道，避免每个角色自建 payload。平台负责 config/handshake、方向、大小、速率、序列、角色、存活、冷却和结构化目标验证。

### 9.1 注册动作

```java
public static final ResourceLocation BITE =
        ResourceLocation.fromNamespaceAndPath("example_mod", "bite");

registrar.action(RoleActionSpec.of(BITE)
        .role(PLAGUE_DOCTOR)
        .direction(RoleActionDirection.BIDIRECTIONAL)
        .maxBytes(64)
        .ratePerSecond(4)
        .cooldownTicks(100)
        .requireCurrentRole(true)
        .requireAlive(true)
        .targetDecoder(ActionTargetCodec.PLAYER_UUID)
        .requireTargetAlive(true)
        .maxDistance(4.0)
        .requireLineOfSight(true)
        .handler(ctx -> {
            if (!(ctx.target() instanceof RoleActionTarget.Player target)) {
                return RoleActionResult.reject(RoleActionResult.TARGET);
            }
            // target 已由平台验证；不要再次信任/解析原始 payload 的 UUID
            return RoleActionResult.success();
        })
        .build());
```

Target codec：

| Codec | payload 前缀 | 平台验证 |
|---|---|---|
| `NONE` | 不解析 | opaque bytes |
| `PLAYER_UUID` | 前 16 字节 big-endian UUID | 在线、同世界；可启用 alive/range/LOS |
| `BLOCK_POS` | 接着 12 字节 big-endian XYZ | 解码 block pos |
| `ENTITY_ID` | 接着 4 字节 big-endian entity ID | 实体存在 |

只有 PLAYER_UUID 支持 `requireTargetAlive/maxDistance/requireLineOfSight`。

### 9.2 客户端发送和回调

```java
RoleActionClientApi.instance().send(BITE, payload, (actionId, result) -> {
    if (!result.ok()) {
        Minecraft.getInstance().player.sendSystemMessage(
                Component.translatable(result.reasonKey()));
    }
});
```

每个请求按 `(actionId, sequence)` 匹配权威结果；无响应和断线会得到 TIMEOUT / DISCONNECTED。不要依赖“最后一个包”的共享状态。

### 9.3 服务端推送

```java
RoleActionApi.instance().sendTo(player, BITE, payload);

Consumer<byte[]> listener = bytes -> handlePush(bytes);
RoleActionClientApi.instance().addPushListener(BITE, listener);
// 客户端卸载/关闭时移除
RoleActionClientApi.instance().removePushListener(BITE, listener);
```

## 10. 有效目录与转职

### 10.1 `RoleCatalogApi`

所有角色候选、职业书、自选、抽奖、命令和存档解析都应走有效目录：

```java
RoleCatalogApi catalog = RoleCatalogApi.instance();

Optional<EffectiveRole> role = catalog.find(PLAGUE_DOCTOR);
RoleKey canonical = catalog.canonicalize(ResourceLocation.parse("old_example:doctor"));
Optional<EffectiveRole> stored = catalog.resolveStored("old_example:doctor");

Collection<EffectiveRole> candidates = catalog.effectiveRoles(RoleQuery.builder()
        .purpose(QueryPurpose.LOTTERY_CARD)
        .factions(RoleFaction.INNOCENT)
        .provider("example_mod")
        .playerCount(10)
        .ordering(RoleOrdering.ID)
        .build());
```

`EffectiveRole.profile()` 是冻结纯数据；归档快照的 `role()` 可能为 null。不要在历史查询中假定存在 live `SRERole`。

`includeDisabled/includeInvalid` 已弃用且不产生有效目录结果；用 `RoleDiagnostics.entries()`。

### 10.2 `RoleChangeApi`

```java
RoleChangeApi change = RoleChangeApi.instance();

RoleChangeResult assigned = change.assign(
        player, PLAGUE_DOCTOR, RoleChangeOptions.defaults());
RoleChangeResult transformed = change.transform(
        player, PLAGUE_DOCTOR, RoleChangeCause.CONVERSION);
RoleChangeResult removed = change.remove(player, RoleChangeCause.REMOVE);

RoleView current = change.current(player);
List<RoleHistoryEntry> history = change.history(player);
```

Options：`defaults()`、`silent()`、`forceReinitialize()`、`withReinitialize()`。失败时检查 `message()` 与 `phase()`。

## 11. 客户端扩展

客户端 provider：

```java
public final class ExampleRoleClientProvider
        implements RoleClientExtensionEntrypoint {
    @Override
    public void register(RoleClientExtensionRegistrar registrar) {
        // 声明客户端扩展
    }
}
```

不要调用 `RoleClientExtensionApi.instance().hud(...)` 等旧写形方法；它们会抛异常。全局 API 只用于查询。

### 11.1 HUD

```java
registrar.hud(RoleHudSpec.of("example_mod", "plague_hint")
        .entryKey("plague_hint")
        .role(PLAGUE_DOCTOR)
        .kind(RoleHudKind.TEXT)
        .textKey("hud.example_mod.plague_hint")
        .color(0xFFFFFF)
        .position(4, 4)
        .showWhenSpectator(false)
        .build());

registrar.hudWidget(
        ResourceLocation.fromNamespaceAndPath("example_mod", "plague_widget"),
        "plague_widget",
        PLAGUE_DOCTOR,
        (width, height, tickDelta) -> renderCustomHud(width, height, tickDelta));
```

TEXT/BADGE 由 stock client 直接绘制。ICON/PROGRESS/COOLDOWN/CHARGE 当前只显示类型前缀 + 文本 fallback；真实纹理、数值和动画使用 `RoleHudWidget`。

### 11.2 直觉、皮肤和名字

```java
registrar.instinct(RoleInstinctRule.of("example_mod", "see_killer")
        .entryKey("see_killer")
        .viewerRole(PLAGUE_DOCTOR)
        .targetRole(RoleKey.of("starrailexpress", "killer"))
        .phase(InstinctPhase.ALIVE_MIDDLE)
        .color(0xFFFF3030)
        .build());

registrar.skin(RoleSkinSpec.of("example_mod", "plague_skin")
        .entryKey("plague_skin")
        .role(PLAGUE_DOCTOR)
        .kind(RoleSkinKind.NORMAL)
        .wide(ResourceLocation.fromNamespaceAndPath(
                "example_mod", "textures/entity/plague_wide.png"))
        .slim(ResourceLocation.fromNamespaceAndPath(
                "example_mod", "textures/entity/plague_slim.png"))
        .build());

registrar.nameRender(RoleNameRenderRule.of("example_mod", "plague_name")
        .entryKey("plague_name")
        .role(PLAGUE_DOCTOR)
        .phase(RoleRenderPhase.NAMEPLATE)
        .color(0xFF7BB661)
        .build());
```

NAMEPLATE 的 hide/color 已有消费点；TAB/INTRO/SPECTATOR/INSANE 仍为预留阶段。

### 11.3 Stock screen

```java
registrar.screen(RoleScreenSpec.of("example_mod", "plague_picker")
        .entryKey("plague_picker")
        .role(PLAGUE_DOCTOR)
        .kind(RoleScreenKind.PLAYER_PICK)
        .titleKey("screen.example_mod.plague_picker")
        .build());
```

声明不会自动决定何时打开。当前 stock 触发桥为客户端类 `com.habitrain.core.client.role.RoleClientExtensionHooks.openRoleScreen()`；只从 provider 的 client-only 代码调用。需要完全自定义布局时可自建 screen，但仍用 catalog/config 状态门控。

## 12. 语音与聊天策略

### 12.1 语音

```java
registrar.voice(RoleVoicePolicy.of("example_mod", "plague_voice")
        .role(PLAGUE_DOCTOR)
        .isolateGroup()
        .hearWorld(false)
        .maxDistance(12.0)
        .build());
```

策略应无条件注册；适配器可选。运行时可用 `RoleExtensionApi.instance().supports(RoleCapabilityKey.VOICE)` 查询是否已绑定。适配器会提供真实 group 和距离，并评估 `muteSend`、`muteReceive`、`isolateGroup`、`hearWorld`、`maxDistance`；缺少适配器时 capability 为 UNAVAILABLE，Core 不加载可选依赖类。

### 12.2 聊天

```java
registrar.chat(RoleChatPolicy.of("example_mod", "plague_chat")
        .role(PLAGUE_DOCTOR)
        .muteSend()
        .build());
```

`muteSend` 当前有服务端事件消费。`muteReceive` 由于 Fabric 1.21.1 缺少逐接收者过滤入口，仍是 experimental，不能作为玩法安全边界。

## 13. 配置、快照、握手与诊断

### 13.1 配置生效

配置：`config/habitrain_role_v2.json`。门控顺序：全局 → provider → entry。

```text
大厅修改 → 新 lobby snapshot 立即激活
对局中修改 → 新 pending snapshot
本局继续使用固定 round snapshot
下一局边界 → pending 提升为 lobby 并激活
```

因此禁用 provider 后，本局玩家不会被中途替换；新配置从下一局完整生效。

### 13.2 握手

服务端发送 manifest 和 snapshot；客户端回报 API 版本、必需 provider 和客户端扩展加载状态。受管 action 对未上报、版本不兼容、缺少 required provider、必要客户端扩展失败等情况 fail-closed。

当前 stock 客户端使用服务端 snapshot 的 definition hash，能发现 manifest/snapshot 不一致，但不是独立重新编译 definition 的双端指纹。不要宣传为完整独立 hash 校验。

### 13.3 命令

```text
/habitrain roleapi providers
/habitrain roleapi list effective
/habitrain roleapi list disabled
/habitrain roleapi list conflict
/habitrain roleapi list invalid
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
/habitrain roleapi manifest
```

读取需要 OP2，配置写入需要 OP4：

```text
/habitrain roleapi config set provider <id> on|off
/habitrain roleapi config set entry <id> on|off
/habitrain roleapi config set allowGlobalHooks on|off
/habitrain roleapi config winner <target#field> <entryId>
```

## 14. v1 迁移

| v1 `ModifyRoleDefinition` | v2 `RolePatch` |
|---|---|
| `namePatch` | `namePatch` |
| `colorPatch` | `colorProvider` 或静态 `color` |
| `descriptionPatch` / `simpleDescriptionPatch` | 同名方法 |
| `defaultItemsPatch` | 同名方法 |
| `shopPatch` / `shopTransform` | 同名方法 |
| `flagsPatch` / `spawnInfoPatch` | 同名动态方法，或字段级 Boolean/Int patch |
| `managedSkillPatch` | `RoleSkillPatch` |
| `roleBookAppendix/Appendices` | `RoleBookPatch.append` |
| `winConditionHook` | 同名方法，或 `RoleWinHooks` |

v1 REPLACE 的新 ID 语义接近 v2 `NEW_ID_WITH_ALIAS`，但迁移时仍要重新审查 exact-ID / exact-object / CCA / HUD / 书页 / 命令依赖。

不要把弃用的 v1 `skillRegistrar` 翻译成永久事件注册；应改成 v2 skill patch 或受管 hooks。

## 15. 完整骨架

```java
public final class ExampleRoleProvider implements RoleExtensionEntrypoint {
    public static final RoleKey DOCTOR = RoleKey.of("example_mod", "doctor");
    public static final ResourceLocation HEAL =
            ResourceLocation.fromNamespaceAndPath("example_mod", "heal");
    public static RoleStateKey<Integer> CHARGES;

    @Override
    public void register(RoleExtensionRegistrar registrar) {
        RoleSkill.Definition healSkill = RoleSkill.skill(
                HEAL, "skill.example_mod.heal", ctx -> true)
                .cooldownSeconds(20).showOnHud(true).build();

        registrar.add(RoleDefinition.builder(DOCTOR)
                .presentation(RolePresentation.builder()
                        .color(0xFF66AA77)
                        .nameKey("role.example_mod.doctor")
                        .descriptionKey("role.example_mod.doctor.description")
                        .build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().defaultMax(1).needPlayerCount(6).build())
                .compatibility(RoleCompatibilityProfile.builder().canBeRandomed().build())
                .skill(RoleSkillSpec.of(healSkill))
                .maxSprintTime(20)
                .build());

        CHARGES = registrar.state(RoleStateSpec.of(
                        "example_mod", "doctor_charges", Integer.class)
                .role(DOCTOR)
                .scope(StateScope.PLAYER)
                .persistence(Persistence.ROUND)
                .sync(SyncPolicy.OWNER)
                .defaultValue(() -> 2)
                .codec(Codec.INT)
                .build());

        registrar.action(RoleActionSpec.of(HEAL)
                .role(DOCTOR)
                .cooldownTicks(20)
                .maxBytes(0)
                .handler(ctx -> {
                    Integer charges = RoleStateApi.instance().get(CHARGES, ctx.playerId());
                    if (charges == null || charges <= 0) {
                        return RoleActionResult.reject(RoleActionResult.COOLDOWN);
                    }
                    RoleStateApi.instance().set(CHARGES, ctx.playerId(), charges - 1);
                    return RoleActionResult.success();
                })
                .build());

        registrar.hooks(DOCTOR, RoleHooks.builder()
                .lifecycle(new RoleLifecycleHooks() {
                    @Override
                    public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
                        RoleStateApi.instance().set(CHARGES, player, 2);
                    }
                })
                .build());

        registrar.alias(RoleAlias.of(
                "old_example", "doctor", "example_mod", "doctor"));
    }

    @Override
    public boolean requiresClient() {
        return true;
    }
}
```

## 16. 能力边界和验收

### 16.1 当前边界

| 能力 | 状态 |
|---|---|
| v1 REPLACE/MODIFY | 正式兼容 |
| v2 ADD/MODIFY/REPLACE/ALIAS、catalog/change、hooks/state/action | preview / experimental，代码已接入 |
| HUD TEXT/BADGE | stock client 可消费 |
| HUD ICON/PROGRESS/COOLDOWN/CHARGE | 文本 fallback；完整视觉需自绘 widget |
| instinct/skin | 已有消费点，仍随 v2 preview |
| name render NAMEPLATE | hide/color 已消费 |
| 其他 name render phase | 预留 |
| stock screen | 有声明与触发桥；触发时机由 provider 决定 |
| voice | 可选适配器消费 |
| chat muteSend | 已消费 |
| chat muteReceive | experimental，当前不可依赖 |
| 独立双端 definition hash | 未闭环 |

### 16.2 发布前检查

1. `./gradlew build` 使用 Java 21 成功。
2. 客户端和专用服务器安装相同 provider/core 版本。
3. `/habitrain roleapi providers/list/inspect/trace` 无 INVALID/CONFLICT。
4. ADD 出现在随机池、职业书、命令和自选的正确用途查询中。
5. MODIFY 开关后基线可恢复，重复开关不累积。
6. REPLACE 目标不再出现在有效目录；replacement 只出现一次。
7. ALIAS 能解析旧存档和命令 ID，无环和悬空。
8. 每种 hook 在正确 scope 触发，disabled/pending 时不越界。
9. 状态 reset、重连全量同步、删除同步、换维度/观战跟踪符合策略。
10. Action 测试 wrong role、dead、rate、cooldown、oversize、replay、target、range、LOS、timeout、disconnect 和 handshake。
11. 对局中改配置只生成 pending，当前局行为不变，下一局激活。
12. 客户端缺资源/扩展时按 `requiresClient()` 设计 fail-closed 或降级。

遇到问题先看 `list invalid/conflict`，再用 `inspect` 和 `trace`，最后核对真实目标 ID、provider namespace、entryKey、快照状态和客户端握手。
