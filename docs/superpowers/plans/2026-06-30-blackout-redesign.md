# 停电模式重设计 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重写停电模式游戏循环（永久停电/维护期/短暂停电三态）、修复投票系统、复用 SRE 角色介绍 GUI、过滤停电专有任务

**Architecture:** 核心变更集中在 `BlackoutTimerSystem`（新状态机）和 `BlackoutMode`（适配层），投票/键盘/留存问题分模块修复。停电角色的 SRE RoleIntroduceScreen 通过向 `TMMRoles.ROLES` 注册 SRERole 实例实现。companion mod 中的 4 个停电任务配合新循环调整效果。

**Tech Stack:** Fabric 1.21.1, Java 21, Minecraft, StarRailExpress (硬依赖)

## Global Constraints

- Java 21 language level
- Minecraft 1.21.1, Fabric Loader ≥0.19.2
- `starrailexpress` as hard dependency via local JAR
- SRE 本体代码不可修改
- 停电模式 ID: `habitrains:blackout`
- 配套 mod ID: `habitrain_more_tasks`
- 核心 mod ID: `habitrain_core`
- 所有 GameMode API 接口不变（GameMode 不新增方法）

---

## 文件结构图

### 核心 api 模组（本仓库）

```
src/main/java/com/habitrain/core/
├── game/blackout/
│   ├── BlackoutTimerSystem.java        ← 重写：新三态状态机
│   ├── BlackoutMode.java               ← 修改：适配新 Timer
│   ├── BlackoutVotingEngine.java       ← 修改：加 isVotingOpen()
│   ├── BlackoutRoleManager.java        ← 不变
│   └── TACZWeaponBridge.java           ← 不变
├── client/
│   ├── BlackoutKeyHandler.java         ← 修改：P 键限制/U 键改导向
│   └── HabiTrainCoreClient.java        ← 修改：注册停电角色
├── client/gui/
│   ├── VoteScreen.java                 ← 修改：ESC 关闭+按钮
│   ├── BlackoutHudOverlay.java         ← 修改：加静态状态查询
│   └── BlackoutRoleIntroduceScreen.java ← 删除（不再使用）
└── network/
    ├── BlackoutTimerPayload.java       ← 不变
    ├── BlackoutVotePayload.java        ← 不变
    └── BlackoutStatusPayload.java      ← 不变
```

### 配套 moretasks 模组（D:\Backup\mc mod\哈比列车更多修改）

```
src/main/java/com/habitrain/moretasks/game/blackout/
├── AddCoalTask.java                    ← 修改：总时间 -30s
├── RepairWiringTask.java               ← 修改：恢复供电
├── SabotageWiringTask.java             ← 修改：+短暂停电
├── FurnaceExplosionTask.java           ← 修改：总时间 +15s
└── MaintainPowerTask.java              ← NEW：维护供电 +15s
```

---

### Task 1: 重写 BlackoutTimerSystem（新三态状态机）

**Files:**
- Create (overwrite): `src/main/java/com/habitrain/core/game/blackout/BlackoutTimerSystem.java`
- Consumers: BlackoutMode (tick + callbacks), companion mod tasks (reduceTime/addTime/triggerBlackout/delayBlackout)

**Interfaces:**
- Consumes: `ServerLevel`, callbacks `onPermanentBlackoutStart(Runnable)`, `onMaintenanceEnd(Runnable)`, `onTimeWarning(Runnable)`
- Produces: static API: `init()`, `tickSecond()`, `reset()`, `restorePower()`, `reduceTime()`, `addTime()`, `triggerTransientBlackout()`, `delayMaintenanceOrCountdown()`, `getTotalTimeRemaining()`, `getMaintenanceTime()`, `isPermanentBlackoutActive()`, `isTransientBlackoutActive()`, `isInMaintenance()`, `isTimeUp()`

- [ ] **Step 1: Write the full new BlackoutTimerSystem**

```java
package com.habitrain.core.game.blackout;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 停电模式 — 三态计时器系统。
 *
 * 状态机：
 *   NORMAL (灯亮) ──停电倒计时归零──→ FIRST_BLACKOUT (永久停电，可恢复)
 *                                         │
 *                                    好人"维修线路" ──→ MAINTENANCE (60s维护期)
 *                                                           │
 *                                                      维护期归零 → SECOND_BLACKOUT (永久停电，不可逆)
 */
public class BlackoutTimerSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutTimer");

    // ====== 常量 ======
    private static final int TOTAL_TIME = 300;          // 对局总时长 300s
    private static final int FIRST_BLACKOUT_CD = 120;   // 第一次停电倒计时 120s
    private static final int MAINTENANCE_DURATION = 60; // 维护期时长 60s
    private static final int TRANSIENT_TICKS = 140;     // 短暂停电 7s × 20 tick

    // ====== 三态枚举 ======
    public enum Phase {
        NORMAL,           // 灯亮，停电倒计时递减
        FIRST_BLACKOUT,   // 第一次永久停电 (可恢复)
        MAINTENANCE,      // 恢复供电维护期
        SECOND_BLACKOUT   // 第二次永久停电 (不可逆)
    }

    private static Phase phase = Phase.NORMAL;

    // ====== 计时器 ======
    private static int totalTimeRemaining = TOTAL_TIME;   // 对局总倒计时
    private static int blackoutCountdown = FIRST_BLACKOUT_CD;  // 停电倒计时
    private static int maintenanceTime = 0;                // 维护期倒计时
    private static boolean warningSent = false;            // 60s 预警

    // 短暂停电 (杀手破坏线路)
    private static boolean transientBlackoutActive = false;
    private static int transientBlackoutTicks = 0;

    private static ServerLevel currentLevel = null;
    private static Runnable onPermanentStart = null;  // 永久停电回调（调用 SRE API）
    private static Runnable onPermanentEnd = null;     // 永久停电恢复（调用 SRE API 恢复灯光）
    private static Runnable onTimeWarning = null;      // 60s 预警

    // ====== 初始化 ======

    public static void init(ServerLevel level, Runnable permanentStartCb, Runnable permanentEndCb, Runnable timeWarningCb) {
        phase = Phase.NORMAL;
        totalTimeRemaining = TOTAL_TIME;
        blackoutCountdown = FIRST_BLACKOUT_CD;
        maintenanceTime = 0;
        warningSent = false;
        transientBlackoutActive = false;
        transientBlackoutTicks = 0;
        currentLevel = level;
        onPermanentStart = permanentStartCb;
        onPermanentEnd = permanentEndCb;
        onTimeWarning = timeWarningCb;
        LOGGER.info("BlackoutTimerSystem initialized: phase=NORMAL, {}s total, {}s blackout CD", TOTAL_TIME, FIRST_BLACKOUT_CD);
    }

    public static void reset() {
        currentLevel = null;
        onPermanentStart = null;
        onPermanentEnd = null;
        onTimeWarning = null;
    }

    // ====== 每秒更新 (由 BlackoutMode.onTick 调用) ======

    public static void tickSecond() {
        if (currentLevel == null) return;

        // === 总时间倒计时 (所有状态下都走，修复之前的冻结 bug) ===
        totalTimeRemaining--;

        // === 60s 预警 ===
        if (totalTimeRemaining <= 60 && !warningSent) {
            warningSent = true;
            if (onTimeWarning != null) onTimeWarning.run();
        }

        // === 胜利检查 (时间归零 → 好人胜利) ===
        if (totalTimeRemaining <= 0) return;

        // === 短暂停电计时 (杀手破坏线路) ===
        if (transientBlackoutActive) {
            transientBlackoutTicks--;
            if (transientBlackoutTicks <= 0) {
                transientBlackoutActive = false;
                LOGGER.info("Transient blackout ended");
            }
        }

        // === 按当前阶段处理 ===
        switch (phase) {
            case NORMAL -> tickNormal();
            case FIRST_BLACKOUT -> {
                // 等待好人"维修线路"任务调用 restorePower()
            }
            case MAINTENANCE -> tickMaintenance();
            case SECOND_BLACKOUT -> {
                // 等待好人做任务减总时间，或杀手击杀全部好人
            }
        }
    }

    private static void tickNormal() {
        blackoutCountdown--;
        if (blackoutCountdown <= 0) {
            // 进入第一次永久停电
            phase = Phase.FIRST_BLACKOUT;
            if (onPermanentStart != null) onPermanentStart.run();
            broadcast("§c⚡ 永久停电！列车陷入黑暗！");
            broadcast("§e好人完成维修任务可恢复供电");
            LOGGER.info("Phase transition: NORMAL → FIRST_BLACKOUT");
        }
    }

    private static void tickMaintenance() {
        maintenanceTime--;
        if (maintenanceTime <= 0) {
            // 进入第二次永久停电 (不可逆)
            phase = Phase.SECOND_BLACKOUT;
            if (onPermanentStart != null) onPermanentStart.run();
            broadcast("§c备用电源耗尽！列车再次陷入黑暗！");
            broadcast("§e好人无法再恢复供电，但做可减少总时间提前胜利！");
            LOGGER.info("Phase transition: MAINTENANCE → SECOND_BLACKOUT");
        }
    }

    // ====== 供电恢复 (由 RepairWiringTask.onComplete 调用) ======

    public static void restorePower() {
        if (phase != Phase.FIRST_BLACKOUT) {
            LOGGER.warn("restorePower called but phase is {} (only valid in FIRST_BLACKOUT)", phase);
            return;
        }
        // 恢复灯光
        if (onPermanentEnd != null) onPermanentEnd.run();
        phase = Phase.MAINTENANCE;
        maintenanceTime = MAINTENANCE_DURATION;
        broadcast("§a✔ 供电已恢复！维护期 " + MAINTENANCE_DURATION + " 秒");
        broadcast("§e请在 " + MAINTENANCE_DURATION + " 秒内尽可能做任务维持供电！");
        LOGGER.info("Power restored, phase: FIRST_BLACKOUT → MAINTENANCE ({}s)", MAINTENANCE_DURATION);
    }

    // ====== 短暂停电 (杀手破坏线路) ======

    public static void triggerTransientBlackout() {
        if (transientBlackoutActive) return;
        transientBlackoutActive = true;
        transientBlackoutTicks = TRANSIENT_TICKS;
        // 调用 SRE 短暂停电 (只给效果，不改变阶段)
        if (onPermanentStart != null) onPermanentStart.run();
        broadcast("§c⚡ 线路被破坏！短暂停电！");
        LOGGER.info("Transient blackout triggered ({} ticks)", TRANSIENT_TICKS);

        // 如果在维护期，减少维护时间
        if (phase == Phase.MAINTENANCE) {
            maintenanceTime = Math.max(0, maintenanceTime - 15);
            broadcast("§c维护期减少 15 秒！");
        }
        // 如果在 NORMAL 阶段，减少停电倒计时
        if (phase == Phase.NORMAL) {
            blackoutCountdown = Math.max(0, blackoutCountdown - 15);
        }
    }

    // ====== 任务交互 API ======

    /** 好人: 减少总时间 (添加煤炭 → -30s) */
    public static void reduceTime(int seconds) {
        totalTimeRemaining = Math.max(0, totalTimeRemaining - seconds);
        LOGGER.info("Total time reduced by {}s, remaining: {}s", seconds, totalTimeRemaining);
    }

    /** 坏人: 增加总时间 (熔炉爆炸 → +15s) */
    public static void addTime(int seconds) {
        totalTimeRemaining += seconds;
        LOGGER.info("Total time increased by {}s, remaining: {}s", seconds, totalTimeRemaining);
    }

    /** 好人: 推迟第一次停电倒计时/增加维护期 (维修线路 → +15s, 维护供电 → +15s) */
    public static void delayMaintenanceOrCountdown(int seconds) {
        switch (phase) {
            case NORMAL -> {
                blackoutCountdown = Math.min(blackoutCountdown + seconds, 300);
                LOGGER.info("Blackout CD delayed by {}s, now: {}s", seconds, blackoutCountdown);
            }
            case MAINTENANCE -> {
                maintenanceTime += seconds;
                LOGGER.info("Maintenance time extended by {}s, now: {}s", seconds, maintenanceTime);
            }
            default -> LOGGER.warn("delayMaintenanceOrCountdown called in phase {}", phase);
        }
    }

    // ====== 读取器 ======

    public static Phase getPhase() { return phase; }
    public static int getTotalTimeRemaining() { return totalTimeRemaining; }
    public static int getBlackoutCountdown() { return phase == Phase.NORMAL ? blackoutCountdown : 0; }
    public static int getMaintenanceTime() { return phase == Phase.MAINTENANCE ? maintenanceTime : 0; }
    public static boolean isPermanentBlackoutActive() {
        return phase == Phase.FIRST_BLACKOUT || phase == Phase.SECOND_BLACKOUT;
    }
    public static boolean isTransientBlackoutActive() { return transientBlackoutActive; }
    public static boolean isInMaintenance() { return phase == Phase.MAINTENANCE; }
    public static boolean isTimeUp() { return totalTimeRemaining <= 0; }

    private static void broadcast(String msg) {
        if (currentLevel == null) return;
        Component c = Component.literal(msg);
        for (ServerPlayer player : currentLevel.players()) {
            player.sendSystemMessage(c);
        }
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `cd "D:/Backup/mc mod/哈比列车api" && ./gradlew clean build`
Expected: BUILD SUCCESSFUL (BlackoutTimerSystem compiles)

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "refactor: rewrite BlackoutTimerSystem with three-phase state machine (NORMAL/FIRST_BLACKOUT/MAINTENANCE/SECOND_BLACKOUT), fix 5min freeze bug"
```

---

### Task 2: 适配 BlackoutMode

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`

**Interfaces:**
- Consumes: `BlackoutTimerSystem` new API (Phase enum, isPermanentBlackoutActive, etc.)
- Produces: callbacks for timer (onPermanentStart, onPermanentEnd, onTimeWarning)

- [ ] **Step 1: Rewrite BlackoutMode lifecycle to use new Timer API**

```java
// 关键变更:
// 1. onPreStart → init 传新回调
// 2. onTick → 移除旧 votingPhasePassed 逻辑; 适配 tickSecond
// 3. triggerSREBlackout → 直接永久调用，不重置
// 4. endSREBlackout → 只由 restorePower 触发
// 5. checkVictory → 总时间归零=好人胜

@Override
public void onPreStart(ServerLevel level) {
    this.currentLevel = level;
    this.tickAccumulator = 0;
    this.gameEnded = false;
    this.sreGameRunning = false;
    this.sreStartAttempted = false;
    this.sreForceActivated = false;
    this.sreStartWaitTicks = 0;

    BlackoutRoleManager.clear();
    BlackoutTimerSystem.init(level,
            this::triggerSREPermanentBlackout,   // onPermanentStart
            this::endSREBlackout,                 // onPermanentEnd
            this::sendTimeWarning                 // onTimeWarning
    );
    TACZWeaponBridge.resetPurchases();
}
```

```java
// onTick 中的计时部分改为:
// 每 20 tick (~1秒) 更新
if (tickAccumulator % 20 == 0) {
    BlackoutTimerSystem.tickSecond();

    // 投票阶段检查 (60s后)
    int totalRemaining = BlackoutTimerSystem.getTotalTimeRemaining();
    int elapsed = 300 - totalRemaining;
    if (!votingPhasePassed && elapsed >= 60) {
        votingPhasePassed = true;
        BlackoutVotingEngine.init(level.getServer());
        BlackoutVotingEngine.openVoting();
    }

    // tick voting engine
    BlackoutVotingEngine.tickVoting();

    // 检查胜利条件
    checkVictory();

    // 广播时间同步
    int totalTime = BlackoutTimerSystem.getTotalTimeRemaining();
    boolean permDark = BlackoutTimerSystem.isPermanentBlackoutActive();
    int maintTime = BlackoutTimerSystem.getMaintenanceTime();
    int cd = BlackoutTimerSystem.getBlackoutCountdown();
    BlackoutTimerPayload.broadcastToAll(level.getServer(),
            totalTime,
            permDark ? 0 : (maintTime > 0 ? maintTime : cd),
            permDark || BlackoutTimerSystem.isTransientBlackoutActive());
}
```

```java
// 胜利条件变更
private void checkVictory() {
    if (!sreGameRunning) return;
    if (BlackoutRoleManager.getRemainingGood() <= 0 && BlackoutRoleManager.getRemainingBad() <= 0) return;

    int goodRemaining = BlackoutRoleManager.getRemainingGood();
    int badRemaining = BlackoutRoleManager.getRemainingBad();

    // 好人胜: 时间归零
    if (BlackoutTimerSystem.isTimeUp()) {
        endGame("§a好人阵营获胜！时间归零，好人成功存活！");
        return;
    }

    // 好人胜: 杀手全灭
    if (badRemaining <= 0 && goodRemaining > 0) {
        endGame("§a好人阵营获胜！所有杀手已被消灭");
        return;
    }

    // 杀手胜: 好人全灭
    if (goodRemaining <= 0 && badRemaining > 0) {
        endGame("§c杀手阵营获胜！所有好人都被淘汰了");
        return;
    }
}
```

```java
// triggerSREBlackout 改为永久/短暂通用的方法
private void triggerSREPermanentBlackout() {
    if (currentLevel == null) return;
    var blackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(currentLevel);
    if (blackout != null) {
        // 传入很大的 duration (如 60000 ticks)，且不基于随机范围，使所有灯永久熄灭
        blackout.triggerBlackout(true, 60000);
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `cd "D:/Backup/mc mod/哈比列车api" && ./gradlew clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "refactor: adapt BlackoutMode for new timer system, change victory to timeUp=goodWin"
```

---

### Task 3: 修复 VoteScreen — ESC 关闭 + 关闭按钮

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/gui/VoteScreen.java`

- [ ] **Step 1: Add ESC close and close button**

```java
// 1. ESC 可关闭
@Override
public boolean shouldCloseOnEsc() { return true; }

// 2. init 中加关闭按钮
@Override
protected void init() {
    super.init();

    addRenderableWidget(Button.builder(
            Component.literal("§a✔ 确认投票"),
            btn -> confirmVote()
    ).bounds(width / 2 - 50, height - 40, 100, 20).build());

    // 关闭按钮
    addRenderableWidget(Button.builder(
            Component.literal("✕"),
            btn -> onClose()
    ).bounds(width - 25, 5, 20, 18).build());
}
```

```java
// 3. render 中如果不是投票时间，显示提示
// 需要新增构造参数
private boolean votingOpen;

public VoteScreen() {
    this(true);
}

public VoteScreen(boolean votingOpen) {
    super(Component.literal("§l投票选举警长"));
    this.votingOpen = votingOpen;
}

@Override
public void render(GuiGraphics g, int mx, int my, float delta) {
    renderBackground(g, mx, my, delta);
    super.render(g, mx, my, delta);

    if (!votingOpen) {
        String msg = "§e当前不在投票时间内";
        g.drawString(font, Component.literal(msg),
                width / 2 - font.width(msg) / 2, height / 2, 0xFFFFAA, false);
        return;
    }

    // 原有渲染代码...
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `cd "D:/Backup/mc mod/哈比列车api" && ./gradlew clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "fix: VoteScreen - add ESC close, close button, non-voting state display"
```

---

### Task 4: 限制 BlackoutKeyHandler — P 键/U 键

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/BlackoutKeyHandler.java`
- Modify: `src/main/java/com/habitrain/core/client/gui/BlackoutHudOverlay.java` (加静态状态查询)

- [ ] **Step 1: 在 BlackoutHudOverlay 添加静态状态查询**

```java
// 添加静态标志位和查询方法
private static boolean blackoutModeActive = false;
private static boolean votingOpen = false;

public static void setBlackoutModeActive(boolean v) { blackoutModeActive = v; }
public static void setVotingOpen(boolean v) { votingOpen = v; }

public static boolean isBlackoutModeActive() { return blackoutModeActive; }
public static boolean isVotingOpen() { return votingOpen; }
```

- [ ] **Step 2: 在 BlackoutMode/HabiTrainCoreClient 中触发设置**

在 `HabiTrainCoreClient` 的 `BlackoutTimerPayload` 接收器中添加：
```java
// BlackoutHudOverlay.setBlackoutModeActive(true) 在收到第一个 timer payload 时设置
```

在 `BlackoutVotingEngine.openVoting()` 中添加：
```java
// 服务端通过 BlackoutStatusPayload 通知客户端
BlackoutStatusPayload.broadcast(server, StatusType.VOTE_OPEN, "");
// 客户端收到时设置 BlackoutHudOverlay.setVotingOpen(true)
```

- [ ] **Step 3: 限制 P 键和 U 键**

```java
// P 键：仅在停电模式 + 投票窗口开放时打开
while (VOTE_KEY.consumeClick()) {
    if (client.player != null && client.screen == null
            && BlackoutHudOverlay.isBlackoutModeActive()
            && BlackoutHudOverlay.isVotingOpen()) {
        client.setScreen(new VoteScreen(true));
    } else if (client.player != null && client.screen == null
            && BlackoutHudOverlay.isBlackoutModeActive()) {
        client.setScreen(new VoteScreen(false));  // 显示"不在投票时间"
    }
}

// U 键：仅在停电模式激活时打开 SRE 原版 RoleIntroduceScreen
while (ROLE_INTRO_KEY.consumeClick()) {
    if (client.player != null && client.screen == null
            && BlackoutHudOverlay.isBlackoutModeActive()) {
        try {
            Class<?> screenClass = Class.forName("org.agmas.noellesroles.client.screen.RoleIntroduceScreen");
            client.setScreen((Screen) screenClass.getConstructor().newInstance());
        } catch (Exception e) {
            LOGGER.error("Failed to open SRE RoleIntroduceScreen", e);
        }
    }
}
```

- [ ] **Step 4: 在 BlackoutVotingEngine.openVoting() 添加日志**

```java
public static void openVoting() {
    voteWindowOpen = true;
    windowElapsedTicks = 0;
    votingResolved = false;
    VOTES.clear();
    broadcast("§e【投票】现在可以投出你觉得的「警长」！按 [P] 键打开投票界面");
    broadcast("§7投票将在 30 秒后截止");
    LOGGER.info("Voting window opened for 30s, server={}", server != null ? "available" : "null");
    // 发送 S2C 状态包
    if (server != null) {
        BlackoutStatusPayload.broadcast(server, StatusType.VOTE_OPEN, "");
    }
}
```

- [ ] **Step 5: Build to verify compilation**

Run: `cd "D:/Backup/mc mod/哈比列车api" && ./gradlew clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "fix: restrict P key to voting window only, route U key to SRE RoleIntroduceScreen, add blackout state queries"
```

---

### Task 5: 注册停电角色到 SRE 角色系统

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java`
- Delete: `src/main/java/com/habitrain/core/client/gui/BlackoutRoleIntroduceScreen.java`

- [ ] **Step 1: 在 HabiTrainCoreClient 注册停电角色**

```java
// 文件顶部导入
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.SRE;

// 注册停电角色（在 onInitializeClient 中）
private void registerBlackoutRoles() {
    try {
        TMMRoles.registerRole(new NormalRole(
                SRE.id("blackout_civilian"), 0x55FF55,
                true, false,
                SRERole.MoodType.HAPPY, 200, true));
        
        TMMRoles.registerRole(new NormalRole(
                SRE.id("blackout_killer"), 0xFF5555,
                false, true,
                SRERole.MoodType.ANGRY, 200, true));
        
        TMMRoles.registerRole(new NormalRole(
                SRE.id("blackout_sheriff"), 0xFFFF55,
                true, true,
                SRERole.MoodType.HAPPY, 200, true));
        
        LOGGER.info("Registered 3 Blackout mode roles into SRE role system");
    } catch (Exception e) {
        LOGGER.error("Failed to register Blackout roles into SRE", e);
    }
}

// 在 onInitializeClient() 末尾调用
registerBlackoutRoles();
```

- [ ] **Step 2: 删除 BlackoutRoleIntroduceScreen.java**

- [ ] **Step 3: 添加语言文件翻译**

在 `src/main/resources/assets/habitrain_core/lang/zh_cn.json` 中添加：
```json
{
  "sre.role.blackout_civilian.name": "停电模式 · 平民",
  "sre.role.blackout_civilian.desc": "普通乘客。在停电中生存，通过完成任务帮助好人阵营获胜。",
  "sre.role.blackout_killer.name": "停电模式 · 杀手",
  "sre.role.blackout_killer.desc": "隐藏在人群中的杀手。利用黑暗掩护行动，消灭所有好人。",
  "sre.role.blackout_sheriff.name": "停电模式 · 警长",
  "sre.role.blackout_sheriff.desc": "唯一可以击杀杀手的好人。通过投票选出，谨慎选择目标。"
}
```

在 `en_us.json` 中添加英文对应翻译：
```json
{
  "sre.role.blackout_civilian.name": "Blackout · Civilian",
  "sre.role.blackout_civilian.desc": "Regular passenger. Survive the blackout, complete tasks to help the good faction win.",
  "sre.role.blackout_killer.name": "Blackout · Killer",
  "sre.role.blackout_killer.desc": "A killer hiding among the crowd. Use the darkness as cover, eliminate all innocents.",
  "sre.role.blackout_sheriff.name": "Blackout · Sheriff",
  "sre.role.blackout_sheriff.desc": "The only good player who can kill killers. Elected by vote, choose your targets wisely."
}
```

- [ ] **Step 4: Build to verify compilation**

Run: `cd "D:/Backup/mc mod/哈比列车api" && ./gradlew clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: register 3 blackout roles into SRE role system, delete custom RoleIntroduceScreen"
```

---

### Task 6: 任务过滤 + Blackout 模式不返回过时胜利条件

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`

- [ ] **Step 1: filterAvailableTasks 过滤**

```java
@Override
public List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player) {
    // 只返回停电模式专属任务
    return tasks.stream()
            .filter(t -> {
                TaskCategory cat = t.getCategory();
                return BLACKOUT_GOOD.equals(cat) || BLACKOUT_BAD.equals(cat);
            })
            .toList();
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `cd "D:/Backup/mc mod/哈比列车api" && ./gradlew clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "fix: filterAvailableTasks to only return blackout-specific tasks"
```

---

### Task 7: 修复 SREWorldBlackoutComponent 调用实现永久停电

这一步不是修改 SRE 本体代码，而是在 `BlackoutMode.triggerSREPermanentBlackout()` 中采用特殊策略来规避 SRE 组件自动恢复的问题。

实际上，因为 `SREWorldBlackoutComponent` 中的 `BlackoutDetails` 会给每个方块不同的随机时长，即使我们传 `60000` ticks，它们在随机范围内也会在不等的时间恢复。

**解决方案：在 `BlackoutMode` 的 `onTick` 中，每次 `permanentBlackoutActive` 时重新调用 `triggerBlackout`，或者直接通过反射/直接设置灯方块状态。**

- [ ] **Step 1: 在 BlackoutMode 中实现永久停电的保持机制**

```java
// 在 onTick 中 (仅每 40 tick 执行一次，减少性能开销)
if (tickAccumulator % 40 == 0) {
    // 保持永久停电状态 — SRE 的 BlackoutDetails 结束后灯会自动恢复
    // 所以需要定期重新触发
    if (BlackoutTimerSystem.isPermanentBlackoutActive()) {
        reapplyPermanentBlackout();
    }
}

private void reapplyPermanentBlackout() {
    if (currentLevel == null) return;
    var blackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(currentLevel);
    if (blackout != null) {
        // 检查是否还有活跃停电方块，如果没有（所有 BlackoutDetails 结束），则重新触发
        // 通过反射读取 blackouts 列表大小
        try {
            var field = blackout.getClass().getDeclaredField("blackouts");
            field.setAccessible(true);
            java.util.List<?> list = (java.util.List<?>) field.get(blackout);
            if (list.isEmpty()) {
                // 所有方块已恢复，重新熄灯 - 传 60000 ticks 但给 0% 随机范围
                blackout.triggerBlackout(false, 60000);
                LOGGER.debug("Re-applied permanent blackout (all blocks had recovered)");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to reapply blackout", e);
        }
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `cd "D:/Backup/mc mod/哈比列车api" && ./gradlew clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "fix: maintain permanent blackout by reapplying when SRE auto-restores lights"
```

---

### Task 8: 配套模组任务改造 — RepairWiringTask 改为恢复供电

**Files:**
- Modify: `D:\Backup\mc mod\哈比列车更多修改\src\main\java\com\habitrain\moretasks\game\blackout\RepairWiringTask.java`

- [ ] **Step 1: 修改维修线路为恢复供电**

```java
package com.habitrain.moretasks.game.blackout;

import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;

import java.awt.Color;

public class RepairWiringTask {
    public static void register() {
        TaskRegistry.register("habitrain_more_tasks", "repair_wiring", builder -> builder
            .displayName("维修线路")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(1.0f)
            .blockTypeId(-1)
            .instinctColor(new Color(255, 215, 0, 200))
            .scanBlocks(net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK)
            .onComplete((player, task) -> {
                // 如果在第一次永久停电阶段，恢复供电
                BlackoutTimerSystem.restorePower();
                player.sendMessage(
                    net.minecraft.network.chat.Component.literal("§a✔ 维修了线路，恢复供电！"),
                    false);
            })
            .completionChecker((player, task) -> task.isFulfilled())
            .build()
        );
    }
}
```

- [ ] **Step 2: Build to verify compilation in companion mod**

Run: `cd "D:/Backup/mc mod/哈比列车更多修改" && ./gradlew clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 确认 JAR 已复制到 临时 目录**

---

### Task 9: 配套模组任务改造 — 其他任务效果调整

**Files:**
- Modify: `D:\Backup\mc mod\哈比列车更多修改\src\main\java\com\habitrain\moretasks\game\blackout\AddCoalTask.java`
- Modify: `D:\Backup\mc mod\哈比列车更多修改\src\main\java\com\habitrain\moretasks\game\blackout\SabotageWiringTask.java`
- Modify: `D:\Backup\mc mod\哈比列车更多修改\src\main\java\com\habitrain\moretasks\game\blackout\FurnaceExplosionTask.java`

- [ ] **Step 1: AddCoalTask — 总时间 -30s**

```java
// onComplete 中
.onComplete((player, task) -> {
    BlackoutTimerSystem.reduceTime(30);
    player.sendMessage(
        net.minecraft.network.chat.Component.literal("§a✔ 找到了煤矿，给锅炉添加煤炭！总时间减少30秒！"),
        false);
})
```

- [ ] **Step 2: SabotageWiringTask — 短暂停电 + 倒计时/维护期 -15s**

```java
.onComplete((player, task) -> {
    BlackoutTimerSystem.triggerTransientBlackout();
    player.sendMessage(
        net.minecraft.network.chat.Component.literal("§c✔ 破坏了线路，触发短暂停电！"),
        false);
})
```

- [ ] **Step 3: FurnaceExplosionTask — 总时间 +15s + TNT**

```java
.onComplete((player, task) -> {
    BlackoutTimerSystem.addTime(15);
    // 引爆 5 格半径内的 TNT（保持原有逻辑）
    if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
        var level = serverPlayer.serverLevel();
        var pos = serverPlayer.blockPosition();
        var tntBlocks = java.util.List.of(
            net.minecraft.world.level.block.Blocks.TNT,
            net.minecraft.world.level.block.Blocks.END_STONE  // 保留原配置
        );
        // 寻找并引爆 TNT
        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    var targetPos = pos.offset(x, y, z);
                    var state = level.getBlockState(targetPos);
                    if (state.is(net.minecraft.world.level.block.Blocks.TNT)) {
                        level.destroyBlock(targetPos, false);
                        // 创建爆炸
                        level.explode(null, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5,
                                4.0f, net.minecraft.world.level.Level.ExplosionInteraction.TNT);
                    }
                }
            }
        }
    }
    player.sendMessage(
        net.minecraft.network.chat.Component.literal("§c✔ 引爆熔炉，制造混乱！总时间增加15秒！"),
        false);
})
```

- [ ] **Step 4: Build to verify compilation in companion mod**

Run: `cd "D:/Backup/mc mod/哈比列车更多修改" && ./gradlew clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit in companion mod**

```bash
cd "D:/Backup/mc mod/哈比列车更多修改"
git add -A && git commit -m "refactor: update blackout tasks for new timer system (coal -30s, sabotage transient + -15s, furnace +15s)"
```

---

### Task 10: 配套模组 — 新增维护供电任务

**Files:**
- Create: `D:\Backup\mc mod\哈比列车更多修改\src\main\java\com\habitrain\moretasks\game\blackout\MaintainPowerTask.java`
- Modify: `D:\Backup\mc mod\哈比列车更多修改\src\main\java\com\habitrain\moretasks\HabiTrainMoreTasks.java`

- [ ] **Step 1: Create MaintainPowerTask.java**

```java
package com.habitrain.moretasks.game.blackout;

import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

import java.awt.Color;

public class MaintainPowerTask {
    public static void register() {
        TaskRegistry.register("habitrain_more_tasks", "maintain_power", builder -> builder
            .displayName("维护供电")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(1.0f)
            .blockTypeId(-1)
            .instinctColor(new Color(0, 200, 255, 200))
            .scanBlocks(Blocks.REDSTONE_LAMP)  // 红石灯作为目标方块
            .onComplete((player, task) -> {
                BlackoutTimerSystem.delayMaintenanceOrCountdown(15);
                player.sendMessage(
                    Component.literal("§a✔ 维护了供电系统，供电时间延长15秒！"),
                    false);
            })
            .completionChecker((player, task) -> task.isFulfilled())
            .build()
        );
    }
}
```

- [ ] **Step 2: 在 HabiTrainMoreTasks.java 中注册**

```java
// 在停电模式任务注册区域添加（第 299 行附近）
com.habitrain.moretasks.game.blackout.MaintainPowerTask.register();
```

- [ ] **Step 3: Build to verify compilation in companion mod**

Run: `cd "D:/Backup/mc mod/哈比列车更多修改" && ./gradlew clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit in companion mod**

```bash
cd "D:/Backup/mc mod/哈比列车更多修改"
git add -A && git commit -m "feat: add maintain_power task (+15s maintenance time during maintenance phase)"
```

---

### Task 11: 最终构建 + JAR 分发

- [ ] **Step 1: 构建核心 mod**

```bash
cd "D:/Backup/mc mod/哈比列车api" && ./gradlew clean build
```

- [ ] **Step 2: 复制 JAR 到临时目录 + 配套 mod 的 libs**

```bash
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/临时/"
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/哈比列车更多修改/libs/"
```

- [ ] **Step 3: 构建配套 mod**

```bash
cd "D:/Backup/mc mod/哈比列车更多修改" && ./gradlew clean build
```

- [ ] **Step 4: 复制配套 mod JAR**

```bash
cp build/libs/habitrain_more_tasks-*.jar "D:/Backup/mc mod/临时/"
```

- [ ] **Step 5: 确认构建成功 + 列出临时目录内容**
