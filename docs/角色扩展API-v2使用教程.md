# 🚂 哈比列车角色扩展 API v2 — 使用教程

> **适用版本：** Minecraft 1.21.1 · Fabric · Java 21 · habitrain_core 2.0.1（API 版本 `2.0`）· StarRailExpress 4.3.0
> **目标读者：** 想要为哈比列车（SRE）**新增角色**、**修改角色**、**替换角色** 或 **迁移旧角色 ID** 的其他 Fabric 模组作者
> **配套文档：** 《哈比列车完整角色扩展API设计与实现指南.md》（架构与维护基线）· `docs/API参考手册.md`（接口速查）
>
> **⚠️ 状态声明（2026-08-16 审核后）：** v2 整体定位为 **preview / experimental**。它实现了声明式 ADD/MODIFY/REPLACE/ALIAS、受管钩子/状态/动作/语音聊天、快照与配置门控；2026-08-16 已补齐 v1 `RoleOverrideApi` 字段级 MODIFY 对等（名称、描述、初始物品、商店、胜利条件），但仍未完成真实 SRE 双端与复杂角色端到端验收。**v1 仍是正式兼容 API，不弃用**；生产附属模组可以继续使用 v1，或在确认本教程各能力矩阵状态后按需使用 v2。逐项能力状态见文末「附录 A. 能力矩阵」。

---

## 目录

1. [五分钟快速开始](#1-五分钟快速开始)
2. [核心概念](#2-核心概念)
3. [依赖与入口配置](#3-依赖与入口配置)
4. [ADD — 创建一个全新角色](#4-add--创建一个全新角色)
5. [MODIFY — 修改已有角色](#5-modify--修改已有角色)
6. [REPLACE — 替换已有角色](#6-replace--替换已有角色)
7. [ALIAS — 旧 ID 迁移](#7-alias--旧-id-迁移)
8. [行为钩子 Hooks — 让角色活起来](#8-行为钩子-hooks--让角色活起来)
9. [角色状态 State — 可持久化数据](#9-角色状态-state--可持久化数据)
10. [受管动作 Action — 安全的网络交互](#10-受管动作-action--安全的网络交互)
11. [客户端扩展 — HUD / 直觉 / 皮肤 / 名字渲染](#11-客户端扩展--hud--直觉--皮肤--名字渲染)
12. [语音 / 聊天能力](#12-语音--聊天能力)
13. [运行时查询与角色变更](#13-运行时查询与角色变更)
14. [配置、管理命令与诊断](#14-配置管理命令与诊断)
15. [版本兼容与客户端握手](#15-版本兼容与客户端握手)
16. [设计准则与红线](#16-设计准则与红线)
17. [完整示例模组](#17-完整示例模组)
18. [FAQ 与故障排查](#18-faq-与故障排查)

---

## 1. 五分钟快速开始

假设你有一个名为 `example` 的模组，想加一个"瘟疫医生"角色。总共三步：

**第一步：** 在 `build.gradle` 添加依赖：

```groovy
repositories {
    flatDir { dirs "libs" }
}

dependencies {
    // 本地 jar 依赖 habitrain_core（同时把 jar 放进你的 libs/ 目录）
    modImplementation files("libs/habitrain_core.jar")
    modImplementation files("libs/star_rail_express-4.3.0.jar") // 编译期需要 SRE
}
```

**第二步：** 在 `fabric.mod.json` 声明入口：

```json
{
  "depends": {
    "minecraft": "~1.21.1",
    "fabricloader": ">=0.18.2",
    "fabric-api": "*",
    "habitrain_core": "*",
    "starrailexpress": "*"
  },
  "entrypoints": {
    "habitrain:role_extensions": [
      "com.example.roles.ExampleRoleProvider"
    ]
  }
}
```

**第三步：** 写 provider 类：

```java
package com.example.roles;

import com.habitrain.core.api.role.v2.RoleExtensionEntrypoint;
import com.habitrain.core.api.role.v2.RoleExtensionRegistrar;
import com.habitrain.core.api.role.v2.definition.*;

public final class ExampleRoleProvider implements RoleExtensionEntrypoint {
    @Override
    public void register(RoleExtensionRegistrar registrar) {
        registrar.add(RoleDefinition.builder("example", "plague_doctor")
                .presentation(RolePresentation.builder()
                        .color(0xFF7BB661)   // ARGB 整数
                        .build())
                .faction(RoleFactionProfile.builder()
                        .innocent()
                        .build())
                .spawn(RoleSpawnProfile.builder()
                        .defaultMax(1)
                        .needPlayerCount(8)
                        .build())
                .compatibility(RoleCompatibilityProfile.builder()
                        .canSeeCoin()
                        .build())
                .maxSprintTime(20)
                .build());
    }
}
```

启动游戏：`/habitrain roleapi list effective` 应能看到 `example:plague_doctor`，`/habitrain roleapi inspect example:plague_doctor` 可查看详情。

> ⚠️ **命名空间规则：** ADD 角色的 ID 必须属于你自己的模组命名空间（`example:xxx`），core 会强制校验，越权直接报错。

---

## 2. 核心概念

| 概念 | 说明 |
|------|------|
| **`RoleKey`** | 角色 canonical 身份（`record RoleKey(ResourceLocation location)`）。所有角色查找/注册都用它 |
| **四种注册操作** | `ADD`（新角色）、`MODIFY`（可撤销补丁）、`REPLACE`（隐藏旧角色并顶替）、`ALIAS`（旧 ID 重定向） |
| **注册期** | 模组初始化阶段（`habitrain:role_extensions` 入口回调），之后注册表冻结，不能再注册 |
| **provider 事务** | 每个 provider 的注册在独立事务中执行：你的 `register()` 抛异常则整体回滚，不留任何半成品 |
| **有效角色目录** | `RoleCatalogApi`：所有查询（抽奖、自选、职业书、命令、存档）都通过它，**不要直接读 `TMMRoles.ROLES`** |
| **快照** | 每局游戏引用一个冻结快照；配置改动默认**下一局生效**（`NEXT_ROUND`），不中途改你正在玩的角色 |
| **受管钩子** | 你通过 `hooks(...)` 挂行为，core 统一注册全局监听器并按角色分发；关闭角色后钩子自动不再被调用 |

**角色 ID 的三条铁律：**

1. 新角色：`你的modid:角色名`，全小写。
2. 修改/替换目标：用**目标角色当前的 canonical ID**，例如 SRE 原版角色 `starrailexpress:vigilante`、`starrailexpress:civilian`。
3. 所有 ID 在 `RoleKey.of("ns", "path")` 里自动规范化（转小写）。

---

## 3. 依赖与入口配置

### 3.1 入口类型

| entrypoint key | 作用 | 加载端 |
|---|---|---|
| `habitrain:role_extensions` | 注册角色定义 / 补丁 / 钩子 / 状态 / 动作 | 服务端 + 客户端集成（逻辑端） |
| `habitrain:role_client_extensions` | 注册 HUD / 直觉 / 皮肤 / 名字渲染 / 屏幕 | 仅客户端 |

接口实现：

```java
// 服务端/通用入口：每个 provider 收到自己的注册器
@FunctionalInterface
public interface RoleExtensionEntrypoint {
    void register(RoleExtensionRegistrar registrar);
}

// 客户端入口
public interface RoleClientExtensionEntrypoint {
    void register(RoleClientExtensionRegistrar registrar);
}
```

### 3.2 注册器能力总览（`RoleExtensionRegistrar`）

```java
SRERole add(RoleDefinition definition);                       // ADD
void modify(RolePatch patch);                                 // MODIFY
void replace(RoleReplacement replacement);                    // REPLACE
void alias(RoleAlias alias);                                  // ALIAS
void hooks(RoleKey role, RoleHooks hooks);                    // 行为钩子（HOLDER 作用域）
void hooks(RoleKey role, RoleScope scope, RoleHooks hooks);   // 行为钩子（显式作用域）
<T> RoleStateKey<T> state(RoleStateSpec<T> spec);             // 角色状态 schema
RoleActionSpec action(RoleActionSpec spec);                   // 受管动作
RoleVoicePolicy voice(RoleVoicePolicy policy);                // 语音策略
RoleChatPolicy chat(RoleChatPolicy policy);                   // 聊天策略
```

> `RoleExtensionApi.instance().registrar()` 是**只读门面**——上面的每个注册方法在它上面调用都会抛异常。所有声明必须走 entrypoint 回调里传入的 provider-scoped registrar。

---

## 4. ADD — 创建一个全新角色

### 4.1 完整静态定义

`RoleDefinition` 是角色的完整静态描述，编译成上游 `SRERole` 对象并**只注册一次**。

```java
public static final RoleKey PLAGUE_DOCTOR = RoleKey.of("example", "plague_doctor");

registrar.add(RoleDefinition.builder(PLAGUE_DOCTOR)   // 或 builder("example", "plague_doctor")
        .presentation(RolePresentation.builder()
                .color(0xFF7BB661)                    // ARGB
                .moodType(SRERole.MoodType.FAKE)      // 情绪类型（可选）
                .build())
        .faction(RoleFactionProfile.builder()
                .innocent()                           // 或 .killer() 等，见 javadoc
                .build())
        .spawn(RoleSpawnProfile.builder()
                .defaultMax(1)                        // 每局最大人数
                .enableChance(100)                    // 启用概率
                .needPlayerCount(8)                   // 最少玩家数
                .maxPlayerCount(15)                   // 最大玩家数
                .build())
        .compatibility(RoleCompatibilityProfile.builder()
                .canSeeCoin()
                .canPickUpRevolver()
                // .componentKey(MyComponent.KEY)      // 绑定你已有的 CCA 组件
                .build())
        .visibility(RoleVisibilityProfile.builder()   // 直觉相关（可选）
                // .instinct(...) / .instinctNightVision(...)
                .build())
        .relations(RoleRelationProfile.builder()      // 职业关系（可选）
                // .occupation(...) / .opposing(...) / .related(...)
                .build())
        .inventory(RoleInventoryProfile.builder()     // 初始物品（可选）
                .item(new ItemStack(Items.STICK))
                .build())
        // .economy(RoleEconomyProfile.builder().live(商店提供函数).build())  // 可选：商店，live 参数签名见 javadoc
        .book(RoleBookContent.of(                     // 职业介绍书（可选）
                RoleBookPage.of(Component.translatable("book.example.plague.title"),
                        Component.translatable("book.example.plague.p1"))))
        .skill(RoleSkillSpec.of(RoleSkill.skill(      // 统一技能（可选，可多个）
                ResourceLocation.fromNamespaceAndPath("example", "heal"),
                "skill.example.plague.heal",           // 翻译键
                ctx -> { /* 技能处理 */ return true; }
        ).cooldownSeconds(30).showOnHud(true).announceToSelf(true).build()))
        .maxSprintTime(20)                            // 【必填】
        .canSeeTime(true)                             // 可选
        .build());
```

### 4.2 必填项

`presentation`、`faction`、`spawn`、`compatibility`、`maxSprintTime(>=0)` 缺一不可，builder 会在 `build()` 时报错。

### 4.3 名称与描述走翻译键

角色名称/描述沿用上游机制：**翻译键由 ID 推导**（如 `example:plague_doctor` → 在 `zh_cn.json` 中加 `"role.example.plague_doctor": "瘟疫医生"`）。不需要也不存在字面量名称 setter。

### 4.4 拿回编译后的对象

`registrar.add(...)` 返回编译好的 `SRERole` 实例，可以存到你的静态字段（core 自己的角色就是这么做的）：

```java
public static SRERole PLAGUE_DOCTOR_ROLE; // 供你现有的事件/组件代码引用

@Override
public void register(RoleExtensionRegistrar registrar) {
    PLAGUE_DOCTOR_ROLE = registrar.add(RoleDefinition.builder("example", "plague_doctor")
            .presentation(...).faction(...).spawn(...).compatibility(...)
            .maxSprintTime(20).build());
}
```

---

## 5. MODIFY — 修改已有角色

不改对象身份：**保留目标 canonical ID、原对象、组件键和子类行为**，只把补丁折叠后写回，关闭配置时完整恢复。

### 5.1 基础示例

```java
registrar.modify(RolePatch.builder("starrailexpress", "vigilante")   // 目标角色
        .priority(PatchPriority.NORMAL)               // EARLY(-100) / NORMAL(0) / LATE(100)
        .entryKey("my_vigilante_buff")                // 稳定条目 ID（诊断/配置里可见）
        .color(0xFF00FF00)
        .defaultMax(RolePatch.IntPatch.set(3))        // 数值：set / add / min / max
        .innocent(RolePatch.BooleanPatch.and(true))   // 布尔：set / and / or
        .skills(RoleSkillPatch.append(RoleSkillSpec.of(healSkill)))
        .book(RoleBookPatch.append(RoleBookPage.of(
                Component.translatable("book.example.vigilante.extra"),
                Component.translatable("book.example.vigilante.extra.p1"))))
        .build());                                    // 至少一个字段操作，否则 build 报错
```

### 5.2 支持的补丁字段

| 类别 | 方法 | 操作类型 |
|---|---|---|
| 展示 | `color(int)`、`mood(MoodType)` | SET |
| 阵营/身份 | `innocent(...)`、`canUseKiller(...)`、`neutral(...)`、`vigilanteTeam(...)`、`neutralForKiller(...)`、`neutralForInnocent(...)`、`mafiaTeam(...)`、`otherModeRole(...)`、`hiddenForRotation(...)` | BooleanPatch |
| 生成 | `defaultMax(...)`、`enableChance(...)`、`needPlayerCount(...)`、`maxPlayerCount(...)`、`occupiedRoleCount(...)`、`specialMapRole(...)` | IntPatch / SpecialMapRoleMap |
| 可见性 | `canSeeCoin(...)`、`canPickUpRevolver(...)`、`canBeRandomed(...)`、`maxSprintTime(...)`、`canSeeTime(...)`、`canUseInstinct(...)`、`instinctNightVision(...)`、`canSeeTeammateKiller(...)` | Boolean/IntPatch |
| 关系 | `occupation(...)`、`opposing(...)`、`related(...)` | RoleKeyListPatch |
| 技能 | `skills(RoleSkillPatch)` | `append` / `removeMatchingIds` / `replaceAll` |
| 职业书 | `book(RoleBookPatch)` | `append` / `removeMatchingTitles` / `replaceAll` |

> 角色列表补丁：`RolePatch.RoleKeyListPatch.append(key)` / `.remove(key)` / `.replaceAll(...)`。
> 技能补丁：`RoleSkillPatch.append(spec...)` / `.removeMatchingIds(spec...)` / `.replaceAll(spec...)`。

### 5.3 多人修改同一角色的顺序与冲突

多个 provider 可以修改同一角色。合并顺序固定为：

```
1. 数值优先级（PatchPriority）
2. provider 模组 ID（字典序）
3. entryKey（字典序）
```

**冲突规则：** 两个同优先级 provider 对同一个独占标量字段同时 `SET` 时，默认标记为冲突。管理员可以在配置里指定赢家（`/habitrain roleapi config winner <target#field> <entryId>`），或禁用其中一条——**配置关闭后补丁完全恢复基线**，可以反复开关不会累积。

---

## 6. REPLACE — 替换已有角色

隐藏目标角色，用你自己的实现顶替。**同一目标只允许一个替换 owner。**

```java
registrar.replace(RoleReplacement.builder(
                RoleKey.of("starrailexpress", "loose_end"),   // 目标
                RoleDefinition.builder("example", "rebuilt_loose_end")  // 新实现
                        .presentation(...).faction(...).spawn(...)
                        .compatibility(...).maxSprintTime(20)
                        .build())
        .identity(ReplacementIdentity.NEW_ID_WITH_ALIAS)   // 身份策略
        .entryKey("rewrite_loose_end")
        .build());
```

### 6.1 两种身份策略

| 策略 | 语义 | 使用场景 |
|---|---|---|
| `KEEP_CANONICAL_ID`（默认） | 新实现**继续使用目标 ID**（目标角色被隐藏，你的定义以目标 ID 生效） | 无缝重写上游角色，存档/命令/对象比较全部兼容 |
| `NEW_ID_WITH_ALIAS` | 你的新 ID 成为 canonical，旧目标 ID 自动变成别名解析到新 ID | 明确迁移到新角色身份 |

### 6.2 约束

- `KEEP_CANONICAL_ID`：定义 ID 必须与目标 ID 一致，否则报错。
- `NEW_ID_WITH_ALIAS`：新 ID 必须在你自己的命名空间，且不能与目标相同。
- 替换生效后：卡牌、自选、轮换、职业书、命令、存档里**都不会再出现被隐藏的旧角色**（这是 v2 目录保证的，不是靠删除 Map）。

---

## 7. ALIAS — 旧 ID 迁移

只做 ID 重定向和数据迁移，**不改变任何行为**。适合模组/角色改名、旧存档 ID 兼容。

```java
registrar.alias(RoleAlias.of("oldmod", "doctor",      // 旧 ID
                              "example", "plague_doctor"));  // canonical 新 ID
```

- `to` 必须在你的 provider 命名空间；
- `from` 和 `to` 不能相同；
- alias 环（A→B 且 B→A）、悬空目标、跨 provider 抢同一 `from` 都会在冻结时被拒绝或标记冲突；
- 之后 `RoleCatalogApi.instance().canonicalize(ResourceLocation.parse("oldmod:doctor"))` 会解析到 `example:plague_doctor`。

---

## 8. 行为钩子 Hooks — 让角色活起来

**核心设计：core 对每种事件只注册一个全局监听器，按快照把事件分发给相关角色。你永远不需要、也不应该自己往 Fabric/SRE 事件总线挂永久监听器。**

### 8.1 基本用法

```java
registrar.hooks(PLAGUE_DOCTOR, RoleHooks.builder()
        .lifecycle(new RoleLifecycleHooks() {
            @Override
            public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
                player.sendSystemMessage(Component.literal("你成为了瘟疫医生！"));
            }
            @Override
            public void onLost(ServerPlayer player, RoleHookContext ctx) {
                // 失去角色时的清理
            }
        })
        .combat(new RoleCombatHooks() {
            @Override
            public Decision allowDeath(ServerPlayer player, ResourceLocation deathReason, RoleHookContext ctx) {
                return Decision.DENY;   // 免疫死亡
            }
            @Override
            public void onKill(ServerPlayer victim, ServerPlayer killer, ResourceLocation deathReason, RoleHookContext ctx) {
                // 击杀回调（确认死亡后触发）
            }
        })
        .tick(new RoleTickHooks() {
            @Override
            public void onServerTick(MinecraftServer server, RoleHookContext ctx) {
                // 保持轻量！每 tick 调用
            }
        })
        .shop(new RoleShopHooks() {
            @Override
            public void onAnyBuy(ServerPlayer buyer, io.wifi.starrailexpress.util.ShopEntry entry, RoleHookContext ctx) {
                // 任意玩家购买时触发（配合作用域使用）
            }
        })
        .win(new RoleWinHooks() {
            @Override
            public WinPatch evaluateWin(ServerLevel level, String proposed, RoleHookContext ctx) {
                return WinPatch.addWinners(somePlayerUuid);
            }
        })
        .build());
```

### 8.2 钩子分类总表

| 分类 | 接口 | 主要方法 |
|---|---|---|
| 生命周期 | `RoleLifecycleHooks` | `onAssigned` / `onLost` / `onGameStart` / `onGameEnd` |
| 战斗/死亡 | `RoleCombatHooks` | `allowDeath`(→Decision) / `allowDeathByKiller` / `onDeath` / `onAnyDeath` / `onKill` / `onDeathWithBody` |
| 交互 | `RoleInteractionHooks` | `useItem`(→InteractionResult) / `useEntity` / `useBlock` |
| 商店 | `RoleShopHooks` | `allowBuy`(→Decision) / `onBuy` / `onAnyBuy` |
| 任务 | `RoleTaskHooks` | `onFinishQuest` |
| 会议 | `RoleMeetingHooks` | `onMeetingStart` / `onMeetingEnd` / `allowVoteOut`(→Decision) |
| 胜利 | `RoleWinHooks` | `allowGameEnd`(→Decision) / `evaluateWin`(→WinPatch) / `afterWinnersFinalized` |
| Tick | `RoleTickHooks` | `onServerTick` |

所有方法都有默认空实现——你只需要覆盖感兴趣的。

### 8.3 判定结果：`Decision` 与 `WinPatch`

```java
enum Decision { PASS, ALLOW, DENY }   // 归并：DENY 优先

// 胜利补丁操作
WinPatch.noChange()
WinPatch.addWinners(UUID... ids)
WinPatch.removeWinners(UUID... ids)
WinPatch.replaceWinners(List<UUID> ids)
WinPatch.declareFaction(String faction)
WinPatch.declareCustom(String customId, List<UUID> winners, String reason)
```

### 8.4 作用域（`RoleScope`）—— 广播类事件必须显式声明

```java
registrar.hooks(PLAGUE_DOCTOR, RoleScope.ROUND_PRESENT, RoleHooks.builder()
        .combat(new RoleCombatHooks() {
            @Override
            public void onAnyDeath(ServerPlayer dead, ResourceLocation deathReason, RoleHookContext ctx) {
                // 本局存在该角色时，任何玩家死亡都会触发
            }
        })
        .build());
```

| 作用域 | 触发条件 |
|---|---|
| `HOLDER`（默认） | 事件玩家（killer/victim 等）当前持有该角色 |
| `ANY_ACTIVE_HOLDER` | 任意在线的该角色持有者 |
| `ROUND_PRESENT` | 该角色在本局快照中（不要求有人在位） |
| `GLOBAL_WHILE_ENABLED` | 只要条目启用就触发（高风险，慎用） |

> 生命周期 `onGameStart`/`onGameEnd` 默认是 `HOLDER` 作用域——需要有人在位才会触发；想让"本局存在即通知"请显式传 `ROUND_PRESENT`。

### 8.5 异常隔离

你的钩子抛异常**不会**崩服务器：core 捕获并记录（provider/entry/角色/事件/快照版本），本事件回退安全基线；连续 5 次失败该钩子熔断，`/habitrain roleapi list broken` 可查看，快照切换时自动恢复。

---

## 9. 角色状态 State — 可持久化数据

provider 注册**状态 schema**（不是 CCA 组件键）；core 拥有存储、同步、重置的完整生命周期。

### 9.1 注册

```java
private static RoleStateKey<Integer> SOULS;

// 在 register() 里：
SOULS = registrar.state(RoleStateSpec.of("example", "souls", Integer.class)
        .role(PLAGUE_DOCTOR)
        .scope(StateScope.PLAYER)                 // PLAYER / WORLD / ROUND
        .persistence(Persistence.ROUND)           // NONE / ROUND / WORLD / PERMANENT
        .sync(SyncPolicy.OWNER_AND_TRACKING)      // NONE / SERVER_ONLY / OWNER / OWNER_AND_TRACKING / ALL
        .defaultValue(() -> 0)
        .codec(Codec.INT)                         // 持久化或同步时【必填】
        .dataVersion(1)
        // .migrate(1, v -> v + 10)               // 数据迁移链（v1→v2 等）
        .resetOn(ResetCause.ROLE_LOST)            // 可选；默认见下
        .maxSerializedBytes(256)
        .build());
```

> ⚠️ `persistence` 为 `WORLD`/`PERMANENT` 或 `sync` 非 `NONE` 时**必须提供 codec**，否则注册直接失败（拒绝静默忽略）。

### 9.2 读写

```java
import com.habitrain.core.api.role.v2.state.RoleStateApi;

// 读（写前返回默认值）
Integer souls = RoleStateApi.instance().get(SOULS, player);

// 写
RoleStateApi.instance().set(SOULS, player, souls + 1);

// 手动重置
RoleStateApi.instance().reset(player, PLAGUE_DOCTOR, ResetCause.ROLE_LOST);
```

### 9.3 默认重置行为（不写 `resetOn` 时）

| scope / persistence | 默认重置 |
|---|---|
| `PLAYER` | 失去角色时（`ROLE_LOST`） |
| `ROUND` scope 或 `ROUND`/`NONE` persistence | 回合结束（`ROUND_END`） |
| `WORLD` scope + `WORLD` persistence | 不自动重置，直到手动 |

### 9.4 与 CCA 的关系

你不需要注册 CCA 组件——core 已提供固定的玩家/世界组件容器，数据按 `provider/role/state-key` 存储。你已有的复杂 CCA 组件可以继续保留，不需要迁移。

---

## 10. 受管动作 Action — 安全的网络交互

角色选择、招募、刻印、决斗这类"客户端 → 服务端"交互，**不要自己写包**。core 用一对包实现多路复用，统一做大小/频率/冷却/角色门校验。

### 10.1 服务端定义

```java
registrar.action(RoleActionSpec.of("example", "plague_bite")
        .role(PLAGUE_DOCTOR)
        .direction(RoleActionDirection.C2S)       // C2S / S2C
        .maxBytes(64)                             // 最大负载字节
        .ratePerSecond(5)                         // 每秒次数
        .cooldownTicks(100)                       // 冷却（tick）
        .requireCurrentRole(true)                 // 必须当前持有该角色
        .requireAlive(true)
        .handler(ctx -> {
            // ctx: RoleActionContext(role, playerId, payload, sequence, targetId)
            byte[] data = ctx.payload();
            // ...你的业务逻辑，在服务端线程执行...
            return RoleActionResult.success();    // 或 reject(RoleActionResult.WRONG_ROLE) 等
        })
        .build());
```

结果常量：`RoleActionResult.OK / WRONG_ROLE / COOLDOWN / RATE / DEAD / HANDSHAKE ...`；
`RoleActionResult.success([payload])` 可携带响应数据（S2C 回传）。

### 10.2 客户端调用

客户端侧使用 `RoleActionClientApi`（仅客户端可引用，专用服务器安全）：

```java
// 客户端：发送动作（发起请求，payload 与序列号自动管理）
RoleActionClientApi.instance().send(ResourceLocation.fromNamespaceAndPath("example", "plague_bite"),
        new byte[]{1, 2, 3}, (result) -> {
            // 服务端响应回调
        });
```

> 服务端主动推送（S2C push）由 `RoleActionApi.instance().sendTo(player, actionId, payload)` 触发，客户端侧同样经 `RoleActionClientApi` 接收。

---

## 11. 客户端扩展 — HUD / 直觉 / 皮肤 / 名字渲染

> **当前状态提醒（2026-08-16 审核后）：** 本包 API 已落地，但能力分级不同：
> - **正式渲染：** HUD 的 `TEXT` / `BADGE`（stock 客户端实际绘制文本）。
> - **基础渲染（`client_hud_visual`）：** HUD 的 `ICON` / `PROGRESS` / `COOLDOWN` / `CHARGE` — stock 客户端现在会以“类型前缀 + 文本”的 fallback 渲染，不再静默忽略；需要真实纹理/数值时请用自定义 `RoleHudWidget`。
> - **name render（`client_name_render`）：** `NAMEPLATE` 阶段已接入 `EntityRenderer` mixin，支持 `hide()` 与 `color()`；`TAB` / `INTRO` / `SPECTATOR` / `INSANE` 仍无运行时消费端。
> - **experimental（`client_screen`）：** 屏幕声明已注册可查询，并新增 stock `RoleScreen` 分发入口（`RoleClientExtensionHooks.openRoleScreen()`）；provider 需自行在合适时机触发。
> - 自定义 `RoleHudWidget` 收到真实的渲染帧 `tickDelta`（非固定 0）。
>
> provider 使用 experimental 能力前请先确认 core 版本并做好降级。

### 11.1 注册

```java
package com.example.roles.client;

import com.habitrain.core.api.role.v2.client.*;

public final class ExampleRoleClientProvider implements RoleClientExtensionEntrypoint {
    @Override
    public void register(RoleClientExtensionRegistrar registrar) {
        // HUD 文字组件
        registrar.hud(RoleHudSpec.of("example", "plague_hud")
                .role(RoleKey.of("example", "plague_doctor"))
                .kind(RoleHudKind.TEXT)             // 正式渲染：TEXT/BADGE；ICON/PROGRESS/COOLDOWN/CHARGE 为基础 fallback 渲染（client_hud_visual）
                .textKey("hud.example.plague.status")
                .color(0xFF7BB661)
                .position(4, 4)
                .build());

        // 直觉高亮规则：瘟疫医生活着时（ALIVE_MIDDLE 阶段）看到某人显示自定义颜色
        registrar.instinct(RoleInstinctRule.of("example", "plague_instinct")
                .viewerRole(RoleKey.of("example", "plague_doctor"))
                .targetRole(RoleKey.of("starrailexpress", "killer"))
                .phase(InstinctPhase.ALIVE_MIDDLE)
                .color(0xFF00FF00)
                .build());

        // 皮肤
        registrar.skin(RoleSkinSpec.of("example", "plague_skin")
                .role(RoleKey.of("example", "plague_doctor"))
                .kind(RoleSkinKind.NORMAL)
                .wide(ResourceLocation.fromNamespaceAndPath("example", "textures/role/plague_wide.png"))
                .slim(ResourceLocation.fromNamespaceAndPath("example", "textures/role/plague_slim.png"))
                .build());

        // 名字渲染（experimental）
        registrar.nameRender(RoleNameRenderRule.of("example", "plague_name")
                .role(RoleKey.of("example", "plague_doctor"))
                .phase(RoleRenderPhase.NAMEPLATE)
                .color(0xFF7BB661)
                .build());

        // 自定义 HUD 控件（复杂绘制）：实现 RoleHudWidget（void render(int w, int h, float tickDelta)）
        registrar.hudWidget(RoleKey.of("example", "plague_doctor"),
                (screenWidth, screenHeight, tickDelta) -> { /* 每帧绘制 */ });
    }
}
```

### 11.2 客户端扩展缺失时的行为

- 专用服务器：绝不加载含 `MinecraftClient` 的类（客户端扩展类型是专用服务器安全的）。
- 客户端没装你的模组/扩展：服务端行为照常运行。握手按你的声明处理：provider 在 `RoleExtensionEntrypoint.requiresClient()` 返回 `true` 时，缺少该模组（或模组在、但客户端扩展未加载）的客户端会被**拒绝受管动作**；未声明则不受影响（`REQUIRED` 不再靠"是否声明了客户端 entrypoint"猜测）。
- stock 客户端不计算 definition/presentation hash（信任服务端），因此 `HASH_MISMATCH` 与展示降级分支对 stock 客户端不触发——详见 §15.2 的降级策略。

---

## 12. 语音 / 聊天能力

```java
// 语音：模组有语音聊天（Simple Voice Chat）时生效
if (RoleExtensionApi.instance().supports(RoleCapabilityKey.VOICE)) {
    registrar.voice(RoleVoicePolicy.of("example", "plague_voice")
            .role(PLAGUE_DOCTOR)
            .isolateGroup()          // 瘟疫医生只能听见瘟疫医生
            // .muteSend() / .muteReceive() / .hearWorld(true) / .maxDistance(10.0)
            .build());
}

// 聊天：聊天静音策略
registrar.chat(RoleChatPolicy.of("example", "silenced_role")
        .role(SOME_ROLE)
        .muteSend()          // 禁言（发）
        // .muteReceive()     // 禁言（收）
        .build());
```

没有对应模组时，策略自动 `UNAVAILABLE`，core **不会加载外部类**，无需 try-catch。

---

## 13. 运行时查询与角色变更

### 13.1 有效角色目录（`RoleCatalogApi`）

任何地方需要"当前有效的角色列表/某个角色"——**必须用目录**：

```java
import com.habitrain.core.api.role.v2.RoleCatalogApi;
import com.habitrain.core.api.role.v2.RoleQuery;
import com.habitrain.core.api.role.v2.QueryPurpose;

RoleCatalogApi catalog = RoleCatalogApi.instance();

// 查单个角色（自动解析 alias / replacement）
catalog.find(RoleKey.of("example", "plague_doctor"));       // Optional<EffectiveRole>

// 规范化任意 ID（alias/replacement 解析到 canonical）
catalog.canonicalize(ResourceLocation.parse("oldmod:doctor"));

// 枚举有效角色（可过滤）
catalog.effectiveRoles();                                   // 全部
catalog.effectiveRoles(RoleQuery.builder()
        .purpose(QueryPurpose.LOTTERY_CARD)                 // 用途：随机/职业书/命令/抽奖/自选...
        .provider("example")
        .build());

// 从原始对象 / 存档字符串解析
catalog.resolve(rawSreRole);                                // Optional<EffectiveRole>
catalog.resolveStored("oldmod:doctor");                     // 存档兼容

// 状态查询
catalog.isActive(RoleKey.of("example", "plague_doctor"));
catalog.isAdded(key) / isModified(key) / isReplaced(key);

// 快照
catalog.snapshot();                                         // 当前快照 ID
```

`EffectiveRole` 提供：`key()`（canonical）、`id()`、`role()`（上游对象）、`source()`（`BASELINE/ADDED/MODIFIED/REPLACEMENT`）、`profile()`。

### 13.2 角色变更（`RoleChangeApi`）

游戏中转职/强制分配/移除**不要直接改角色 Map**，走事务化服务：

```java
import com.habitrain.core.api.role.v2.RoleChangeApi;
import com.habitrain.core.api.role.v2.RoleChangeCause;
import com.habitrain.core.api.role.v2.RoleChangeOptions;

RoleChangeApi change = RoleChangeApi.instance();

// 强制分配（记时间线+统计）
RoleChangeResult r = change.assign(player, RoleKey.of("example", "plague_doctor"),
        RoleChangeOptions.defaults());       // silent() 静默 / forceReinitialize() 强制重跑

// 转职（带原因）
change.transform(player, RoleKey.of("example", "plague_doctor"), RoleChangeCause.CONVERSION);

// 移除
change.remove(player, RoleChangeCause.REMOVE);

// 查询
RoleView current = change.current(player);
List<RoleHistoryEntry> history = change.history(player);
```

事务内部会：解析 alias → 校验 → 旧角色 `onLost` → 清理状态/技能/物品 → 更新 SRE 角色映射与阵营 → 写历史 → 初始化新角色 → `onAssigned` → 同步客户端；失败回滚。

---

## 14. 配置、管理命令与诊断

### 14.1 配置文件

路径：`config/habitrain_role_v2.json`（服务端权威，ModMenu「角色扩展」页可可视化编辑，OP4 可改）：

```json
{
  "roleExtensionsV2": {
    "version": 1,
    "enabled": true,
    "providers": { "example": true },
    "entries": { "example$example:plague_doctor": true },
    "conflictWinners": { "starrailexpress:vigilante#defaultMax": "othermod$winning_entry" },
    "allowGlobalHooks": false
  }
}
```

开关顺序：**全局 → provider → 条目**。关闭后：技能、钩子、状态、商店、可见性全部恢复基线，下一局生效。

### 14.2 管理命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/habitrain roleapi providers` | OP2 | provider 列表 |
| `/habitrain roleapi list [effective\|disabled\|conflict\|invalid\|legacy\|broken]` | OP2 | 按状态列出条目 |
| `/habitrain roleapi inspect <role>` | OP2 | 单角色详情 |
| `/habitrain roleapi trace <role> <field>` | OP2 | **字段级追溯**：基线 → 每个补丁 → 最终值 → 冲突决定 |
| `/habitrain roleapi aliases [role]` | OP2 | 别名视图 |
| `/habitrain roleapi snapshot` / `hooks <role>` / `actions` / `capabilities` / `perf` / `archive` / `legacy` / `state [player]` | OP2 | 快照/钩子/动作/能力/性能/归档/旧式/状态 |
| `/habitrain roleapi config status` | OP2 | 配置与持久化状态 |
| `/habitrain roleapi config set provider\|entry <id> on\|off` | OP4 | 开关 provider/条目 |
| `/habitrain roleapi config set allowGlobalHooks on\|off` | OP4 | 全局钩子开关 |
| `/habitrain roleapi config winner <target#field> <entryId>` | OP4 | 指定冲突赢家 |
| `/habitrain roleapi manifest` | OP2 | 握手清单 |

> **调试定位冲突的第一工具是 `trace`**：它逐字段显示"谁在什么时候把这个字段改成了什么、最终为什么是这个值"。

---

## 15. 版本兼容与客户端握手

### 15.1 API 版本

```java
RoleExtensionApi.instance().apiVersion();   // 当前 "2.0"
RoleExtensionApi.instance().supports(RoleCapabilityKey.VOICE);  // 能力协商
```

你的 provider 应在注册时检查版本，缺少必需能力时主动报错（而不是等到游戏里 `NoSuchMethodError`）。

### 15.2 客户端握手（自动）

- 服务端在玩家加入时发送 **manifest**（provider 清单、API 版本、definitionHash、presentationHash、配置、能力集合）；客户端返回本地报告，服务端匹配：
  - `OK` → 放行受管动作；
  - `REJECTED_MISSING_PROVIDER`（API 版本不匹配 / 缺少必需 provider / 必需 provider 的客户端扩展未加载）→ **拒绝该玩家的受管动作**；
  - `HASH_MISMATCH`（仅当客户端上报了独立的非空 definition hash 且不一致）→ 拒绝；
  - `DEGRADED_CLIENT_EXTENSION`（仅当客户端上报了非空 presentation hash 且不一致、又缺展示资源）→ 降级放行；
  - 无报告（旧客户端/异常）→ fail-closed 拒绝。
- **降级策略（诚实版）：** stock 客户端**不计算** definition/presentation hash（它看不到服务端编译视图与配置），上报 `null` 即"信任服务端"。因此 `HASH_MISMATCH` 与 `DEGRADED_CLIENT_EXTENSION` 分支对 stock 客户端**不会触发**；受管动作的握手门控实际依赖：报告完整性、API 版本一致性与必需 provider（含客户端扩展加载）检查。在客户端具备独立指纹能力之前，不要把 definition-hash 双端校验宣传为已闭环。
- 握手失败**不影响 SRE 原版玩法**，只影响 v2 受管动作。

---

## 16. 设计准则与红线

| ✅ 应该 | ❌ 不要 |
|---|---|
| 新角色用 `registrar.add(...)` | `TMMRoles.registerRole(...)` 直接注册 |
| 修改用 `registrar.modify(...)` | 直接改 `SRERole` 字段/Map（不可恢复） |
| 角色行为用 `registrar.hooks(...)` | 自己注册 `ServerTickEvents`/死亡等全局监听器（关闭后无法撤销） |
| 查询角色用 `RoleCatalogApi` | 遍历 `TMMRoles.ROLES.values()` |
| 转职/移除用 `RoleChangeApi` | 只改某一个角色 Map |
| 网络交互用 `registrar.action(...)` | 自建无校验的自定义包 |
| provider 自己的 Mixin 用 `catalog.isActive(...)` 门控 | 类加载时永久改全局注册表 |

**其他约定：**

- 所有 ID（角色、状态、动作、技能、hook 条目）必须有你的模组命名空间且稳定——改名会破坏存档与配置。
- 你的 `register()` 抛异常 = 整体回滚（零条目零泄漏），但不要依赖它做流程控制。
- 动态回调（如商店 `live(...)`）必须声明服务端线程语义；不要在客户端线程调用只含服务端对象的函数。
- 客户端扩展缺失时要有降级，不要假设双方一定装了同一套模组。
- 钩子保持轻量：tick 类钩子每 tick 调用；core 有性能统计（`/habitrain roleapi perf`），超预算会告警。

---

## 17. 完整示例模组

一个集成四操作 + 钩子 + 状态 + 动作 + 客户端扩展的完整骨架（可复制修改）：

```java
package com.example.roles;

import com.habitrain.core.api.role.v2.*;
import com.habitrain.core.api.role.v2.definition.*;
import com.habitrain.core.api.role.v2.behavior.*;
import com.habitrain.core.api.role.v2.state.*;
import com.habitrain.core.api.role.v2.action.*;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.level.ServerPlayer;

public final class ExampleRoleProvider implements RoleExtensionEntrypoint {

    public static final RoleKey PLAGUE_DOCTOR = RoleKey.of("example", "plague_doctor");

    public static SRERole PLAGUE_DOCTOR_ROLE;
    public static RoleStateKey<Integer> SOULS;

    @Override
    public void register(RoleExtensionRegistrar registrar) {
        // ---------- ADD ----------
        PLAGUE_DOCTOR_ROLE = registrar.add(RoleDefinition.builder(PLAGUE_DOCTOR)
                .presentation(RolePresentation.builder().color(0xFF7BB661).build())
                .faction(RoleFactionProfile.builder().innocent().build())
                .spawn(RoleSpawnProfile.builder().defaultMax(1).needPlayerCount(8).build())
                .compatibility(RoleCompatibilityProfile.builder().canSeeCoin().build())
                .inventory(RoleInventoryProfile.builder()
                        .item(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK))
                        .build())
                .maxSprintTime(20)
                .build());

        // ---------- State ----------
        SOULS = registrar.state(RoleStateSpec.of("example", "souls", Integer.class)
                .role(PLAGUE_DOCTOR)
                .scope(StateScope.PLAYER)
                .persistence(Persistence.ROUND)
                .sync(SyncPolicy.OWNER_AND_TRACKING)
                .defaultValue(() -> 0)
                .codec(com.mojang.serialization.Codec.INT)
                .dataVersion(1)
                .build());

        // ---------- Action ----------
        registrar.action(RoleActionSpec.of("example", "plague_bite")
                .role(PLAGUE_DOCTOR)
                .cooldownTicks(100)
                .handler(ctx -> {
                    if (RoleStateApi.instance().get(SOULS, ctx.playerId()) == null) {
                        return RoleActionResult.reject(RoleActionResult.COOLDOWN);
                    }
                    // ...业务...
                    return RoleActionResult.success();
                })
                .build());

        // ---------- Hooks ----------
        registrar.hooks(PLAGUE_DOCTOR, RoleHooks.builder()
                .lifecycle(new RoleLifecycleHooks() {
                    @Override
                    public void onAssigned(ServerPlayer player, RoleHookContext ctx) {
                        RoleStateApi.instance().set(SOULS, player, 0);
                    }
                })
                .combat(new RoleCombatHooks() {
                    @Override
                    public Decision allowDeath(ServerPlayer player,
                                               net.minecraft.resources.ResourceLocation deathReason,
                                               RoleHookContext ctx) {
                        return Decision.DENY;   // 瘟疫不死（示例）
                    }
                })
                .build());

        // ---------- MODIFY：顺便给某原版角色加一行书页 ----------
        registrar.modify(RolePatch.builder("starrailexpress", "vigilante")
                .entryKey("example_vigilante_book")
                .book(com.habitrain.core.api.role.v2.book.RoleBookPatch.append(
                        com.habitrain.core.api.role.book.RoleBookPage.of(
                                net.minecraft.network.chat.Component.translatable("book.example.vigi.title"),
                                net.minecraft.network.chat.Component.translatable("book.example.vigi.p1"))))
                .build());

        // ---------- ALIAS ----------
        registrar.alias(RoleAlias.of("legacymod", "doctor", "example", "plague_doctor"));
    }
}
```

```json
{
  "entrypoints": {
    "habitrain:role_extensions": ["com.example.roles.ExampleRoleProvider"],
    "habitrain:role_client_extensions": ["com.example.roles.client.ExampleRoleClientProvider"]
  },
  "depends": {
    "minecraft": "~1.21.1",
    "fabricloader": ">=0.18.2",
    "fabric-api": "*",
    "habitrain_core": "*",
    "starrailexpress": "*"
  }
}
```

---

## 18. FAQ 与故障排查

**Q: 注册时报 "namespace must be the provider mod id"？**
ADD/ALIAS 的 `to`/REPLACE 的新 ID 必须用你自己的 mod id 作为命名空间。core 从 entrypoint 自动识别你的 mod id，不要用别人的命名空间。

**Q: 我的角色在 `/habitrain roleapi list effective` 里看不到？**
按顺序查：① 条目是否 enabled（`list disabled`/`list conflict`/`list invalid`）；② `inspect <role>` 看状态与错误消息；③ `trace <role> <field>` 看冲突。

**Q: 配置里关了角色，但效果还在？**
配置改动默认**下一局生效**（`NEXT_ROUND`）；本局内的变化需要等下一局。全局钩子开关 `allowGlobalHooks` 另查。

**Q: 我的补丁和别人的补丁打架怎么办？**
同字段同优先级冲突 → 管理员用 `/habitrain roleapi config winner <target#field> <entryId>` 指定赢家，或调整你的 `priority`（EARLY/NORMAL/LATE）。

**Q: 客户端报 HASH_MISMATCH / 受管动作被拒？**
服务端与客户端的模组/版本/配置不一致。检查：服务端与客户端装了一样的模组版本？provider 是否两边都装了？`/habitrain roleapi manifest` 对比 definitionHash。

**Q: 我需要发送自定义动画/渲染，API 不支持？**
服务端规则类能力优先用受管钩子；纯客户端渲染（复杂粒子、屏幕特效）如果 API 表达不了，可以用你自己的 Mixin/包——但**必须用 `RoleCatalogApi.isActive(...)` 门控**，且不能在类加载时永久修改全局注册表。

**Q: 我的角色需要特殊胜利（瘟疫、决斗、多阶段）？**
用 `RoleWinHooks`：`allowGameEnd`（阻止正常结束）+ `evaluateWin`（返回 `WinPatch` 补丁）+ `afterWinnersFinalized`（结算后只读回调）。多阶段复杂流程建议自己维护状态（`RoleStateApi`）+ `RoleChangeApi` 转换。

**Q: 已有角色被替换后，我的 MODIFY 还生效吗？**
MODIFY 作用于 canonical 角色。若你 MODIFY 的目标被 REPLACE（`NEW_ID_WITH_ALIAS`），MODIFY 会折叠到替换后的角色上（`MODIFY of the replacement id` 语义）；目标被隐藏时，对旧 ID 的修改仍然作用于最终生效角色。冲突时用 `trace` 查看。

---

> **相关文档：** 《哈比列车完整角色扩展API设计与实现指南.md》（架构/维护基线）· `API参考手册.md`（接口速查）· `/habitrain roleapi` 命令
> **最后更新：** 2026-08-16 · 对应 habitrain_core 2.0.1 / API `2.0`

---

## 附录 A. 能力矩阵（2026-08-16 审核后）

状态含义：**STABLE**（正式可用）/ **EXPERIMENTAL**（已实现或已声明，但尚未验证/未闭环）/ **UNSUPPORTED**（v2 当前无等价能力）/ **REQUIRES_PROVIDER_MIXIN**（core 不自动接管，需 provider 自己实现）。

| 能力 | 状态 | 说明 |
|---|---|---|
| v1 `RoleOverrideApi`（REPLACE/MODIFY） | STABLE | 正式兼容 API，不弃用（2026-08-15 撤回弃用） |
| v2 ADD / MODIFY / REPLACE / ALIAS | EXPERIMENTAL | 实现完整，但 v2 总体仍为 preview，未做真实 SRE 双端验证 |
| v2 MODIFY 字段（颜色/名称/描述/物品/商店/阵营/生成/关系/技能/职业书/胜利） | EXPERIMENTAL | 已补齐 v1 字段级对等（2026-08-16），可逆、有基线/冲突/恢复 |
| v2 state / action / voice / chat | EXPERIMENTAL | 已实现并接入 provider/entry 配置门控（审核 P1-2 修复） |
| `useItem` / `useEntity` / `useBlock` / `attackEntity` / `attackBlock` / `breakBlock` | EXPERIMENTAL | 运行时已接通全局监听（审核 P0-3/P1-5 修复），属 preview API |
| `client_hud` TEXT / BADGE | STABLE（消费范围） | stock 客户端实际渲染的 HUD 类型；不改变 v2 总体 preview 状态 |
| `client_hud_visual` ICON/PROGRESS/COOLDOWN/CHARGE | EXPERIMENTAL | stock 客户端以“类型前缀 + 文本”fallback 渲染，不再静默忽略 |
| `client_instinct` / `client_skin` | EXPERIMENTAL | 已实现运行时消费 |
| `client_name_render` | EXPERIMENTAL | NAMEPLATE 已接入（hide/color）；其余阶段尚无消费端 |
| `client_screen` | EXPERIMENTAL | 已声明可查询，并新增 stock `RoleScreen` 分发入口（需 provider 触发） |
| v1 名称 / 描述 / 初始物品 / 商店能力 | SUPPORTED | 已通过 v2 RolePatch 字段迁移（2026-08-16） |
| 胜利条件钩子与动态回调语义 | REQUIRES_PROVIDER_MIXIN | core 不自动接管上游私有/动态行为 |
| definition hash 双端独立校验 | UNSUPPORTED（未闭环） | stock 客户端上报服务端快照 hash，可捕获 manifest/snapshot 不一致；但尚不能独立计算服务端编译视图哈希 |

> 使用 EXPERIMENTAL / UNSUPPORTED 能力前，先确认 core 版本，并让模组在能力缺失时优雅降级（版本检查 + 可选功能）。
