# 停电模式 (Blackout Mode) — 设计规格书

> **日期**: 2026-06-28
> **项目**: 哈比列车核心 (HabiTrain Core) + 哈比列车更多修改 (HabiTrain More Tasks)
> **状态**: 设计已批准

---

## 1. 概述

基于现有的 GameMode API 框架，在 `habitrain_core` 中新增"停电模式" (`habitrains:blackout`)，同时在 `habitrain_more_tasks` 中提供对应的任务内容。该模式以 SRE 小游戏为基础，引入倒计时、停电机制、投票选举警长、TACZ 枪械桥接等新玩法。

### 设计原则

- **职责分离** — API 层（core）负责框架、计时、投票逻辑、网络同步；内容层（more_tasks）负责具体任务定义与客户端按键
- **仅作用于此模式** — 所有改动隔离在 blackout 模式内，不影响其他游戏模式
- **可扩展预留** — 阵营/职业系统预留接口，后续可加新角色而无需修改 core 层核心代码
- **复用现有系统** — 停电效果调用 SRE 自带的停电 API，商店复用 SRE 背包商店页面

---

## 2. 模式基本信息

| 属性 | 值 |
|------|-----|
| 模式 ID | `habitrains:blackout` |
| 显示名称 | 停电模式 |
| 启动命令 | `/habi_api blackout` (OP 权限) |
| 依赖 | `habitrain_core` → `starrailexpress` (SRE 前置) |
| | `habitrain_core` → `tacz-refabricated` (TACZ 前置) |

---

## 3. 核心游戏规则

### 3.1 时间系统

| 参数 | 值 |
|------|-----|
| 总对局时间 | 300 秒（5 分钟） |
| 开局停电倒计时 | 120 秒（2 分钟） |
| 停电持续时长 | 7 秒 |
| 停电倒计时重置值（停电恢复后） | 120 秒 |
| 全服预警通知 | 剩余 60 秒时触发 |

- 总时间对所有玩家可见（屏幕顶部 HUD）
- 停电倒计时对所有玩家可见
- 停电倒计时归零 → 调用 SRE 自带的停电效果 API
- 停电效果结束后，停电倒计时重置为 120 秒

### 3.2 停电触发规则

| 触发方式 | 效果 | 由谁触发 |
|----------|------|----------|
| 倒计时自然归零 | 停电 7 秒 | 自动 |
| 破坏线路任务完成 | 立即停电 7 秒 | 坏人 |
| 维修线路任务完成 | 停电倒计时推迟 +15 秒 | 好人 |

### 3.3 胜利条件

| 阵营 | 胜利条件 |
|------|----------|
| 好人阵营 | 总时间减到 0 秒 或 解决所有杀手 |
| 坏人阵营 | 所有好人被淘汰 或 5 分钟倒计时结束 |

### 3.4 时间修改规则

| 任务 | 效果 | 阵营 |
|------|------|------|
| 添加煤炭 | 总时间 -30 秒 | 好人 |
| 维修线路 | 停电倒计时 +15 秒 | 好人 |
| 破坏线路 | 立即触发停电 7 秒 | 坏人 |
| 熔炉爆炸 | 总时间 +15 秒 + 点燃附近 TNT | 坏人 |

---

## 4. 代码拆分方案

### 4.1 `habitrain_core`（API 层）— 框架与状态管理

```
game/blackout/
├── BlackoutMode.java               GameMode 接口实现
├── BlackoutTimerSystem.java        总时间 + 停电倒计时管理
├── BlackoutVotingEngine.java       投票选举逻辑（服务端）
├── BlackoutRoleManager.java        阵营/职业注册与管理（可扩展）
├── BlackoutTaskInterceptor.java    按阵营过滤任务分配
└── TACZWeaponBridge.java           TACZ 桥接 + 命中拦截 + 商店集成

network/
├── BlackoutTimerPayload.java       时间同步 S→C（全服可见）
├── BlackoutVotePayload.java        投票数据 C↔S
└── BlackoutStatusPayload.java      停电/状态变更同步 S→C

client/gui/
├── VoteScreen.java                 投票选举 GUI
├── BlackoutHudOverlay.java         顶部 HUD 覆盖层（时间/倒计时渲染）
└── BlackoutKeyHandler.java         投票快捷键（P）注册
```

### 4.2 `habitrain_more_tasks`（内容层）— 具体任务实现

```
game/blackout/
├── AddCoalTask.java          好人: 添加煤炭（采集煤矿 → 总时间-30s）
├── RepairWiringTask.java     好人: 维修线路（右键线路 → 停电倒计时+15s）
├── SabotageWiringTask.java   坏人: 破坏线路（破坏电线 → 立即停电7s）
└── FurnaceExplosionTask.java 坏人: 熔炉爆炸（右键熔炉 → 总时间+15s + 点燃TNT）
```

---

## 5. 模块详细设计

### 5.1 BlackoutMode

```java
// GameMode 接口实现
public class BlackoutMode implements GameMode {
    getId()             → "habitrains:blackout"
    getDisplayName()    → "停电模式"
    getTaskCategories() → BLACKOUT_GOOD, BLACKOUT_BAD

    isActive(level)     → 检测当前 SRE 子游戏是否为停电模式
}
```

**生命周期行为：**

| 钩子 | 行为 |
|------|------|
| `onPreStart(level)` | 初始化 timerSystem、roleManager；重置所有状态 |
| `onStart(level)` | 设置总时间 300s + 停电倒计时 120s；开始主计时器 |
| `onTick(level)` | 每秒更新双计时器 → 检查停电触发 → 检查投票窗口 → 检查胜利条件 |
| — | 剩余 60s 时全服通知「还剩1分钟！」 |
| `onTaskComplete(player, task)` | 根据任务类型调用 timerSystem 的 reduceTime / addTime / delayBlackout / triggerBlackout |
| `onPlayerJoin(player)` | 同步当前时间/状态给新玩家 |
| `onPlayerLeave(player)` | 如在游戏中且人数不足时处理 |
| `onEnd(level, result)` | 清理计时器、停电状态、发送结算 |
| `onCleanup(level)` | 恢复默认 SRE 设置 |

### 5.2 BlackoutTimerSystem

```java
public class BlackoutTimerSystem {
    private int totalTimeRemaining;       // 秒
    private int blackoutCountdown;        // 秒
    private boolean blackoutActive;       // 是否正在停电
    private int blackoutElapsed;          // 停电已持续 tick 数

    // 公开方法（供任务层调用）
    static void reduceTime(int seconds);           // 好人任务: 减时间
    static void addTime(int seconds);              // 坏人任务: 加时间
    static void delayBlackout(int seconds);        // 好人: 推迟停电
    static void triggerBlackout(int seconds);      // 坏人: 立即停电
    static void tick();                            // 每秒更新

    // 事件回调
    static boolean isTimeUp();                     // 总时间 ≤ 0
    static boolean isBlackoutTriggered();          // 停电倒计时 ≤ 0
}
```

**tick 逻辑（伪代码）：**
```
每 tick (20次/秒，每20tick执行一次实际更新):
  if blackoutActive:
    blackoutElapsed++
    if blackoutElapsed >= 7秒:
      调用 SRE 恢复供电 API
      blackoutActive = false
      blackoutCountdown = 120
      blackoutElapsed = 0

  if !blackoutActive:
    totalTimeRemaining--
    if totalTimeRemaining ≤ 60s: 标记需要发送预警
    blackoutCountdown--

    if blackoutCountdown ≤ 0:
      调用 SRE 停电 API
      blackoutActive = true

  广播 BlackoutTimerPayload 给所有玩家
```

### 5.3 阵营/职业管理器（BlackoutRoleManager）

```java
public enum Faction {
    GOOD,      // 好人阵营
    BAD        // 坏人阵营
}

public enum RoleType {
    CIVILIAN,  // 平民（初始默认）
    KILLER,    // 杀手
    SHERIFF,   // 警长（投票选出）
    // 预留: FUTURE_ROLE_1, FUTURE_ROLE_2
}

public class BlackoutRoleManager {
    void assignRole(UUID playerId, RoleType role, Faction faction);
    Faction getFaction(UUID playerId);
    RoleType getRole(UUID playerId);
    void setSheriff(UUID playerId);
    UUID getSheriff();
    boolean isSheriff(UUID playerId);
    boolean canBeSheriff(UUID playerId);          // 非杀手可当选
    int getRemainingGood();                       // 剩余好人数
    int getRemainingBad();                        // 剩余坏人数
}
```

- **警长可属于任意阵营**（当前初始实现中只有好人能当选，架构预留）
- `filterAvailableTasks()` 根据 `getFaction()` 返回：
  - 好人 → 只有 `BLACKOUT_GOOD` 分类的任务
  - 坏人 → 只有 `BLACKOUT_BAD` 分类的任务

### 5.4 投票系统（BlackoutVotingEngine）

```
服务端流程:
1. 开局 60 秒后
2. broadcast("§e【投票】现在可以投出你觉得的「警长」！按 [P] 键打开投票界面")
3. 设置 voteWindowOpen = true, windowDuration = 30s
4. 玩家按 P → VoteScreen 打开 → 选择其他玩家 → BlackoutVotePayload(C→S)
5. 30 秒截止:
   ├─ 有人获得最高票 → 当选警长，全服通知
   │    → roleManager.setSheriff(winnerUUID)
   └─ 无人投票/平票 → 系统从非杀手玩家中随机选
        → roleManager.setSheriff(pickedUUID)

警长当选后:
  → 全服广播 "§e【投票】[玩家名] 当选为警长！"
  → 警长获得访问 SRE 背包商店的权限（购买沙漠之鹰）
```

**VoteScreen GUI 设计：**

```
按 P → 打开 VoteScreen（覆盖在游戏画面上方）

┌──────────────────────────────────────────┐
│      ═══ 投票选举警长 ═══  剩余24秒      │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │  ● Player1    票数: 2              │ │
│  │  ○ Player2    票数: 0              │ │
│  │  ○ Player3    票数: 1              │ │
│  │  ○ Player4    票数: 0              │ │
│  └─────────────────────────────────────┘ │
│                                          │
│           [ 确认投票 ]                    │
└──────────────────────────────────────────┘
```

**特殊规则：**
- 仅存活玩家可投票和被投票
- 投票窗口期内未投票的玩家视为弃权
- 投票窗口结束后不能再次打开

### 5.5 TACZ 枪械桥接（TACZWeaponBridge）

```java
public class TACZWeaponBridge {
    // 静态初始化: 注册枪械, 挂钩命中事件
    static void register();

    // 将沙漠之鹰上架到 SRE 背包商店
    static void addDesertEagleToShop();

    // 给玩家沙漠之鹰（警长每局限购一次）
    static void giveDesertEagle(ServerPlayer player);

    // 给玩家子弹
    static void giveAmmo(ServerPlayer player, int count);

    // 拦截 TACZ 子弹击中事件 → 触发 SRE 游戏死亡
    // 在 TACZ 的 EntityHitEvent / ProjectileHitEvent 中调用
    static void onBulletHitEntity(ServerPlayer shooter, LivingEntity target);
}
```

**TACZ 命中 → SRE 死亡流程：**

```
TACZ 子弹击中玩家
  → onBulletHitEntity(shooter, target)
  → 检测: 当前世界是否在 blackout 模式中?
  → 检测: shooter 是否为警长?
  → 检测: target 是否为存活玩家?
  → 调用 SRE 的 role/kill 逻辑（淘汰目标）
  → BlackoutTimerSystem.checkWinCondition()
     ├─ 好人数为 0 → 杀手胜
     └─ 杀手数为 0 → 好人胜
```

**注意：** 此死亡是 SRE 游戏内的死亡（角色淘汰），不是 Minecraft 玩家死亡。目标被淘汰后仍以旁观者身份留在游戏中，不影响 `onPlayerLeave` 等事件。

**商店购买信息：**

| 商品 | 价格 | 限购 |
|------|------|------|
| TACZ 沙漠之鹰 | 50 币 | 每局 1 次 |
| 沙漠之鹰子弹（1发） | 50 币 | 不限 |

商店复用 SRE 自带的背包商店页面，不单独实现 GUI。仅警长角色可访问该商店。

---

## 6. 网络同步

### BlackoutTimerPayload（S→C，周期性广播）

```java
public class BlackoutTimerPayload {
    int totalTimeRemaining;      // 剩余总时间（秒，全服可见）
    int blackoutCountdown;       // 下次停电倒计时（秒，全服可见）
    boolean blackoutActive;      // 当前是否在停电中
}
```

### BlackoutVotePayload（C↔S）

```java
// C→S: 玩家投票
public class BlackoutVotePayload {
    UUID targetUUID;             // 投票给谁
}

// S→C: 投票结果同步
public class BlackoutVoteResultPayload {
    UUID sheriffUUID;            // 当选警长
    int voteCount;               // 获得票数
}
```

### BlackoutStatusPayload（S→C，事件广播）

```java
public class BlackoutStatusPayload {
    StatusType type;              // BLACKOUT_START | BLACKOUT_END | VOTE_OPEN | VOTE_RESULT | TIME_WARNING
    String data;                  // 按类型不同
}
```

---

## 7. TaskCategory 注册

```java
// 在 BlackoutMode 构造时注册
public static final TaskCategory BLACKOUT_GOOD =
    new TaskCategory("habitrain:blackout_good", "好人任务", "habitrains:blackout");
public static final TaskCategory BLACKOUT_BAD =
    new TaskCategory("habitrain:blackout_bad", "坏人任务", "habitrains:blackout");
```

- `filterAvailableTasks()` 根据玩家阵营返回对应分类的任务列表
- 后续扩展新角色时：只需添加新 `TaskCategory` 并在 `filterAvailableTasks()` 中添加映射

---

## 8. 命令注册

在 `HabiTrainCore.java` 的 `registerCommands()` 中追加：

```
/habi_api blackout         启动停电模式（当前世界）
/habi_api stop             停止当前停电模式
/habi_api list             列出所有可用的 GameMode
```

```java
dispatcher.register(Commands.literal("habi_api")
    .requires(source -> source.hasPermission(2))
    .then(Commands.literal("blackout")
        .executes(ctx -> {
            // 启动停电模式
            ServerLevel level = ctx.getSource().getLevel();
            GameModeRegistry.start("habitrains:blackout", level);
            ctx.getSource().sendSuccess(
                Component.literal("§a停电模式已启动！"), true);
            return 1;
        })
    )
    .then(Commands.literal("stop")
        .executes(ctx -> {
            // 停止当前模式
            GameModeRegistry.stopActive(ctx.getSource().getLevel());
            ctx.getSource().sendSuccess(
                Component.literal("§c当前游戏模式已停止"), true);
            return 1;
        })
    )
    .then(Commands.literal("list")
        .executes(ctx -> {
            // 列出所有已注册模式
            String modes = GameModeRegistry.getAll().stream()
                .map(GameMode::getId)
                .collect(Collectors.joining(", "));
            ctx.getSource().sendSuccess(
                Component.literal("§e已注册模式: " + modes), true);
            return 1;
        })
    )
);
```

---

## 9. HUD 显示与交互

### 顶部 HUD（BlackoutHudOverlay）

```
┌─────────────────────────────────────────────────────┐
│  ⚡ 停电模式   剩余: 03:28   ⚡ 下次停电: 01:15     │
│  ████████████████░░░░░░░░░░░░░░░░░░░░░░              │
│  ████████████████░░░░░░░░░░░░░░░░░░░░░░              │
│  (实心 = 已过时间 / 空心 = 剩余 / 红色 = 停电段)     │
└─────────────────────────────────────────────────────┘
```

- 时间进度条显示在屏幕顶部
- 停电倒计时用红色标记在进度条上
- 停电中 → 进度条闪烁红色 + 屏幕边缘红色效果

### 快捷键

| 按键 | 功能 | 条件 |
|------|------|------|
| P | 打开投票选举 GUI | 投票窗口开放期间 |
| B | 打开背包商店（警长） | 已成为警长 |

---

## 10. 可扩展性设计

### 添加新角色

1. 在 `BlackoutRoleManager.RoleType` 中添加新枚举值
2. 在 more-tasks 中实现该角色的任务（使用 `BLACKOUT_GOOD` 或 `BLACKOUT_BAD`）
3. 在 `BlackoutMode.filterAvailableTasks()` 中添加该角色对应的 `TaskCategory` 映射
4. **无需修改** `BlackoutTimerSystem`、`BlackoutVotingEngine`、`TACZWeaponBridge` 等框架代码

### 添加新任务

1. 在 more-tasks 的 `game/blackout/` 包中创建新类
2. 使用 `TaskRegistry.register()` 注册任务，指定 `BLACKOUT_GOOD` 或 `BLACKOUT_BAD`
3. 在 `onComplete()` 中调用 `BlackoutTimerSystem` 的公开静态方法
4. **无需修改** core 层代码

### 添加新模式

1. 在 core 的 `game/` 下创建新包（如 `game/arena/`）
2. 实现 `GameMode` 接口
3. 注册新 `TaskCategory`
4. 在 `onInitialize()` 中注册到 `GameModeRegistry`
5. **无需修改** `BlackoutMode` 或现有模式的代码

---

## 11. 实施顺序

| # | 阶段 | 内容 | 模块 |
|---|------|------|------|
| 1 | TimerSystem | `BlackoutTimerSystem` 双计时器实现 | core |
| 2 | RoleManager | `BlackoutRoleManager` 阵营/职业管理 | core |
| 3 | BlackoutMode | `BlackoutMode` GameMode 实现 + HUD | core |
| 4 | 命令 | `/habi_api` 命令注册 | core |
| 5 | VoteEngine | `BlackoutVotingEngine` 投票逻辑 | core |
| 6 | VoteScreen | `VoteScreen` GUI + `BlackoutKeyHandler` | core |
| 7 | 网络包 | `BlackoutTimerPayload`、`BlackoutVotePayload`、`BlackoutStatusPayload` | core |
| 8 | TACZBridge | `TACZWeaponBridge` 桥接 + 商店集成 + 命中拦截 | core |
| 9 | 好人任务 | `AddCoalTask` + `RepairWiringTask` | more_tasks |
| 10 | 坏人任务 | `SabotageWiringTask` + `FurnaceExplosionTask` | more_tasks |
| 11 | 集成构建 | core + more_tasks 联调，构建验证 | all |

---

## 12. 不包含的范围

- ❌ 不修改现有 SRE 谋杀/修机模式的任何逻辑
- ❌ 不修改现有任务系统的注册/调度机制
- ❌ 不修改 `habitrain_core` 中其他 GameMode 的代码
- ❌ 不实现通用事件总线（推迟到后续版本）
- ❌ 不修改 SRE 原版商店页面的 UI 布局
- ❌ 不修改 TACZ 模组的任何代码（仅作为前置调用）
