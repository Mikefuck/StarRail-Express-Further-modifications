# Blackout 模式杀手分配修复与优化

**日期:** 2026-06-29
**状态:** 已批准
**关联项目:** habiTrain API (Core)

---

## 问题描述

### Bug: 开局游戏立即结束

测试发现 Blackout 模式启动后 SRE 立即结束游戏，日志时间线如下：

```
17:01:02  BlackoutMode.onStart() → 启动 SRE 游戏（触发 SREBlackoutGameMode.initializeGame）
17:01:05  SRE 检测到全员 CIVILIAN → "Game Stopped!"
17:01:05  BlackoutRoleManager: Assigned 2 BAD / 8 GOOD（角色分配发生在 SRE 结束之后）
17:01:05  "对局结束"
```

**根因：** `BlackoutRoleManager.initRandomAssignment()` 在 `BlackoutMode.onTick()` 中等待 SRE 游戏异步启动后方执行，而 SRE 在初始化阶段已检测到所有玩家为 `TMMRoles.CIVILIAN`（在 `SREBlackoutGameMode.initializeGame()` 中设置的），SRE 判定"全员同阵营"从而立即结束游戏。

### 需求: 新的杀手分配公式

将当前 `25%` 比例改为 `ceil(玩家数 / 6)` 的固定公式：

| 玩家数 | 杀手数 |
|--------|--------|
| 1–6 | 1 |
| 7–12 | 2 |
| 13–18 | 3 |
| 19–24 | 4 |

---

## 设计

### 方案: 提前分配 + 公式替换

将角色分配的**时机**从 `BlackoutMode.onTick()`（异步，太晚）提前到 `SREBlackoutGameMode.initializeGame()`（同步，SRE 检查胜利条件之前），同时替换分配公式。

### 改动范围

涉及 **3 个文件**，核心 API 包内：

#### 1. `BlackoutRoleManager.initRandomAssignment()`

- 去掉 `float badRatio` 参数
- 使用 `Math.ceil(players.size() / 6.0)` 计算杀手数量（至少 1 人）
- 其余分配逻辑不变

#### 2. `SREBlackoutGameMode.initializeGame()`

- 在 `executeFunction("harpymodloader:start_game")` 后、`game.addRole(player, TMMRoles.CIVILIAN, false)` 前插入 `BlackoutRoleManager.initRandomAssignment(players)`
- SRE 层面继续保持全员 CIVILIAN（避免给杀手发 SRE 刀），阵营信息存储在 BlackoutRoleManager 中供 `checkVictory()` 使用

#### 3. `BlackoutMode.onTick()`

- 删除 `initRandomAssignment()` 调用
- 保留 `sreGameRunning = true` 标记和 HUD 通知逻辑

---

## 不变的部分

- SRE 角色分配：全员 CIVILIAN
- 胜利条件：`checkVictory()` 使用 BlackoutRoleManager 的阵营统计
- 击杀机制：警长通过 TACZ 沙漠之鹰击杀，TACZWeaponBridge 不做改动
- 投票机制：BlackoutVotingEngine 不变
- 计时器：BlackoutTimerSystem 不变
