# 哈比列车核心 性能优化 + 架构重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 系统性优化哈比列车核心 mod——拆分 8 个超大/混淆类、修复 8 个性能热点、删除死代码，使 20+ 人服务器停电模式不再卡顿。

**Architecture:** 两层并行推进：L1 性能层定向修复 O(n²)/广播/per-tick 热点；L2 结构层拆分 >400 行 + 混淆类，在拆分中嵌入 L1 优化。每个拆分/优化点独立 commit，失败可单独 revert。

**Tech Stack:** Fabric 1.21.1, Java 21, Fabric Loom 1.16.3, Mixin, Gson, Fabric Networking API

## Global Constraints

- **Java 21**（`options.release = 21`），CI 用 JDK 25 编译但字节码目标仍为 21
- **无测试代码**：仓库无 test 源集，验证靠 `./gradlew build`（编译 + processResources）和游戏内运行
- **mod ID**: `habitrain_core`
- **API 可破坏性变更**：已确认覆盖 AGENTS.md 中"DLC 不可改"限制
- **行为边界**：玩法/数值/胜负逻辑完全不变。仅允许同步频率微调
- **Mixin 拆分策略**：先试拆分 Mixin 本身，注入顺序出问题则退回 helper 模式
- **每个任务独立提交**：commit message 用 `refactor:` 或 `perf:` 前缀
- **不要新增测试代码**：项目约定无测试，验证靠 build + 游戏内
- **不要写注释**：除非用户明确要求

## 项目构建 & 验证命令

```powershell
./gradlew build          # 编译 + processResources，每个任务结束必须跑
./gradlew runClient      # 启动开发客户端（游戏内验证用）
```

构建成功 = 无编译错误 + 无 mixin 注入失败。如需游戏内验证，任务会标注 "🎮 游戏内验证"。

## 关键参考路径

- 本项目（可修改）: `D:\Backup\mc mod\哈比列车api`
- Spec 文档: `D:\Backup\mc mod\临时\2026-07-07-performance-refactor-spec.md`
- AGENTS.md: `D:\Backup\mc mod\哈比列车api\AGENTS.md`（注意：API 破坏性限制已被本计划覆盖）

---

## Task 1: 删除死代码（Engine + BlackoutStatusPayload）

**Files:**
- Delete: `src/main/java/com/habitrain/core/task/Engine.java`
- Delete: `src/main/java/com/habitrain/core/network/BlackoutStatusPayload.java`
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java`（移除 BlackoutStatusPayload 注册行）
- Modify: `src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java`（移除 BlackoutStatusPayload 接收注册 + 相关 import）

**Interfaces:**
- Consumes: 无
- Produces: 无（删除死代码，不产生新接口）

- [ ] **Step 1: 确认 Engine.java 无引用**

Run: `rg "com.habitrain.core.task.Engine" src/main/java/`
Expected: 仅 Engine.java 自身的 package 声明，无其他引用

- [ ] **Step 2: 确认 BlackoutStatusPayload 仅被注册但从未发送**

Run: `rg "BlackoutStatusPayload" src/main/java/`
Expected: 出现在 `BlackoutStatusPayload.java`（定义）、`HabiTrainCore.java:121`（register）、`HabiTrainCoreClient.java`（import + registerGlobalReceiver）。搜索 `new BlackoutStatusPayload` 应无结果（确认从未实例化发送）。

- [ ] **Step 3: 删除 Engine.java**

删除 `src/main/java/com/habitrain/core/task/Engine.java`

- [ ] **Step 4: 删除 BlackoutStatusPayload.java**

删除 `src/main/java/com/habitrain/core/network/BlackoutStatusPayload.java`

- [ ] **Step 5: 修改 HabiTrainCore.java 移除注册行**

在 `src/main/java/com/habitrain/core/HabiTrainCore.java` 约 `:121` 找到：
```java
BlackoutStatusPayload.register();
```
删除这一行。

同时移除对应的 import（如果有，搜索 `import com.habitrain.core.network.BlackoutStatusPayload`）。

- [ ] **Step 6: 修改 HabiTrainCoreClient.java 移除接收注册**

在 `src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java`：

1. 移除 import:
   - `import com.habitrain.core.network.BlackoutStatusPayload;`
   - `import com.habitrain.core.network.BlackoutStatusPayload.StatusType;`

2. 删除整块接收器代码（约 `:206-225`）:
```java
// 网络接收: 状态事件
ClientPlayNetworking.registerGlobalReceiver(BlackoutStatusPayload.TYPE, (payload, ctx) -> {
    ctx.client().execute(() -> {
        var st = payload.statusType();
        Component main;
        Component sub;
        if (st == StatusType.BLACKOUT_START) {
            main = Component.literal("§c停电");
            sub = Component.literal("§c⚡ 停电了！");
        } else if (st == StatusType.BLACKOUT_END) {
            main = Component.literal("§a供电恢复");
            sub = Component.literal("§a⚡ 供电恢复");
        } else if (st == StatusType.TIME_WARNING) {
            main = Component.literal("§e时间警告");
            sub = Component.literal("§e⚠ 仅剩 1 分钟！");
        } else {
            return;
        }
        com.habitrain.core.client.util.ClientSubtitleNotifier.sendTop(main, sub, 80);
    });
});
```

- [ ] **Step 7: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL，无编译错误

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m "refactor: remove dead code (Engine.java, BlackoutStatusPayload.java)"
```

---

## Task 2: look_my_eyes O(n²) → AABB 邻居查询

**Files:**
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java:490-555`（look_my_eyes 任务的 onTick lambda）

**Interfaces:**
- Consumes: `net.minecraft.world.phys.AABB`, `net.minecraft.server.level.ServerPlayer`, `net.minecraft.world.entity.player.Player` (vanilla API)
- Produces: 无（仅改 onTick lambda 内部实现）

- [ ] **Step 1: 找到 look_my_eyes 任务的 onTick**

在 `src/main/java/com/habitrain/core/HabiTrainCore.java` 约 `:490-555`，找到 `.onTick((player, task) -> { ... })` 块。

- [ ] **Step 2: 确认 AABB import**

检查文件顶部 import 区。如无 `net.minecraft.world.phys.AABB`，则添加：
```java
import net.minecraft.world.phys.AABB;
```
还需 `import java.util.List;`（如已有则跳过）。

- [ ] **Step 3: 替换 onTick lambda 中的玩家遍历逻辑**

将整个 `.onTick((player, task) -> { ... })` 块替换为：

```java
.onTick((player, task) -> {
    if (task.getProgress() >= task.getMaxProgress()) return;
    if (!(player instanceof ServerPlayer serverPlayer)) return;

    Vec3 eyePos = serverPlayer.getEyePosition();
    AABB searchBox = new AABB(eyePos.x - 3.0, eyePos.y - 3.0, eyePos.z - 3.0,
                               eyePos.x + 3.0, eyePos.y + 3.0, eyePos.z + 3.0);
    List<ServerPlayer> nearby = serverPlayer.serverLevel()
            .getEntitiesOfClass(ServerPlayer.class, searchBox,
                    p -> p != serverPlayer && p.isAlive());

    Vec3 lookVec = serverPlayer.getLookAngle();
    boolean eyeContact = false;

    for (ServerPlayer otherPlayer : nearby) {
        Vec3 toOther = otherPlayer.getEyePosition().subtract(eyePos);
        double distance = toOther.length();
        if (distance > 3.0) continue;

        Vec3 dirToOther = toOther.normalize();
        Vec3 otherLookVec = otherPlayer.getLookAngle();
        Vec3 dirToThis = eyePos.subtract(otherPlayer.getEyePosition()).normalize();

        double dotThis = lookVec.dot(dirToOther);
        double dotOther = otherLookVec.dot(dirToThis);

        if (dotThis > 0.8 && dotOther > 0.8) {
            eyeContact = true;
            break;
        }
    }

    if (eyeContact) {
        task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
    } else {
        if (task.getProgress() > 0) {
            task.setProgress(0);
        }
    }
})
```

- [ ] **Step 4: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```powershell
git add -A
git commit -m "perf: use AABB spatial query for look_my_eyes eye-contact detection (O(n²)→O(k))"
```

🎮 游戏内验证（可选但推荐）：2 玩家 3 米内对视，进度条递增 3 秒完成；3 米外无反应。

---

## Task 3: SlownessReapplyManager — 合并 7 个 per-tick slowness 监听器

**Files:**
- Create: `src/main/java/com/habitrain/core/task/SlownessReapplyManager.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/AddCoalHandler.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/RepairWiringHandler.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/SabotageWiringHandler.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/MaintainPowerHandler.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/RestorePowerHandler.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/FurnaceExplosionHandler.java`
- Modify: `src/main/java/com/habitrain/core/task/BackpackSearchHandler.java`
- Modify: `src/main/java/com/habitrain/core/task/GameLifecycleHandler.java`
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java`（注册 SlownessReapplyManager）

**Interfaces:**
- Consumes: 无
- Produces:
  - `SlownessReapplyManager.register(levelKey, playerId, spec)` — 注册玩家减速
  - `SlownessReapplyManager.unregister(levelKey, playerId)` — 注销
  - `SlownessReapplyManager.clearAll(levelKey)` — 清空某 level 全部
  - `SlownessReapplyManager.EffectSpec(amplifier, duration, sourceTag)` — 减速规格 record

**重要约束**：7 个 handler 的 per-tick 逻辑略有差异。本任务**不统一所有 handler 的减速逻辑**，仅把"每 tick 重新施加 SLOWNESS"这单一职责抽到 `SlownessReapplyManager`。每个 handler 仍保留自己的 `activeStates`/`slowUntilTickMap`（用于阶段推进、物品发放等业务逻辑），但移除其中的 `addEffect` 调用，改为在任务开始/交互时调用 `SlownessReapplyManager.register`，在完成/清理时调用 `unregister`。

**关键差异**：部分 handler（如 AddCoalHandler）的 per-tick 逻辑不仅是减速，还有阶段推进（slowUntilTick 到期后发煤炭、推进 progress）。这类 handler **保留自己的 per-tick listener**用于阶段推进，仅把 `addEffect` 部分委托给 `SlownessReapplyManager`。简单 handler（如 RepairWiringHandler）的 per-tick 逻辑纯减速，可完全移除 listener。

- [ ] **Step 1: 创建 SlownessReapplyManager.java**

创建 `src/main/java/com/habitrain/core/task/SlownessReapplyManager.java`:

```java
package com.habitrain.core.task;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SlownessReapplyManager {

    public record EffectSpec(int amplifier, int duration, ResourceLocation sourceTag) {}

    private static final Map<ResourceKey<Level>, Map<UUID, EffectSpec>> activeEntries = new ConcurrentHashMap<>();
    private static boolean registered = false;

    public static void registerTickHandler() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (activeEntries.isEmpty()) return;
            for (var levelEntry : activeEntries.entrySet()) {
                ServerLevel level = server.getLevel(levelEntry.getKey());
                if (level == null) continue;
                Map<UUID, EffectSpec> levelMap = levelEntry.getValue();
                if (levelMap.isEmpty()) continue;
                for (var entry : levelMap.entrySet()) {
                    ServerPlayer player = level.getPlayerByUUID(entry.getKey());
                    if (player == null) continue;
                    EffectSpec spec = entry.getValue();
                    player.addEffect(new MobEffectInstance(
                            MobEffects.MOVEMENT_SLOWDOWN, spec.duration(), spec.amplifier(),
                            false, true, true));
                }
            }
        });
    }

    public static void register(ResourceKey<Level> levelKey, UUID playerId, EffectSpec spec) {
        activeEntries.computeIfAbsent(levelKey, k -> new ConcurrentHashMap<>()).put(playerId, spec);
    }

    public static void unregister(ResourceKey<Level> levelKey, UUID playerId) {
        Map<UUID, EffectSpec> levelMap = activeEntries.get(levelKey);
        if (levelMap != null) {
            levelMap.remove(playerId);
            if (levelMap.isEmpty()) activeEntries.remove(levelKey);
        }
    }

    public static void clearAll(ResourceKey<Level> levelKey) {
        activeEntries.remove(levelKey);
    }

    public static void clearAll() {
        activeEntries.clear();
    }
}
```

- [ ] **Step 2: 在 HabiTrainCore.onInitialize 注册 SlownessReapplyManager**

在 `src/main/java/com/habitrain/core/HabiTrainCore.java` 的 `onInitialize()` 中，在 `registerMoreTasks()` 之前添加：
```java
SlownessReapplyManager.registerTickHandler();
```
并添加 import: `import com.habitrain.core.task.SlownessReapplyManager;`

- [ ] **Step 3: 改造 RepairWiringHandler（纯减速 handler，完全移除 listener）**

在 `src/main/java/com/habitrain/core/game/blackout/task/RepairWiringHandler.java`：

1. 在 `onUseBlock` 中，把 `addEffect` + `slowUntilTickMap.put` 替换为：
```java
SlownessReapplyManager.register(serverPlayer.serverLevel().dimension(), serverPlayer.getUUID(),
        new SlownessReapplyManager.EffectSpec(2, 120,
                ResourceLocation.parse("habitrain_core:repair_wiring")));
```
（保留 `task.setProgress` 和 SubtitleNotifier）

2. 移除整个 `ServerTickEvents.END_SERVER_TICK.register(server -> { ... })` 块（`:38-61`）

3. `clearState` 改为：
```java
public static void clearState(UUID uuid) {
    // 需要知道 levelKey；由于 clearState 是静态方法且无 level 参数，
    // 改为遍历所有 level 注销（或改为接受 level 参数）
    // 简单做法：在 SlownessReapplyManager 加 unregisterAll(playerId)
}
```

**注意**：`clearState(UUID)` 没有 level 参数。需要在 `SlownessReapplyManager` 加一个 `unregisterAllLevels(playerId)` 方法。

在 `SlownessReapplyManager.java` 添加：
```java
public static void unregisterAllLevels(UUID playerId) {
    for (var levelMap : activeEntries.values()) {
        levelMap.remove(playerId);
    }
}
```

然后 `RepairWiringHandler.clearState` 改为：
```java
public static void clearState(UUID uuid) {
    SlownessReapplyManager.unregisterAllLevels(uuid);
}
```

`clearAll` 改为：
```java
public static void clearAll() {
    SlownessReapplyManager.clearAll();
}
```

4. 移除 `slowUntilTickMap` 字段和 `import ServerTickEvents`（如不再使用）

- [ ] **Step 4: 改造 SabotageWiringHandler、MaintainPowerHandler、RestorePowerHandler（同 RepairWiringHandler 模式）**

对这 3 个 handler 重复 Step 3 的模式：
- 交互时调 `SlownessReapplyManager.register`
- 移除 `ServerTickEvents.END_SERVER_TICK` listener
- `clearState` → `SlownessReapplyManager.unregisterAllLevels`
- `clearAll` → `SlownessReapplyManager.clearAll`
- 移除不再使用的字段

**先读每个 handler 确认它们的 per-tick 逻辑确实是纯减速**（无阶段推进、无物品发放）。如发现非纯减速，按 Step 5 的 AddCoalHandler 模式处理（保留 listener 做阶段推进，仅把 addEffect 委托）。

Run: `rg "ServerTickEvents.END_SERVER_TICK" src/main/java/com/habitrain/core/game/blackout/task/SabotageWiringHandler.java src/main/java/com/habitrain/core/game/blackout/task/MaintainPowerHandler.java src/main/java/com/habitrain/core/game/blackout/task/RestorePowerHandler.java`
读取每个文件的 per-tick 块确认逻辑。

- [ ] **Step 5: 改造 AddCoalHandler（混合 handler，保留 listener 做阶段推进，仅把 addEffect 委托）**

在 `src/main/java/com/habitrain/core/game/blackout/task/AddCoalHandler.java`：

AddCoalHandler 的 per-tick 逻辑（`:55-97`）不仅减速，还在 `slowUntilTick <= tick` 时发煤炭、推进阶段。因此：

1. `giveSlow` 方法改为：
```java
private static void giveSlow(ServerPlayer sp, UUID uuid, boolean phaseProgressed) {
    SlownessReapplyManager.register(sp.serverLevel().dimension(), uuid,
            new SlownessReapplyManager.EffectSpec(2, SLOW_TICKS + 10,
                    ResourceLocation.parse("habitrain_core:add_coal")));
    long tick = sp.serverLevel().getServer().overworld().getGameTime();
    CoalState s = new CoalState();
    s.slowUntilTick = tick + SLOW_TICKS;
    s.phaseProgressed = phaseProgressed;
    activeStates.put(uuid, s);
}
```

2. per-tick listener 中移除 `addEffect` 调用（`:70-72`），保留阶段推进逻辑。在 `slowUntilTick <= tick` 分支中调 `SlownessReapplyManager.unregister`。

3. `clearState` 改为：
```java
public static void clearState(UUID uuid) {
    activeStates.remove(uuid);
    SlownessReapplyManager.unregisterAllLevels(uuid);
}
```

`clearAll` 改为：
```java
public static void clearAll() {
    activeStates.clear();
    SlownessReapplyManager.clearAll();
}
```

- [ ] **Step 6: 改造 FurnaceExplosionHandler 和 BackpackSearchHandler**

先读这两个 handler 确认 per-tick 逻辑类型，按 Step 3 或 Step 5 模式处理。FurnaceExplosionHandler 有 TNT 爆炸队列，需保留 listener 处理爆炸，仅把 addEffect 委托。

- [ ] **Step 7: 在 GameLifecycleHandler.onGameEnd 加 clearAll 兜底**

在 `src/main/java/com/habitrain/core/task/GameLifecycleHandler.java` 的 `OnGameEnd` 回调中添加：
```java
SlownessReapplyManager.clearAll();
```
并添加 import。

- [ ] **Step 8: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```powershell
git add -A
git commit -m "perf: consolidate 7 slowness-reapply tick listeners into SlownessReapplyManager"
```

🎮 游戏内验证（关键）：跑 add_coal、repair_wiring、sabotage_wiring、maintain_power、restore_power、furnace_explosion、search_backpack 各一次，确认减速正确施加 + 任务完成后消失 + 游戏结束清空。

---

## Task 4: 客户端 Iris 检测节流（100 tick → 600 tick）

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java:140-156, :270-296`

**Interfaces:**
- Consumes: 无
- Produces: 无（仅改检测频率）

- [ ] **Step 1: 修改 shader monitor tick 阈值**

在 `src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java:148`，把：
```java
if (shaderMonitorTick % 100 != 0) return; // ~5秒检查一次
```
改为：
```java
if (shaderMonitorTick % 600 != 0) return; // ~30秒检查一次（反射检测昂贵，降低频率）
```

- [ ] **Step 2: 缓存 Iris Class 引用**

在类顶部静态字段区（约 `:47-51`）添加：
```java
private static Class<?> cachedIrisClass;
```

在 `detectCurrentShaderPack()` 方法（`:270-296`）中，把：
```java
Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
```
改为：
```java
if (cachedIrisClass == null) {
    cachedIrisClass = Class.forName("net.irisshaders.iris.Iris");
}
Class<?> irisClass = cachedIrisClass;
```

- [ ] **Step 3: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```powershell
git add -A
git commit -m "perf: throttle client Iris shader detection from 5s to 30s + cache reflected class"
```

---

## Task 5: BlackoutMode 拆分 5 类 + Timer diff-broadcast + SRE 状态缓存

这是最大的任务。拆分 `BlackoutMode`（583 行）为 5 个类，同时嵌入优化点 3（Timer diff-broadcast）和优化点 6（SRE 状态缓存）。

**Files:**
- Create: `src/main/java/com/habitrain/core/game/blackout/BlackoutTickCoordinator.java`
- Create: `src/main/java/com/habitrain/core/game/blackout/BlackoutVictoryChecker.java`
- Create: `src/main/java/com/habitrain/core/game/blackout/BlackoutSyncManager.java`
- Create: `src/main/java/com/habitrain/core/game/blackout/BlackoutSheriffResolver.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`（精简为骨架）
- Modify: `src/main/java/com/habitrain/core/network/BlackoutTimerPayload.java`（字段 countdown → endTimeTick）
- Modify: `src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java`（BlackoutTimerPayload 接收改为 endTimeTick）
- Modify: `src/main/java/com/habitrain/core/client/gui/BlackoutHudOverlay.java`（本地算 countdown）

**Interfaces:**
- Consumes: `BlackoutTimerSystem`, `BlackoutSheriffVoteManager`, `BlackoutRoleManager`, `TaskManager`, `TaskRegistry`, `GameModeRegistry`
- Produces:
  - `BlackoutTickCoordinator.tick(level)` — per-tick 入口
  - `BlackoutVictoryChecker.tickSecond(level)` — 每秒检查
  - `BlackoutSyncManager.tickSecond(level)` — 每秒 diff-broadcast
  - `BlackoutSyncManager.syncReset(level)` — 重置同步
  - `BlackoutSyncManager.broadcast(level, message)` — 广播消息
  - `BlackoutSheriffResolver.applyVoteResult(level, resolution)` — 投票结果应用

**关键约束**：
- `BlackoutTimerPayload` 字段从 `int blackoutCountdown` 改为 `long endTimeTick`（破坏性变更，需同步改客户端接收）
- 客户端 `BlackoutHudOverlay.updateTime` 需改为本地算 countdown
- SRE 状态缓存：`BlackoutTickCoordinator` 用 `cachedSreActive` 代替每 tick lookup。SRE 的 `OnGameStarted`/`OnGameEnd` 事件已有 listener，需在 listener 中更新缓存

- [ ] **Step 1: 修改 BlackoutTimerPayload 字段**

在 `src/main/java/com/habitrain/core/network/BlackoutTimerPayload.java`：

1. record 字段改为：
```java
public record BlackoutTimerPayload(
    int totalTimeRemaining,
    long endTimeTick,
    boolean blackoutActive,
    int phase
) implements CustomPacketPayload {
```

2. 构造器 `private BlackoutTimerPayload(FriendlyByteBuf buf)` 改为：
```java
private BlackoutTimerPayload(FriendlyByteBuf buf) {
    this(buf.readVarInt(), buf.readVarLong(), buf.readBoolean(), buf.readVarInt());
}
```

3. `write` 方法改为：
```java
private void write(FriendlyByteBuf buf) {
    buf.writeVarInt(totalTimeRemaining);
    buf.writeVarLong(endTimeTick);
    buf.writeBoolean(blackoutActive);
    buf.writeVarInt(phase);
}
```

4. `broadcastToAll` 签名改为：
```java
public static void broadcastToAll(MinecraftServer server, int totalTime, long endTimeTick, boolean active, int phase) {
    var payload = new BlackoutTimerPayload(totalTime, endTimeTick, active, phase);
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
        ServerPlayNetworking.send(player, payload);
    }
}
```

- [ ] **Step 2: 创建 BlackoutSyncManager.java**

创建 `src/main/java/com/habitrain/core/game/blackout/BlackoutSyncManager.java`:

```java
package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.network.BlackoutSheriffVotePayload;
import com.habitrain.core.network.BlackoutTimerPayload;
import com.habitrain.core.util.SubtitleNotifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

class BlackoutSyncManager {
    private BlackoutTimerSnapshot lastTimerSnapshot;
    private int calibrationCounter = 0;

    private record BlackoutTimerSnapshot(int totalTimeRemaining, long endTimeTick,
                                          boolean isPermanent, int phaseOrdinal) {}

    void tickSecond(ServerLevel level) {
        var phase = BlackoutTimerSystem.getPhase(level);
        long serverTick = level.getServer().getTickCount();
        long endTimeTick = phase == BlackoutTimerSystem.Phase.NORMAL
                ? serverTick + BlackoutTimerSystem.getBlackoutCountdown(level)
                : (phase == BlackoutTimerSystem.Phase.MAINTENANCE
                    ? serverTick + BlackoutTimerSystem.getMaintenanceTime(level)
                    : 0L);

        BlackoutTimerSnapshot current = new BlackoutTimerSnapshot(
                BlackoutTimerSystem.getTotalTimeRemaining(level),
                endTimeTick,
                BlackoutTimerSystem.isPermanentBlackoutActive(level),
                phase.ordinal());

        calibrationCounter++;
        boolean forceCalibration = (calibrationCounter % 10 == 0); // 每 10 秒强制校准

        boolean shouldBroadcast = lastTimerSnapshot == null
                || current.totalTimeRemaining != lastTimerSnapshot.totalTimeRemaining
                || Math.abs(current.endTimeTick - lastTimerSnapshot.endTimeTick) > 2
                || current.isPermanent != lastTimerSnapshot.isPermanent
                || current.phaseOrdinal != lastTimerSnapshot.phaseOrdinal
                || forceCalibration;

        if (shouldBroadcast) {
            BlackoutTimerPayload.broadcastToAll(level.getServer(),
                    current.totalTimeRemaining, current.endTimeTick,
                    current.isPermanent, current.phaseOrdinal);
            lastTimerSnapshot = current;
        }
    }

    void syncReset(ServerLevel level) {
        if (level == null || level.getServer() == null) return;
        BlackoutTimerPayload.broadcastToAll(level.getServer(), 0, 0L, false, 0);
        BlackoutSheriffVotePayload.broadcastToAll(level.getServer(), false, 0, 15, 1, List.of());
        lastTimerSnapshot = null;
        calibrationCounter = 0;
    }

    void broadcast(ServerLevel level, String message) {
        if (level == null) return;
        Component component = Component.literal(message);
        for (ServerPlayer player : level.players()) {
            SubtitleNotifier.sendTop(player, Component.empty(), component, 80);
        }
    }

    void onPreStart() {
        lastTimerSnapshot = null;
        calibrationCounter = 0;
    }
}
```

**注意**：上面有个 typo `calcalibrationCounter`，应为 `calibrationCounter`。构建时会报错，修正后继续。

- [ ] **Step 3: 创建 BlackoutVictoryChecker.java**

创建 `src/main/java/com/habitrain/core/game/blackout/BlackoutVictoryChecker.java`:

```java
package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.api.WinResult;
import com.habitrain.core.network.ActiveTaskPayload;
import com.habitrain.core.task.TaskManager;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREWorldBlackoutComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

class BlackoutVictoryChecker {
    private final BlackoutMode mode;
    private final BlackoutSyncManager syncManager;

    BlackoutVictoryChecker(BlackoutMode mode, BlackoutSyncManager syncManager) {
        this.mode = mode;
        this.syncManager = syncManager;
    }

    void tickSecond(ServerLevel level) {
        checkSanityDeaths(level);
        checkVictory(level);
    }

    private void checkSanityDeaths(ServerLevel level) {
        if (mode.getCurrentLevel() == null || mode.isGameEnded()) return;
        var server = level.getServer();
        if (server == null) return;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.isCreative()) continue;
            UUID id = player.getUUID();
            if (!BlackoutRoleManager.isAlive(level, id)) continue;
            if (BlackoutRoleManager.getFaction(level, id) != BlackoutRoleManager.Faction.GOOD) continue;

            try {
                var mood = io.wifi.starrailexpress.cca.SREPlayerMoodComponent.KEY.get(player);
                if (mood == null) continue;
                if (!mood.isLowerThanDepressed()) continue;

                GameUtils.killPlayer(player, true, null,
                        ResourceLocation.fromNamespaceAndPath("habitrain_core", "sanity_collapse"));
                BlackoutRoleManager.eliminate(level, id);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.error("checkSanityDeaths: failed for player {}", id, t);
            }
        }
    }

    private void checkVictory(ServerLevel level) {
        if (mode.getCurrentLevel() == null) return;
        int goodRemaining = BlackoutRoleManager.getRemainingGood(level);
        int badRemaining = BlackoutRoleManager.getRemainingBad(level);

        if (goodRemaining <= 0 && badRemaining <= 0) {
            mode.setLastWinningFaction(null);
            endGame(level, WinResult.noWinner("同归于尽"), "双方同归于尽，游戏结束。");
            return;
        }
        if (BlackoutTimerSystem.isTimeUp(level)) {
            mode.setLastWinningFaction(BlackoutRoleManager.Faction.GOOD);
            endGame(level, WinResult.noWinner("时间归零"), "§a好人阵营获胜！时间归零，好人成功存活！");
            return;
        }
        if (badRemaining <= 0 && goodRemaining > 0) {
            mode.setLastWinningFaction(BlackoutRoleManager.Faction.GOOD);
            endGame(level, WinResult.noWinner("杀手全灭"), "§a好人阵营获胜！所有杀手已被消灭");
            return;
        }
        if (goodRemaining <= 0 && badRemaining > 0) {
            mode.setLastWinningFaction(BlackoutRoleManager.Faction.BAD);
            endGame(level, WinResult.noWinner("好人全灭"), "§c杀手阵营获胜！所有好人都被淘汰了");
        }
    }

    void endGame(ServerLevel level, WinResult result, String message) {
        if (mode.isGameEnded()) return;
        mode.setGameEnded(true);
        mode.setPendingEndMessage(message);
        if (level != null) {
            try {
                var sreGame = SREGameWorldComponent.KEY.get(level);
                if (sreGame != null) {
                    sreGame.setGameStatus(SREGameWorldComponent.GameStatus.STOPPING);
                    sreGame.clearRoleMap();
                }
            } catch (Exception e) {
                HabiTrainCore.LOGGER.error("endGame: failed to stop SRE game", e);
            }
            com.habitrain.core.api.GameModeRegistry.stop(level, result);
        }
    }

    void endGame(ServerLevel level, String message) {
        mode.setLastWinningFaction(null);
        endGame(level, WinResult.forceEnd("游戏结束"), message);
    }

    void triggerSREPermanentBlackout(ServerLevel level) {
        if (level == null) return;
        var blackout = SREWorldBlackoutComponent.KEY.get(level);
        if (blackout != null) {
            blackout.triggerBlackout(true, 600000);
        }
        com.habitrain.core.game.blackout.task.RestorePowerHandler.resetCompleted();
        forceAssignRestorePowerToAllGood(level);
    }

    private void forceAssignRestorePowerToAllGood(ServerLevel level) {
        if (level == null) return;
        TaskManager mgr = TaskManager.getInstance();
        var restoreDef = TaskRegistry.get("habitrain_core:restore_power");
        if (restoreDef == null) return;

        for (UUID uuid : BlackoutRoleManager.getAllAlive(level)) {
            if (BlackoutRoleManager.getFaction(level, uuid) != BlackoutRoleManager.Faction.GOOD) continue;

            TaskInstance existing = mgr.getActiveTask(uuid);
            if (existing != null && !existing.isFulfilled()
                    && existing.getFullId() != null
                    && existing.getFullId().startsWith("habitrain_core:")) {
                ServerPlayer existingPlayer = level.getServer().getPlayerList().getPlayer(uuid);
                if (existingPlayer != null) {
                    try {
                        existing.setFulfilled(true);
                        existing.getDefinition().onComplete(existingPlayer, existing);
                    } catch (Throwable t) {
                        HabiTrainCore.LOGGER.error(
                                "forceAssignRestorePowerToAllGood: failed to complete existing task {} for {}",
                                existing.getFullId(), uuid, t);
                    }
                }
            }

            mgr.removeActiveTask(uuid);
            mgr.clearBlackoutRotationFlag(uuid);

            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(uuid);
            if (sp != null) {
                try {
                    var taskComp = io.wifi.starrailexpress.cca.SREPlayerTaskComponent.KEY.get(sp);
                    if (taskComp != null) {
                        taskComp.clear();
                    }
                } catch (Throwable t) {
                    HabiTrainCore.LOGGER.error("forceAssignRestorePowerToAllGood: failed to clear SRE tasks for {}", uuid, t);
                }
            }

            TaskInstance instance = new TaskInstance(restoreDef);
            if (sp != null) {
                restoreDef.onAssign(sp, instance);
            }
            mgr.setActiveTask(uuid, instance);

            if (sp != null) {
                ActiveTaskPayload.sendToPlayer(sp, restoreDef.getFullId());
            }
        }
    }

    void reapplyPermanentBlackout(ServerLevel level) {
        if (level == null) return;
        try {
            var blackout = SREWorldBlackoutComponent.KEY.get(level);
            if (blackout != null && !blackout.isBlackoutActive()) {
                blackout.triggerBlackout(false, 600000);
                HabiTrainCore.LOGGER.debug("Re-applied permanent blackout via API (recovery)");
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("Failed to reapply blackout", e);
        }
    }

    void endSREBlackout(ServerLevel level) {
        if (level == null) return;
        var blackout = SREWorldBlackoutComponent.KEY.get(level);
        if (blackout != null) {
            blackout.reset();
        }
        syncManager.broadcast(level, "§a供电已恢复");
    }
}
```

- [ ] **Step 4: 创建 BlackoutSheriffResolver.java**

创建 `src/main/java/com/habitrain/core/game/blackout/BlackoutSheriffResolver.java`:

```java
package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.network.BlackoutAnnouncePayload;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class BlackoutSheriffResolver {
    void applyVoteResult(ServerLevel level, BlackoutSheriffVoteManager.VoteResolution resolution) {
        if (level == null || resolution == null) return;
        if (resolution.winnerIds().isEmpty()) return;

        try {
            java.util.Random random = new java.util.Random(level.getRandom().nextLong());
            Map<UUID, ServerPlayer> playerMap = new HashMap<>();
            for (ServerPlayer player : level.players()) {
                playerMap.put(player.getUUID(), player);
            }

            var gameWorld = SREGameWorldComponent.KEY.get(level);

            for (int i = 0; i < resolution.winnerIds().size(); i++) {
                UUID winnerId = resolution.winnerIds().get(i);
                boolean wasKiller = resolution.winnerWasKillers().get(i);
                ServerPlayer player = playerMap.get(winnerId);
                if (player == null) continue;

                BlackoutRoleManager.Faction currentFaction =
                        BlackoutRoleManager.getFaction(level, player.getUUID());

                if (wasKiller || currentFaction == BlackoutRoleManager.Faction.BAD) {
                    BlackoutRoleManager.setSheriff(level, player.getUUID());

                    var revolverItem = BuiltInRegistries.ITEM
                            .get(ResourceLocation.parse("trainmurdermystery:revolver"));
                    if (revolverItem != null && revolverItem != net.minecraft.world.item.Items.AIR) {
                        ItemStack gun = new ItemStack(revolverItem, 1);
                        boolean added = player.getInventory().add(gun);
                        if (!added) {
                            player.drop(gun, false);
                        }
                        SubtitleNotifier.sendTop(player,
                                Component.literal("§6警长入场"),
                                Component.literal("§6你被票选为警长，获得了一把左轮手枪。"),
                                80);
                    }
                    HabiTrainCore.LOGGER.info("[SheriffVote] killer {} voted as sheriff, kept killer identity + given revolver",
                            player.getName().getString());
                } else {
                    io.wifi.starrailexpress.api.SRERole policeRole = BlackoutRoleManager.getRandomPoliceRole(random);
                    if (policeRole == null) continue;
                    BlackoutRoleManager.setSheriff(level, player.getUUID(), policeRole, null);

                    String roleName = policeRole.getName().getString();
                    String subtitle = policeRole.getDescription().getString();
                    String goal = policeRole.getGoal().getString();
                    ServerPlayNetworking.send(player, new BlackoutAnnouncePayload(
                            roleName,
                            subtitle,
                            goal,
                            BlackoutRoleManager.getRemainingBad(level),
                            BlackoutRoleManager.getRemainingGood(level)
                    ));

                    var shop = SREPlayerShopComponent.KEY.get(player);
                    if (shop != null) {
                        shop.addToBalance(200);
                    }
                    SubtitleNotifier.sendTop(player,
                            Component.literal("§6警长入场"),
                            Component.literal("§6你因为被票选为警长获得了 200 金币。"),
                            80);
                }
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("Failed to grant sheriff vote reward", e);
        }
    }
}
```

- [ ] **Step 5: 创建 BlackoutTickCoordinator.java**

创建 `src/main/java/com/habitrain/core/game/blackout/BlackoutTickCoordinator.java`:

```java
package com.habitrain.core.game.blackout;

import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;

class BlackoutTickCoordinator {
    private final BlackoutMode mode;
    private final BlackoutVictoryChecker victoryChecker;
    private final BlackoutSyncManager syncManager;
    private final BlackoutSheriffResolver sheriffResolver;

    private int tickAccumulator = 0;
    private boolean sreGameRunning = false;
    private boolean cachedSreActive = false;

    BlackoutTickCoordinator(BlackoutMode mode, BlackoutVictoryChecker victoryChecker,
                             BlackoutSyncManager syncManager,
                             BlackoutSheriffResolver sheriffResolver) {
        this.mode = mode;
        this.victoryChecker = victoryChecker;
        this.syncManager = syncManager;
        this.sheriffResolver = sheriffResolver;
    }

    void onSreGameStarted(ServerLevel level) {
        cachedSreActive = true;
    }

    void onSreGameEnded(ServerLevel level) {
        cachedSreActive = false;
    }

    void onPreStart() {
        tickAccumulator = 0;
        sreGameRunning = false;
        cachedSreActive = false;
    }

    void tick(ServerLevel level) {
        if (level != mode.getCurrentLevel() || mode.isGameEnded()) return;

        // 首次进入时检查 SRE 状态（兜底，防止事件 listener 未触发）
        if (!cachedSreActive && !sreGameRunning) {
            var sreGame = SREGameWorldComponent.KEY.get(level);
            boolean sreActive = sreGame != null && sreGame.isRunning();
            if (sreActive) {
                cachedSreActive = true;
            } else {
                return;
            }
        }

        if (cachedSreActive && !sreGameRunning) {
            sreGameRunning = true;
        }

        if (!cachedSreActive && sreGameRunning) {
            sreGameRunning = false;
            victoryChecker.endGame(level, "游戏结束");
            return;
        }

        if (!cachedSreActive) return;

        tickAccumulator++;
        if (tickAccumulator % 20 == 0) {
            BlackoutTimerSystem.tickSecond(level);

            syncManager.tickSecond(level);

            BlackoutSheriffVoteManager.tickSecond(level)
                    .ifPresent(res -> sheriffResolver.applyVoteResult(level, res));

            victoryChecker.tickSecond(level);

            if (mode.getCurrentLevel() == null || mode.isGameEnded()) return;

            if (tickAccumulator % 40 == 0 && BlackoutTimerSystem.isPermanentBlackoutActive(level)) {
                victoryChecker.reapplyPermanentBlackout(level);
            }
        }
    }
}
```

- [ ] **Step 6: 重写 BlackoutMode.java 为骨架**

将 `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java` 精简为：

```java
package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskInstance;
import com.habitrain.core.api.WinResult;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BlackoutMode implements GameMode {

    public static final String MODE_ID = "habitrains:blackout";
    public static final String MODE_DISPLAY = "停电模式";

    public static final TaskCategory BLACKOUT_GOOD =
            new TaskCategory("habitrain:blackout_good", "好人任务", MODE_ID);
    public static final TaskCategory BLACKOUT_BAD =
            new TaskCategory("habitrain:blackout_bad", "坏人任务", MODE_ID);

    public static final Set<String> ONCE_PER_GAME_TASK_IDS =
            Collections.unmodifiableSet(new HashSet<>(List.of("habitrain_core:furnace_explosion")));

    private ServerLevel currentLevel;
    private boolean gameEnded = false;
    private String pendingEndMessage = null;

    private final Set<String> assignedOncePerGameTasks = new HashSet<>();

    private final BlackoutSyncManager syncManager = new BlackoutSyncManager();
    private final BlackoutVictoryChecker victoryChecker = new BlackoutVictoryChecker(this, syncManager);
    private final BlackoutSheriffResolver sheriffResolver = new BlackoutSheriffResolver();
    private final BlackoutTickCoordinator tickCoordinator =
            new BlackoutTickCoordinator(this, victoryChecker, syncManager, sheriffResolver);

    private static volatile BlackoutRoleManager.Faction lastWinningFaction = null;

    ServerLevel getCurrentLevel() { return currentLevel; }
    boolean isGameEnded() { return gameEnded; }
    void setGameEnded(boolean v) { gameEnded = v; }
    void setPendingEndMessage(String m) { pendingEndMessage = m; }
    void setLastWinningFaction(BlackoutRoleManager.Faction f) { lastWinningFaction = f; }

    @Override
    public String getId() { return MODE_ID; }

    @Override
    public String getDisplayName() { return MODE_DISPLAY; }

    @Override
    public List<TaskCategory> getTaskCategories() { return List.of(BLACKOUT_GOOD, BLACKOUT_BAD); }

    @Override
    public boolean isActive(ServerLevel level) {
        return currentLevel != null && currentLevel.dimension().equals(level.dimension());
    }

    @Override
    public void onPreStart(ServerLevel level) {
        currentLevel = level;
        gameEnded = false;
        pendingEndMessage = null;
        lastWinningFaction = null;
        assignedOncePerGameTasks.clear();

        BlackoutRoleManager.clear(level);
        BlackoutSheriffVoteManager.reset(level);
        BlackoutTimerSystem.init(level,
                () -> victoryChecker.triggerSREPermanentBlackout(currentLevel),
                () -> victoryChecker.endSREBlackout(currentLevel),
                () -> {});
        BlackoutShopService.resetRound(level);
        syncManager.onPreStart();
        syncManager.syncReset(level);
        tickCoordinator.onPreStart();
    }

    @Override
    public void onStart(ServerLevel level) {
        ResourceLocation blackoutModeId = ResourceLocation.fromNamespaceAndPath("sre", "blackout");
        var sreMode = SREGameModes.GAME_MODES.get(blackoutModeId);
        if (sreMode == null) {
            HabiTrainCore.LOGGER.error("SREBlackoutGameMode not found!");
            return;
        }
        var sreGame = SREGameWorldComponent.KEY.get(level);
        if (sreGame != null && !sreGame.isRunning()) {
            GameUtils.startGame(level, sreMode,
                    GameConstants.getInTicks(((io.wifi.starrailexpress.api.GameMode) sreMode).defaultStartTime, 0));
        }
    }

    @Override
    public void onTick(ServerLevel level) {
        tickCoordinator.tick(level);
    }

    @Override
    public void onTaskComplete(ServerPlayer player, TaskInstance task) {
        if (currentLevel != null && player != null && task != null) {
            TaskCategory cat = task.getDefinition().getCategory();
            if (BLACKOUT_BAD.equals(cat)) {
                onKillerRealTaskComplete(player, task);
            } else if (BLACKOUT_GOOD.equals(cat)) {
                onKillerFakeTaskComplete(player, task);
            }
        }
        victoryChecker.checkVictory(currentLevel);
    }

    @Override
    public void onTaskAssign(ServerPlayer player, TaskInstance task) {
        if (task != null && ONCE_PER_GAME_TASK_IDS.contains(task.getFullId())) {
            assignedOncePerGameTasks.add(task.getFullId());
            HabiTrainCore.LOGGER.info("[Blackout] Once-per-game task {} assigned to {}, will not reassign this round",
                    task.getFullId(), player.getName().getString());
        }
    }

    protected void onKillerRealTaskComplete(ServerPlayer player, TaskInstance task) {}
    protected void onKillerFakeTaskComplete(ServerPlayer player, TaskInstance task) {}

    @Override
    public void onPlayerJoin(ServerPlayer player) {
        BlackoutSheriffVoteManager.onPlayerJoined(currentLevel, player);
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        BlackoutRoleManager.eliminate(currentLevel, player.getUUID());
        victoryChecker.checkVictory(currentLevel);
    }

    @Override
    public void onEnd(ServerLevel level, WinResult result) {
        String message = pendingEndMessage != null ? pendingEndMessage : "结束对局";
        syncManager.broadcast(level, message);
        pendingEndMessage = null;
        syncManager.syncReset(level);
        currentLevel = null;
    }

    @Override
    public void onCleanup(ServerLevel level) {
        syncManager.syncReset(level);
        BlackoutSheriffVoteManager.reset(level);
        BlackoutTimerSystem.reset(level);
        BlackoutShopService.resetRound(level);
        currentLevel = null;
        gameEnded = false;
        pendingEndMessage = null;
    }

    @Override
    public List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player) {
        return tasks.stream()
                .filter(t -> {
                    TaskCategory cat = t.getCategory();
                    return BLACKOUT_GOOD.equals(cat) || BLACKOUT_BAD.equals(cat);
                })
                .filter(t -> {
                    if (ONCE_PER_GAME_TASK_IDS.contains(t.getFullId())
                            && assignedOncePerGameTasks.contains(t.getFullId())) {
                        HabiTrainCore.LOGGER.debug("[Blackout] Excluding once-per-game task {} (already assigned this round)",
                                t.getFullId());
                        return false;
                    }
                    return true;
                })
                .toList();
    }

    public static BlackoutRoleManager.Faction getLastWinningFaction() {
        return lastWinningFaction;
    }

    public static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }

    public static void broadcast(ServerLevel level, String message) {
        if (level == null) return;
        var component = net.minecraft.network.chat.Component.literal(message);
        for (ServerPlayer player : level.players()) {
            com.habitrain.core.util.SubtitleNotifier.sendTop(player, net.minecraft.network.chat.Component.empty(), component, 80);
        }
    }
}
```

**注意**：上面 import 区有 SRE 相关 import，Java 不支持 `as` 别名语法——确保不要写 `import ... as ...`。原 BlackoutMode 也没有这种写法。

- [ ] **Step 7: 修改 HabiTrainCoreClient.java 的 BlackoutTimerPayload 接收**

在 `src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java:192-203`，把：
```java
ClientPlayNetworking.registerGlobalReceiver(BlackoutTimerPayload.TYPE, (payload, ctx) -> {
    ctx.client().execute(() -> {
        if (payload.totalTimeRemaining() <= 0) {
            BlackoutHudOverlay.reset();
            BlackoutWelcomeRenderer.reset();
            return;
        }
        BlackoutHudOverlay.updateTime(
            payload.totalTimeRemaining(), payload.blackoutCountdown(), payload.blackoutActive(), payload.phase());
    });
});
```
改为：
```java
ClientPlayNetworking.registerGlobalReceiver(BlackoutTimerPayload.TYPE, (payload, ctx) -> {
    ctx.client().execute(() -> {
        if (payload.totalTimeRemaining() <= 0) {
            BlackoutHudOverlay.reset();
            BlackoutWelcomeRenderer.reset();
            return;
        }
        BlackoutHudOverlay.updateTime(
            payload.totalTimeRemaining(), payload.endTimeTick(), payload.blackoutActive(), payload.phase());
    });
});
```

- [ ] **Step 8: 修改 BlackoutHudOverlay.java 支持本地 countdown 计算**

在 `src/main/java/com/habitrain/core/client/gui/BlackoutHudOverlay.java`：

1. 找到 `updateTime` 方法。原签名是 `updateTime(int totalTime, int countdown, boolean active, int phase)`。改为 `updateTime(int totalTime, long endTimeTick, boolean active, int phase)`。

2. 缓存 `endTimeTick` 而非 `countdown`：
```java
private static long cachedEndTimeTick;
private static long cachedServerTickBase;
```

3. 在 `updateTime` 中存 `endTimeTick` 并用 `Minecraft.getInstance().level.getGameTime()` 算本地 countdown：
```java
public static void updateTime(int totalTime, long endTimeTick, boolean active, int phase) {
    showHud = true;
    totalTimeRemaining = totalTime;
    cachedEndTimeTick = endTimeTick;
    blackoutActive = active;
    currentPhase = phase;
    // countdown 在 render 时本地算
}

private static int getLocalCountdown() {
    var level = Minecraft.getInstance().level;
    if (level == null) return 0;
    long now = level.getGameTime();
    return (int) Math.max(0, cachedEndTimeTick - now);
}
```

4. 在 render 方法中，把原来用 `blackoutCountdown` 字段的地方改为调 `getLocalCountdown()`。

**注意**：需先读 BlackoutHudOverlay.java 确认现有字段名和 render 逻辑，再精确替换。

- [ ] **Step 9: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。如遇编译错误，按错误信息修正（主要是 import 缺失、字段名不匹配、typo）。

- [ ] **Step 10: Commit**

```powershell
git add -A
git commit -m "refactor: split BlackoutMode into 5 classes + timer diff-broadcast + SRE state cache"
```

🎮 游戏内验证（关键）：跑完整停电对局（20+ 人），确认：
- 倒计时显示正常（无跳变、无漂移）
- phase 切换立即更新
- permanent 切换立即更新
- 投票正常
- 理智判死正常
- 胜负判定正常
- 重置正常

---

## Task 6: GenerateTaskMixin 拆分 4 类 + TaskPoolBuilder 缓存

**Files:**
- Create: `src/main/java/com/habitrain/core/game/sre/FactionFilter.java`
- Create: `src/main/java/com/habitrain/core/task/TaskPoolBuilder.java`
- Create: `src/main/java/com/habitrain/core/game/sre/TaskWeightCurves.java`
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java`（精简为 @Inject 入口 + 委托）
- Modify: `src/main/java/com/habitrain/core/api/TaskRegistry.java`（freeze 时调 TaskPoolBuilder.invalidateAll）
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java`（setTaskConfig 时调 TaskPoolBuilder.invalidateAll）

**Interfaces:**
- Consumes: `TaskRegistry`, `TaskDefinition`, `TaskCategory`, `GameMode`, `ConfigManager`, `TaskConfigEntry`
- Produces:
  - `FactionFilter.determineFaction(player, tasks, activeMode, mgr)` → `FactionContext`
  - `TaskPoolBuilder.getPool(activeMode, mapName, forcedCategory, currentCategory, player)` → `List<TaskDefinition>`
  - `TaskPoolBuilder.invalidateAll()` / `invalidate(modeId)`
  - `TaskWeightCurves.applyWeights(pool, timesGotten, moodComponent, factionContext)` → `List<WeightedTask>`

**关键约束**：helper 是普通 Java 类，不持有 Mixin 引用。@Shadow 字段作为参数传入。先试拆分 Mixin 本身——但 `GenerateTaskMixin` 只有一个 `@Inject` 入口，实际不需要拆 Mixin 类本身，只在 Mixin 内部委托 helper 即可（等同 helper 模式）。

- [ ] **Step 1: 创建 FactionFilter.java**

创建 `src/main/java/com/habitrain/core/game/sre/FactionFilter.java`。从 `GenerateTaskMixin` 中抽出 `isKillerDualTaskMode`、`resolveActiveGameMode`、forcedCategory 判定逻辑、blackout 日常/供给池过滤逻辑。

**注意**：需要先读 `GenerateTaskMixin.java:120-450` 完整理解 faction 逻辑，再抽取出 `determineFaction` 方法返回 `FactionContext(forcedCategory, killerDualTask, isParallelCall, currentIsFakeTask, activeMode, currentCategory)`。

- [ ] **Step 2: 创建 TaskPoolBuilder.java**

创建 `src/main/java/com/habitrain/core/task/TaskPoolBuilder.java`。从 `GenerateTaskMixin` 抽出 `getAvailableDlcTasks`、`isBuiltinSreTask`、`isTaskMapEnabled`、`isTaskAllowedForPool`。加缓存层（`PoolKey(modeId, mapName, categoryId)` → `List<TaskDefinition>`）。

- [ ] **Step 3: 创建 TaskWeightCurves.java**

创建 `src/main/java/com/habitrain/core/game/sre/TaskWeightCurves.java`。从 `GenerateTaskMixin` 抽出 `computeUrgencyMultiplier`、`computeSurvivalMultiplier`、`addOriginalTasks`、`shouldIncludeOriginalTasks`、防重复权重、mood 权重逻辑。

- [ ] **Step 4: 改造 GenerateTaskMixin.java 为委托入口**

将 `GenerateTaskMixin.java` 精简为：保留所有 `@Shadow` + `@Inject`，方法体内部调用 `FactionFilter.determineFaction(...)` → `TaskPoolBuilder.getPool(...)` → `TaskWeightCurves.applyWeights(...)`，然后选任务 + `createTaskInstance` + `cir.setReturnValue`。

- [ ] **Step 5: 在 TaskRegistry.freeze() 调 TaskPoolBuilder.invalidateAll()**

在 `src/main/java/com/habitrain/core/api/TaskRegistry.java` 的 `freeze()` 方法末尾添加：
```java
com.habitrain.core.task.TaskPoolBuilder.invalidateAll();
```

- [ ] **Step 6: 在 ConfigManager.setTaskConfig 调 TaskPoolBuilder.invalidateAll()**

在 `src/main/java/com/habitrain/core/config/ConfigManager.java` 的 `setTaskConfig` 方法末尾添加：
```java
com.habitrain.core.task.TaskPoolBuilder.invalidateAll();
```

- [ ] **Step 7: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m "refactor: split GenerateTaskMixin into FactionFilter/TaskPoolBuilder/TaskWeightCurves + pool caching"
```

🎮 游戏内验证（关键）：多次派发任务（普通模式、停电模式好人/坏人、杀手双任务），确认候选池正确，无空池，无跨阵营泄漏；OP 改 config 后下次派发用新池。

---

## Task 7: HabiTrainCore 拆分 4 类

**Files:**
- Create: `src/main/java/com/habitrain/core/BuiltinTaskRegistrar.java`
- Create: `src/main/java/com/habitrain/core/ModTickHandler.java`
- Create: `src/main/java/com/habitrain/core/LootHelper.java`
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java`（精简为 init wiring）

**Interfaces:**
- Consumes: `TaskRegistry`, `ConfigManager`, `GameModeRegistry`, 各种 payload register
- Produces:
  - `BuiltinTaskRegistrar.register()` — 注册 4 个内置任务
  - `ModTickHandler.register()` — 注册 per-tick 调度
  - `ModTickHandler.tickMoreMods(server)` — per-tick 更多模组处理
  - `LootHelper.giveRandomBackpackItem(player, faction)` — 背包道具发放

- [ ] **Step 1: 创建 LootHelper.java**

从 `HabiTrainCore.java:585-695`（`giveRandomBackpackItem` 方法）抽出到 `src/main/java/com/habitrain/core/LootHelper.java`。

- [ ] **Step 2: 创建 BuiltinTaskRegistrar.java**

从 `HabiTrainCore.java:347-583`（`registerMoreTasks` 中的 4 个内置任务注册）抽出到 `src/main/java/com/habitrain/core/BuiltinTaskRegistrar.java`。注意 `look_my_eyes` 的 onTick 已在 Task 2 改为 AABB 查询，保留改动。

- [ ] **Step 3: 创建 ModTickHandler.java**

从 `HabiTrainCore.java:207-214`（END_SERVER_TICK listener）+ `:715-736`（`tickMoreMods`）抽出到 `src/main/java/com/habitrain/core/ModTickHandler.java`。

- [ ] **Step 4: 精简 HabiTrainCore.java**

`HabiTrainCore.onInitialize()` 改为调用：
```java
ConfigManager.getInstance().load();
registerGameModes();
registerPayloads();
registerCommands();
registerLifecycleEvents();
BuiltinTaskRegistrar.register();
ModTickHandler.register();
initBetelSystem();
registerMoreSounds();
```

移除 `registerMoreTasks`（已委托 BuiltinTaskRegistrar）、`tickMoreMods`（已委托 ModTickHandler）、`giveRandomBackpackItem`（已委托 LootHelper）。

- [ ] **Step 5: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```powershell
git add -A
git commit -m "refactor: split HabiTrainCore into BuiltinTaskRegistrar/ModTickHandler/LootHelper"
```

---

## Task 8: BetelQuestState 拆分 4 类

**Files:**
- Create: `src/main/java/com/habitrain/core/betel/BetelTickEngine.java`
- Create: `src/main/java/com/habitrain/core/betel/BetelFoodRestriction.java`
- Create: `src/main/java/com/habitrain/core/betel/BetelWithdrawal.java`
- Modify: `src/main/java/com/habitrain/core/betel/BetelQuestState.java`（精简为 state 容器）
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java`（initBetelSystem 改为调 BetelFoodRestriction.register + BetelTickEngine.register）

**Interfaces:**
- Consumes: `BetelNutConfig`, `BetelNutEntityComponents`, `EffectOwnershipTracker`, SRE components
- Produces:
  - `BetelTickEngine.tickPlayer(player)` — per-tick 检测
  - `BetelTickEngine.isGameActive(level)` — 游戏激活检查
  - `BetelFoodRestriction.register()` — 注册食物限制 callback
  - `BetelWithdrawal.applyEffects(player, stage)` — 应用戒断效果

- [ ] **Step 1: 先读 BetelQuestState.java 完整内容**

读 `src/main/java/com/habitrain/core/betel/BetelQuestState.java`（534 行），理解 per-tick 逻辑、UseItemCallback、戒断效果、stage 转换。

- [ ] **Step 2: 创建 BetelWithdrawal.java**

从 `BetelQuestState` 抽出戒断效果应用逻辑（DARKNESS/SLOWNESS/PEED + EffectOwnershipTracker 协调）。

- [ ] **Step 3: 创建 BetelFoodRestriction.java**

从 `BetelQuestState` 抽出 `UseItemCallback` 食物限制逻辑。

- [ ] **Step 4: 创建 BetelTickEngine.java**

从 `BetelQuestState` 抽出 `tickPlayer`、`isGameActive`、吃食检测、角色暴露逻辑。

- [ ] **Step 5: 精简 BetelQuestState.java**

仅保留 `PlayerBetelData` map + getter/setter + `resetAll`。

- [ ] **Step 6: 修改 HabiTrainCore.initBetelSystem**

把 `BetelQuestState.registerFoodRestriction()` 改为 `BetelFoodRestriction.register()`。
把 `BetelQuestState.tickPlayer` 调用改为 `BetelTickEngine.tickPlayer`（在 ModTickHandler 中）。
把 `BetelQuestState.isGameActive` 调用改为 `BetelTickEngine.isGameActive`（在 ModTickHandler 中）。

- [ ] **Step 7: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m "refactor: split BetelQuestState into BetelTickEngine/BetelFoodRestriction/BetelWithdrawal"
```

🎮 游戏内验证：跑槟榔任务，确认成瘾/戒断/暴露正常。

---

## Task 9: ConfigManager 拆分 5 类

**Files:**
- Create: `src/main/java/com/habitrain/core/config/ConfigStore.java`
- Create: `src/main/java/com/habitrain/core/config/ConfigRepository.java`
- Create: `src/main/java/com/habitrain/core/config/ConfigSync.java`
- Create: `src/main/java/com/habitrain/core/config/MinigameEnforcement.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java`（精简为 facade）

**Interfaces:**
- Consumes: Gson, `TaskRegistry`, SRE `AreasWorldComponent`
- Produces:
  - `ConfigStore.save(repo)` / `load()` — 文件 IO
  - `ConfigRepository.getTaskConfig(id)` / `putTaskConfig(id, entry)` — cache
  - `ConfigSync.applySyncData(...)` / `broadcastToClients()` — 网络
  - `MinigameEnforcement.apply(server)` — 写入 SRE

**关键约束**：`ConfigManager` 是 singleton，被大量热路径调用。facade 保持所有现有 public 方法签名不变（getter 委托 `ConfigRepository`，setter 委托 `ConfigRepository` + `ConfigStore.save` + `ConfigSync.broadcastToClients`）。

- [ ] **Step 1: 先读 ConfigManager.java 完整内容**

读 `src/main/java/com/habitrain/core/config/ConfigManager.java`（494 行），理解 load/save/applySyncData/applyMinigameEnforcement/setOnSaveCallback 逻辑。

- [ ] **Step 2: 创建 ConfigRepository.java**

从 `ConfigManager` 抽出所有 in-memory map（taskConfigs/gameModeConfigs/minigameConfigs/shaderWhitelist）+ getter/setter。

- [ ] **Step 3: 创建 ConfigStore.java**

从 `ConfigManager` 抽出 `load`、`save`、`createDefaultConfig`、`buildJsonRoot`、`toJsonString`、`loadFromJsonString`。

- [ ] **Step 4: 创建 ConfigSync.java**

从 `ConfigManager` 抽出 `applySyncData`、`applyShaderWhitelistSync`、`setOnSaveCallback`、broadcast 逻辑。

- [ ] **Step 5: 创建 MinigameEnforcement.java**

从 `ConfigManager` 抽出 `applyMinigameEnforcement`。

- [ ] **Step 6: 精简 ConfigManager.java 为 facade**

`ConfigManager` 保留 singleton 结构，持有 `ConfigStore`/`ConfigRepository`/`ConfigSync`/`MinigameEnforcement`，所有 public 方法委托。`setTaskConfig` 末尾调 `TaskPoolBuilder.invalidateAll()`（如 Task 6 已完成）。

- [ ] **Step 7: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m "refactor: split ConfigManager into ConfigStore/ConfigRepository/ConfigSync/MinigameEnforcement facade"
```

🎮 游戏内验证：OP 通过 ModMenu 改配置 + 网络同步，确认保存/同步/重启后保留 + minigame 强制生效。

---

## Task 10: BlackoutSheriffVoteManager 拆分 2 类 + Vote diff-broadcast

**Files:**
- Create: `src/main/java/com/habitrain/core/game/blackout/SheriffVoteBroadcaster.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutSheriffVoteManager.java`（精简为 state，委托 broadcaster）

**Interfaces:**
- Consumes: `BlackoutSheriffVotePayload`, `VoteState`
- Produces:
  - `SheriffVoteBroadcaster.tickSecond(level, state)` — diff-broadcast
  - `SheriffVoteBroadcaster.resetCache()`

- [ ] **Step 1: 创建 SheriffVoteBroadcaster.java**

创建 `src/main/java/com/habitrain/core/game/blackout/SheriffVoteBroadcaster.java`。从 `BlackoutSheriffVoteManager` 抽出 `syncToAll`，加 hash 缓存 + diff 逻辑。

- [ ] **Step 2: 改造 BlackoutSheriffVoteManager.java**

`syncToAll` 改为委托 `SheriffVoteBroadcaster.broadcast(level, state)`。`tickSecond` 中调 `SheriffVoteBroadcaster.tickSecond`。`onPlayerRemoved`/`castVote` 中调 `SheriffVoteBroadcaster.tickSecond`（变化时广播）。

- [ ] **Step 3: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```powershell
git add -A
git commit -m "refactor: split BlackoutSheriffVoteManager + add vote diff-broadcast"
```

🎮 游戏内验证：跑警长投票，确认界面更新正常（窗口开关、新票、倒计时），无延迟。

---

## Task 11: SREPlayerTaskComponentMixin 抽 helper + TaskEditScreen 拆分

**Files:**
- Create: `src/main/java/com/habitrain/core/game/sre/PerPlayerTaskTicker.java`
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/SREPlayerTaskComponentMixin.java`（精简为 @Inject + 委托）
- Create: `src/main/java/com/habitrain/core/client/gui/TaskColorPicker.java`
- Create: `src/main/java/com/habitrain/core/client/gui/TaskMapFilterEditor.java`
- Create: `src/main/java/com/habitrain/core/client/gui/TaskSaveController.java`
- Modify: `src/main/java/com/habitrain/core/client/gui/TaskEditScreen.java`（精简为外壳 + 委托）

**Interfaces:**
- Consumes: `TaskInstance`, `TaskManager`, `ActiveTaskPayload`
- Produces:
  - `PerPlayerTaskTicker.tick(player, customTask, fakeTask)` — per-player task tick
  - `TaskColorPicker` — GUI 组件
  - `TaskMapFilterEditor` — GUI 组件
  - `TaskSaveController` — 保存逻辑

- [ ] **Step 1: 创建 PerPlayerTaskTicker.java**

从 `SREPlayerTaskComponentMixin` 抽出 `customTask.tick`/`fakeTask.tick` 调度 + killer 并行任务生成逻辑。

- [ ] **Step 2: 精简 SREPlayerTaskComponentMixin.java**

保留 `@Shadow` + `@Inject`，方法体调 `PerPlayerTaskTicker.tick(...)`。

- [ ] **Step 3: 先读 TaskEditScreen.java**

读 `src/main/java/com/habitrain/core/client/gui/TaskEditScreen.java`（720 行），理解颜色选择器、地图过滤编辑、保存逻辑边界。

- [ ] **Step 4: 创建 TaskColorPicker.java**

从 `TaskEditScreen` 抽出颜色选择器组件。

- [ ] **Step 5: 创建 TaskMapFilterEditor.java**

从 `TaskEditScreen` 抽出地图过滤编辑组件。

- [ ] **Step 6: 创建 TaskSaveController.java**

从 `TaskEditScreen` 抽出保存逻辑。

- [ ] **Step 7: 精简 TaskEditScreen.java**

保留为外壳，持有 3 个组件，仅协调布局 + 委托。

- [ ] **Step 8: 构建验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```powershell
git add -A
git commit -m "refactor: extract PerPlayerTaskTicker helper + split TaskEditScreen into 3 components"
```

🎮 游戏内验证：ModMenu 打开任务编辑界面，确认颜色选择/地图过滤/保存全正常。

---

## Self-Review Checklist

完成所有任务后，对照 spec 检查：

- [ ] Spec 第三章 8 个优化点是否全部实现？（1:AABB 2:SlownessReapply 3:Timer diff 4:Vote diff 5:Pool cache 6:SRE cache 7:Iris throttle 8:死代码删除）
- [ ] Spec 第四章 7 个拆分是否全部完成？（BlackoutMode/GenerateTaskMixin/HabiTrainCore/BetelQuestState/ConfigManager/BlackoutSheriffVoteManager/SREPlayerTaskComponentMixin+TaskEditScreen）
- [ ] Spec 第九章文件改动清单中 23 个新增文件是否全部创建？
- [ ] Spec 第九章 2 个删除文件是否已删？
- [ ] 所有 commit 后 `./gradlew build` 是否通过？
- [ ] 关键游戏内验证点（commit 3/5/6/9 之后）是否已跑？