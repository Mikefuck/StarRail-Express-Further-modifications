# HabiTrain Core 质量修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 2026-07-09 全量质量审查报告，分批修复 S0/S1 正确性与性能问题，并清理死代码/边界/文档，使停电模式与全模式小游戏经济在 20 人服可稳定运行。

**Architecture:** 先合并已验证的 WIP（死亡 eliminate / 雇警 / 语音），再修全局正确性闸门（token、faction 历史、局终清理、计时、force/sync 奖励、二次停电、瞬时真断电），随后压缩 tick 与网络热点，最后删死栈与文档对齐。每批独立 commit + `./gradlew clean build` + 确认 JAR 进入 `D:\Backup\mc mod\临时\`。

**Tech Stack:** Fabric 1.21.1, Java 21, Fabric Loom, Mixin, Gson, SRE CCA

**Spec:** `docs/superpowers/specs/2026-07-09-habitrain-core-quality-audit-report.md`  
**Design:** `docs/superpowers/specs/2026-07-09-habitrain-core-quality-audit-design.md`

## Global Constraints

- **无测试源集** — 不要新增 `src/test`；验收 = `./gradlew clean build` 成功 + 文中 🎮 检查点（人工/开发客户端）
- **每批结束必须** `./gradlew clean build`，产物经 `copyReleaseJar`/`assemble` 到 `D:\Backup\mc mod\临时\habitrain_core-2.0.0.jar`（以实际 jar 名为准）
- **禁止** 访问 `D:\Backup\mc mod\backup\`
- **玩法数值默认不变**；改规则标 `needs-mike-decision` 并在任务内写死默认（与报告/历史决策一致）
- **不要写无意义注释**；除非修复意图非显然
- **commit 前缀：** `fix:` / `perf:` / `refactor:` / `docs:`
- **WIP 默认决策（实施时采用，Mike 可改口）：**
  1. 合并全部 WIP（含杀手可雇）
  2. killer-sheriff **占用** `police≤killer` cap（保持现 WIP）
  3. killer-sheriff **允许** `buy_gun`（保持 `isSheriff`；若 Mike 否决再收紧）
  4. 同步供电池：**不同步发奖**（对齐 20-issue #2/#3）
  5. 强制 betel 成瘾：**本批不改行为**（仅 R4 可选改为 config）
- **一次_revolver 缺失时：** 不要 fallback 满装 `trainmurdermystery:revolver`（改为日志 + 不加枪，或只发金币）

---

## File map（本计划主要触碰）

| 文件 | 职责 |
|------|------|
| `.../blackout/BlackoutDeathHandler.java` | WIP：OnPlayerDeath → eliminate（合并） |
| `.../blackout/BlackoutMode.java` | 局终清理、once-per-game invalidate、victory 入口 |
| `.../blackout/BlackoutRoleManager.java` | factionHistory；getFaction 修正 |
| `.../blackout/sre/SREBlackoutGameMode.java` | finalizeGame 用历史阵营 |
| `.../blackout/BlackoutVictoryChecker.java` | forceAssign 不 onComplete；二次停电分支 |
| `.../blackout/BlackoutTimerSystem.java` | 瞬时真断电；phase 查询 |
| `.../blackout/BlackoutSyncManager.java` | endTimeTick = gameTime + sec*20 |
| `.../client/gui/BlackoutHudOverlay.java` | 已用 gameTime；核对单位 |
| `.../task/TaskManager.java` | clear 含 dlcTaskCounts |
| `.../task/TaskPoolBuilder.java` | once-per-game invalidate |
| `.../task/GameLifecycleHandler.java` | 补 RestorePower clear |
| `.../task/SupplyTaskSyncHelper.java` | 同步无奖 |
| `.../game/sre/mixin/MinigameRewardMixin.java` | token 默认不覆盖 |
| `.../blackout/task/BlackoutLookMyEyesTask.java` | AABB |
| `.../ModTickHandler.java` | betel 门控 |
| `.../blackout/BlackoutPoliceHireService.java` | WIP + revolver fallback |
| `AGENTS.md` | payload 表 |

---

## Batch R0 — 正确性闸门

### Task 0: 冻结并提交 WIP（死亡/雇警/语音）

**Files:**
- Keep as-is (already modified):  
  `BlackoutDeathHandler.java` (untracked), `HabiTrainCore.java`, `BlackoutPoliceHireService.java`, `BlackoutRoleManager.java`, `BlackoutExileVoteManager.java`, `BlackoutHornVoteHandler.java`, `BlackoutMode.java`, `BlackoutPhoneHandler.java`, `BlackoutPhoneHireScreen.java`, `SREGameModeBase.java`, `SREWeatherController.java`

**Interfaces:**
- Produces: `BlackoutDeathHandler.register()`；`BlackoutMode.checkVictoryAfterExile(ServerLevel)`（package）；`BlackoutRoleManager.getRandomHireTarget(...)`

- [ ] **Step 1: 核对 WIP 完整性**

确认 working tree 仍含：
- `BlackoutDeathHandler` 注册于 `HabiTrainCore.onInitialize` / lifecycle
- `eliminate` 调 `BlackoutExileVoteManager.onPlayerRemoved`
- `checkVictoryAfterExile` 在 `BlackoutMode` 且 DeathHandler 同包可调用
- JOIN 用 `isAnySreGameStartingOrRunning`

Run: `git status --short`

- [ ] **Step 2: 收紧 revolver fallback（小改，同批提交）**

在 `BlackoutPoliceHireService.giveOnceRevolver`（或等价方法）：若 `noellesroles:once_revolver` 缺失，**不要** `new ItemStack(trainmurdermystery:revolver)`；改为 `LOGGER.warn` 并 return（金币奖励仍发）。

- [ ] **Step 3: Build**

```powershell
./gradlew clean build
```

Expected: `BUILD SUCCESSFUL`  
确认 `D:\Backup\mc mod\临时\` 出现更新后的 `habitrain_core-*.jar`

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/habitrain/core/HabiTrainCore.java `
  src/main/java/com/habitrain/core/game/blackout/ `
  src/main/java/com/habitrain/core/game/sre/SREGameModeBase.java `
  src/main/java/com/habitrain/core/game/sre/SREWeatherController.java `
  src/main/java/com/habitrain/core/client/gui/BlackoutPhoneHireScreen.java
git commit -m "fix(blackout): merge death eliminate, hire/voice hardening, no full-revolver fallback"
```

---

### Task 1: Minigame token 默认不覆盖（S6-01）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java`

**Interfaces:**
- Consumes: `MinigameConfigEntry` gold/emotion only
- Produces: token arg = original unless future config says replace

- [ ] **Step 1: 改 ModifyArg**

将：

```java
private int habitrain$overrideTokenReward(int originalReward) {
    try {
        return 0;
    } catch (Throwable t) {
        return originalReward;
    }
}
```

改为：

```java
private int habitrain$overrideTokenReward(int originalReward) {
    // 默认不替换 SRE token；自定义金币/情绪仍由 RETURN 注入发放
    return originalReward;
}
```

保留 `habitrain$applyHabiRewards` 中 `goldReward`/`emotionReward` 逻辑不变。

- [ ] **Step 2: Build + commit**

```powershell
./gradlew clean build
git add src/main/java/com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java
git commit -m "fix: stop zeroing minigame token rewards by default"
```

🎮：完成任一未配置/已配置小游戏，token 应恢复为 SRE 原值；配置了 goldReward 时额外加金币。

---

### Task 2: factionHistory — 结算不默认 GOOD（B2-06）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/sre/SREBlackoutGameMode.java`

**Interfaces:**
- Produces:
  - `BlackoutRoleManager.getHistoricalFaction(ServerLevel, UUID) -> Faction`（无历史返回 null）
  - `getFaction` 仅对**存活**语义使用；结算用 historical

- [ ] **Step 1: RoleState 增加 factionHistory**

在 `RoleState` 增加：

```java
final Map<UUID, Faction> factionHistory = new HashMap<>();
```

- [ ] **Step 2: 所有写入 factions 的路径同时写 history**

在 `assignRole`、`syncFactionsFromSreRoles`、`setSheriff(..., faction)` 中：

```java
state.factions.put(playerId, faction);
state.factionHistory.put(playerId, faction);
```

`setSheriff(level, id)` 仅加 sheriffs 时：若已有 faction，刷新 history：

```java
Faction f = state.factions.get(playerId);
if (f != null) state.factionHistory.put(playerId, f);
```

- [ ] **Step 3: eliminate 不删 history**

`eliminate` 继续 `factions/roles/sheriffs.remove`，**不要** `factionHistory.remove`。

- [ ] **Step 4: API**

```java
@Nullable
public static Faction getHistoricalFaction(ServerLevel level, UUID playerId) {
    return getOrCreate(level).factionHistory.get(playerId);
}
```

`getFaction` 改为：存活用 map；若已 eliminate，返回 historical（避免默认 GOOD）：

```java
public static Faction getFaction(ServerLevel level, UUID playerId) {
    RoleState state = getOrCreate(level);
    Faction live = state.factions.get(playerId);
    if (live != null) return live;
    Faction hist = state.factionHistory.get(playerId);
    return hist != null ? hist : Faction.GOOD; // 仅真正未知仍 GOOD，历史优先
}
```

更严可选：未知返回 null 并改所有调用方 — 优先最小改动用 historical 优先。

- [ ] **Step 5: finalizeGame**

`SREBlackoutGameMode.finalizeGame` 中：

```java
Faction f = BlackoutRoleManager.getHistoricalFaction(world, p.getUUID());
if (f == null) f = BlackoutRoleManager.getFaction(world, p.getUUID());
boolean didWin = (winner != null && f == winner);
```

- [ ] **Step 6: clear 时清 history**

`BlackoutRoleManager.clear` 已 remove 整个 RoleState — OK。

- [ ] **Step 7: Build + commit**

```powershell
./gradlew clean build
git add src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java `
  src/main/java/com/habitrain/core/game/blackout/sre/SREBlackoutGameMode.java
git commit -m "fix(blackout): keep faction history for dead players in settle screen"
```

🎮：杀手被杀后好人胜，结算该杀手应显示负/未胜，而非胜。

---

### Task 3: 局终统一清理 TaskManager + Restore + handlers（L1-01/02/05/03）

**Files:**
- Modify: `src/main/java/com/habitrain/core/task/TaskManager.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`
- Modify: `src/main/java/com/habitrain/core/task/GameLifecycleHandler.java`
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java`（SERVER_STOPPING 补清）
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/RestorePowerHandler.java`（确认 `resetCompleted`/`clearAll` 存在）

**Interfaces:**
- Produces: `TaskManager.clearAllActiveTasks()` 同时清 `dlcTaskCounts`
- Produces: `BlackoutMode.onCleanup` 调用完整 cleanup 序列

- [ ] **Step 1: TaskManager.clearAllActiveTasks**

```java
public void clearAllActiveTasks() {
    activeCustomTasks.clear();
    activeFakeTasks.clear();
    blackoutNextDailyPool.clear();
    dlcTaskCounts.clear();
}
```

- [ ] **Step 2: BlackoutMode.onCleanup 增加**

在现有 reset 之后、null currentLevel 之前：

```java
TaskManager.getInstance().clearAllActiveTasks();
com.habitrain.core.game.blackout.task.RestorePowerHandler.resetCompleted();
com.habitrain.core.game.blackout.task.RestorePowerHandler.clearAll(); // 若无则实现为 clear activeStates
com.habitrain.core.game.blackout.task.AddCoalHandler.clearAll();
// ... Repair/Maintain/Furnace/Sabotage/Eat/Drink 与 GameLifecycleHandler 列表一致
// 注意：当前各 clearAll 会全局 wipe Slowness — R0 可暂保持；R3 再按源拆
BlackoutRoleManager.clear(level);
```

`onEnd` 也可调 `TaskManager.clearAllActiveTasks()` 一次（幂等）。

- [ ] **Step 3: GameLifecycleHandler.handleGameEnd finally 块**

在 Eat/Drink clear 后加：

```java
com.habitrain.core.game.blackout.task.RestorePowerHandler.resetCompleted();
com.habitrain.core.game.blackout.task.RestorePowerHandler.clearAll();
```

- [ ] **Step 4: SERVER_STOPPING**

在现有 blackout manager 清理循环内，每 level 或循环后：

```java
TaskManager.getInstance().clearAllActiveTasks();
SlownessReapplyManager.clearAll();
RestorePowerHandler.resetCompleted();
BlackoutHornVoteHandler.clearAll();
```

- [ ] **Step 5: Build + commit**

```powershell
./gradlew clean build
git add src/main/java/com/habitrain/core/task/TaskManager.java `
  src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java `
  src/main/java/com/habitrain/core/task/GameLifecycleHandler.java `
  src/main/java/com/habitrain/core/HabiTrainCore.java `
  src/main/java/com/habitrain/core/game/blackout/task/RestorePowerHandler.java
git commit -m "fix: clear TaskManager and restore/handler state on blackout end"
```

🎮：连开两局停电，第二局不应继承上局活跃任务/恢复供电完成标记。

---

### Task 4: forceAssign 禁止 onComplete（B2-02）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutVictoryChecker.java`

- [ ] **Step 1: 替换 force 完成块**

将：

```java
existing.setFulfilled(true);
existing.getDefinition().onComplete(existingPlayer, existing);
```

改为（取消路径，不发奖）：

```java
try {
    existing.getDefinition().onRemove(existingPlayer, existing);
} catch (Throwable t) {
    HabiTrainCore.LOGGER.error("forceAssign: onRemove failed for {}", existing.getFullId(), t);
}
try {
    existing.getDefinition().onReclaim(existingPlayer, existing);
} catch (Throwable t) {
    HabiTrainCore.LOGGER.error("forceAssign: onReclaim failed for {}", existing.getFullId(), t);
}
// 不要 setFulfilled(true) / onComplete
```

然后 `mgr.removeActiveTask` 等后续逻辑保持。

- [ ] **Step 2: Build + commit**

```powershell
./gradlew clean build
git add src/main/java/com/habitrain/core/game/blackout/BlackoutVictoryChecker.java
git commit -m "fix(blackout): cancel tasks without rewards when force-assigning restore_power"
```

---

### Task 5: 同步供电池无奖（B2-08）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/SupplyTaskSyncHelper.java`

- [ ] **Step 1: 改 sync 循环体**

对同步者（非 completer）：

```java
task.setFulfilled(true);
// 不调用 onComplete（不发金币/情绪、不二次 applyTimeImpact）
try {
    task.getDefinition().onRemove(other, task);
} catch (Throwable t) { ... }
mgr.removeActiveTask(uuid);
// 可选：ActiveTaskPayload 清空
```

更新类注释：同步只清状态，**奖励与时间效果仅完成者**。

确认 `AddCoalTask`/`RepairWiringTask`/`MaintainPowerTask` 的 onComplete 仍对**真实完成者**调用 `syncCompletion` + 自己的 grant/timeImpact 一次即可。

- [ ] **Step 2: Build + commit**

```powershell
./gradlew clean build
git add src/main/java/com/habitrain/core/game/blackout/task/SupplyTaskSyncHelper.java
git commit -m "fix(blackout): synced supply tasks clear without rewards"
```

🎮：两好人同做添煤，一人完成 → 另一人任务消失且**不**双倍减总时间/双倍金币。

---

### Task 6: 二次永久停电不派 restore（B2-09）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutVictoryChecker.java`
- 可能读: `BlackoutTimerSystem.getPhase`

- [ ] **Step 1: 分支 triggerSREPermanentBlackout**

```java
void triggerSREPermanentBlackout(ServerLevel level) {
    if (level == null) return;
    var blackout = SREWorldBlackoutComponent.KEY.get(level);
    if (blackout != null) {
        blackout.triggerBlackout(true, 600000);
    }
    var phase = BlackoutTimerSystem.getPhase(level);
    if (phase == BlackoutTimerSystem.Phase.SECOND_BLACKOUT) {
        // 二次永久停电：不断电任务重派
        return;
    }
    RestorePowerHandler.resetCompleted();
    forceAssignRestorePowerToAllGood(level);
}
```

（`tickMaintenance` 在调 callback 前已把 phase 设为 SECOND_BLACKOUT；`tickNormal` 设为 FIRST_BLACKOUT — 与现 Timer 代码一致。）

- [ ] **Step 2: Build + commit**

```powershell
./gradlew clean build
git add src/main/java/com/habitrain/core/game/blackout/BlackoutVictoryChecker.java
git commit -m "fix(blackout): skip restore_power force-assign on second permanent blackout"
```

---

### Task 7: 瞬时真断电（B2-10）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutTimerSystem.java`

- [ ] **Step 1: triggerTransientBlackout 接 SRE**

```java
public static void triggerTransientBlackout(ServerLevel level) {
    var s = getOrCreate(level);
    if (!s.initialized || s.transientBlackoutActive) return;
    s.transientBlackoutActive = true;
    s.transientBlackoutTicks = TRANSIENT_TICKS;
    try {
        var blackout = io.wifi.starrailexpress.cca.SREWorldBlackoutComponent.KEY.get(level);
        if (blackout != null) {
            // 非永久、持续约 TRANSIENT_TICKS（若 API 单位为 tick 用 140；若为 ms 按 SRE 文档）
            blackout.triggerBlackout(false, TRANSIENT_TICKS);
        }
    } catch (Throwable t) {
        LOGGER.error("Failed to trigger SRE transient blackout", t);
    }
    broadcast(level, "§c⚡ 短暂停电！");
    // 维护/NORMAL 惩罚逻辑保持
    ...
}
```

瞬时结束时（`transientBlackoutTicks <= 0`）若 SRE 未自动恢复，调用 `blackout.reset()` 或等价恢复 API（对照永久 `endSREBlackout`）。

- [ ] **Step 2: Build + commit**

```powershell
./gradlew clean build
git add src/main/java/com/habitrain/core/game/blackout/BlackoutTimerSystem.java
git commit -m "fix(blackout): drive transient blackout through SRE world component"
```

🎮：破坏供电池触发瞬时停电时，世界灯光/SRE 断电状态应可见，约 7s 后恢复。

---

### Task 8: 倒计时 endTimeTick 单位修正（B2-07）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutSyncManager.java`
- Verify: `src/main/java/com/habitrain/core/client/gui/BlackoutHudOverlay.java`
- Grep: 所有 `BlackoutTimerPayload.broadcastToAll` / `new BlackoutTimerPayload` 调用点

- [ ] **Step 1: SyncManager 用 gameTime + 秒*20**

```java
void tickSecond(ServerLevel level) {
    var phase = BlackoutTimerSystem.getPhase(level);
    long now = level.getGameTime();
    long endTimeTick;
    if (phase == BlackoutTimerSystem.Phase.NORMAL) {
        endTimeTick = now + BlackoutTimerSystem.getBlackoutCountdown(level) * 20L;
    } else if (phase == BlackoutTimerSystem.Phase.MAINTENANCE) {
        endTimeTick = now + BlackoutTimerSystem.getMaintenanceTime(level) * 20L;
    } else {
        endTimeTick = 0L; // 永久停电阶段无本地 countdown 或单独 UI
    }
    ...
}
```

- [ ] **Step 2: 开局/其它 broadcast 对齐**

搜索 `broadcastToAll` 传入的 countdown 字段，确保不再把「秒」当「绝对 tick」乱传。开局若有硬编码 `120`，改为 `level.getGameTime() + 240*20L` 等与 `FIRST_BLACKOUT_CD` 一致。

- [ ] **Step 3: 客户端**

`getLocalCountdown` 已是 `cachedEndTimeTick - getGameTime()` — 在服务端改为绝对 gameTime 后，返回值单位为 **tick**。若 HUD 显示为秒，应 `/20`：

```java
return (int) Math.max(0, (cachedEndTimeTick - now) / 20L);
```

核对 `render` 使用 countdown 的所有位置。

- [ ] **Step 4: Build + commit**

```powershell
./gradlew clean build
git add src/main/java/com/habitrain/core/game/blackout/BlackoutSyncManager.java `
  src/main/java/com/habitrain/core/client/gui/BlackoutHudOverlay.java
# 以及其它改过的 broadcast 调用点
git commit -m "fix(blackout): align timer endTimeTick to world gameTime seconds*20"
```

🎮：HUD 停电倒计时与服务器 phase 切换大致同步，无狂跳。

---

### Task 9: once-per-game 缓存失效（T3-02）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`
- Modify: `src/main/java/com/habitrain/core/task/TaskPoolBuilder.java`（确认 `invalidateAll`/`invalidate(modeId)` 存在）

- [ ] **Step 1: onTaskAssign**

```java
if (task != null && ONCE_PER_GAME_TASK_IDS.contains(task.getFullId())) {
    assignedOncePerGameTasks.add(task.getFullId());
    TaskPoolBuilder.invalidateAll(); // 或 invalidate(MODE_ID)
}
```

- [ ] **Step 2: onPreStart** 已 clear set；再 `TaskPoolBuilder.invalidateAll()` 一次。

- [ ] **Step 3: Build + commit**

```powershell
./gradlew clean build
git add src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java
git commit -m "fix: invalidate task pool when once-per-game task is assigned"
```

---

## Batch R1 — Tick 与网络热点

### Task 10: BlackoutLookMyEyes AABB（T3-07）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutLookMyEyesTask.java`

- [ ] **Step 1:** 将 `serverLevel().players()` 循环替换为与 `BuiltinTaskRegistrar.look_my_eyes` 相同的 AABB `getEntitiesOfClass` 块（复制 L198–223 逻辑）。

- [ ] **Step 2:** Build + commit `perf: AABB query for blackout look_my_eyes`

---

### Task 11: Betel tick 门控（T4-02）

**Files:**
- Modify: `src/main/java/com/habitrain/core/ModTickHandler.java`
- 可能读: `BetelTickEngine.isGameActive`

- [ ] **Step 1:** 仅对「所在 level `BetelTickEngine.isGameActive(world)`」的玩家调用 `tickPlayer` / ExtraSlot：

```java
for (ServerPlayer player : server.getPlayerList().getPlayers()) {
    if (!(player.level() instanceof ServerLevel sl)) continue;
    if (!BetelTickEngine.isGameActive(sl)) continue;
    BetelTickEngine.tickPlayer(player);
    ExtraSlotComponent.KEY.get(player).serverTick();
}
```

- [ ] **Step 2:** Build + commit `perf: tick betel only for players in active game levels`

---

### Task 12: 放逐投票 diff 广播（N5-03）— 最小版

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutExileVoteManager.java`

- [ ] **Step 1:** 仿 `SheriffVoteBroadcaster`：缓存 last hash（active+remaining+candidateOrder+votes）；`tickSecond` 仅 remaining 变化或 hash 变才 `broadcastState`。castVote 仍立即 broadcast。

- [ ] **Step 2:** Build + commit `perf: diff-broadcast exile vote state`

---

### Task 13: JOIN 配置瘦身（N5-04）

**Files:**
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java` JOIN 块

- [ ] **Step 1:** JOIN 只发 `FullConfigSyncPayload` + `CustomTaskBlockPayload` + `ShaderConfigPayload`（若 Full 已含 shader 白名单则再评估）。**去掉**与 Full 重复的 `TaskConfigPayload.sendToPlayer`，或保留 TaskConfig 删 Full 中 tasks — 选「去 TaskConfig 留 Full」。

- [ ] **Step 2:** 保存配置广播路径：避免 TaskConfig+Full 双发（保留 Full + Shader 即可，若客户端仍依赖 TaskConfig receiver 则保留 TaskConfig 仅在 OP 保存时）。

- [ ] **Step 3:** Build + commit `perf: reduce join config payload duplication`

---

## Batch R2 — 死代码

### Task 14: 删除/隔离警长投票死栈（B2-03/C7-01）

**决策：** 产品已用电话雇警 — **删除自动警长投票运行时路径**，保留文件删除或 `@Deprecated` 整包。

最小安全方案（推荐）：
1. 停止注册 `BlackoutSheriffVoteCastPayload` / `BlackoutSheriffVotePayload`（若无其它引用）
2. 客户端移除 sheriff screen 注册与 HUD「警长投票」文案
3. `BlackoutMode` / SERVER_STOPPING 去掉 SheriffVote reset（或保留 reset 空操作）
4. 删除或保留源文件但不注册 — 优先删除未引用类以减面

若删除编译牵连过大：feature flag `false` 固定，去掉 C2S 注册与 client receiver。

- [ ] Build + commit `refactor: remove dead auto sheriff vote network/UI path`

---

### Task 15: 删除空 Eat/Drink Handler（T3-01）

**Files:** 删 `BlackoutEatHandler`/`BlackoutDrinkHandler` 或保留 clear no-op 但去掉 register 调用与 GameLifecycle 引用；mixin 完成路径不动。

- [ ] Build + commit `refactor: remove empty blackout eat/drink handlers`

---

### Task 16: AGENTS.md payload 表（N5-08）

- 删除 `BlackoutStatusPayload` 行  
- 增加 FullConfigSync、Phone、Vote、Hire 等与代码一致  

- [ ] Commit `docs: refresh AGENTS.md network payload table`

---

## Batch R3 — 边界与去重（摘要任务）

### Task 17: Slowness 按源清理（T3-06）

- `SlownessReapplyManager` 增加 `clearBySource(ResourceLocation)` / `unregister` 已有则用  
- 各 handler `clearAll` 只清自己 source，不 `clearAll()` 全局  
- 局终仍可一次 `clearAll()`

### Task 18: Config load 路径 invalidate 池（T3-16）

- `ConfigSync.loadFromJsonString` / `applySyncData` / `setAllConfigs` 末尾 `TaskPoolBuilder.invalidateAll()`

### Task 19: Config merge-not-clear（N5-05）— 谨慎

- `loadFromJsonString`：缺 `tasks` 根时不 clear 该 map；或拒绝保存  
- 单测无 → 用手工 JSON 缺段验证

### Task 20: 客户端单 blackout 会话 flag（C7-02）

- 统一 `BlackoutVoteState` 或 `BlackoutHudOverlay` 一处；另一处委托

每任务各自 build+commit。

---

## Batch R4 — 标识与文档（摘要）

### Task 21: UI `habitrain_taskapi` → `habitrain_core`（C7-04）
### Task 22: canAssign / tagGrantedItem 补门（T3-17/18）— 按任务列表补  
### Task 23: config 门控字段（nunchuck/invis/replaceToken）— 默认保持当前安全行为 + 可选 JSON  
### Task 24: README/使用教程过时 API 名扫一遍  

---

## R0 完成后的验收清单（合并）

| # | 检查 |
|---|------|
| 1 | `./gradlew clean build` 绿 |
| 2 | 临时目录 jar 时间戳更新 |
| 3 | 小游戏 token 非 0 |
| 4 | 击杀 → 胜负可结束 |
| 5 | 死杀手结算非「胜」 |
| 6 | 连开两局无任务残留 |
| 7 | 永久停电切任务不白嫖金币 |
| 8 | 同步添煤不双倍扣时 |
| 9 | 二次停电不刷 restore 任务 |
| 10 | HUD 倒计时合理 |

---

## Self-review（对照报告 §1.2 Top10）

| Top 项 | 任务 |
|--------|------|
| S6-01 token | Task 1 |
| B2-01 死亡 | Task 0 |
| B2-06 faction | Task 2 |
| L1 clear | Task 3 |
| B2-07 计时 | Task 8 |
| B2-02 force | Task 4 |
| B2-08 同步奖 | Task 5 |
| B2-09 二次停电 | Task 6 |
| T3-07/T4-02 | Task 10–11 |
| N5-05 | Task 19（R3） |
| T3-02 once | Task 9 |
| B2-10 瞬时 | Task 7 |

无 TBD 占位；无测试代码要求；每 R0 任务含 build/commit。

---

## 执行交接

计划完成后可选：

1. **Subagent-Driven（推荐）** — 每 Task 新 agent + 审查  
2. **Inline Execution** — 本会话按 executing-plans 连续做  

从 **Task 0（合并 WIP）** 开始。
