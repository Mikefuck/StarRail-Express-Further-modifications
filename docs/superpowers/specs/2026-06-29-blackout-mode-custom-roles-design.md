# 停电模式 V2 — 独立阵营系统与消息清理

> **日期**: 2026-06-29
> **项目**: 哈比列车核心 (HabiTrain Core) + 哈比列车更多修改 (HabiTrain More Tasks)
> **状态**: 设计已批准
> **前置**: [停电模式设计规格书](../specs/2026-06-28-blackout-mode-design.md)

---

## 1. 概述

基于已有的停电模式 (`habitrains:blackout`)，进行两项改进：

1. **消息清理** — 移除带 ⚡/⚠ 图标的系统消息，去掉启动冗余提示
2. **独立阵营系统** — 不再依赖 SRE 内置职业系统，改为在 SRE 中注册一个自定义游戏模式，由 `BlackoutRoleManager` 独立分配好人/坏人阵营

### 设计原则

- **职责分离** — companion mod 负责向 SRE 注册新游戏模式；core mod 负责阵营管理、计时、投票
- **最小侵入** — 不修改 SRE 源码，仅通过 `SREGameModes.registerGameMode()` 注册新模式
- **向后兼容** — 不影响 SRE 谋杀/修机等其他模式的运行
- **可独立测试** — 阵营分配逻辑与 SRE 解耦，可单独验证

---

## 2. 消息清理

### 2.1 修改内容

`BlackoutMode.java` 中以下消息修改：

| 位置 | 原内容 | 新内容 |
|------|--------|--------|
| `onStart()` L94 | `§e⚡ 停电模式已启动！总时间: 5:00` | **删除** |
| `onStart()` L95 | `§7停电倒计时: 2:00 — 做好任务来应对停电！` | **删除** |
| `onPlayerJoin()` L206 | `§e⚡ 当前游戏: 停电模式 剩余: ...` | `§e当前游戏: 停电模式 剩余: ...` |
| `triggerSREBlackout()` L257 | `§c⚡ 停电了！黑暗笼罩一切...` | `§c停电了！黑暗笼罩一切...` |
| `endSREBlackout()` L266 | `§a⚡ 供电已恢复` | `§a供电已恢复` |
| `sendTimeWarning()` L270 | `§e⚠ 仅剩 1 分钟！` | `§e仅剩 1 分钟！` |

### 2.2 设计说明

- 启动消息 (`onStart()`) 完全删除，不在服务器启动或模式初始化时广播冗余提示
- 玩家加入时仍然显示当前模式信息，但去掉 ⚡ 图标使文本更干净
- 停电/恢复/预警消息保留但去掉图标，保持通信功能的同时提升视觉体验

---

## 3. 独立阵营系统

### 3.1 架构概览

```
  companion mod (habitrain_more_tasks)
  ┌────────────────────────────────────┐
  │ HabiTrainMoreTasks.onInitialize()  │
  │ └─ SREGameModes.registerGameMode() │
  │      └─ SREBlackoutGameMode        │
  │           └─ initializeGame()       │
  │                → 所有人 = CIVILIAN  │
  └──────────┬─────────────────────────┘
             │
             ▼
  api mod (habitrain_core)
  ┌────────────────────────────────────┐
  │ BlackoutMode.onStart()             │
  │ └─ startGame(SREBlackoutGameMode)  │
  │                                    │
  │ BlackoutRoleManager                │
  │ └─ initRandomAssignment()          │
  │      → GOOD / BAD 独立分配         │
  └────────────────────────────────────┘
```

### 3.2 SREBlackoutGameMode（companion mod 新建）

位于 `com.habitrain.moretasks.game.blackout.sre.SREBlackoutGameMode`

#### 类定义

```java
public class SREBlackoutGameMode extends SREMurderGameMode {
    public SREBlackoutGameMode() {
        super(ResourceLocation.fromNamespaceAndPath("sre", "blackout"), 10, 1);
        // minimum player = 1, 方便单人测试
    }

    @Override
    public void initializeGame(ServerLevel world, SREGameWorldComponent game,
                               List<ServerPlayer> players) {
        game.clearRoleMap();
        // 所有玩家分配为 CIVILIAN, 不赋予任何特殊能力
        for (ServerPlayer player : players) {
            game.addRole(player, TMMRoles.CIVILIAN, false);
        }
        game.syncRoles();
    }

    @Override
    public boolean shouldRecordPlayerStats() {
        return false;  // 由 BlackoutMode 自己判定胜负
    }

    @Override
    public boolean hasSafeTime() {
        return false;  // 由 BlackoutTimerSystem 控制时间
    }

    @Override
    public boolean requiresAssignedRole() {
        return false;  // 所有参与玩家都是 CIVILIAN
    }
}
```

#### 注册位置

在 `HabiTrainMoreTasks.onInitialize()` 末尾添加：

```java
// 注册停电模式专用的 SRE GameMode
SREGameModes.registerGameMode(new SREBlackoutGameMode());
```

### 3.3 BlackoutMode 调整（core mod）

#### `onStart()` 变更

```java
// 旧: 启动 SRE 谋杀模式再同步角色
GameUtils.startGame(level, SREGameModes.MURDER, ...);
// → syncRolesFromSRE() ...

// 新: 启动 SRE 停电模式（所有人 = CIVILIAN）
ResourceLocation id = ResourceLocation.fromNamespaceAndPath("sre", "blackout");
GameMode sreMode = SREGameModes.GAME_MODES.get(id);
GameUtils.startGame(level, sreMode, 300);
```

但这里有一个关键问题：`GameUtils.startGame()` 需要 `GameMode` 对象，而 companion mod 注册的 `SREBlackoutGameMode` 在初始化时可能还没有加载。

**解决方案**：core mod 通过 `SREGameModes.GAME_MODES` map 按 ID 查找：

```java
ResourceLocation blackoutModeId = ResourceLocation.fromNamespaceAndPath("sre", "blackout");
GameMode sreMode = SREGameModes.GAME_MODES.get(blackoutModeId);
if (sreMode == null) {
    LOGGER.error("SREBlackoutGameMode not found! Is habitrain_more_tasks loaded?");
    return;
}
GameUtils.startGame(level, sreMode, 300);
```

#### 移除 `syncRolesFromSRE()`

删除整个 `syncRolesFromSRE()` 方法及其所有调用。角色分配不再依赖 SRE。

#### `onStart()` 简化后流程

```
onStart(level):
  1. TACZWeaponBridge.register()
  2. 查找并启动 SREBlackoutGameMode
  3. (不再广播启动消息)
```

#### `sreGameRunning` 检测逻辑调整

SRE 游戏启动后（`onTick` 中检测到 `sreGame.isRunning()`），调用 `BlackoutRoleManager.initRandomAssignment()` 分配阵营：

```java
// 在 sreGameRunning = true 的瞬间:
BlackoutRoleManager.initRandomAssignment(level.getServer().getPlayerList().getPlayers(), 0.25f);
// 25% 坏人, 75% 好人
```

### 3.4 BlackoutRoleManager 扩展（core mod）

添加阵营独立分配方法：

```java
/**
 * 独立分配阵营 — 不再依赖 SRE 角色同步。
 * @param players  所有参与玩家列表
 * @param badRatio 坏人比例 (0.0 ~ 1.0)
 */
public static void initRandomAssignment(List<ServerPlayer> players, float badRatio) {
    clear();
    List<ServerPlayer> shuffled = new ArrayList<>(players);
    Collections.shuffle(shuffled);

    int badCount = Math.max(1, (int)(shuffled.size() * badRatio));
    // 至少 1 个坏人，至少 1 个好人的保证在调用方处理

    for (int i = 0; i < shuffled.size(); i++) {
        UUID id = shuffled.get(i).getUUID();
        if (i < badCount) {
            assignRole(id, RoleType.KILLER, Faction.BAD);
        } else {
            assignRole(id, RoleType.CIVILIAN, Faction.GOOD);
        }
    }
    LOGGER.info("BlackoutRoleManager: Assigned {} BAD / {} GOOD",
            badCount, shuffled.size() - badCount);
}
```

### 3.5 依赖关系

| 新增依赖 | 方向 | 说明 |
|----------|------|------|
| `habitrain_core` → `habitrain_more_tasks` | 运行时 | BlackoutMode 需要 companion mod 注册的 SRE GameMode |
| `habitrain_more_tasks` → `SRE` | 编译 | 注册 GameMode 需要 `SREGameModes` 和 `GameMode` 类 |
| `habitrain_more_tasks` → `habitrain_core` | 无 | companion mod 不需要感知 core mod |

---

## 4. 文件清单

### 新建文件

| 文件 | 所在模组 |
|------|----------|
| `src/main/java/com/habitrain/moretasks/game/blackout/sre/SREBlackoutGameMode.java` | companion |

### 修改文件

| 文件 | 改动 | 所在模组 |
|------|------|----------|
| `src/main/java/com/habitrain/moretasks/HabiTrainMoreTasks.java` | 注册 SREBlackoutGameMode | companion |
| `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java` | 消息清理 + 启动新 GameMode + 移除 syncRolesFromSRE | core |
| `src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java` | 添加 initRandomAssignment() | core |

---

## 5. 不包含的范围

- ❌ 不修改 SRE 源码
- ❌ 不修改 SRE 谋杀/修机模式的任何逻辑
- ❌ 不修改现有任务系统
- ❌ 不修改 TACZ 桥接逻辑
- ❌ 不修改投票引擎或计时系统
- ❌ 不修改网络包或客户端 HUD

---

## 6. 实施顺序

| # | 内容 | 文件 | 模组 |
|---|------|------|------|
| 1 | 消息清理：删除启动广播 + 去掉图标 | `BlackoutMode.java` | core |
| 2 | 新建 SREBlackoutGameMode | `SREBlackoutGameMode.java` | companion |
| 3 | 在 companion mod 注册新模式 | `HabiTrainMoreTasks.java` | companion |
| 4 | BlackoutMode 改启动目标 + 查找模式 | `BlackoutMode.java` | core |
| 5 | 移除 syncRolesFromSRE | `BlackoutMode.java` | core |
| 6 | 添加 initRandomAssignment | `BlackoutRoleManager.java` | core |
| 7 | 构建验证 | `gradlew clean build` | all |
