# 停电模式 SRE 生命周期整合设计

**日期**: 2026-07-01  
**状态**: 已批准  
**标签**: blackout, sre, lifecycle, integration

---

## 1. 动机

经过测试发现四个关联问题，根源均为停电模式（BlackoutMode）与 SRE（StarRailExpress / TMM）原版列车生命周期没有正确对齐：

1. `/habi_api stop` 结束后 HUD 时间进度条未消失 — 停电模式独立管理自己的生命周期，不响应 SRE 游戏结束事件
2. 警长需开局自动分配，不应有投票系统 — 当前实现使用 `BlackoutVotingEngine` 投票选警长，违背原版开局即分配的设计
3. 开局没有音效报幕 — 当前使用静态 Title 包而非 SRE 原生的 `RoundTextRenderer` 报幕系统
4. 按 U 键角色介绍页显示其他模式角色 — 打开的是 SRE 全局 `RoleIntroduceScreen`，加载了所有模式的角色

## 2. 设计决策

- **方向修正优先**: 不修补现有独立生命周期，而是将停电模式完全挂载到 SRE 生命周期上
- **删除独立组件**: 移除 `/habi_api stop`、`BlackoutVotingEngine`、`VoteScreen`、`BlackoutVotePayload`
- **复用原生系统**: 使用 SRE 的 `AnnounceWelcomePayload` + `RoundTextRenderer` 实现开局报幕
- **自维护 UI 代码**: 复制并裁剪 `RoleIntroduceScreen` 到 API 模组内维护

## 3. 详细设计

### 3.1 生命周期整合

#### 服务端 (`SREBlackoutGameMode`)

`SREBlackoutGameMode` 是停电模式真正的游戏控制器。它继承 `SREMurderGameMode`，当前已经覆盖了 `initializeGame()`。变更如下：

**`initializeGame()` 新的执行顺序：**

1. 标准 SRE 初始化
2. 分配停电阵营（`BlackoutRoleManager.initRandomAssignment`）
3. 分配警长（按杀手数量同比）
4. 分配 SRE 角色（好人=CIVILIAN，坏人=KILLER）
5. 发送 `AnnounceWelcomePayload` 给每位玩家（触发报幕+音效）
6. 发送 `BlackoutTimerPayload`（启动 HUD 计时器）
7. 执行 SRE 的 `start_game` 函数

**`BlackoutMode` 简化：**

- 移除 `sreGameRunning`、`sreStartAttempted`、`sreForceActivated`、`sreStartWaitTicks` 状态字段
- 移除 `onTick()` 中等待 SRE 启动的循环
- `onStart()` 只注册 TACZ 监听，不再调用 `GameUtils.startGame`
- `onEnd()` / `onCleanup()` 由 SRE `finalizeGame()` 触发，确保完整清理
- 移除 `forceEndGame()` 方法 — 不再需要独立结束路径

#### 客户端 (`HabiTrainCoreClient`)

- 移除当前 `BlackoutTimerPayload` 接收器中通过反射访问 `BlackoutHudOverlay` 的逻辑
- 监听 SRE 的 `OnGameFinishedClient` 事件 → 调用 `BlackoutHudOverlay.reset()`
- 监听 SRE 的 `OnGameStartedClient` 事件 → 准备接收计时器数据（通过 `BlackoutTimerPayload`）
- `BlackoutHudOverlay` 当前使用全局 static 变量，保持此模式不变

#### Stop 命令处理

- 删除 `/habi_api stop` 命令
- `tmm stop` → SRE 调用 `finalizeGame()` → `gameWorldComponent.setGameStatus(STOPPING)` → 触发 `onCleanup()`
- 客户端收到 `OnGameFinishedPayload` → 触发 `OnGameFinishedClient` 事件 → `BlackoutHudOverlay.reset()`

### 3.2 警长分配 + 移除投票

#### 警长分配逻辑（`BlackoutRoleManager`）

```java
// initRandomAssignment 之后调用
static void assignSheriffs() {
    int killerCount = getRemainingBad();
    int sheriffCount = Math.max(1, killerCount); // 最少 1 个警长
    
    List<UUID> goodCandidates = getAllAlive().stream()
        .filter(id -> getRole(id) == RoleType.CIVILIAN)
        .collect(Collectors.toList());
    Collections.shuffle(goodCandidates);
    
    for (int i = 0; i < Math.min(sheriffCount, goodCandidates.size()); i++) {
        setSheriff(goodCandidates.get(i));
    }
}
```

- 杀手 1 人 → 警长 1 人
- 杀手 2 人 → 警长 2 人
- 警长身份隐藏，仅自己通过开局报幕知道
- 警长角色显示为 CIVILIAN 的子类，SRE 层面仍为 TMMRoles.CIVILIAN

#### 被移除的组件

| 文件 | 原因 |
|------|------|
| `BlackoutVotingEngine.java` | 不再需要投票 |
| `VoteScreen.java` | 不再需要投票 GUI |
| `BlackoutVotePayload.java` | 不再需要投票网络包 |
| P 键处理 (BlackoutKeyHandler) | 不再需要投票热键 |

#### 对现有代码的影响

- `BlackoutMode.onTick()` 中移除投票阶段检查和 `tickVoting()` 调用
- 修改 `BlackoutHudOverlay`：移除 `votingOpen` 状态和 `setVotingOpen()` 方法
- 修改 `BlackoutMode.sendRoleTitles()`：不再发送基础 Title，改为发送 `AnnounceWelcomePayload`

### 3.3 开局音效报幕

#### 复用 SRE 原生报幕系统

在 `SREBlackoutGameMode.initializeGame()` 中，角色分配完成后，**每位玩家发送 `AnnounceWelcomePayload`**：

```java
// 在原 initializeGame() 末尾追加
for (ServerPlayer player : players) {
    var sreRole = gameWorldComponent.getRole(player);
    int killerCount = BlackoutRoleManager.getRemainingBad();
    ServerPlayNetworking.send(player,
        new AnnounceWelcomePayload(
            sreRole.getIdentifier().toString(),
            killerCount,
            players.size() - killerCount
        )
    );
}
```

**注意：** 由于停电模式的玩家在 SRE 层看到的角色是 `CIVILIAN` 或 `KILLER`，报幕将显示 SRE 默认的平民/杀手欢迎文本。如需显示自定义文本（如"黑化杀手"、"黑化平民"、"警长"），有两种方式：

1. **映射法（推荐）**：在 `getAnnouncementText()` 中根据 `BlackoutRoleManager` 的实际角色返回自定义文本——但 `RoundTextRenderer` 已使用 `RoleAnnouncementTexts` 查找表
2. **发送自定义网络包**：创建 `BlackoutAnnouncePayload`，在客户端触发自定义报幕逻辑

**选用方案 2**，因为停电模式的角色体系（KILLER/CIVILIAN/SHERIFF）与 SRE 的角色体系（各种 mod 角色）不共享，自定义报幕更可控。

#### 自定义报幕网络包

```java
// BlackoutAnnouncePayload.java
public record BlackoutAnnouncePayload(
    String roleName,      // "黑化平民" / "黑化杀手" / "警长"
    String subtitle,      // 副标题描述
    int killerCount,      // 杀手数量
    int targetCount       // 好人数
) implements CustomPacketPayload { ... }
```

客户端接收后，调用一个轻量级的报幕渲染器（基于 `RoundTextRenderer` 的简化版）播放音效 + 显示文字。

**报幕动画流程：**
| 时机 | 动作 |
|------|------|
| tick 200 | 播放 `TMMSounds.UI_RISER` (上升音) |
| tick 180 → 121 | 显示大号角色名称，播放 `UI_PIANO` (1.25x) |
| tick 120 → 61 | 显示副标题/描述，播放 `UI_PIANO` (1.5x) |
| tick 60 → 1 | 显示目标/提示，播放 `UI_PIANO` (1.75x) |
| tick 1 → 0 | 播放 `UI_PIANO_STINGER` (结束音) |

### 3.4 角色介绍页筛选

#### 复制的 GUI 类

从 SRE 复制并修改 `RoleIntroduceScreen.java` → `BlackoutRoleIntroduceScreen.java`：

**保留的代码：**
- 面板布局、卡片渲染、详情面板、滚动、颜色样式

**移除的内容：**
- 模式切换（MURDER/REPAIR/OTHER）
- 分类标签（ALL/CIVILIAN/VIGILANTE/NEUTRAL 等）
- 搜索框
- 物品/修饰符支持
- 商店物品渲染
- SRE 特有的 `ClientSponsorCache`、`HMLModifiers` 等依赖

**数据来源：**
```java
private static final List<RoleInfo> ROLES = List.of(
    new RoleInfo("黑化平民", "§7好人阵营", "存活到最后，完成好人任务", 0xFF44BB66),
    new RoleInfo("黑化杀手", "§c坏人阵营", "消灭所有好人，破坏列车", 0xFFCC2233),
    new RoleInfo("警长", "§b好人阵营", "找出并制裁杀手", 0xFF22BBCC)
);
```

#### 热键修改

`BlackoutKeyHandler`：U 键改为打开 `BlackoutRoleIntroduceScreen`（不再通过反射打开 SRE 的 RoleIntroduceScreen）

## 4. 文件变更清单

### 新增文件
| 文件 | 说明 |
|------|------|
| `BlackoutAnnouncePayload.java` | 自定义报幕网络包 |
| `BlackoutRoleIntroduceScreen.java` | 黑灯模式专用角色介绍 GUI |
| `BlackoutRoleCardHudRenderer.java` | 轻量级报幕渲染器 |

### 修改文件
| 文件 | 变更 |
|------|------|
| `SREBlackoutGameMode.java` | 添加警长分配 + 发送报幕包 |
| `BlackoutMode.java` | 简化生命周期，移除独立 start/end 管理 |
| `BlackoutRoleManager.java` | 添加 `assignSheriffs()` 方法 |
| `BlackoutKeyHandler.java` | 移除 P 键，U 键改为新界面 |
| `BlackoutHudOverlay.java` | 移除 voting 相关状态 |
| `HabiTrainCoreClient.java` | 监听 `OnGameStartedClient` / `OnGameFinishedClient` |
| `HabiTrainCore.java` | 移除 `/habi_api stop` 命令 |

### 删除文件
| 文件 | 说明 |
|------|------|
| `BlackoutVotingEngine.java` | 投票引擎 |
| `VoteScreen.java` | 投票 GUI |
| `BlackoutVotePayload.java` | 投票网络包 |

## 5. 测试要点

1. `tmm stop` → HUD 消失，无报幕残留
2. 开局 → 角色名称+描述+音效正常显示/播放
3. 杀手 1 人 → 警长自动分配 1 人
4. 杀手 3 人 → 警长自动分配 3 人
5. 警长自己看到警长身份，他人看不出
6. U 键只显示黑灯模式 3 个角色
7. `VoteScreen` 不再打开（P 键无反应）
