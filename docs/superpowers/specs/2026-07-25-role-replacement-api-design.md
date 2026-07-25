# 哈比列车角色替换/修改 API 设计文档

> 版本：v1.0（设计阶段）  
> 日期：2026-07-25  
> 负责人：Mike / 哈比列车 core 维护者  
> 目标阅读者：后续实现者、DLC 模组开发者、Claude Code 技能使用者

---

## 1. 背景与目标

### 1.1 背景

哈比列车（`habitrain_core`）的角色系统建立在 SRE（`starrailexpress`）的 `TMMRoles.ROLES` 之上。
所有职业身份最终都是 `ResourceLocation → SRERole` 的映射，分配池、角色介绍书、命令补全都从这里读取。

当前生态中，DLC 想替换或深度修改一个已有角色（例如把 `sre:killer` 换成自己的设计）非常困难：
- 直接 `TMMRoles.registerRole` 无法覆盖已有 id；
- 直接改 `SRERole` 字段会污染全局状态；
- 没有统一的启用/禁用/冲突管理。

### 1.2 目标

由 `habitrain_core` 提供一套**受控的角色替换/修改 API**，让其他模组可以：

1. **REPLACE（替换）**：把任意已注册角色（目标角色）从分配池、角色介绍书、命令补全中隐藏，并以新角色取而代之。新角色 id 统一规范为 `modId:roleName`。
2. **MODIFY（调整）**：在不改变目标角色 id 的前提下，动态覆盖其显示文案、阵营 flags、商店、初始物品、生成参数、技能/被动、胜利条件等。
3. **管理开关**：在 ModMenu 中提供“角色覆盖”Tab，玩家/OP 可以全局启用/停用，并对冲突条目进行裁决。
4. **实时生效**：开关变更后立即影响分配池、角色介绍书、命令可见性；但不影响**已经分配到玩家身上的角色**。

### 1.3 设计原则

| 原则 | 说明 |
|---|---|
| Core 掌控启用权 | 外部模组只能“提交意图”，最终是否生效由 core 的配置 + 冲突裁决决定。 |
| 不物理删除原角色 | 从 `TMMRoles.ROLES` 中临时 `remove` 风险过高；采用过滤/补丁层/禁用路径实现隐藏。 |
| 标准 ID | 新角色 id 必须是 `modId:roleName` 形式，core 会强制校验或改写 namespace。 |
| 冲突禁止自动合并 | 同一目标若有多条 REPLACE、多条 MODIFY 或 REPLACE+MODIFY，默认全部不生效，须用户在 ModMenu 中手动选一条。 |
| 互斥 | 同一目标同一时刻只能生效 REPLACE 或 MODIFY 中的一种。 |
| 与现有生态兼容 | 新包 `com.habitrain.core.api.role` 风格对齐 `GameModeRegistry`、`TaskRegistry`。 |

---

## 2. 已确认的产品决策

| 决策项 | 结论 |
|---|---|
| 可被替换/修改的目标范围 | 全量 `TMMRoles.ROLES` |
| 新角色 ID 格式 | `modId:roleName`（标准 `ResourceLocation`） |
| 多 mod 冲突策略 | **禁止冲突**：同目标多条覆盖默认都不生效，须在 ModMenu 手动选一条 |
| 开关生效时机 | **立即实时**：池、角色介绍书、命令补全；但不局中实时转职已分配玩家 |
| REPLACE 注册模型 | 外部提供完整 `SRERole`，core 负责隐藏目标、协调启用 |
| MODIFY v1 覆盖范围 | 显示文案、阵营/flags、商店、初始物品、生成参数、技能/被动、胜利条件 |
| REPLACE ↔ MODIFY 关系 | **互斥**：同一目标同时只能有一种生效 |
| 架构路径 | **方案 A**：Core 协调层 + 上游钩子/mixin |

---

## 3. 架构概览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 外部 DLC mod (在 ModInitializer.onInitialize 中)                               │
│   RoleOverrideApi.registerReplace(ReplaceRoleDefinition)                      │
│   RoleOverrideApi.registerModify(ModifyRoleDefinition)                      │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ com.habitrain.core.api.role                                                   │
│   RoleOverrideApi            ← 公共注册入口                                   │
│   ReplaceRoleDefinition      ← REPLACE 提交定义                               │
│   ModifyRoleDefinition       ← MODIFY 提交定义                                │
│   RoleOverrideKind           ← REPLACE / MODIFY                             │
│   RoleOverrideEntry          ← 运行时条目视图                                 │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ com.habitrain.core.role.override                                              │
│   RoleOverrideRegistry       ← 收集所有提交，注册阶段可追加                     │
│   RoleOverrideEngine         ← 根据配置裁决 effective snapshot              │
│   EffectiveSnapshot            ← 当前生效的 REPLACE/MODIFY 集合               │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ com.habitrain.core.config                                                     │
│   RoleOverrideConfigSection  ← JSON 中的 "roleOverrides" 段                   │
│   ConfigRepository/ConfigStore/ConfigSync 接入并同步                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 上游 SRE / noelles / habitrain 游戏逻辑                                       │
│   RoleAssignmentPool 谓词过滤                                                 │
│   RoleIntroduceScreen 列表过滤/重建                                          │
│   RoleArgumentType 命令补全过滤                                               │
│   SRERole 属性补丁层（显示名、颜色、商店、初始物品、flags、spawnInfo）        │
│   技能/被动/胜利条件钩子                                                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. 公共 API 表面

### 4.1 包

```
com.habitrain.core.api.role
```

### 4.2 注册入口

```java
public final class RoleOverrideApi {
    /** 提交一条 REPLACE 定义。必须在 mod 的 onInitialize 中调用。 */
    public static void registerReplace(ReplaceRoleDefinition def) { ... }

    /** 提交一条 MODIFY 定义。必须在 mod 的 onInitialize 中调用。 */
    public static void registerModify(ModifyRoleDefinition def) { ... }

    /** 运行时读取当前生效条目（服务端/客户端都可调用，结果以本地 effective snapshot 为准）。 */
    public static Collection<RoleOverrideEntry> getEffectiveEntries() { ... }

    /** 判断某目标角色当前是否被 REPLACE。 */
    public static boolean isReplaced(ResourceLocation targetRoleId) { ... }

    /** 获取某目标角色被 REPLACE 后的实际角色实例（未替换则 null）。 */
    public static @Nullable SRERole getReplacement(ResourceLocation targetRoleId) { ... }

    /** 判断某目标角色当前是否被 MODIFY。 */
    public static boolean isModified(ResourceLocation targetRoleId) { ... }

    /** 获取某目标角色当前生效的 MODIFY 定义（未修改则 null）。 */
    public static @Nullable ModifyRoleDefinition getActiveModify(ResourceLocation targetRoleId) { ... }
}
```

### 4.3 REPLACE 定义

```java
public final class ReplaceRoleDefinition {
    public String sourceModId();                     // 来源 mod id
    public RoleOverrideKind kind();                  // REPLACE
    public Component displayName();                  // ModMenu 列表显示名
    public Optional<Component> description();        // 可选简介
    public Optional<ResourceLocation> icon();        // 可选图标
    public Optional<String> customTypeLabel();       // 自定义“替换/调整”标签，如 "完全替换"
    public ResourceLocation targetRoleId();          // 被替换的原版角色 id
    public SRERole replacementRole();                // 外部构造好的角色实例
    public ResourceLocation replacementId();         // 最终分配的角色 id
}

public final class ReplaceRoleDefinition.Builder {
    Builder sourceModId(String modId);
    Builder displayName(Component name);
    Builder description(Component desc);
    Builder icon(ResourceLocation icon);
    Builder customTypeLabel(String label);           // 默认 "替换"
    Builder targetRoleId(ResourceLocation id);
    Builder replacementRole(SRERole role);
    Builder replacementId(ResourceLocation id);      // 可选；namespace 会被 core 校验/改写
    ReplaceRoleDefinition build();
}
```

### 4.4 MODIFY 定义

```java
public final class ModifyRoleDefinition {
    public String sourceModId();
    public RoleOverrideKind kind();                  // MODIFY
    public Component displayName();
    public Optional<Component> description();
    public Optional<ResourceLocation> icon();
    public Optional<String> customTypeLabel();       // 默认 "调整"
    public ResourceLocation targetRoleId();

    // 以下为可选补丁回调；不设置表示不改该属性
    public Optional<NamePatch> namePatch();
    public Optional<ColorPatch> colorPatch();
    public Optional<ShopPatch> shopPatch();
    public Optional<DefaultItemsPatch> defaultItemsPatch();
    public Optional<FlagsPatch> flagsPatch();
    public Optional<SpawnInfoPatch> spawnInfoPatch();
    public Optional<SkillRegistrar> skillRegistrar();
    public Optional<WinConditionHook> winConditionHook();
}
```

补丁回调接口示例：

```java
@FunctionalInterface
public interface NamePatch {
    Component getName(SRERole original, MinecraftServer server);
}

@FunctionalInterface
public interface ShopPatch {
    List<ShopEntry> getShopEntries(SRERole original, MinecraftServer server);
}

@FunctionalInterface
public interface SkillRegistrar {
    /** 在 MODIFY 首次生效时调用；负责注册技能/被动。 */
    void register(SRERole original);
}

@FunctionalInterface
public interface WinConditionHook {
    /** 返回是否劫持胜利判定；null 表示不干预。 */
    @Nullable WinResult check(BlackoutWinCheckContext ctx);
}
```

### 4.5 运行时条目视图

```java
public final class RoleOverrideEntry {
    public String entryId();                         // 全局唯一 key
    public String sourceModId();
    public RoleOverrideKind kind();
    public Component displayName();
    public ResourceLocation targetRoleId();
    public Optional<ResourceLocation> replacementId();
    public OverrideStatus status();                  // ACTIVE / CONFLICT / DISABLED / INVALID
    public Optional<String> statusMessage();         // 冲突/无效原因
}
```

---

## 5. 生效引擎

### 5.1 核心类

```
com.habitrain.core.role.override.RoleOverrideEngine
```

职责：
- 持有 `RoleOverrideRegistry` 的所有提交；
- 根据 `RoleOverrideConfigSection` 计算 `EffectiveSnapshot`；
- 提供 `rebuild()` 方法，在配置变化时重新裁决；
- 提供查询方法供游戏逻辑、UI、mixin 调用。

### 5.2 裁决规则

1. 全局开关关闭 → `EffectiveSnapshot` 为空。
2. 全局开关打开：
   - 只处理 `entryEnabled` 中标记为启用的条目。
   - 对每一个 `targetRoleId`：
     - 若存在 REPLACE，且启用条数为 1 → 该 REPLACE 生效。
     - 若存在 REPLACE，且启用条数 > 1 → 全部标记 CONFLICT，均不生效。
     - 若存在 MODIFY，且启用条数为 1 → 该 MODIFY 生效。
     - 若存在 MODIFY，且启用条数 > 1 → 全部标记 CONFLICT，均不生效。
     - 若同时存在 REPLACE 和 MODIFY 启用 → 全部标记 CONFLICT，均不生效。
3. 用户解决冲突：在 ModMenu 中选中一条时，core 自动关闭同组其他条目。
4. `targetRoleId` 在 `TMMRoles.ROLES` 中不存在 → 该条目标记 INVALID，不生效。

### 5.3 隐藏被替换原角色（L1 + L2）

#### 分配池过滤

对 `RoleAssignmentPool.create` 使用的角色源做 hook。推荐 mixin 点：

- `org.agmas.harpymodloader.modded_murder.RoleAssignmentPool.createInternal` 中读取 `TMMRoles.ROLES.values()` 后的过滤阶段；
- 或在 `org.agmas.noellesroles.Noellesroles.getEnableAndAvailableRoles` / `getAllRoles` 入口做过滤。

过滤条件：`RoleOverrideEngine.getReplacement(role.identifier()) != null` 的角色不进入候选列表。

#### 角色介绍书过滤

对 `org.agmas.noellesroles.client.screen.RoleIntroduceScreen` 做 mixin：

- 构造时 `availableRoles` 不再直接使用 `Noellesroles.getAllRolesSorted(true)`，而是调用 `RoleOverrideFilter.apply(List)` 后填入。
- 接收到配置同步时，调用内部 `refreshFilter()` 重新生成列表。
- 目标原角色不进入列表；REPLACE 的新角色进入列表。

#### 命令补全过滤

对 `org.agmas.harpymodloader.commands.argument.RoleArgumentType` 做 mixin：

- 建议列表生成时过滤掉被 REPLACE 的目标角色。
- 命令执行时若玩家输入了被替换的目标角色 id，返回友好错误：`"该职业已被 <replacementId> 替换，已禁用。"`

#### SRE 禁用路径（L2）

如果上游 `SREDisableManager.isRoleDisabled` 在分配池/命令/介绍书等路径被广泛查询，
可在 core 中维护一个扩展禁用集合 `coreDisabledRoleIds`，并在 mixin 中把该集合注入到 `SREDisableManager.isRoleDisabled` 判定中。

此做法需确认 `SREDisableManager` 的行为不会导致副作用（例如错误地影响任务、商店等）。
若副作用不可控，则完全回退到 L1 过滤。

### 5.4 REPLACE 新角色的注册

- 外部提交的 `replacementRole` **不得** 已被 `TMMRoles.registerRole`。
- 在 `RoleOverrideEngine.rebuild()` 发现某 REPLACE 应生效时：
  - 若 `replacementRole` 尚未注册，调用 `TMMRoles.registerRole(replacementRole)`，但**仅在第一次**。
  - 若之后该 REPLACE 被禁用，不 `remove` 该角色；通过 L1/L2 过滤使其不再出现。
  - 新角色 id 在 `replacementRole.identifier()` 中被 core 校验/改写为 `sourceModId:path`。

### 5.5 MODIFY 属性补丁

采用**补丁层 + 条件回调**为主，不物理替换 `SRERole` 对象。

需要 mixin/桥接的属性点：

| 属性 | 接入点 | 备注 |
|---|---|---|
| 显示名 | `SRERole.getName()` | 优先返回 MODIFY `namePatch` |
| 颜色 | `SRERole.getColor()` | 优先返回 MODIFY `colorPatch` |
| 商店 | `SRERole.getShopEntries()` | 完全替换为 patch 结果 |
| 初始物品 | `SRERole.getDefaultItems()` | 完全替换为 patch 结果 |
| 阵营 flags | 直接 setter / 字段 patch | 如 `setInnocent`、`setCanUseKiller`；在主 tick 应用 |
| 生成参数 | `SRERole.spawnInfo` 字段 patch | 在主 tick 应用 |
| 技能/被动 | 外部 `SkillRegistrar` 在启用时注册 | 禁用时不注销（上游无注销 API），handler 内判断启用状态 |
| 胜利条件 | `BlackoutVictoryChecker` / SRE win hooks | hook 内判断 MODIFY 是否生效 |

所有补丁查询都先检查 `RoleOverrideEngine.isModified(targetId)`，否则透传原方法。

### 5.6 实时刷新路径

```
玩家 ModMenu 改开关
  → ConfigRootScreen.onClose() → ConfigManager.save()
  → ClientLifecycleHandler setOnSaveCallback
  → ConfigUpdatePayload (C2S，仅 OP/服务端)
  → C2SReceiverRegistrar.mergeFromJsonString + save
  → RoleOverrideEngine.rebuild() (server)
  → FullConfigSyncPayload.broadcastToAll()
  → client RoleOverrideEngine.rebuild() (memory only)
  → RoleOverrideRefreshDispatcher.fire()
       - 若 RoleIntroduceScreen 打开：重建 availableRoles + refreshFilter
       - 通知分配池缓存失效（下一局重新构造）
       - 命令补全缓存失效
```

---

## 6. ModMenu 配置页

### 6.1 UI 位置

新增第 6 个 Tab：`ConfigRootScreen.TAB_ROLE_OVERRIDES = 5`。

```java
private static final String[] TAB_LABELS = {
    "任务配置", "小游戏", "全局设置", "投票设置", "环境设置", "角色覆盖"
};
```

实现类：`com.habitrain.core.client.gui.config.RoleOverrideTabScreen`。

### 6.2 展示内容

- **全局总开关**：标题“角色覆盖总开关”，默认启用。
- **冲突横幅**：顶部黄色条，提示“X 组冲突待解决”。
- **条目列表**：按来源 mod 或目标角色分组（实现时可先按来源 mod）。
  - 每行显示：
    - 来源 mod id
    - 目标角色 id
    - 新角色 id（REPLACE）或“调整”（MODIFY）
    - 自定义/默认类型标签
    - 启用/停用 pill
- **详情区**：选中条目时显示完整信息、冲突提示、启用开关。

### 6.3 交互规则

- 全局开关关闭时，列表灰显，所有条目不生效。
- 冲突组内只能启用一条；启用一条时自动关闭同组其他条目。
- 非 OP 客户端只读；footer 提示“仅 OP 可修改服务端配置”。
- 未解决的冲突条目状态显示为 `CONFLICT`。

### 6.4 持久化

在 `config/habitrain_core.json` 中新增 `"roleOverrides"` 段：

```json
{
  "roleOverrides": {
    "globalEnabled": true,
    "entries": {
      "my_dlc$shadow_killer@sre:killer": {
        "enabled": true
      },
      "other_mod$berserk_killer@sre:killer": {
        "enabled": false
      }
    },
    "conflictResolution": {
      "sre:killer": "my_dlc$shadow_killer@sre:killer"
    }
  }
}
```

`entryId` 生成规则：`sourceModId + "$" + localName + "@" + targetRoleId`。

---

## 7. 接入文档（给 DLC 开发者）

### 7.1 交付位置

- 新增：`docs/API参考手册.md` 中“角色替换与修改 API”章节；或单独 `docs/角色替换与修改API.md`。

### 7.2 文档章节

1. 概述与原则
2. REPLACE 最小示例
3. MODIFY 最小示例
4. ID 与命名规范
5. 技能、商店、初始物品、CCA 组件
6. 冲突与禁用机制
7. ModMenu 展示（标签、图标、描述）
8. 限制与注意事项
9. 故障排查

### 7.3 最小示例草案

```java
public class MyDlcMod implements ModInitializer {
    @Override
    public void onInitialize() {
        SRERole shadowKiller = new NormalRole(
            ResourceLocation.fromNamespaceAndPath("my_dlc", "shadow_killer"),
            0x5A3A8A, false, true,
            SRERole.MoodType.FAKE,
            Integer.MAX_VALUE, true
        );

        // 注册技能（自己负责；core 只控制是否启用）
        RoleSkill.register(shadowKiller, RoleSkill.skill(...).build());

        // 提交替换意图
        RoleOverrideApi.registerReplace(ReplaceRoleDefinition.builder()
            .sourceModId("my_dlc")
            .displayName(Component.literal("暗影列车员"))
            .customTypeLabel("完全替换")
            .targetRoleId(ResourceLocation.parse("sre:killer"))
            .replacementRole(shadowKiller)
            .build());
    }
}
```

```java
RoleOverrideApi.registerModify(ModifyRoleDefinition.builder()
    .sourceModId("my_dlc")
    .displayName(Component.literal("杀手·狂暴化"))
    .customTypeLabel("属性调整")
    .targetRoleId(ResourceLocation.parse("sre:killer"))
    .namePatch((original, server) -> Component.literal("狂暴杀手"))
    .shopPatch((original, server) -> MyShops.berzerkShop())
    .flagsPatch((original, server) -> new FlagsPatch().setCanUseKiller(true))
    .skillRegistrar(original -> RoleSkill.register(original, MySkills.berzerkSkill(original)))
    .winConditionHook(ctx -> {
        if (ctx.roleIsModified()) {
            // 自定义胜利判定
        }
        return null;
    })
    .build());
```

---

## 8. Claude Skill

### 8.1 交付位置

`.claude/skills/using-habitrain-role-override/SKILL.md`

### 8.2 触发条件

当用户请求涉及以下主题时激活：
- “替换哈比列车角色”
- “接入角色覆盖 API”
- “修改 habitrain_core 角色”
- “写一个新角色替换 sre:killer / civilian / vigilante”

### 8.3 Skill 内容要点

1. 先判断用户要 REPLACE 还是 MODIFY。
2. 给出标准 builder 模板。
3. 强调：
   - id 必须是 `modId:roleName`；
   - 新角色构造仍遵循 `adding-habitrain-role` skill 的规则；
   - 技能/被动由外部自己注册；core 只控制启用/隐藏；
   - 同一目标不能同时 REPLACE+MODIFY；
   - 冲突需要在 ModMenu 中手动解决；
   - 改完后 `gradlew clean build` 并 jar 复制到 `临时/`。
4. 与 `adding-habitrain-role` skill 的衔接说明。

---

## 9. 错误处理与边界情况

### 9.1 注册阶段

| 情况 | 行为 |
|---|---|
| `sourceModId` 查不到 | 抛 `IllegalArgumentException`，拒绝注册 |
| `targetRoleId` 不存在 | 进入 PENDING_TARGET；SERVER_STARTED 再校验；仍不存在则 INVALID |
| `replacementRole` 已注册 | 拒绝；core 要求自己控制注册 |
| `replacementId` namespace 不匹配 | core 强制改写为 `sourceModId:path` |
| `replacementId` 与已有条目冲突 | 拒绝 |
| MODIFY 目标为基础角色 | 允许，但 UI 显示 ⚠ 提示 |

### 9.2 运行时

| 情况 | 行为 |
|---|---|
| 同目标多条 REPLACE / MODIFY / REPLACE+MODIFY | 全部 CONFLICT，不生效 |
| 全局开关关闭 | effective snapshot 为空；原角色恢复 |
| 新角色本身被 SRE 禁用 | UI 提示“替换已启用，但新角色被 SRE 禁用” |
| 局中改开关 | 不影响已分配玩家；只影响池/介绍书/命令 |
| 输入被替换的原角色 id 到命令 | 解析成功，执行提示“已被 xxx 替换” |
| 介绍书 CURRENT 模式查看被替换角色 | 仍显示当前实际 role；v1 不额外提示 |
| 配置 JSON 损坏 | Gson 容错；缺失字段默认；极端错误回退空 section |

### 9.3 线程与生命周期

- 所有 `RoleOverrideEngine.rebuild()` 调用在主线程。
- 不物理 `remove` `TMMRoles.ROLES` 中的条目。
- REPLACE 启用时注册新角色；禁用时仅通过过滤隐藏。

---

## 10. 实现风险与注意事项

| 风险 | 缓解 |
|---|---|
| mixin 点不稳定 | 所有 mixin 都加 `require = 0` 备选；记录上游版本兼容性。 |
| 上游 `SREDisableManager` 副作用 | 先验证 L1 过滤是否足够；再决定是否注入 L2。 |
| 技能无注销 API | MODIFY 禁用后 handler 内检查 `isModified`；REPLACE 新角色禁用后靠过滤隐藏。 |
| 静态 `TMMRoles.CIVILIAN` 等引用 | 只过滤这些 id 的“选择”，不改变静态字段本身。 |
| 跨 mod 加载顺序 | `RoleOverrideRegistry` 在 `SERVER_STARTED` 冻结并校验，允许目标 mod 后加载。 |
| 客户端/服务端状态不一致 | 通过 `FullConfigSyncPayload` 全量同步；客户端未收到时 UI 只读。 |

---

## 11. 测试清单

| 场景 | 预期 |
|---|---|
| REPLACE 启用 | 分配池不出现原角色；介绍书出现新角色；命令补全不出现原角色 |
| REPLACE 禁用 | 原角色恢复 |
| 两条 REPLACE 同目标 | ModMenu 显示冲突，只能启用一条 |
| MODIFY 启用 | 原角色名字/颜色/商店/初始物品改变 |
| MODIFY 禁用 | 原角色恢复 |
| 全局开关关闭 | 所有覆盖失效 |
| 多人 OP 改配置 | 客户端打开的介绍书立即刷新 |
| 新角色 id 非法 | core 强制改写或拒绝 |
| 已分配玩家 | 局中改开关不转职 |
| 命令输入旧 id | 执行提示“已被 xxx 替换” |

---

## 12. 后续可能扩展（v2+）

- 对替换后的新角色再做 MODIFY（链式叠加）。
- 按地图/游戏模式分别配置覆盖。
- 接入数据统计/热加载。
- 局中实时把玩家转职为 REPLACE 新角色（需额外命令与广播）。

---

## 13. 自审结论

- 无 TBD / TODO 占位。
- 内部一致：API 注册 → 引擎裁决 → 配置同步 → UI 展示 → 上游钩子 形成闭环。
- 范围聚焦：v1 只做 REPLACE/MODIFY + ModMenu 管理 + 实时池/介绍书/命令刷新。
- 已明确不做：局中已分配玩家实时转职、物理删除 `TMMRoles.ROLES`、REPLACE+MODIFY 同目标叠加。
