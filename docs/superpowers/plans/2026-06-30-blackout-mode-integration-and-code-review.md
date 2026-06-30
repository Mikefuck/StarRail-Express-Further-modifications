# 停电模式接入修复 + 代码质量提升 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为停电模式接入 SRE 原版开局安全时间、修复 VoteScreen 单人测试兼容性、增强 /habi_api stop 命令使其完整接入 SRE 游戏生命周期、并按优先级修复 13 项代码审视发现的问题。

**Architecture:** 改动分布在 3 个层面：(1) `SREBlackoutGameMode` — 开关类 SRE 行为；(2) `BlackoutMode` + `GameModeRegistry` — 游戏生命周期管理；(3) `VoteScreen` — 客户端 GUI 兼容。代码审视修复按 严重→中度→轻微 三阶段执行，阶段 1 与功能修复并行。

**Tech Stack:** Fabric 1.21.1, Java 21, SRE API, Minecraft Protocol (ClientboundSetTitleTextPacket)

## Global Constraints

- 所有服务端代码只能使用 Minecraft vanilla 协议包或 Fabric API，不能反射调客户端类（F4 在修复中）
- `GameModeRegistry.stop()` 后必须设置 SRE 游戏状态为 STOPPING
- `WinResult` 已存在，见 `com.habitrain.core.api.WinResult`，无 `forceEnd()` 方法 — 使用 `WinResult.noWinner("管理员终止")`
- `SREGameWorldComponent.GameStatus.STOPPING` 已确认存在
- `SREGameWorldComponent.clearRoleMap()` 已确认存在
- VoteScreen 改动涉及客户端类 `com.habitrain.core.client.gui.VoteScreen`
- 所有修改后必须运行 `./gradlew clean build` 验证编译通过
- 禁止访问 `D:\Backup\mc mod\backup\` 目录

---

## 文件改动总览

| 文件路径 | 改动类型 | 负责 |
|---------|---------|------|
| `src/main/java/com/habitrain/core/game/blackout/sre/SREBlackoutGameMode.java` | 修改 | Task 3 |
| `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java` | 修改 | Task 2, Task 3, Task 5, Task 6 |
| `src/main/java/com/habitrain/core/api/GameModeRegistry.java` | 修改 | Task 1, Task 2 |
| `src/main/java/com/habitrain/core/HabiTrainCore.java` | 修改 | Task 2 |
| `src/main/java/com/habitrain/core/api/WinResult.java` | 修改 | Task 1 |
| `src/main/java/com/habitrain/core/client/gui/VoteScreen.java` | 修改 | Task 4 |
| `src/main/java/com/habitrain/core/game/sre/SREMurderMode.java` | 修改 | Task 7 |
| `src/main/java/com/habitrain/core/game/sre/SERepairMode.java` | 修改 | Task 7 |
| `src/main/java/com/habitrain/core/game/blackout/BlackoutVotingEngine.java` | 修改 | Task 7 |
| `src/main/java/com/habitrain/core/game/blackout/BlackoutTimerSystem.java` | 修改 | Task 6 |

---

### Task 1: 增强 GameModeRegistry 支持 WinResult 参数 + 添加 WinResult.forceEnd()

**Files:**
- Modify: `src/main/java/com/habitrain/core/api/GameModeRegistry.java:70-78`
- Modify: `src/main/java/com/habitrain/core/api/WinResult.java`

**Interfaces:**
- Consumes: `WinResult` (现有类)
- Produces: `GameModeRegistry.stop(ServerLevel, WinResult)` 新重载；`WinResult.forceEnd(String)` 新工厂方法

- [ ] **Step 1: 给 WinResult 添加 forceEnd() 工厂方法**

```java
// WinResult.java — 添加在 noWinner() 旁边
public static WinResult forceEnd(String reason) {
    return new WinResult(List.of(), reason);
}
```

- [ ] **Step 2: 新增 GameModeRegistry.stop(level, result) 重载**

```java
// GameModeRegistry.java — 新增方法
public static void stop(ServerLevel level, WinResult result) {
    ResourceKey<Level> levelKey = level.dimension();
    GameMode mode = ACTIVE_MODES.remove(levelKey);
    if (mode != null) {
        mode.onEnd(level, result);
        mode.onCleanup(level);
        LOGGER.info("Stopped GameMode: {} in {} (result: {})",
                mode.getId(), levelKey.location(), result.getReason());
    }
}
```

- [ ] **Step 3: 修改原 stop(level) 调用新重载以兼容**

```java
// GameModeRegistry.java — 原 stop(level) 改为
public static void stop(ServerLevel level) {
    stop(level, WinResult.forceEnd("管理员终止"));
}
```

- [ ] **Step 4: 编译验证**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/habitrain/core/api/GameModeRegistry.java src/main/java/com/habitrain/core/api/WinResult.java
git commit -m "fix: enhance GameModeRegistry.stop to accept WinResult, add WinResult.forceEnd"
```

---

### Task 2: 添加 forceEndGame + /habi_api stop 集成

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java`

**Interfaces:**
- Consumes: `GameModeRegistry.stop(ServerLevel, WinResult)` (Task 1), `SREGameWorldComponent.KEY`
- Produces: `BlackoutMode.forceEndGame(WinResult, String)` 公开方法

- [ ] **Step 1: BlackoutMode 添加 forceEndGame()**

```java
// BlackoutMode.java — 新公开方法
/**
 * 强制终止当前游戏（由 /habi_api stop 或管理员命令触发）。
 * 完整走一遍 SRE 停服 + GameModeRegistry.stop 流程。
 */
public void forceEndGame(WinResult result, String message) {
    if (gameEnded) return;
    gameEnded = true;
    sreGameRunning = false;

    if (currentLevel != null) {
        broadcast(message);

        try {
            var sreGame = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(currentLevel);
            if (sreGame != null) {
                sreGame.setGameStatus(
                    io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.STOPPING);
                sreGame.clearRoleMap();
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("forceEndGame: failed to stop SRE game", e);
        }

        GameModeRegistry.stop(currentLevel, result);
    }
}
```

- [ ] **Step 2: 修改 /habi_api stop 命令处理**

```java
// HabiTrainCore.java — 找到 .then(Commands.literal("stop")) 的 .executes 块，替换为：
.executes(ctx -> {
    ServerLevel level = ctx.getSource().getLevel();
    var active = GameModeRegistry.getActiveForLevel(level);
    if (active.isPresent() && active.get() instanceof BlackoutMode bm) {
        bm.forceEndGame(WinResult.forceEnd("管理员终止"),
                "§c⏹ 游戏已被管理员终止");
    } else {
        GameModeRegistry.stop(level);
    }
    ctx.getSource().sendSuccess(
            () -> Component.literal("§c⏹ 当前游戏模式已停止"), true);
    return 1;
})
```

- [ ] **Step 3: 在 BlackoutMode.java 顶部添加 import**

```java
// 确保已有:
import com.habitrain.core.api.WinResult;
import com.habitrain.core.api.GameModeRegistry;
```

- [ ] **Step 4: 编译验证**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java src/main/java/com/habitrain/core/HabiTrainCore.java
git commit -m "fix: add BlackoutMode.forceEndGame, integrate /habi_api stop with SRE lifecycle"
```

---

### Task 3: 开局接入 — hasSafeTime + Title 显示角色

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/sre/SREBlackoutGameMode.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`

**Interfaces:**
- Consumes: `BlackoutRoleManager.getFaction(UUID)`, `BlackoutRoleManager.getRole(UUID)`
- Produces: 服务端向所有在线玩家发送 `ClientboundSetTitleTextPacket` + `ClientboundSetSubtitleTextPacket`

- [ ] **Step 1: SREBlackoutGameMode.hasSafeTime() 返回 true**

```java
// SREBlackoutGameMode.java — 第 78 行
@Override
public boolean hasSafeTime() {
    return true;  // 开启 SRE 原版安全时间（无敌+无碰撞+音效）
}
```

- [ ] **Step 2: BlackoutMode.onStart() 末尾添加 Title 发逻辑**

```java
// BlackoutMode.java — onStart() 末尾
@Override
public void onStart(ServerLevel level) {
    // ... 现有 TACZ 注册和 SRE 启动代码保持不变 ...

    // ★ 新: 安全时间角色显示
    if (level.getServer() != null) {
        scheduleRoleTitle(level);
    }
}

// 新增方法:
private void scheduleRoleTitle(ServerLevel level) {
    // 延迟 5 tick (~0.25s) 等 SRE 安全时间初始化完成后发送自定义 Title
    // 避免与 SRE 原生 Title 竞争
    level.getServer().execute(() -> {
        level.getServer().execute(() -> {
            level.getServer().execute(() -> {
                level.getServer().execute(() -> {
                    level.getServer().execute(() -> {
                        sendRoleTitles(level);
                    });
                });
            });
        });
    });
}

private void sendRoleTitles(ServerLevel level) {
    for (ServerPlayer player : level.players()) {
        if (!BlackoutRoleManager.isAlive(player.getUUID())) continue;

        var role = BlackoutRoleManager.getRole(player.getUUID());
        var faction = BlackoutRoleManager.getFaction(player.getUUID());

        String roleName;
        String subtitle;

        switch (role) {
            case KILLER -> {
                roleName = "黑化杀手";
                subtitle = "§7坏人阵营 — 破坏列车，消灭好人";
            }
            case SHERIFF -> {
                roleName = "警长";
                subtitle = "§7好人阵营 — 维护秩序，保护列车";
            }
            default -> {
                roleName = "黑化平民";
                subtitle = "§7好人阵营 — 完成好人任务，存活到最后";
            }
        }

        String titleText = "§6§l你是 " + roleName;
        player.connection.send(
            new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                net.minecraft.network.chat.Component.literal(titleText)));
        player.connection.send(
            new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                net.minecraft.network.chat.Component.literal(subtitle)));
        player.connection.send(
            new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 60, 20));
    }
}
```

- [ ] **Step 3: BlackoutMode.java 顶部添加 import**

```java
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
```

- [ ] **Step 4: 编译验证**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/habitrain/core/game/blackout/sre/SREBlackoutGameMode.java src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java
git commit -m "feat: enable SRE safe time for blackout mode, add custom role title on game start"
```

---

### Task 4: VoteScreen — 单人测试兼容 + isAlive 过滤

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/gui/VoteScreen.java`

- [ ] **Step 1: 修改 isAlive 过滤和单人兼容**

`VoteScreen.java` 第 76-93 行 `render()` 方法和第 107-118 行 `mouseClicked()` 方法需要修改：

```java
// render() 方法中 — 替换第 76-93 行
var players = Minecraft.getInstance().level.players();
var self = Minecraft.getInstance().player;
boolean isAlone = players.size() <= 1;
int y = 55;
for (var player : players) {
    if (player.getUUID().equals(self.getUUID())) continue;

    // ★ 新: 只显示停电模式存活玩家（或单人模式全部显示）
    if (!isAlone && !com.habitrain.core.client.gui.BlackoutHudOverlay.isBlackoutModeActive()) continue;

    boolean hovered = mx >= LIST_X && mx < LIST_X + LIST_W && my >= y && my < y + ENTRY_H;
    boolean selected = player.getUUID().equals(selectedTarget);
    // ... 其余渲染代码不变 ...
}
```

以及 `mouseClicked()` 相应修改：
```java
// mouseClicked() 中 — 替换第 107-118 行
var players = Minecraft.getInstance().level.players();
var self = Minecraft.getInstance().player;
boolean isAlone = players.size() <= 1;
int y = 55;
for (var player : players) {
    if (player.getUUID().equals(self.getUUID())) continue;
    if (!isAlone && !com.habitrain.core.client.gui.BlackoutHudOverlay.isBlackoutModeActive()) continue;
    if (mx >= LIST_X && mx < LIST_X + LIST_W && my >= y && my < y + ENTRY_H) {
        selectedTarget = player.getUUID();
        return true;
    }
    y += ENTRY_H;
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/habitrain/core/client/gui/VoteScreen.java
git commit -m "fix: VoteScreen filter by blackout active, single-player self-display"
```

---

Phase 1 功能修复完成。接下来是 Phase 2 代码质量问题。

---

### Task 5: 移除服务端反射调客户端 BlackoutHudOverlay (F4)

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`

**问题：** `onTick()` 第 141-143 行、`onEnd()` 第 215-217 行、`onCleanup()` 第 229-231 行使用 `Class.forName("com.habitrain.core.client.gui.BlackoutHudOverlay")` 和反射调 `setVisible()`。

**方案：** 客户端 HUD 显示状态已由 `BlackoutTimerPayload` 和 `BlackoutStatusPayload` 网络包驱动。只需移除这些反射调用。

- [ ] **Step 1: 删除 onTick() 中的反射调用**

将 `BlackoutMode.onTick()` 中第 140-143 行：
```java
// 通知 HUD 显示
try {
    var cls = Class.forName("com.habitrain.core.client.gui.BlackoutHudOverlay");
    cls.getMethod("setVisible", boolean.class).invoke(null, true);
} catch (Exception ignored) {}
```
替换为：
```java
// HUD 显示由 BlackoutTimerPayload 网络包驱动，无需服务端反射调客户端
HabiTrainCore.LOGGER.info("BlackoutMode: SRE game running, HUD will activate via network sync");
```

- [ ] **Step 2: 删除 onEnd() 中的反射调用**

将 `BlackoutMode.onEnd()` 中第 215-217 行：
```java
try {
    var cls = Class.forName("com.habitrain.core.client.gui.BlackoutHudOverlay");
    cls.getMethod("setVisible", boolean.class).invoke(null, false);
} catch (Exception ignored) {}
```
替换为：
```java
// HUD 隐藏由 `onCleanup` 的 `BlackoutTimerSystem.reset()` 后，
// 下次 BlackoutTimerPayload 广播时会带上总时间=0，客户端自行隐藏
```

- [ ] **Step 3: 删除 onCleanup() 中的反射调用**

将 `BlackoutMode.onCleanup()` 中第 229-231 行：
```java
try {
    var cls = Class.forName("com.habitrain.core.client.gui.BlackoutHudOverlay");
    cls.getMethod("setVisible", boolean.class).invoke(null, false);
} catch (Exception ignored) {}
```
替换为（注意保留其他清理逻辑）：
```java
// HUD 隐藏已由网络包驱动
```

- [ ] **Step 4: 确保客户端 BlackoutHudOverlay 在网络包到来时自我隐藏**

检查 `HabiTrainCoreClient.java` 中的 `BlackoutTimerPayload` 处理器 — 当 `totalTimeRemaining <= 0` 或 `phase` 指示游戏结束时，客户端应自行隐藏 HUD。

- [ ] **Step 5: 编译验证**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java
git commit -m "refactor: remove Class.forName reflection to client HUD class, use network-driven display"
```

---

### Task 6: 修复 timer/victory 同步问题 + Javadoc 完善 (F5, F7, F8)

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutTimerSystem.java`

- [ ] **Step 1: BlackoutTimerSystem.tickSecond() 添加调用约定 javadoc**

```java
// BlackoutTimerSystem.java — tickSecond() 上方
/**
 * 每秒更新一次计时器状态。
 * <p>
 * <strong>调用约定：</strong>此方法必须由 {@code BlackoutMode.onTick()} 驱动，
 * 且 {@code onTick()} 应保证每秒恰好调用一次本方法。
 * 调用后应在同一 tick 内调用 {@code BlackoutMode.checkVictory()} 以确保时间归零和
 * 胜利判定在同一帧完成。
 */
public static void tickSecond() {
    // ... 现有代码不变 ...
}
```

- [ ] **Step 2: BlackoutMode.onTick() 保证 tickSecond + checkVictory 同一 tick**

`BlackoutMode.onTick()` 中，确保 `tickAccumulator % 20 == 0` 块内的调用顺序为：
1. `BlackoutTimerSystem.tickSecond()`
2. 投票阶段检查
3. `BlackoutVotingEngine.tickVoting()`
4. **`checkVictory()` — 紧接在 timer tick 之后**
5. 广播时间同步

检查现有代码是否已保证此顺序（应该是的），无需代码改动。只需在关键位置加注释明确约定。

- [ ] **Step 3: 编译验证 + 提交**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build 2>&1 | tail -20
git add src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java src/main/java/com/habitrain/core/game/blackout/BlackoutTimerSystem.java
git commit -m "docs: add tickSecond calling convention javadoc, document checkVictory ordering"
```

---

### Task 7: 修复 SREMurderMode.isActive + 重命名 SERepairMode (F9, F10)

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/sre/SREMurderMode.java`
- Modify: `src/main/java/com/habitrain/core/game/sre/SERepairMode.java`
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java` (引用改名)

- [ ] **Step 1: SREMurderMode.isActive 用精确匹配替代启发式**

```java
// SREMurderMode.java — isActive() 内第 33 行
// 改前:
return !modeId.contains("repair");
// 改后:
return "sre:murder".equals(modeId) || (!modeId.contains("repair") && !modeId.contains("blackout"));
```

- [ ] **Step 2: SERepairMode → SRERepairMode 重命名**

```java
// SERepairMode.java — 类声明
// 改前:
public class SERepairMode extends SREGameModeBase {
// 改后:
public class SRERepairMode extends SREGameModeBase {
```

并更新 `HabiTrainCore.java` 中的引用：
```java
// HabiTrainCore.java — 第 62 行
// 改前:
GameModeRegistry.register(MOD_ID, "sre:repair", new SERepairMode());
// 改后:
GameModeRegistry.register(MOD_ID, "sre:repair", new SRERepairMode());
```

- [ ] **Step 3: 编译验证**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL
（注意：如果 companion mod 或其他地方引用了 `SERepairMode`，需要一并修改）

- [ ] **Step 4: 检查 companion mod 引用**

```bash
cd "D:\Backup\mc mod\哈比列车api"
grep -r "SERepairMode" ../哈比列车更多修改 --include="*.java" 2>/dev/null || echo "No cross references"
```

(如有引用，需同步修改 companion mod)

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/habitrain/core/game/sre/SREMurderMode.java src/main/java/com/habitrain/core/game/sre/SERepairMode.java src/main/java/com/habitrain/core/HabiTrainCore.java
git commit -m "fix: precise modeId matching in SREMurderMode.isActive, rename SERepairMode to SRERepairMode"
```

---

### Task 8: tickVoting +=20 修复 + 静态管理注释 (F11, F12)

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutVotingEngine.java`
- Modify: `src/main/java/com/habitrain/core/client/gui/BlackoutHudOverlay.java`

- [ ] **Step 1: BlackoutVotingEngine.tickVoting() 使用 += 1**

```java
// BlackoutVotingEngine.java — 第 59 行
// 改前:
windowElapsedTicks += 20; // +1 秒
// 改后:
windowElapsedTicks++;  // +1 tick（caller 保证每秒调一次，但实际累加 tick）
```

同时调整 `WINDOW_DURATION_TICKS` 常量：
```java
// 改前:
private static final int WINDOW_DURATION_TICKS = 600; // 30s × 20 tick
// 改后:
private static final int WINDOW_DURATION_TICKS = 600; // 600 tick = 30s（在 onTick 每秒调一次）
```

以及 `tickVoting()` 中第 59 行后的剩余时间计算保持不变（已为 ticks）。

- [ ] **Step 2: BlackoutHudOverlay 加 static 限制注释**

```java
// BlackoutHudOverlay.java — 类声明上方
/**
 * 停电模式 HUD 覆盖层。
 * <p>
 * <strong>注意：</strong>此类使用全局 static 变量，不支持多世界。
 * 当前设计假设同一时刻只有一个停电模式游戏运行，
 * 若需多 world/多实例支持，需改为实例化设计。
 */
public class BlackoutHudOverlay {
```

- [ ] **Step 3: 编译验证 + 提交**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build 2>&1 | tail -20
git add src/main/java/com/habitrain/core/game/blackout/BlackoutVotingEngine.java src/main/java/com/habitrain/core/client/gui/BlackoutHudOverlay.java
git commit -m "refactor: fix tickVoting increment semantics, add static limitation note to HUD"
```

---

### Task 9: 消除 reapplyPermanentBlackout 中反射访问 SRE 私有字段 (F6)

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`

**问题：** `reapplyPermanentBlackout()` 第 270 行反射访问 `blackout.getClass().getDeclaredField("blackouts")`。

**方案：** 改用一个简单防反弹策略 — 定期（每 2s）直接调 `triggerBlackout(false, 60000)`，SRE API 允许重复触发同样的停电。移除反射代码。

- [ ] **Step 1: 简化 reapplyPermanentBlackout**

```java
// BlackoutMode.java — 替换 reapplyPermanentBlackout()
private void reapplyPermanentBlackout() {
    if (currentLevel == null) return;
    try {
        var blackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(currentLevel);
        if (blackout != null) {
            // 简单的周期性重新触发（SRE API 允许重复触发）
            blackout.triggerBlackout(false, 60000);
            HabiTrainCore.LOGGER.debug("Re-applied permanent blackout via API (periodic push)");
        }
    } catch (Exception e) {
        HabiTrainCore.LOGGER.error("Failed to reapply blackout", e);
    }
}
```

删除 `getDeclaredField("blackouts")` 的反射块。

- [ ] **Step 2: 编译验证 + 提交**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build 2>&1 | tail -20
git add src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java
git commit -m "fix: replace reflective SRE field access with API call in reapplyPermanentBlackout"
```

---

### Task 10: 最终构建 + 完整性验证

- [ ] **Step 1: 完整构建**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 如有 companion mod，同步 JAR 并编译**

```bash
cp build/libs/*.jar ../哈比列车更多修改/libs/
cd "../哈比列车更多修改"
./gradlew clean build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL (或说明无需编译)

- [ ] **Step 3: 提交所有剩余改动**

```bash
cd "D:\Backup\mc mod\哈比列车api"
git add -A
git commit -m "chore: final build after all phase 1-2 fixes"
```
