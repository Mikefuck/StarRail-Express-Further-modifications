# Blackout 模式杀手分配修复与优化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Blackout 模式开局无杀手导致游戏立即结束的 bug，并将杀手分配公式从 25% 改为 ceil(玩家数/6)

**Architecture:** 在 `SREBlackoutGameMode.initializeGame()` 中同步调用 `BlackoutRoleManager.initRandomAssignment()`（SRE 检查胜负前），替代原来在 `BlackoutMode.onTick()` 中异步等待的调用，同时将公式改为固定整数公式而非比例

**Tech Stack:** Java 21, Fabric 1.21.1, SRE API (starrailexpress)

## Global Constraints

- 所有修改在 `D:\Backup\mc mod\哈比列车api\` 项目内完成
- 杀手数量公式: `Math.ceil(players.size() / 6.0)`，至少 1 名
- SRE 层面保持全员 CIVILIAN 不变 — 不给杀手发 SRE 刀
- 胜利条件、投票机制、计时器、TACZ 桥接均不做改动
- 修改完成后必须执行 `./gradlew clean build` 并拷贝 JAR 到 `D:\Backup\mc mod\临时\`

---

### Task 1: 修改 BlackoutRoleManager 的分配公式

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java` (lines 101-123)

**Interfaces:**
- Consumes: 无（此任务独立）
- Produces: `public static void initRandomAssignment(List<ServerPlayer> players)` — 新签名，用 `ceil(size/6)` 公式替换原来的 `float badRatio`

- [ ] **Step 1: 修改 `initRandomAssignment()` 方法签名和实现**

将：
```java
public static void initRandomAssignment(List<ServerPlayer> players, float badRatio) {
    clear();
    List<ServerPlayer> shuffled = new ArrayList<>(players);
    Collections.shuffle(shuffled);

    int badCount = Math.max(1, (int)(shuffled.size() * badRatio));

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

改为：
```java
public static void initRandomAssignment(List<ServerPlayer> players) {
    clear();
    List<ServerPlayer> shuffled = new ArrayList<>(players);
    Collections.shuffle(shuffled);

    int killerCount = Math.max(1, (int) Math.ceil(shuffled.size() / 6.0));

    for (int i = 0; i < shuffled.size(); i++) {
        UUID id = shuffled.get(i).getUUID();
        if (i < killerCount) {
            assignRole(id, RoleType.KILLER, Faction.BAD);
        } else {
            assignRole(id, RoleType.CIVILIAN, Faction.GOOD);
        }
    }
    LOGGER.info("BlackoutRoleManager: Assigned {} KILLER / {} CIVILIAN ({} players, formula n/6 ceil)",
            killerCount, shuffled.size() - killerCount, shuffled.size());
}
```

- [ ] **Step 2: 确认无其他调用方**

检查 `initRandomAssignment` 在项目中是否还有别的调用方（除了我们下一步要改的 `BlackoutMode.onTick()`）。

Run: `grep -rn "initRandomAssignment" src/`

Expected: 只在 `BlackoutMode.java` 和 `BlackoutRoleManager.java` 中出现。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java
git commit -m "refactor: change BlackoutRoleManager.initRandomAssignment to ceil(n/6) formula"
```

---

### Task 2: 在 SREBlackoutGameMode 初始化时分配角色

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/sre/SREBlackoutGameMode.java` (lines 40-57)

**Interfaces:**
- Consumes: `BlackoutRoleManager.initRandomAssignment(List<ServerPlayer>)` — 上一步产出
- Produces: 在 SRE 游戏初始化阶段即完成角色分配，供 `BlackoutMode.checkVictory()` 在后续 tick 中读取

- [ ] **Step 1: 在 `initializeGame()` 中添加 `BlackoutRoleManager.initRandomAssignment()` 调用**

```java
package com.habitrain.core.game.blackout.sre;

import com.habitrain.core.game.blackout.BlackoutRoleManager;
// ... 其他 import 保持不变

@Override
public void initializeGame(ServerLevel world, SREGameWorldComponent game,
                           List<ServerPlayer> players) {
    Harpymodloader.refreshRoles();
    game.clearRoleMap();

    addPlayersToTeam(world.getServer().createCommandSourceStack(), players, "harpymodloader_game");
    executeFunction(world.getServer().createCommandSourceStack(), "harpymodloader:start_game");

    // ==== 在此处分配 Blackout 角色（SRE 检查胜利条件之前） ====
    BlackoutRoleManager.initRandomAssignment(players);

    // 所有玩家在 SRE 层面分配为 CIVILIAN（不赋予杀手能力）
    for (ServerPlayer player : players) {
        game.addRole(player, TMMRoles.CIVILIAN, false);
    }
    game.syncRoles();
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/sre/SREBlackoutGameMode.java
git commit -m "fix: assign Blackout roles in SREBlackoutGameMode.initializeGame before SRE checks"
```

---

### Task 3: 清理 BlackoutMode.onTick 中的重复分配调用

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java` (lines 134-148)

**Interfaces:**
- Consumes: 无（此任务是清理）
- Produces: 干净的 `onTick()` 入口块，不再有角色分配调用

- [ ] **Step 1: 删除 `onTick()` 中的角色分配调用**

查找以下代码块（`sreActive && !sreGameRunning` 块内）：
```java
if (sreActive && !sreGameRunning) {
    // 独立分配阵营（不再依赖 SRE 角色同步）
    sreGameRunning = true;
    sreStartAttempted = false;
    var server = level.getServer();
    if (server != null) {
        BlackoutRoleManager.initRandomAssignment(
                server.getPlayerList().getPlayers(), 0.25f);
    }

    // 通知 HUD 显示
    try {
        var cls = Class.forName("com.habitrain.core.client.gui.BlackoutHudOverlay");
        cls.getMethod("setVisible", boolean.class).invoke(null, true);
    } catch (Exception ignored) {}
}
```

删除 `var server = ...` 到 `}` 的角色分配块，保留 `sreGameRunning = true`、`sreStartAttempted = false` 和 HUD 通知：

```java
if (sreActive && !sreGameRunning) {
    sreGameRunning = true;
    sreStartAttempted = false;

    // 通知 HUD 显示
    try {
        var cls = Class.forName("com.habitrain.core.client.gui.BlackoutHudOverlay");
        cls.getMethod("setVisible", boolean.class).invoke(null, true);
    } catch (Exception ignored) {}
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java
git commit -m "fix: remove duplicate BlackoutRoleManager.initRandomAssignment from onTick (now in SREBlackoutGameMode)"
```

---

### Task 4: Build & Deploy

- [ ] **Step 1: Build 核心 mod**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew clean build
```

Expected: `BUILD SUCCESSFUL`，JAR 生成在 `build/libs/`

- [ ] **Step 2: 复制 JAR 到临时文件夹**

```bash
cp build/libs/*.jar "D:/Backup/mc mod/临时/"
```

- [ ] **Step 3: 构建 companion mod（如果依赖核心 mod）**

```bash
cd "D:/Backup/mc mod/哈比列车更多修改"
# 如果依赖更新后的核心 JAR，先复制 core JAR 到 libs/
cp "D:/Backup/mc mod/哈比列车api/build/libs/*.jar" libs/
./gradlew clean build
cp build/libs/*.jar "D:/Backup/mc mod/临时/"
```

- [ ] **Step 4: Final commit**

```bash
cd "D:/Backup/mc mod/哈比列车api"
git add -A
git commit -m "chore: build artifacts after blackout killer fix"
```
