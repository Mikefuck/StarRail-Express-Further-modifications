# Batch 1：Blackout 模块修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan.

**Goal:** 修复 Blackout 核心模块 28 项问题：关键 bug 修复、死代码清理、性能优化、状态/耦合修复。

**Architecture:** 先删 Sheriff 整套死代码避免干扰，再修关键 bug，最后性能优化 + 状态修复。

---

## 全局约束

1. 每 Task 完成后运行 `./gradlew clean build`
2. JAR 复制到 `D:\Backup\mc mod\临时\`
3. 禁止访问 `D:\Backup\mc mod\backup\`

---

### Task 1-1: 删除 Sheriff 投票整套死系统

**文件：**
- Modify: `game/blackout/BlackoutSheriffVoteManager.java`
- Modify/Delete: `game/blackout/BlackoutSheriffResolver.java`
- Modify/Delete: `game/blackout/SheriffVoteBroadcaster.java`
- Modify: `game/blackout/BlackoutMode.java`
- Modify: `HabiTrainCore.java`

**说明：** Sheriff 投票整套系统是死代码。删除以下：

1. **BlackoutSheriffVoteManager.java**：
   - 删除 `startVote()` 方法（private，无调用方）
   - 删除 `resolve()` 方法
   - 删除 `syncToPlayer()` 方法
   - 删除 `syncToAll()` 方法
   - 删除 `isVoteOpen()` 方法
   - 保留 `onPlayerJoined`、`onPlayerRemoved`、`reset()`（这些仍然被 BlackoutMode 调用）
   - 保留 `castVote()` 虽然返回 false（但外部有调用方）

2. **BlackoutSheriffResolver.java**：
   - 删除 `applyVoteResult()` 方法（全仓无调用方）
   - 如果整类只剩下空壳，考虑删除整个文件（但保留类以防 BlackoutMode 持有字段引用）

3. **SheriffVoteBroadcaster.java**：
   - 删除 `resetCache()` 方法（已在 Batch 0）
   - 删除 `broadcastState()` 如果无调用方（确认后）

4. **清理引用**：
   - `BlackoutMode.java` 中删除对 `sheriffResolver` 的调用
   - `HabiTrainCore.java` 中删除 BlackoutSheriffVotePayload 注册（如果确定无调用）

**构建验证 + JAR 复制 + Commit：**
```
git commit -m "batch1: remove dead sheriff voting system (manager/resolver/broadcaster)"
```

---

### Task 1-2: 关键 Bug 修复

**文件：**
- Modify: `game/blackout/task/SupplyTaskSyncHelper.java`
- Modify: `task/TaskManager.java`
- Modify: `client/gui/BlackoutSheriffVoteState.java`
- Modify: `client/gui/BlackoutPhoneHireScreen.java`
- Modify: `client/gui/BlackoutVoteScreen.java`

#### 1-2a: syncCompletion 链式递归修复（S8-001）

**问题：** syncCompletion 对每个未完成玩家递归调用 onComplete，onComplete 内又调 syncCompletion，且每次 onComplete 都 applyTimeImpact，导致时间影响重复施加 N 次。

**修复方案：**
`SupplyTaskSyncHelper.java`：
```java
// 引入去重守卫
private static final Set<UUID> syncCompletedPlayers = ConcurrentHashMap.newKeySet();

public static void syncCompletion(ServerLevel level, TaskInstance task) {
    // 检查是否已经同步过该任务实例
    // 对每个目标玩家只执行 setFulfilled + 状态清理，不执行 onComplete（时间影响只由原始完成者施加）
}
```

`RepairWiringTask.java:54`、`MaintainPowerTask.java:37`、`AddCoalTask.java:65`：
- 在 onComplete 中保留 syncCompletion 调用，但 syncCompletion 内部不再递归调 onComplete

**关键契约：** 时间影响（applyTimeImpact）只由第一个完成者施加一次。同步完成的玩家只获得状态清理，不额外施加时间影响。

#### 1-2b: dlcTaskCounts 跨局清理（S2-001）

`TaskManager.java`：
```java
// 在 clearAllActiveTasks() 中添加：
dlcTaskCounts.clear();
```

#### 1-2c: toggleSelection 替换未发撤回（S11-014）

`BlackoutSheriffVoteState.java:73`：
```java
// 替换场景：对旧目标发送撤回 payload
if (selectedTargetIds.size() >= sheriffCount) {
    UUID replaced = selectedTargetIds.get(0);
    // 发送撤回 payload
    sendVoteCast(getPurpose(), null, replaced); // 新增参数或方法
    selectedTargetIds.set(0, targetId);
} else {
    selectedTargetIds.add(targetId);
}
```

#### 1-2d: statusText 无回写路径（S11-016）

`BlackoutPhoneHireScreen.java` — 增加服务端结果回执 payload。
新增 `BlackoutHireResultPayload` S2C：
```java
// 服务端 hire 完成后 broadcast 结果
// 客户端 receiver：
// 成功 → statusText = "聘请成功"
// 失败 → statusText = "聘请失败: " + reason
// 金币不足 → statusText = "金币不足"
```

#### 1-2e: lockCountdownTicks 同步（S11-015）

`BlackoutPhoneHireScreen.java` — 禁用态以服务端为准。服务端 hire 失败时发回执驱动 statusText。

#### 1-2f: tick 不本地递减（S11-011）

`BlackoutVoteScreen.java:40` + `BlackoutSheriffVoteScreen.java:42`：
```java
// 在 screen tick() 中添加本地递减
@Override
public void tick() {
    super.tick();
    if (remainingSeconds > 0) {
        remainingSeconds--;
    }
}
```

---

### Task 1-3: 性能优化

**文件：**
- Modify: `game/blackout/task/BlackoutLookMyEyesTask.java`
- Modify: `game/blackout/task/AddCoalTask.java`
- Modify: `network/BlackoutVotePayload.java`
- Modify: `client/gui/BlackoutHudOverlay.java`
- Modify: `client/gui/BlackoutVoteScreen.java`

#### 1-3a: look_my_eyes 改 AABB（S8-002）

`BlackoutLookMyEyesTask.java:30`：
```java
@Override
public void onTick(ServerLevel level, ServerPlayer serverPlayer, TaskInstance task) {
    // 旧：全服遍历 for (ServerPlayer other : level.players()) { 向量计算 }
    // 新：AABB 预过滤
    AABB searchBox = serverPlayer.getBoundingBox().inflate(3.0);
    List<ServerPlayer> nearby = level.getEntitiesOfClass(
        ServerPlayer.class, searchBox,
        p -> p != serverPlayer && p.isAlive()
    );
    // 再对 nearby 做向量检测
}
```

#### 1-3b: AddCoalTask 事件驱动（S8-003）

`AddCoalTask.java:46`：
```java
// 旧：每 tick 全背包扫描 hasPlayerCoal()
// 新：注册 InventoryChangeListener，仅背包变动时检查
// 或：仅在玩家右键发电机时校验煤炭
```

#### 1-3c: VotePayload 哈希门控（S5-001）

`BlackoutVotePayload.java:35` — 参照 `SheriffVoteBroadcaster.java:19-24` 的 computeHash/asLastPayloadHash 模式：
```java
// broadcastState 中：
// 1. 计算当前 state 的 hash
// 2. if (hash == lastPayloadHash) skip
// 3. else lastPayloadHash = hash; broadcast
```

#### 1-3d: getLocalCountdown 缓存（S11-001）

`BlackoutHudOverlay.java:86`：
```java
// render 入口缓存一次
long now = getLocalCountdown();
// 后续全部复用 now 而非重复调用 getLocalCountdown()
```

#### 1-3e: Component 缓存（S11-012）

`BlackoutVoteScreen.java:63` — 将静态文本（标题、描述、表头）提升为字段，避免每帧分配。

---

### Task 1-4: 状态修复 + 耦合 + 命名

**文件：**
- Modify: `game/blackout/task/RestorePowerHandler.java`
- Modify: `game/blackout/BlackoutMode.java`
- Modify: `client/mixin/HudCustomTaskMixin.java`
- Modify: `client/gui/BlackoutWelcomeRenderer.java`
- Modify: `game/blackout/task/AddCoalHandler.java`
- Modify: `game/blackout/sre/SREBlackoutGameMode.java`
- Modify: `game/blackout/BlackoutShopCatalog.java`
- Modify: `client/gui/BlackoutHudOverlay.java`
- Modify: `client/gui/BlackoutVoteScreen.java`

#### 1-4a: restoreCompleted 跨局静态 → 按 level 隔离（S8-009）

`RestorePowerHandler.java`：`restoreCompleted` 从 `private static boolean` 改为按 level 存储（如 Map<ResourceKey, Boolean>），并在游戏结束时清理。

#### 1-4b: lastWinningFaction 对象化（S7-007）

`BlackoutMode.java`：`lastWinningFaction` 从 `private static volatile` 改为对局实例字段，不再跨实例共享。

#### 1-4c: ActiveTaskCache 双写路径（S9-009）

`HudCustomTaskMixin.java:20` — 明确单一权威写入源（payload receiver），NBT 同步路径不写入缓存。

#### 1-4d: BlackoutWelcomeRenderer 静态状态（S11-010）

将 `roleName/subtitle/goal/welcomeTime` 从 `static mutable` 集中到 client state holder。

#### 1-4e~j: 命名和常量提取
- `AddCoalHandler.java` — 校准 Javadoc 或提前发煤
- `SREBlackoutGameMode.java:38` — 10/1 提取具名常量
- `BlackoutShopCatalog.java` — KEY 常量降为 private
- `BlackoutHudOverlay.java` — HUD 颜色/坐标/phase 提取常量，phase 用枚举
- `BlackoutHudOverlay.java` — blackoutModeActive 移到 client state holder
- `BlackoutVoteScreen.java:133` — null UUID 弃票改为 sendVoteRevoke

---

## Batch 1 验证

- [ ] 构建通过
- [ ] JAR 已复制到临时目录
- [ ] 停电模式下加载正常（无 mixin 错误）
- [ ] syncCompletion 不再重复施加时间影响
- [ ] Sheriff 投票页面不再打开
