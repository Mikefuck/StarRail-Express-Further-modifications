# 停电模式 V2 — 独立阵营系统与消息清理 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清理停电模式消息中的图标 + 在 SRE 中注册自定义 GameMode，实现独立阵营分配

**Architecture:** companion mod (`哈比列车更多修改`) 向 SRE 注册一个新 `GameMode`（所有人分配为 CIVILIAN）；core mod (`哈比列车api`) 的 `BlackoutMode` 改启动目标到新模式，移除 `syncRolesFromSRE()`，由 `BlackoutRoleManager.initRandomAssignment()` 独立分配 GOOD/BAD 阵营。

**Tech Stack:** Minecraft 1.21.1 Fabric, SRE (StarRail Express) API, TACZ Refabricated

## Global Constraints

- 不修改 SRE 模组源码
- 不修改现有任务系统、TACZ 桥接、投票引擎、计时系统、网络包、客户端 HUD
- companion mod 通过 `SREGameModes.registerGameMode()` 注册新模式
- companion mod 通过 `compileOnly files("libs/star_rail_express-4.2.0.jar")` 依赖 SRE
- companion mod 通过 `modImplementation files("libs/habitrain_core-2.0.0.jar")` 依赖 core mod
- 构建顺序: core mod 先 → companion mod 后（需要新 core JAR）

---

### Task 1: 消息清理 — 删除启动广播 + 去掉图标（core mod）

**Files:**
- Modify: `D:\Backup\mc mod\哈比列车api\src\main\java\com\habitrain\core\game\blackout\BlackoutMode.java`

**Interfaces:**
- Consumes: (none — standalone text change)
- Produces: (none — no API change)

- [ ] **Step 1: 删除 `onStart()` 中的启动广播消息**

定位 `onStart()` 方法（当前约 L80-L96），删除以下两行：
```java
broadcast("§e⚡ 停电模式已启动！总时间: 5:00");
broadcast("§7停电倒计时: 2:00 — 做好任务来应对停电！");
```

修改后 `onStart()` 变为：
```java
@Override
public void onStart(ServerLevel level) {
    // 注册 TACZ 子弹监听
    TACZWeaponBridge.register();

    // 启动 SRE 原版游戏 (地图重置、房间传送、角色分配等)
    var sreGame = SREGameWorldComponent.KEY.get(level);
    if (sreGame != null && !sreGame.isRunning()) {
        sreStartAttempted = true;
        sreStartWaitTicks = 0;
        GameUtils.startGame(level, io.wifi.starrailexpress.api.SREGameModes.MURDER,
                GameConstants.getInTicks(io.wifi.starrailexpress.api.SREGameModes.MURDER.defaultStartTime, 0));
    }
}
```
（注意：`GameUtils.startGame` 参数暂时保留原样，Task 5 会改）

- [ ] **Step 2: 去掉 `onPlayerJoin()` 中的图标**

定位 `onPlayerJoin()` 中的消息（约 L205-L206），将 `§e⚡` 改为 `§e`：

```java
player.sendSystemMessage(Component.literal(
    "§e当前游戏: 停电模式  剩余: §l" + formatTime(BlackoutTimerSystem.getTotalTimeRemaining()) + "§r"));
```

- [ ] **Step 3: 去掉 `triggerSREBlackout()` 中的图标**

定位 `triggerSREBlackout()` 方法（约 L251-L258），将 `§c⚡` 改为 `§c`：

```java
broadcast("§c停电了！黑暗笼罩一切...");
```

- [ ] **Step 4: 去掉 `endSREBlackout()` 中的图标**

定位 `endSREBlackout()` 方法（约 L260-L267），将 `§a⚡` 改为 `§a`：

```java
broadcast("§a供电已恢复");
```

- [ ] **Step 5: 去掉 `sendTimeWarning()` 中的图标**

定位 `sendTimeWarning()` 方法（约 L269-L271），将 `§e⚠` 改为 `§e`：

```java
broadcast("§e仅剩 1 分钟！");
```

- [ ] **Step 6: 构建验证（core mod）**

```powershell
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
cd "D:/Backup/mc mod/哈比列车api"
git add src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java
git commit -m "fix: remove startup broadcast and text icons from blackout mode messages"
```

---

### Task 2: 添加 `initRandomAssignment()` 到 BlackoutRoleManager（core mod）

**Files:**
- Modify: `D:\Backup\mc mod\哈比列车api\src\main\java\com\habitrain\core\game\blackout\BlackoutRoleManager.java`

**Interfaces:**
- Consumes: (none — standalone new method)
- Produces: `BlackoutRoleManager.initRandomAssignment(List<ServerPlayer>, float)` — later called by Task 5

- [ ] **Step 1: 添加 `initRandomAssignment()` 方法**

在 `BlackoutRoleManager.java` 末尾（`clear()` 方法之后），添加：

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

注意：需要在文件顶部添加 import：
```java
import net.minecraft.server.level.ServerPlayer;
```
以及 Logger：
```java
private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutRoleManager");
```
（如果还没有的话，检查文件顶部已有 `java.util.*` import 应已覆盖 `UUID`、`ArrayList`、`Collections`）

- [ ] **Step 2: 构建验证**

```powershell
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
cd "D:/Backup/mc mod/哈比列车api"
git add src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java
git commit -m "feat: add initRandomAssignment() to BlackoutRoleManager for independent faction assignment"
```

---

### Task 3: 创建 SREBlackoutGameMode（companion mod）

**Files:**
- Create: `D:\Backup\mc mod\哈比列车更多修改\src\main\java\com\habitrain\moretasks\game\blackout\sre\SREBlackoutGameMode.java`

**Interfaces:**
- Consumes: `SREGameModes.registerGameMode()` (SRE API), `TMMRoles.CIVILIAN` (SRE API)
- Produces: `SREBlackoutGameMode` 实例 — 由 Task 4 注册到 SRE，由 Task 5 运行时查找使用

- [ ] **Step 1: 创建包目录和文件**

```powershell
New-Item -ItemType Directory -Force -Path "D:\Backup\mc mod\哈比列车更多修改\src\main\java\com\habitrain\moretasks\game\blackout\sre"
```

- [ ] **Step 2: 写入 SREBlackoutGameMode.java**

```java
package com.habitrain.moretasks.game.blackout.sre;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.modes.SREMurderGameMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 停电模式专用的 SRE GameMode。
 * 所有玩家分配为 CIVILIAN（普通乘客），
 * 不赋予任何杀手能力/商店权限。
 * 阵营分配由 BlackoutRoleManager 独立完成。
 */
public class SREBlackoutGameMode extends SREMurderGameMode {

    public static final ResourceLocation MODE_ID =
            ResourceLocation.fromNamespaceAndPath("sre", "blackout");

    public SREBlackoutGameMode() {
        super(MODE_ID, 10, 1);
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
        return false;
    }

    @Override
    public boolean hasSafeTime() {
        return false;
    }

    @Override
    public boolean requiresAssignedRole() {
        return false;
    }
}
```

- [ ] **Step 3: 构建验证（companion mod）**

```powershell
cd "D:\Backup\mc mod\哈比列车更多修改"
./gradlew clean build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
cd "D:/Backup/mc mod/哈比列车更多修改"
git add src/main/java/com/habitrain/moretasks/game/blackout/sre/SREBlackoutGameMode.java
git commit -m "feat: create SREBlackoutGameMode with all-CIVILIAN role assignment"
```

---

### Task 4: 注册 SREBlackoutGameMode 到 SRE（companion mod）

**Files:**
- Modify: `D:\Backup\mc mod\哈比列车更多修改\src\main\java\com\habitrain\moretasks\HabiTrainMoreTasks.java`

**Interfaces:**
- Consumes: `SREBlackoutGameMode` (Task 3), `SREGameModes.registerGameMode()` (SRE API)
- Produces: SRE GameMode 在运行时可用 — 被 Task 5 的 BlackoutMode 查找调用

- [ ] **Step 1: 添加 import**

在 `HabiTrainMoreTasks.java` 的 import 区域添加：

```java
import com.habitrain.moretasks.game.blackout.sre.SREBlackoutGameMode;
import io.wifi.starrailexpress.api.SREGameModes;
```

- [ ] **Step 2: 在 `onInitialize()` 末尾添加注册代码**

在 `LOGGER.info("测试任务模组已加载！..." ...)` 之前添加：

```java
// ===== 注册停电模式专用的 SRE GameMode =====
SREGameModes.registerGameMode(new SREBlackoutGameMode());
LOGGER.info("停电模式 SRE GameMode 已注册");
```

- [ ] **Step 3: 构建验证**

```powershell
cd "D:\Backup\mc mod\哈比列车更多修改"
./gradlew clean build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
cd "D:/Backup/mc mod/哈比列车更多修改"
git add src/main/java/com/habitrain/moretasks/HabiTrainMoreTasks.java
git commit -m "feat: register SREBlackoutGameMode in SREGameModes"
```

---

### Task 5: 重构 BlackoutMode — 使用新 SREGameMode + 移除 syncRolesFromSRE + 添加自动阵营分配（core mod）

**Files:**
- Modify: `D:\Backup\mc mod\哈比列车api\src\main\java\com\habitrain\core\game\blackout\BlackoutMode.java`

**Interfaces:**
- Consumes: `SREGameModes.GAME_MODES` (SRE API), `SREBlackoutGameMode.MODE_ID` (Task 3), `BlackoutRoleManager.initRandomAssignment()` (Task 2)
- Produces: 清理后的 `BlackoutMode` — 不再依赖 SRE 角色同步

- [ ] **Step 1: 修改 `onStart()` — 改启动目标为 SREBlackoutGameMode**

将 `onStart()` 中的 `GameUtils.startGame(level, SREGameModes.MURDER, ...)` 替换为查找并启动 `SREBlackoutGameMode`：

```java
@Override
public void onStart(ServerLevel level) {
    // 注册 TACZ 子弹监听
    TACZWeaponBridge.register();

    // 查找 companion mod 注册的 SREBlackoutGameMode
    ResourceLocation blackoutModeId = ResourceLocation.fromNamespaceAndPath("sre", "blackout");
    GameMode sreMode = SREGameModes.GAME_MODES.get(blackoutModeId);
    if (sreMode == null) {
        LOGGER.error("SREBlackoutGameMode not found! Is habitrain_more_tasks loaded?");
        return;
    }

    // 启动 SRE 原版游戏 (地图重置、房间传送等)
    var sreGame = SREGameWorldComponent.KEY.get(level);
    if (sreGame != null && !sreGame.isRunning()) {
        sreStartAttempted = true;
        sreStartWaitTicks = 0;
        GameUtils.startGame(level, sreMode,
                GameConstants.getInTicks(
                    ((io.wifi.starrailexpress.api.GameMode)sreMode).defaultStartTime, 0));
    }
}
```

需要添加 import：
```java
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.game.modes.SREMurderGameMode;
```

- [ ] **Step 2: 在 `sreGameRunning` 触发时调用 `initRandomAssignment()`**

定位 `onTick()` 中 `sreGameRunning = true` 的位置（约 L121-L132），在 `syncRolesFromSRE(level)` 那一行之后（但 syncRolesFromSRE 即将删除），改为调用 `initRandomAssignment()`：

```java
if (sreActive && !sreGameRunning) {
    // SRE 游戏刚刚开始 → 独立分配阵营（不再依赖 SRE 角色同步）
    sreGameRunning = true;
    sreStartAttempted = false;
    BlackoutRoleManager.initRandomAssignment(
            level.getServer().getPlayerList().getPlayers(), 0.25f);
    // ... (保留 HUD 通知代码)
```

- [ ] **Step 3: 删除 `syncRolesFromSRE()` 方法**

删除整个 `syncRolesFromSRE(ServerLevel level)` 方法（约 L172-L196）：

```java
// 删除以下整个方法块：
/** 从 SRE 角色系统同步到我们的阵营/职业管理器 */
private void syncRolesFromSRE(ServerLevel level) {
    ...
}
```

- [ ] **Step 4: 删除 `onPlayerJoin()` 中的 `syncRolesFromSRE` 调用**

定位 `onPlayerJoin()`（约 L204-L212），删除或注释掉第 L209-L211 的重新同步逻辑：

```java
// 移除:
if (currentLevel != null && sreGameRunning) {
    syncRolesFromSRE(currentLevel);
}
```

- [ ] **Step 5: 构建验证（core mod）**

```powershell
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
cd "D:/Backup/mc mod/哈比列车api"
git add src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java
git commit -m "refactor: switch to SREBlackoutGameMode, remove syncRolesFromSRE, add independent role assignment"
```

---

### Task 6: 构建验证 + JAR 部署

**Files:**
- Build outputs: `D:\Backup\mc mod\哈比列车api\build\libs\habitrain_core-*.jar`
- Copy to: `D:\Backup\mc mod\哈比列车更多修改\libs\habitrain_core-2.0.0.jar`
- Build output: `D:\Backup\mc mod\哈比列车更多修改\build\libs\habitrain_more_tasks-*.jar`
- Copy to: `D:\Backup\mc mod\临时\`

- [ ] **Step 1: 构建 core mod**

```powershell
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 复制 core JAR 到 companion mod 的 libs**

```powershell
Copy-Item "D:\Backup\mc mod\哈比列车api\build\libs\habitrain_core-*.jar" "D:\Backup\mc mod\哈比列车更多修改\libs\habitrain_core-2.0.0.jar" -Force
```

- [ ] **Step 3: 构建 companion mod**

```powershell
cd "D:\Backup\mc mod\哈比列车更多修改"
./gradlew clean build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 复制两个 JAR 到临时目录**

```powershell
Copy-Item "D:\Backup\mc mod\哈比列车api\build\libs\habitrain_core-*.jar" "D:\Backup\mc mod\临时\" -Force
Copy-Item "D:\Backup\mc mod\哈比列车更多修改\build\libs\habitrain_more_tasks-*.jar" "D:\Backup\mc mod\临时\" -Force
```

- [ ] **Step 5: 提交所有未提交的更改**

```bash
cd "D:/Backup/mc mod/哈比列车api"
git add -A
git commit -m "build: verify and deploy blackout mode v2 JARs"
```

---

## 自审清单

- **Spec 覆盖:** 所有 spec 中的需求都有对应任务：
  - 消息清理 → Task 1
  - `SREBlackoutGameMode` 创建 → Task 3
  - 注册到 SRE → Task 4
  - `BlackoutMode` 调整 → Task 5
  - `BlackoutRoleManager.initRandomAssignment()` → Task 2
  - 构建验证 → Task 6

- **无占位符:** 所有任务均有完整代码和命令，无 TBD/TODO

- **类型一致性:**
  - `SREBlackoutGameMode` 使用 `ResourceLocation.fromNamespaceAndPath("sre", "blackout")` — Task 3 定义，Task 5 用同一 ID 查找
  - `BlackoutRoleManager.initRandomAssignment(List<ServerPlayer>, float)` — Task 2 定义，Task 5 调用
  - `SREGameModes.GAME_MODES.get(id)` — Task 3 注册到 map，Task 5 从 map 读取

- **构建顺序正确:** core mod (Task 1, 2, 5) → companion mod (Task 3, 4) → 最终联合构建 (Task 6)
