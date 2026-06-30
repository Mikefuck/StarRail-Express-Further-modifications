# 停电模式接入修复 + 代码审视设计

> **日期**: 2026-06-30
> **项目**: 哈比列车核心 (HabiTrain Core) — 停电模式
> **状态**: 设计已批准

---

## 1. 概述

本文档记录三个功能修复和一个全面的代码审视结果：

| # | 模块 | 问题 | 方案 |
|---|------|------|------|
| 1 | **开局接入** | 停电模式开局没有音效、角色显示、无敌/无碰撞体 | 开启 SRE 原版 `hasSafeTime()` + 自定义 Title 显示停电角色名 |
| 2 | **选警长列表** | 单人测试时 VoteScreen 列表为空（跳过自己） | 单人模式不跳过自己 |
| 3 | **/habi_api stop** | 命令只调 `GameModeRegistry.stop(level)` 传 null WinResult，不清理 SRE 游戏状态 | 增强 stop 流程使之完整终结 SRE 游戏 |
| 4 | **代码审视** | 发现多处设计问题和代码缺漏 | 按严重度逐一修复 |

---

## 2. 开局接入设计

### 2.1 问题分析

`SREBlackoutGameMode` 继承自 `SREMurderGameMode`，后者通过 `hasSafeTime()` 控制开局安全时间（无敌+无碰撞+音效+角色显示）。当前 `SREBlackoutGameMode.hasSafeTime()` 返回 `false`，完全禁用了该机制。

### 2.2 实现方案

**涉及文件：**

- `SREBlackoutGameMode.java` — `hasSafeTime()` → `true`
- `BlackoutMode.java` — `onStart()` 末尾发送自定义 Title

**具体改动：**

1. `SREBlackoutGameMode.hasSafeTime()` 改为返回 `true`（一行改动）
   - 效果：SRE 底层自动施加无敌 + 无碰撞体 + 播放开局音效
   - 安全期时长由 SRE 父类控制，停电模式无需额外管理

2. `BlackoutMode.onStart()` 末尾添加 Title 逻辑：

```
在 onStart() 中，根据 BlackoutRoleManager.getRole(playerUUID) 获取每个玩家的停电角色
→ 发送 ClientboundSetTitleTextPacket("你是 [角色名]")
→ 发送 ClientboundSetSubtitleTextPacket("[阵营描述]")
```

| Blackout 角色 | Title 文本 | Subtitle 文本 |
|---------------|-----------|--------------|
| CIVILIAN | 你是 黑化平民 | 好人阵营 — 完成好人任务，存活到最后 |
| KILLER | 你是 黑化杀手 | 坏人阵营 — 破坏列车，消灭好人 |
| SHERIFF | 你是 警长 | 好人阵营 — 维护秩序，保护列车 |

Title 样式参考 SRE 原生风格：金色加粗标题 + 白色副标题，时间 10fade-in + 60stay + 20fade-out ticks。

---

## 3. 选警长列表（单人测试兼容）

**涉及文件：** `VoteScreen.java`

**当前行为：** 第 80 行无条件跳过自己。单人测试时在线玩家只有自己，列表为空。

**修改：** 当世界玩家总数 ≤ 1 时，保留自己显示在列表中：

```java
// 改前
if (player.getUUID().equals(self.getUUID())) continue;

// 改后
boolean isAlone = players.size() <= 1;
if (!isAlone && player.getUUID().equals(self.getUUID())) continue;
```

此改动同时是临时性的，待多人测试后可以移除/改为配置项。

---

## 4. /habi_api stop 完整接入

**涉及文件：** `HabiTrainCore.java`、`BlackoutMode.java`、`GameModeRegistry.java`

### 4.1 当前问题

`/habi_api stop` 目前直接调用 `GameModeRegistry.stop(level)`，该方法：
1. 从 `ACTIVE_MODES` 移除当前模式
2. 调 `onEnd(level, null)` — 传入 null WinResult
3. 调 `onCleanup(level)`

**缺失的部分：**
- 不设置 SRE 游戏状态为 STOPPING
- 不清理 SRE 角色数据
- 不触发任何胜利/失败展示
- 不清理 `BlackoutMode.gameEnded`、`sreGameRunning` 之外的内部状态

### 4.2 重构方案

**BlackoutMode 新增 `forceEndGame()` 方法：**

```java
public void forceEndGame(WinResult result, String message) {
    if (gameEnded) return;
    gameEnded = true;
    sreGameRunning = false;
    broadcast(message);

    if (currentLevel != null) {
        // 1) 停 SRE 游戏
        var sreGame = SREGameWorldComponent.KEY.get(currentLevel);
        if (sreGame != null) {
            sreGame.setGameStatus(GameStatus.STOPPING);
            sreGame.removeAllRoles();
        }
        // 2) 触发完整游戏结束链
        GameModeRegistry.stop(currentLevel, result);
    }
}
```

**`/habi_api stop` 命令处理改为：**

```java
.then(Commands.literal("stop")
    .executes(ctx -> {
        ServerLevel level = ctx.getSource().getLevel();
        var active = GameModeRegistry.getActiveForLevel(level);
        if (active.isPresent() && active.get() instanceof BlackoutMode bm) {
            bm.forceEndGame(WinResult.forceEnd(), "§c游戏已被管理员终止");
        } else {
            GameModeRegistry.stop(level);
        }
        return 1;
    })
)
```

**GameModeRegistry.stop() 增强为传 WinResult：**

```java
public static void stop(ServerLevel level, WinResult result) {
    ResourceKey<Level> levelKey = level.dimension();
    GameMode mode = ACTIVE_MODES.remove(levelKey);
    if (mode != null) {
        mode.onEnd(level, result);  // 传入 WinResult 而非 null
        mode.onCleanup(level);
        LOGGER.info("Stopped GameMode: {} in {}", mode.getId(), levelKey.location());
    }
}
```

保持原有无参 `stop(level)` 重载兼容，内部传 `WinResult.forceEnd()`。

---

## 5. 代码审视修复清单

### 5.1 🔴 严重 — 优先修复

| ID | 问题 | 修法 |
|----|------|------|
| F1 | `GameModeRegistry.stop()` 传 null WinResult | 按 §4.2 改造，传有效 WinResult |
| F2 | 静态管理器架构松散 | 短期：`onCleanup()` 中加入更多 reset 调用；长期考虑实例化 |
| F3 | `VoteScreen` 不按 `isAlive()` 过滤 | 改为用 `BlackoutRoleManager.getAllAlive()` 过滤玩家列表，而非全部 level.players() |

### 5.2 🟡 中度 — 第二阶段修复

| ID | 问题 | 修法 |
|----|------|------|
| F4 | 服务端用反射调客户端 `BlackoutHudOverlay` | 改为 `BlackoutTimerPayload` 网络包驱动显示状态，移除 `Class.forName` |
| F5 | `gameEnded` 死锁风险 | `onEnd()` 内部设置 `gameEnded` 前先完成所有外部队列操作 |
| F6 | 反射访问 SRE private 字段 `blackouts` | 尝试调 SRE 公开 API 替代；或加 version-guard 的 try-catch |
| F7 | `tickSecond()` 调用约定不明确 | 加 javadoc 说明由 `BlackoutMode.onTick` 每 ~1s 调一次，添加 `isInGameTick()` 保护 |
| F8 | `checkVictory()` 和 timer 同步 | 保证在 `tickSecond` 之后同一 tick 内检查，避免 timer 归零与 gameEnded 不一致 |

### 5.3 🟢 轻微 — 第三阶段（代码整洁）

| ID | 问题 | 修法 |
|----|------|------|
| F9 | SREMurderMode.isActive 启发式判断 | 改为判断 modeId 精确匹配 "sre:murder" 而非 "not repair" |
| F10 | SERepairMode 命名不一致 | `SERepairMode` → `SRERepairMode` |
| F11 | tickVoting 用 +=20 | 改为 `windowElapsedTicks += 1`，caller 保证每秒调一次 |
| F12 | BlackoutHudOverlay 全局 static | 暂保持，添加注释说明限制 |
| F13 | 配置文件名与 mod id 冲突 | 无偏移修复，仅记录 |

---

## 6. 实施顺序

```
阶段 1 (必做，立即修复)
├── F1: 增强 GameModeRegistry.stop 传 WinResult
├── F3: VoteScreen 用 isAlive 过滤
├── §2: 开局接入 (hasSafeTime + Title)
├── §3: VoteScreen 单人测试兼容
└── §4: /habi_api stop 接入完整流程

阶段 2 (代码质量)
├── F4: 移除 Class.forName 反射
├── F5-F8: 中度问题修复

阶段 3 (整洁)
├── F9-F13: 轻微修正
└── SERepairMode 重命名
```
