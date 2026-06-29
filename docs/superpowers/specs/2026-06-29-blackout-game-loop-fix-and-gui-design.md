# Blackout 模式游戏循环修复与角色介绍 GUI 设计

**日期:** 2026-06-29
**状态:** 已批准
**关联项目:** habiTrain API (Core)

---

## 1. 问题描述

### 1.1 游戏开局立即结束

Blackout 模式启动后，SRE 检测到所有玩家均为 `TMMRoles.CIVILIAN`（全员同阵营），触发胜负判定 → `Game Stopped!`。

```log
17:55:58  BlackoutRoleManager: Assigned 2 KILLER / 8 CIVILIAN (10 players, formula n/6 ceil)
17:55:58  Game Stopped!
```

### 1.2 角色介绍 GUI 缺失

Blackout 模式有 3 种阵营角色（平民/杀手/警长），但玩家在游戏中无法查看角色说明。
SRE 自带的角色介绍 GUI（绑定 U 键）只显示 SRE 原版角色，不包含 Blackout 自定义角色。

---

## 2. 根因分析

在 `SREBlackoutGameMode.initializeGame()` 中的执行顺序和角色分配有问题：

```java
executeFunction("harpymodloader:start_game");  // ← 过早启动游戏
BlackoutRoleManager.initRandomAssignment(players);
for (...) game.addRole(player, TMMRoles.CIVILIAN, false);  // ← 全员 CIVILIAN
game.syncRoles();  // ← SRE 检测"全员同阵营"→ 结束
```

两个根本问题：
1. **`start_game` 执行在角色分配之前** — SRE 启动时角色未就位
2. **所有玩家都是 CIVILIAN** — SRE 的 `getAlivePlayerRoleTeamInfo()` 只看到一个阵营

SRE 的胜利判定逻辑：
```
tickServerGameLoop → AlivePlayerRoleTeamInfo
  → innocent = 全部, killer = 0, all_neturals = 0
  → hasInnocentAndVigilante = true, hasNeuturals = false
  → 只有一个阵营存活 → 游戏结束
```

---

## 3. 设计方案

### 3.1 核心修复：自定义中立角色 + 调整初始化顺序

**方案选择：** 不使用 `AllowGameEnd` 拦截（仅修改胜负结果，不阻止结束），不使用 Mixin（额外维护成本）。而是**在开局时让 SRE 看到多个阵营**，从源头避免触发胜负判定。

**思路：** 为 Blackout 模式的坏人阵营创建一个**自定义 SRE 中立角色**，注册到 SRE 角色表并分配。

#### 3.1.1 创建自定义角色

利用 `NormalRole` + `TMMRoles.registerRole()` 注册一个专属角色：

```java
private static final NormalRole BLACKOUT_BAD_ROLE = createBadRole();

private static NormalRole createBadRole() {
    NormalRole role = new NormalRole(
        ResourceLocation.fromNamespaceAndPath("habitrain", "blackout_bad"),
        0xAA0000,                    // 角色颜色（暗红）
        false,                       // isInnocent → 不在平民阵营
        false,                       // canUseKiller → 无 SRE 杀手能力
        SRERole.MoodType.NEUTRAL,
        100,                         // maxSprintTime
        false                        // canSeeTime
    );
    role.setNeutrals(true);          // 标记为中立阵营
    role.setCanPickUpRevolver(false); // 不能捡 SRE 左轮
    role.setCanUseInstinct(false);    // 不能使用本能
    role.setCanAutoAddMoney(false);   // 不自动获得金钱
    role.setCanHavePassiveIncome(false);
    TMMRoles.registerRole(role);      // 注册到 SRE 角色注册表
    return role;
}
```

#### 3.1.2 调整 initializeGame 顺序

```java
@Override
public void initializeGame(ServerLevel world, SREGameWorldComponent game,
                           List<ServerPlayer> players) {
    Harpymodloader.refreshRoles();
    game.clearRoleMap();
    addPlayersToTeam(world.getServer().createCommandSourceStack(), players, "harpymodloader_game");

    // 【先】分配 Blackout 阵营（记录到 BlackoutRoleManager）
    BlackoutRoleManager.initRandomAssignment(players);

    // 【再】分配 SRE 角色（两个不同阵营 → SRE 不会判定游戏结束）
    for (ServerPlayer player : players) {
        boolean isBad = BlackoutRoleManager.getFaction(player.getUUID()) == Faction.BAD;
        game.addRole(player, isBad ? BLACKOUT_BAD_ROLE : TMMRoles.CIVILIAN, false);
    }
    game.syncRoles();

    // 【最后】启动 SRE 游戏（此时角色已就位）
    executeFunction(world.getServer().createCommandSourceStack(), "harpymodloader:start_game");
}
```

#### 3.1.3 SRE 阵营判定变化

| 项目 | 修复前 | 修复后 |
|------|--------|--------|
| 平民 SRE 角色 | `TMMRoles.CIVILIAN` | `TMMRoles.CIVILIAN` |
| 杀手 SRE 角色 | `TMMRoles.CIVILIAN` | 自定义中立角色 `BLACKOUT_BAD_ROLE` |
| SRE 看到的阵营 | `innocent=10`（1 个阵营） | `innocent=8, neutrals=2`（2 个阵营） |
| 触发"全员同阵营" | ✅ 触发 → 结束 | ❌ 不触发 → 继续 |

#### 3.1.4 为什么不使用 LOOSE_END

最初方案是使用 SRE 内置的 `TMMRoles.LOOSE_END`。但：
- `LOOSE_END` 是 SRE 谋杀模式中的"自由人"角色，有独立胜利条件
- 可能触发 SRE 的 loose end 专属胜负判定 (`isLooseEndMode`)
- 角色显示名称为"Loose End"，和 Blackout 的"坏人"概念不符

使用 `NormalRole` 自定义角色可以完全控制角色属性，标识符为 `habitrain:blackout_bad`，和 SRE 角色池完全隔离。

### 3.2 角色介绍 GUI

**方案选择：** 不修改 SRE 的 `RoleIntroduceScreen`（enum 无法扩展、维护性差），而是**新建** `BlackoutRoleIntroduceScreen`。

#### 3.2.1 GUI 布局

```
┌────────────────────────────────────────────────────┐
│  ⚡ 停电模式角色介绍                                 │
│                                                    │
│  ┌─────────────┐  ┌──────────────────────────────┐ │
│  │  👤 平民     │  │  杀手                         │ │
│  │              │  │  ────────                     │ │
│  │  🔪 杀手    │  │  阵营：坏人                    │ │
│  │              │  │  目标：消灭所有好人             │ │
│  │  ⭐ 警长     │  │  能力：可使用 TACZ 武器击杀平民 │ │
│  │              │  │                              │ │
│  │              │  │  隐藏在人群中的杀手。在停电     │ │
│  │              │  │  期间可以行动，但注意不要暴露   │ │
│  └─────────────┘  └──────────────────────────────┘ │
│                                                    │
│  按下 [U] 键打开/关闭                               │
└────────────────────────────────────────────────────┘
```

#### 3.2.2 角色内容

| 角色 | 阵营 | 图标 | 目标 | 能力 |
|------|------|------|------|------|
| **平民** | 👥 好人 | 👤 | 存活到最后，在停电中生存 | 无特殊能力 |
| **杀手** | ♠ 坏人 | 🔪 | 消灭所有好人 | 可在商店购买 TACZ 沙漠之鹰 |
| **警长** | ⭐ 好人 | ⭐ | 找出并消灭杀手 | 通过投票选出，可购买 TACZ 武器 |

#### 3.2.3 技术实现

**新文件：** `src/main/java/com/habitrain/core/client/gui/BlackoutRoleIntroduceScreen.java`

- 继承 `net.minecraft.client.gui.screens.Screen`
- 使用原版 `DrawContext` API 渲染
- 左侧角色列表：3 张可点击卡片
- 右侧详情面板：展示选中角色的详细信息
- 支持滚动条（预留未来角色扩展）
- 纯客户端、无网络请求

**修改文件：** `BlackoutKeyHandler.java`

- 注册新按键映射 `key.habitrain.blackout.role_intro`，默认绑定 U 键
- 点击时判断当前是否在 Blackout 模式中
- 是 → 打开 `BlackoutRoleIntroduceScreen`
- 否 → 忽略（不干扰 SRE 原版 U 键功能）

**不变的部分：**
- SRE 的 `RoleIntroduceScreen` 不受影响
- SRE 的 U 键绑定不受影响
- 非 Blackout 模式下 U 键行为不变

#### 3.2.4 与 SRE 原版 GUI 对比

| 项目 | SRE RoleIntroduceScreen | 我们的 |
|------|------------------------|--------|
| 代码位置 | SRE 模组 jar 内 | 我们的源码内 |
| 模式选择 | 谋杀/修机/其他 | 仅停电模式 |
| 角色源 | SRE 角色注册表 | Blackout 的 3 种角色 |
| 商品信息 | 展示商店物品 | 无 |
| 修饰符/自定义角色 | 支持 | 无 |
| 搜索框 | 有 | 无 |
| SRE 更新影响 | — | 完全不受影响 |

---

## 4. 改动范围

### 4.1 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `SREBlackoutGameMode.java` | 修改 | 创建自定义角色 + 调整初始化顺序 |
| `BlackoutRoleIntroduceScreen.java` | 新建 | 角色介绍 GUI |
| `BlackoutKeyHandler.java` | 修改 | 添加 U 键绑定，打开新 GUI |
| `HabiTrainCoreClient.java` | 无需修改 | BlackoutKeyHandler 已注册 |

### 4.2 数据流

```
游戏启动:
  BlackoutMode.onStart()
    → GameUtils.startGame(sreMode)          // 启动 SRE 游戏
    → SREBlackoutGameMode.initializeGame()
      → clearRoleMap()                      // 清空旧角色
      → initRandomAssignment(players)       // BlackoutRoleManager 分配阵营
      → addRole(CIVILIAN / BLACKOUT_BAD)    // SRE 分配两个阵营
      → syncRoles()                         // SRE 看到多个阵营 → 继续
      → executeFunction("start_game")       // 正式启动（角色已就位）

游戏运行:
  BlackoutMode.onTick()
    → BlackoutTimerSystem.tickSecond()
    → BlackoutVotingEngine.tickVoting()
    → checkVictory()                        // Blackout 自己的胜负判定
    → (SRE 的 tickServerGameLoop 运行，但看到多阵营 → 不干涉)

玩家按 U:
  → BlackoutKeyHandler 监听
  → 当前是 Blackout 模式？→ 打开 BlackoutRoleIntroduceScreen
  → 不是？→ 忽略（SRE 原版 U 键处理正常运行）
```

---

## 5. 不变的部分

- SRE 黑场机制（停电/恢复供电）不变
- `BlackoutTimerSystem` 不变
- `BlackoutVotingEngine` 不变
- `TACZWeaponBridge` 不变
- `BlackoutRoleManager` 不变
- SRE 地图重置/房间传送/商店等机制不变
- SRE 原版 U 键角色介绍 GUI 不变

---

## 6. 边界情况

| 场景 | 预期行为 |
|------|----------|
| 单人游戏 | 角色分配正常 (`ceil(1/6) = 1` 杀手) |
| 10 人游戏 | 2 杀手 + 8 平民 |
| 24 人游戏 | 4 杀手 + 20 平民 |
| 非 Blackout 模式按 U | 不影响 SRE 原版 GUI |
| Blackout 模式中死亡后按 U | GUI 仍可打开 |
| GUI 打开时游戏退出 | Screen 随 World 正常关闭 |
| 自定义角色注册冲突 | 使用 `habitrain:blackout_bad` 命名空间，和 SRE 不冲突 |
