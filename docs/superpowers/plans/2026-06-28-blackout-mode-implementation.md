# 停电模式 (Blackout Mode) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the `habitrains:blackout` game mode with timer system, voting, TACZ weapon bridge, and 4 blackout-specific tasks split across `habitrain_core` (API layer) and `habitrain_more_tasks` (content layer).

**Architecture:** `BlackoutMode` implements `GameMode` from the existing framework, using `BlackoutTimerSystem` (static methods) + `BlackoutRoleManager` for core state, `BlackoutVotingEngine` for sheriff election, and `TACZWeaponBridge` for TACZ ↔ SRE death bridge. The content layer registers 4 tasks (2 good, 2 bad) via `TaskRegistry`, calling back into `BlackoutTimerSystem`'s static methods. Network payloads sync timer/vote/status to clients. HUD overlay renders time info. All changes are isolated to the blackout package.

**Tech Stack:** Fabric 1.21.1, SRE (starrailexpress) as hard dependency, TACZ-Refabricated as hard dependency, Java 21.

## Global Constraints

- All new code goes in `game/blackout/` package — do not modify existing SRE Murder/Repair modes
- Use `com.habitrain.core.game.blackout` for core API layer files
- Use `com.habitrain.moretasks.game.blackout` for content layer task files
- Network payloads follow the existing `CustomPayload` pattern (see `network/` existing files)
- Task IDs: `habitrain_more_tasks:add_coal`, `habitrain_more_tasks:repair_wiring`, `habitrain_more_tasks:sabotage_wiring`, `habitrain_more_tasks:furnace_explosion`
- Task categories: `habitrain:blackout_good` (好人任务), `habitrain:blackout_bad` (坏人任务)
- TACZ is a mod dependency (`depends` in fabric.mod.json) — use direct class references, no reflection
- Commands use OP level 2 (`source.hasPermission(2)`)

---

### Task 1: Add lifecycle management to GameModeRegistry

**Files:**
- Modify: `src/main/java/com/habitrain/core/api/GameModeRegistry.java`
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java`

**Interfaces:**
- Consumes: existing `GameMode` interface + existing `GameModeRegistry`
- Produces: `GameModeRegistry.start(String fullId, ServerLevel level)` and `GameModeRegistry.stop(ServerLevel level)` — used by `BlackoutMode` to manage active state

This task adds explicit start/stop lifecycle tracking to `GameModeRegistry`. Currently modes are passive (they `isActive()` by probing SRE state). Blackout mode needs explicit activation via command.

- [ ] **Step 1: Add start/stop tracking to GameModeRegistry**

```java
// src/main/java/com/habitrain/core/api/GameModeRegistry.java
// Add new fields and methods:

private static final Map<ResourceKey<Level>, GameMode> ACTIVE_MODES = new HashMap<>();

/**
 * Start a GameMode in the given level. Calls onPreStart + onStart.
 * Throws if another mode is already active in this level.
 */
public static void start(String fullId, ServerLevel level) {
    ResourceKey<Level> levelKey = level.dimension();
    if (ACTIVE_MODES.containsKey(levelKey)) {
        throw new IllegalStateException("A GameMode is already active in " + levelKey.location());
    }
    GameMode mode = REGISTRY.get(fullId);
    if (mode == null) {
        throw new IllegalArgumentException("GameMode '" + fullId + "' is not registered");
    }
    ACTIVE_MODES.put(levelKey, mode);
    mode.onPreStart(level);
    mode.onStart(level);
    LOGGER.info("Started GameMode: {} in {}", fullId, levelKey.location());
}

/**
 * Stop the active GameMode in the given level. Calls onEnd + onCleanup.
 * No-op if no mode is active.
 */
public static void stop(ServerLevel level) {
    ResourceKey<Level> levelKey = level.dimension();
    GameMode mode = ACTIVE_MODES.remove(levelKey);
    if (mode != null) {
        mode.onEnd(level, null);
        mode.onCleanup(level);
        LOGGER.info("Stopped GameMode: {} in {}", mode.getId(), levelKey.location());
    }
}

/**
 * Tick all active GameModes. Call from ServerTickEvents.END_SERVER_TICK.
 */
public static void tickAll(MinecraftServer server) {
    for (ServerLevel level : server.getAllLevels()) {
        ResourceKey<Level> levelKey = level.dimension();
        GameMode mode = ACTIVE_MODES.get(levelKey);
        if (mode != null) {
            mode.onTick(level);
        }
    }
}

/**
 * Get the active GameMode in a level (also checks passive isActive() as fallback).
 */
public static Optional<GameMode> getActiveForLevel(ServerLevel level) {
    ResourceKey<Level> levelKey = level.dimension();
    GameMode explicit = ACTIVE_MODES.get(levelKey);
    if (explicit != null) return Optional.of(explicit);
    // fallback: passive check
    return REGISTRY.values().stream()
            .filter(m -> m.isActive(level))
            .findFirst();
}

public static boolean isActiveInLevel(ServerLevel level) {
    return ACTIVE_MODES.containsKey(level.dimension());
}
```

- [ ] **Step 2: Wire tick + join/leave events in HabiTrainCore**

Add to `registerLifecycleEvents()` in `HabiTrainCore.java`:

```java
// In the existing ServerTickEvents.END_SERVER_TICK, APPEND to the existing lambda:
ServerTickEvents.END_SERVER_TICK.register(server -> {
    SREGameModeBase.processPendingVoiceJoins(server);
    SREGameModeBase.processGameEndGroupJoin(server);
    // NEW: tick active game modes
    GameModeRegistry.tickAll(server);
});

// In the existing ServerPlayConnectionEvents.JOIN, APPEND:
ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
    // ... existing code ...
    
    // NEW: notify active game mode of player join
    ServerPlayer player = handler.getPlayer();
    ServerLevel level = server.getLevel(Level.OVERWORLD);
    if (level != null) {
        GameModeRegistry.getActiveForLevel(level)
            .ifPresent(mode -> mode.onPlayerJoin(player));
    }
});
```

Also add the new imports at the top:

```java
import com.habitrain.core.api.GameModeRegistry;
import net.minecraft.world.level.Level;
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — no errors in GameModeRegistry.java or HabiTrainCore.java.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/api/GameModeRegistry.java src/main/java/com/habitrain/core/HabiTrainCore.java
git commit -m "feat: add lifecycle management to GameModeRegistry (start/stop/tick)"
```

---

### Task 2: Create BlackoutTimerSystem + BlackoutRoleManager

**Files:**
- Create: `src/main/java/com/habitrain/core/game/blackout/BlackoutTimerSystem.java`
- Create: `src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java`

**Interfaces:**
- Consumes: nothing (no blackout package dependencies)
- Produces: static methods in `BlackoutTimerSystem` (reduceTime/addTime/delayBlackout/triggerBlackout/tick), enums + class in `BlackoutRoleManager` (assignRole/getFaction/getRole/setSheriff/...)

- [ ] **Step 1: Create BlackoutTimerSystem.java**

```java
package com.habitrain.core.game.blackout;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 停电模式 — 双计时器系统。
 * 管理总对局时间 (5分钟) + 停电倒计时 (2分钟循环)。
 * 所有方法均为静态，供 BlackoutMode (tick) 和任务层 (reduceTime/addTime 等) 调用。
 */
public class BlackoutTimerSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutTimer");

    private static int totalTimeRemaining = 300;       // 秒
    private static int blackoutCountdown = 120;         // 秒
    private static boolean blackoutActive = false;
    private static int blackoutElapsedTicks = 0;        // 停电已持续 tick 数
    private static boolean warningSent = false;         // 60s 预警是否已发

    private static ServerLevel currentLevel = null;
    private static Runnable onBlackoutStart = null;     // 回调: 调用 SRE 停电 API
    private static Runnable onBlackoutEnd = null;       // 回调: 调用 SRE 恢复供电 API
    private static Runnable onTimeWarning = null;       // 回调: 剩余60秒通知

    // ====== 初始化 ======

    public static void init(ServerLevel level, Runnable blackoutStartCb, Runnable blackoutEndCb, Runnable timeWarningCb) {
        totalTimeRemaining = 300;
        blackoutCountdown = 120;
        blackoutActive = false;
        blackoutElapsedTicks = 0;
        warningSent = false;
        currentLevel = level;
        onBlackoutStart = blackoutStartCb;
        onBlackoutEnd = blackoutEndCb;
        onTimeWarning = timeWarningCb;
        LOGGER.info("BlackoutTimerSystem initialized: 300s total, 120s blackout CD");
    }

    public static void reset() {
        currentLevel = null;
        onBlackoutStart = null;
        onBlackoutEnd = null;
        onTimeWarning = null;
    }

    // ====== 每秒更新 (由 BlackoutMode.onTick 调用, 每秒仅执行一次) ======

    public static void tickSecond() {
        if (currentLevel == null) return;

        // 停电中：计时
        if (blackoutActive) {
            blackoutElapsedTicks++;
            if (blackoutElapsedTicks >= 140) { // 7秒 × 20 tick
                // 恢复供电
                if (onBlackoutEnd != null) onBlackoutEnd.run();
                blackoutActive = false;
                blackoutCountdown = 120;
                blackoutElapsedTicks = 0;
                LOGGER.info("Blackout ended, reset CD to 120s");
            }
            return; // 停电期间不更新主计时器
        }

        // 正常状态：更新主计时器
        totalTimeRemaining--;
        if (totalTimeRemaining <= 60 && !warningSent) {
            warningSent = true;
            if (onTimeWarning != null) onTimeWarning.run();
        }

        // 更新停电倒计时
        blackoutCountdown--;
        if (blackoutCountdown <= 0) {
            // 触发停电
            if (onBlackoutStart != null) onBlackoutStart.run();
            blackoutActive = true;
            blackoutElapsedTicks = 0;
            LOGGER.info("Blackout triggered by countdown");
        }
    }

    // ====== 任务交互 API ======

    /** 好人任务: 减少总时间 (添加煤炭 → -30s) */
    public static void reduceTime(int seconds) {
        totalTimeRemaining = Math.max(0, totalTimeRemaining - seconds);
        LOGGER.info("Total time reduced by {}s, remaining: {}s", seconds, totalTimeRemaining);
    }

    /** 坏人任务: 增加总时间 (熔炉爆炸 → +15s) */
    public static void addTime(int seconds) {
        totalTimeRemaining += seconds;
        LOGGER.info("Total time increased by {}s, remaining: {}s", seconds, totalTimeRemaining);
    }

    /** 好人任务: 推迟停电倒计时 (维修线路 → +15s) */
    public static void delayBlackout(int seconds) {
        blackoutCountdown = Math.min(blackoutCountdown + seconds, 300); // 上限5分钟
        LOGGER.info("Blackout delayed by {}s, CD now: {}s", seconds, blackoutCountdown);
    }

    /** 坏人任务: 立即触发停电 (破坏线路 → 停电7s) */
    public static void triggerBlackout() {
        if (blackoutActive || currentLevel == null) return;
        if (onBlackoutStart != null) onBlackoutStart.run();
        blackoutActive = true;
        blackoutElapsedTicks = 0;
        LOGGER.info("Blackout triggered manually by task");
    }

    // ====== 读取器 ======

    public static int getTotalTimeRemaining() { return totalTimeRemaining; }
    public static int getBlackoutCountdown() { return blackoutActive ? 0 : blackoutCountdown; }
    public static boolean isBlackoutActive() { return blackoutActive; }
    public static boolean isTimeUp() { return totalTimeRemaining <= 0; }
}
```

- [ ] **Step 2: Create BlackoutRoleManager.java**

```java
package com.habitrain.core.game.blackout;

import java.util.*;

/**
 * 停电模式 — 阵营/职业管理器。
 * 预留扩展接口：后续添加新角色只需新增 enum 值 + 修改分配逻辑。
 */
public class BlackoutRoleManager {

    public enum Faction {
        GOOD,   // 好人阵营
        BAD     // 坏人阵营
    }

    public enum RoleType {
        CIVILIAN,  // 平民
        KILLER,    // 杀手
        SHERIFF,   // 警长（投票选出）
        // 预留扩展: FUTURE_ROLE_1, FUTURE_ROLE_2
    }

    private static final Map<UUID, RoleType> ROLES = new HashMap<>();
    private static final Map<UUID, Faction> FACTIONS = new HashMap<>();
    private static UUID sheriffId = null;

    public static void assignRole(UUID playerId, RoleType role, Faction faction) {
        ROLES.put(playerId, role);
        FACTIONS.put(playerId, faction);
    }

    public static Faction getFaction(UUID playerId) {
        return FACTIONS.getOrDefault(playerId, Faction.GOOD);
    }

    public static RoleType getRole(UUID playerId) {
        return ROLES.getOrDefault(playerId, RoleType.CIVILIAN);
    }

    public static boolean isAlive(UUID playerId) {
        return ROLES.containsKey(playerId);
    }

    /** 淘汰玩家 (被枪击杀等) */
    public static void eliminate(UUID playerId) {
        ROLES.remove(playerId);
        FACTIONS.remove(playerId);
        if (playerId.equals(sheriffId)) sheriffId = null;
    }

    // ====== 警长 ======

    public static void setSheriff(UUID playerId) {
        sheriffId = playerId;
        ROLES.put(playerId, RoleType.SHERIFF);
    }

    public static UUID getSheriff() { return sheriffId; }

    public static boolean isSheriff(UUID playerId) {
        return playerId.equals(sheriffId);
    }

    /** 非杀手可当选警长 */
    public static boolean canBeSheriff(UUID playerId) {
        return isAlive(playerId) && getRole(playerId) != RoleType.KILLER;
    }

    // ====== 阵营统计 ======

    public static int getRemainingCount(Faction faction) {
        return (int) FACTIONS.values().stream().filter(f -> f == faction).count();
    }

    public static int getRemainingGood() { return getRemainingCount(Faction.GOOD); }
    public static int getRemainingBad() { return getRemainingCount(Faction.BAD); }

    /** 获取所有存活玩家ID (排除已淘汰的) */
    public static List<UUID> getAllAlive() {
        return new ArrayList<>(ROLES.keySet());
    }

    /** 获取可被投票的玩家 (存活且非自己) */
    public static List<UUID> getVotablePlayers(UUID voterId) {
        return getAllAlive().stream()
                .filter(id -> !id.equals(voterId))
                .toList();
    }

    public static void clear() {
        ROLES.clear();
        FACTIONS.clear();
        sheriffId = null;
    }
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — both new files compile.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutTimerSystem.java src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java
git commit -m "feat: add BlackoutTimerSystem and BlackoutRoleManager"
```

---

### Task 3: Create BlackoutMode (GameMode implementation)

**Files:**
- Create: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`
- Modify: `src/main/java/com/habitrain/core/api/TaskCategory.java` (if constants needed)

**Interfaces:**
- Consumes: `BlackoutTimerSystem`, `BlackoutRoleManager`, `BlackoutVotingEngine` (will be created later, stub for now)
- Produces: `BlackoutMode implements GameMode` — full lifecycle with timer/wiring/stubs

- [ ] **Step 1: Create BlackoutMode.java**

```java
package com.habitrain.core.game.blackout;

import com.habitrain.core.api.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * 停电模式 GameMode 实现 — habitrains:blackout
 *
 * 生命周期由 GameModeRegistry.start/stop 驱动，
 * onTick 每秒调用一次 BlackoutTimerSystem.tickSecond()。
 */
public class BlackoutMode implements GameMode {

    public static final String MODE_ID = "habitrains:blackout";
    public static final String MODE_DISPLAY = "停电模式";

    public static final TaskCategory BLACKOUT_GOOD =
            new TaskCategory("habitrain:blackout_good", "好人任务", MODE_ID);
    public static final TaskCategory BLACKOUT_BAD =
            new TaskCategory("habitrain:blackout_bad", "坏人任务", MODE_ID);

    private ServerLevel currentLevel;
    private int tickAccumulator = 0;   // 用于将 20tick/s 转为 1tick/s 更新
    private boolean votingPhasePassed = false;  // 60s 投票阶段标识

    @Override
    public String getId() { return MODE_ID; }

    @Override
    public String getDisplayName() { return MODE_DISPLAY; }

    @Override
    public List<TaskCategory> getTaskCategories() {
        return List.of(BLACKOUT_GOOD, BLACKOUT_BAD);
    }

    /**
     * 主动模式: isActive 返回 GameModeRegistry 中是否有显式启动记录。
     * 被动 fallback 由 GameModeRegistry.getActiveForLevel 处理。
     */
    @Override
    public boolean isActive(ServerLevel level) {
        return currentLevel != null && currentLevel.dimension().equals(level.dimension());
    }

    // ====== 生命周期 ======

    @Override
    public void onPreStart(ServerLevel level) {
        this.currentLevel = level;
        this.tickAccumulator = 0;
        this.votingPhasePassed = false;

        BlackoutRoleManager.clear();
        BlackoutTimerSystem.init(level,
                this::triggerSREBlackout,       // 停电回调
                this::endSREBlackout,            // 恢复供电回调
                this::sendTimeWarning            // 60s 预警回调
        );
    }

    @Override
    public void onStart(ServerLevel level) {
        // 广播模式开始
        broadcast("§e⚡ 停电模式已启动！总时间: 5:00");
        broadcast("§7停电倒计时: 2:00 — 做好任务来应对停电！");
    }

    @Override
    public void onTick(ServerLevel level) {
        if (level != currentLevel) return;

        tickAccumulator++;
        // 每 20 tick (~1秒) 更新一次计数器和检查
        if (tickAccumulator % 20 == 0) {
            BlackoutTimerSystem.tickSecond();

            // 60秒投票阶段检查
            int totalRemaining = BlackoutTimerSystem.getTotalTimeRemaining();
            int elapsed = 300 - totalRemaining;
            if (!votingPhasePassed && elapsed >= 60) {
                votingPhasePassed = true;
                // 委托给 VotingEngine (空实现，Task 5 填充)
                onVotingPhaseStart();
            }

            // 检查胜利条件
            checkVictory();
        }

        // (未来) 每 tick 同步网络包
    }

    @Override
    public void onTaskComplete(ServerPlayer player, TaskInstance task) {
        // 任务完成时: 检查胜利条件
        checkVictory();
    }

    @Override
    public void onPlayerJoin(ServerPlayer player) {
        // 同步当前时间/状态给新玩家
        player.sendSystemMessage(Component.literal(
            "§e⚡ 当前游戏: 停电模式  剩余: §l" + formatTime(BlackoutTimerSystem.getTotalTimeRemaining()) + "§r"));
    }

    @Override
    public void onPlayerLeave(ServerPlayer player) {
        BlackoutRoleManager.eliminate(player.getUUID());
        checkVictory();
    }

    @Override
    public void onEnd(ServerLevel level, WinResult result) {
        broadcast("§6对局结束！");
        currentLevel = null;
    }

    @Override
    public void onCleanup(ServerLevel level) {
        BlackoutRoleManager.clear();
        BlackoutTimerSystem.reset();
        currentLevel = null;
    }

    @Override
    public List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player) {
        // 根据阵营过滤任务
        // 由 more_tasks 在注册任务时设置 category = BLACKOUT_GOOD 或 BLACKOUT_BAD
        // 这里按阵营分配: 好人只能接 GOOD, 坏人只能接 BAD
        // 暂按原样返回 — more_tasks 通过 TaskCategory 区分
        return tasks;
    }

    // ====== 内部方法 ======

    private void triggerSREBlackout() {
        // TODO: 调用 SRE 停电 API
        // 需研究 SRE 的停电触发方法
        broadcast("§c⚡ 停电了！黑暗笼罩一切...");
    }

    private void endSREBlackout() {
        // TODO: 调用 SRE 恢复供电 API
        broadcast("§a⚡ 供电已恢复");
    }

    private void sendTimeWarning() {
        broadcast("§e⚠ 仅剩 1 分钟！");
    }

    private void onVotingPhaseStart() {
        broadcast("§e【投票】现在可以投出你觉得的「警长」！按 [P] 键打开投票界面");
        // TODO: 委托 BlackoutVotingEngine 启动投票窗口 (Task 5)
    }

    private void checkVictory() {
        int goodRemaining = BlackoutRoleManager.getRemainingGood();
        int badRemaining = BlackoutRoleManager.getRemainingBad();

        // 杀手胜: 好人全灭
        if (goodRemaining <= 0) {
            endGame("§c杀手阵营获胜！所有好人都被淘汰了");
            return;
        }

        // 好人胜: 杀手全灭
        if (badRemaining <= 0) {
            endGame("§a好人阵营获胜！所有杀手已被消灭");
            return;
        }

        // 好人胜: 时间到0
        if (BlackoutTimerSystem.isTimeUp()) {
            endGame("§a好人阵营获胜！时间归零，好人成功存活！");
            return;
        }

        // 杀手胜: 5分钟到 → 由 onTick 检查总时间
        // (timeUp 已经是 totalTimeRemaining <= 0, 但杀手5分钟胜是 elapsed >= 300)
        int elapsed = 300 - BlackoutTimerSystem.getTotalTimeRemaining();
        if (elapsed >= 300 && !BlackoutTimerSystem.isTimeUp()) {
            // 5分钟耗尽 → 杀手胜 (好人没把时间减到0)
            endGame("§c杀手阵营获胜！5分钟已到，好人未能完成任务");
        }
    }

    private void endGame(String message) {
        broadcast(message);
        if (currentLevel != null) {
            com.habitrain.core.api.GameModeRegistry.stop(currentLevel);
        }
    }

    private void broadcast(String message) {
        if (currentLevel == null) return;
        Component component = Component.literal(message);
        for (ServerPlayer player : currentLevel.players()) {
            player.sendSystemMessage(component);
        }
    }

    public static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java
git commit -m "feat: add BlackoutMode GameMode implementation"
```

---

### Task 4: Create network payloads

**Files:**
- Create: `src/main/java/com/habitrain/core/network/BlackoutTimerPayload.java`
- Create: `src/main/java/com/habitrain/core/network/BlackoutVotePayload.java`
- Create: `src/main/java/com/habitrain/core/network/BlackoutStatusPayload.java`

**Interfaces:**
- Consumes: Fabric `CustomPayload` pattern (see existing `ActiveTaskPayload.java`)
- Produces: 3 `CustomPayload` types registered in `HabiTrainCore.onInitialize()` + client receivers in `HabiTrainCoreClient.onInitializeClient()`

- [ ] **Step 1: Create BlackoutTimerPayload.java**

```java
package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public record BlackoutTimerPayload(
    int totalTimeRemaining,
    int blackoutCountdown,
    boolean blackoutActive
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutTimerPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("blackout_timer"));

    public static final StreamCodec<FriendlyByteBuf, BlackoutTimerPayload> CODEC =
            StreamCodec.ofMember(BlackoutTimerPayload::write, BlackoutTimerPayload::new);

    private BlackoutTimerPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(totalTimeRemaining);
        buf.writeVarInt(blackoutCountdown);
        buf.writeBoolean(blackoutActive);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void broadcastToAll(MinecraftServer server, int totalTime, int blackoutCD, boolean active) {
        var payload = new BlackoutTimerPayload(totalTime, blackoutCD, active);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        }
    }
}
```

- [ ] **Step 2: Create BlackoutVotePayload.java**

```java
package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * C2S: 玩家投票 (targetUUID = 投票给谁)
 * S2C: 投票结果同步 (sheriffUUID = 当选警长)
 */
public record BlackoutVotePayload(UUID targetUUID, boolean isResult) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutVotePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("blackout_vote"));

    public static final StreamCodec<FriendlyByteBuf, BlackoutVotePayload> CODEC =
            StreamCodec.ofMember(BlackoutVotePayload::write, BlackoutVotePayload::new);

    private BlackoutVotePayload(FriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readBoolean());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(targetUUID);
        buf.writeBoolean(isResult);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
```

- [ ] **Step 3: Create BlackoutStatusPayload.java**

```java
package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C: 停电/模式状态变更事件广播
 */
public record BlackoutStatusPayload(
    StatusType type,
    String data  // 按 type 不同: VOTE_OPEN → "", VOTE_RESULT → sheriffName, BLACKOUT_START → "", etc.
) implements CustomPacketPayload {
    public enum StatusType {
        BLACKOUT_START,
        BLACKOUT_END,
        VOTE_OPEN,
        VOTE_RESULT,
        TIME_WARNING
    }

    public static final CustomPacketPayload.Type<BlackoutStatusPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("blackout_status"));

    public static final StreamCodec<FriendlyByteBuf, BlackoutStatusPayload> CODEC =
            StreamCodec.ofMember(BlackoutStatusPayload::write, BlackoutStatusPayload::new);

    private BlackoutStatusPayload(FriendlyByteBuf buf) {
        this(buf.readEnum(StatusType.class), buf.readUtf(64));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeEnum(type);
        buf.writeUtf(data, 64);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
```

- [ ] **Step 4: Register all 3 payloads in HabiTrainCore.onInitialize()**

Add to `HabiTrainCore.java` around the existing `registerCommands()` call:

```java
// In onInitialize(), after existing payload registrations:
BlackoutTimerPayload.register();
BlackoutVotePayload.register();
BlackoutStatusPayload.register();
```

Add import:
```java
import com.habitrain.core.network.BlackoutTimerPayload;
import com.habitrain.core.network.BlackoutVotePayload;
import com.habitrain.core.network.BlackoutStatusPayload;
```

- [ ] **Step 5: Register client-side receivers in HabiTrainCoreClient**

Add to `HabiTrainCoreClient.onInitializeClient()`:

```java
// Register blackout mode network receivers
ClientPlayNetworking.registerGlobalReceiver(BlackoutTimerPayload.TYPE, (payload, ctx) -> {
    ctx.client().execute(() -> {
        // The HUD overlay reads static fields set here
        com.habitrain.core.game.blackout.client.BlackoutHudOverlay.updateTime(
            payload.totalTimeRemaining(), payload.blackoutCountdown(), payload.blackoutActive());
    });
});

ClientPlayNetworking.registerGlobalReceiver(BlackoutStatusPayload.TYPE, (payload, ctx) -> {
    ctx.client().execute(() -> {
        String msg = switch (payload.type()) {
            case BLACKOUT_START -> "§c⚡ 停电了！";
            case BLACKOUT_END -> "§a⚡ 供电恢复";
            case VOTE_OPEN -> "§e【投票】按 [P] 键打开投票界面！";
            case VOTE_RESULT -> "§e【投票】" + payload.data() + " 当选警长！";
            case TIME_WARNING -> "§e⚠ 仅剩 1 分钟！";
        };
        ctx.client().player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg));
    });
});
```

Add imports:
```java
import com.habitrain.core.game.blackout.client.BlackoutHudOverlay;
import com.habitrain.core.network.BlackoutTimerPayload;
import com.habitrain.core.network.BlackoutStatusPayload;
```

- [ ] **Step 6: Verify build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/habitrain/core/network/BlackoutTimerPayload.java src/main/java/com/habitrain/core/network/BlackoutVotePayload.java src/main/java/com/habitrain/core/network/BlackoutStatusPayload.java
git commit -m "feat: add blackout mode network payloads"
```

---

### Task 5: Create BlackoutVotingEngine + VoteScreen + BlackoutKeyHandler

**Files:**
- Create: `src/main/java/com/habitrain/core/game/blackout/BlackoutVotingEngine.java`
- Create: `src/main/java/com/habitrain/core/client/gui/VoteScreen.java`
- Create: `src/main/java/com/habitrain/core/client/BlackoutKeyHandler.java`

**Interfaces:**
- Consumes: `BlackoutRoleManager`, `BlackoutVotePayload`
- Produces: `BlackoutVotingEngine` (startTick/resolve/getVoteWindowOpen) called from `BlackoutMode.onTick`, `VoteScreen` opened by `BlackoutKeyHandler` on P key

- [ ] **Step 1: Create BlackoutVotingEngine.java**

```java
package com.habitrain.core.game.blackout;

import com.habitrain.core.network.BlackoutVotePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 停电模式 — 投票选举警长引擎。
 *
 * 流程:
 * 1. 开局60s后 → openVoting() → 广播 VOTE_OPEN + 设置30s窗口
 * 2. 玩家按P打开VoteScreen → 选人 → BlackoutVotePayload C2S
 * 3. 30s后 → resolveVoting() → 计票 → 公告结果 / 补选
 */
public class BlackoutVotingEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlackoutVote");

    private static boolean voteWindowOpen = false;
    private static int windowElapsedTicks = 0;
    private static final int WINDOW_DURATION_TICKS = 600; // 30s × 20 tick
    private static boolean votingResolved = false;
    private static final Map<UUID, UUID> VOTES = new HashMap<>(); // voter → target
    private static MinecraftServer server = null;

    public static void init(MinecraftServer srv) {
        server = srv;
        voteWindowOpen = false;
        windowElapsedTicks = 0;
        votingResolved = false;
        VOTES.clear();
    }

    /** 开启投票窗口 (由 BlackoutMode.onTick 在60s时调用) */
    public static void openVoting() {
        voteWindowOpen = true;
        windowElapsedTicks = 0;
        votingResolved = false;
        VOTES.clear();
        broadcast("§e【投票】现在可以投出你觉得的「警长」！按 [P] 键打开投票界面");
        broadcast("§7投票将在 30 秒后截止");
        LOGGER.info("Voting window opened for 30s");
    }

    /** 每秒调用 (由 BlackoutMode.onTick 驱动) */
    public static void tickVoting() {
        if (!voteWindowOpen || votingResolved || server == null) return;

        windowElapsedTicks += 20; // +1 秒
        int remaining = WINDOW_DURATION_TICKS - windowElapsedTicks;

        // 每10秒提醒一次
        if (remaining > 0 && remaining % 200 == 0) {
            broadcast("§7投票剩余: " + (remaining / 20) + " 秒");
        }

        if (windowElapsedTicks >= WINDOW_DURATION_TICKS) {
            resolveVoting();
        }
    }

    /** 处理玩家投票 (由 C2S 接收器调用) */
    public static boolean castVote(UUID voterId, UUID targetId) {
        if (!voteWindowOpen || votingResolved) return false;
        if (!BlackoutRoleManager.isAlive(voterId)) return false;
        if (!BlackoutRoleManager.isAlive(targetId)) return false;
        if (voterId.equals(targetId)) return false;

        VOTES.put(voterId, targetId);
        LOGGER.info("Vote cast: {} → {}", voterId, targetId);
        return true;
    }

    /** 结算投票 */
    private static void resolveVoting() {
        votingResolved = true;
        voteWindowOpen = false;

        Map<UUID, Integer> tally = new HashMap<>();
        for (UUID target : VOTES.values()) {
            tally.merge(target, 1, Integer::sum);
        }

        UUID winner = null;
        int maxVotes = 0;

        // 找最高票
        for (var entry : tally.entrySet()) {
            if (entry.getValue() > maxVotes) {
                maxVotes = entry.getValue();
                winner = entry.getKey();
            }
        }

        // 检查平票 (有相同票数的其他玩家)
        if (winner != null) {
            int finalMax = maxVotes;
            boolean tie = tally.entrySet().stream()
                    .filter(e -> !e.getKey().equals(winner))
                    .anyMatch(e -> e.getValue() == finalMax);
            if (tie) winner = null;
        }

        // 无人当选/平票 → 系统选非杀手
        if (winner == null) {
            List<UUID> eligible = BlackoutRoleManager.getAllAlive().stream()
                    .filter(BlackoutRoleManager::canBeSheriff)
                    .toList();
            if (!eligible.isEmpty()) {
                winner = eligible.get(new Random().nextInt(eligible.size()));
            }
        }

        if (winner != null) {
            String playerName = "";
            if (server != null) {
                ServerPlayer p = server.getPlayerList().getPlayer(winner);
                if (p != null) playerName = p.getName().getString();
            }
            BlackoutRoleManager.setSheriff(winner);
            broadcast("§e【投票】" + playerName + " 当选为警长！");
            LOGGER.info("Sheriff elected: {} (votes: {})", winner, maxVotes);
        } else {
            broadcast("§e【投票】无人可当选警长...警长位置空缺");
        }
    }

    public static boolean isVoteWindowOpen() { return voteWindowOpen; }

    public static void reset() {
        voteWindowOpen = false;
        windowElapsedTicks = 0;
        votingResolved = false;
        VOTES.clear();
        server = null;
    }

    private static void broadcast(String msg) {
        if (server == null) return;
        Component c = Component.literal(msg);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(c);
        }
    }
}
```

- [ ] **Step 2: Wire C2S vote receiver in HabiTrainCore**

Add to `HabiTrainCore.java`'s `registerLifecycleEvents()`:

```java
// C2S: 投票
ServerPlayNetworking.registerGlobalReceiver(BlackoutVotePayload.TYPE, (payload, context) -> {
    context.server().execute(() -> {
        if (payload.isResult()) return; // ignore S2C on server
        ServerPlayer player = context.player();
        if (player == null) return;
        BlackoutVotingEngine.castVote(player.getUUID(), payload.targetUUID());
    });
});
```

- [ ] **Step 3: Create VoteScreen.java**

```java
package com.habitrain.core.client.gui;

import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.network.BlackoutVotePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.UUID;

/**
 * 停电模式 — 投票选举警长 GUI
 *
 * 按 P 键打开, 30秒投票窗口, 显示所有存活玩家, 点击选择 + 确认。
 */
public class VoteScreen extends Screen {

    private static final int ENTRY_H = 24;
    private static final int LIST_X = 60;
    private static final int LIST_W = 200;

    private UUID selectedTarget = null;
    private int windowRemaining = 30; // 秒, 由服务端同步 (当前简化: 本地倒计时)
    private boolean confirmed = false;

    public VoteScreen() {
        super(Component.literal("§l投票选举警长"));
    }

    @Override
    protected void init() {
        super.init();

        // 确认按钮
        addRenderableWidget(Button.builder(
                Component.literal("§a✔ 确认投票"),
                btn -> confirmVote()
        ).bounds(width / 2 - 50, height - 40, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        super.render(g, mx, my, delta);

        // 标题
        g.drawString(font, Component.literal("§l══════════ 投票选举警长 ══════════"),
                width / 2 - 90, 20, 0xFFFFFF, false);
        g.drawString(font, Component.literal("§7剩余: " + windowRemaining + " 秒"),
                width / 2 - 25, 36, 0xAAAAAA, false);

        // 玩家列表 (所有存活玩家)
        List<Player> players = Minecraft.getInstance().level.players();
        Player self = Minecraft.getInstance().player;
        int y = 55;
        for (Player player : players) {
            if (player.getUUID().equals(self.getUUID())) continue;

            boolean hovered = mx >= LIST_X && mx < LIST_X + LIST_W && my >= y && my < y + ENTRY_H;
            boolean selected = player.getUUID().equals(selectedTarget);

            // 背景
            if (selected) g.fill(LIST_X, y, LIST_X + LIST_W, y + ENTRY_H, 0x44333388);
            else if (hovered) g.fill(LIST_X, y, LIST_X + LIST_W, y + ENTRY_H, 0x22222255);

            // 选中标记
            String prefix = selected ? "§b● " : "§7○ ";
            g.drawString(font, Component.literal(prefix + player.getName().getString()),
                    LIST_X + 10, y + 7, selected ? 0x8888FF : 0xDDDDDD, false);

            y += ENTRY_H;
        }

        if (confirmed) {
            String msg = "§a✔ 已投票！等待结果...";
            g.drawString(font, Component.literal(msg), width / 2 - font.width(msg) / 2, height / 2 + 50, 0, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (super.mouseClicked(mx, my, btn)) return true;
        if (confirmed) return false;

        List<Player> players = Minecraft.getInstance().level.players();
        Player self = Minecraft.getInstance().player;
        int y = 55;
        for (Player player : players) {
            if (player.getUUID().equals(self.getUUID())) continue;
            if (mx >= LIST_X && mx < LIST_X + LIST_W && my >= y && my < y + ENTRY_H) {
                selectedTarget = player.getUUID();
                return true;
            }
            y += ENTRY_H;
        }
        return false;
    }

    private void confirmVote() {
        if (selectedTarget == null || confirmed) return;
        confirmed = true;
        ClientPlayNetworking.send(new BlackoutVotePayload(selectedTarget, false));
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; } // 投票窗口期内不能跳过
}
```

- [ ] **Step 4: Create BlackoutKeyHandler.java**

```java
package com.habitrain.core.client;

import com.habitrain.core.client.gui.VoteScreen;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * 停电模式 — 快捷键注册
 * P = 打开投票GUI
 * (B = 打开商店, 由 SRE 已有键处理)
 */
public class BlackoutKeyHandler {

    private static final KeyMapping VOTE_KEY = new KeyMapping(
            "key.habitrain.blackout.vote",
            GLFW.GLFW_KEY_P,
            "category.habitrain.blackout"
    );

    private static boolean registered = false;

    public static void register() {
        if (registered) return;
        registered = true;

        KeyBindingHelper.registerKeyBinding(VOTE_KEY);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (VOTE_KEY.consumeClick()) {
                // 只在游戏中且当前没有屏幕时打开
                if (client.player != null && client.screen == null) {
                    client.setScreen(new VoteScreen());
                }
            }
        });
    }
}
```

- [ ] **Step 5: Register key handler in HabiTrainCoreClient**

Add to `HabiTrainCoreClient.onInitializeClient()`:

```java
// 停电模式快捷键注册
BlackoutKeyHandler.register();
```

Add import:
```java
import com.habitrain.core.client.BlackoutKeyHandler;
```

- [ ] **Step 6: Wire BlackoutVotingEngine into BlackoutMode.onTick**

Update `BlackoutMode.onTick()` — add voting engine tick after the existing timer check:

```java
// In onTick(), after BlackoutTimerSystem.tickSecond():
BlackoutVotingEngine.tickVoting();
```

And in `onVotingPhaseStart()`:

```java
private void onVotingPhaseStart() {
    BlackoutVotingEngine.init(currentLevel.getServer());
    BlackoutVotingEngine.openVoting();
}
```

Add import to BlackoutMode:
```java
import com.habitrain.core.game.blackout.BlackoutVotingEngine;
```

- [ ] **Step 7: Verify build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/BlackoutVotingEngine.java src/main/java/com/habitrain/core/client/gui/VoteScreen.java src/main/java/com/habitrain/core/client/BlackoutKeyHandler.java
git commit -m "feat: add blackout voting engine + VoteScreen + key handler"
```

---

### Task 6: Create BlackoutHudOverlay

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/BlackoutHudOverlay.java`

**Interfaces:**
- Consumes: none (standalone rendering class called from client network receivers)
- Produces: Static `updateTime()` + `render()` method called from a HUD rendering hook

- [ ] **Step 1: Create BlackoutHudOverlay.java**

```java
package com.habitrain.core.client.gui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 停电模式 — 顶部 HUD 覆盖层
 * 显示: 模式名称、总时间、停电倒计时、进度条
 *
 * 由客户端网络接收器通过 updateTime() 更新数据,
 * 在 InGameOverlay 渲染事件中调用 render()。
 */
public class BlackoutHudOverlay {

    private static int totalTimeRemaining = 300;
    private static int blackoutCountdown = 120;
    private static boolean blackoutActive = false;
    private static boolean showHud = false;

    public static void updateTime(int total, int cd, boolean active) {
        totalTimeRemaining = total;
        blackoutCountdown = cd;
        blackoutActive = active;
        showHud = true;
    }

    public static void setVisible(boolean visible) { showHud = visible; }

    /**
     * 在 HUD 渲染时调用 (注册到 ClientTickEvents 或 mixin Overlay)
     * 目前简单方案: 直接渲染在屏幕顶部
     */
    public static void render(GuiGraphics g) {
        if (!showHud) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Font font = mc.font;
        int width = mc.getWindow().getGuiScaledWidth();
        int barW = 200;
        int barX = (width - barW) / 2;
        int barY = 8;
        int barH = 8;

        // 顶部信息行
        String timeStr = formatTime(totalTimeRemaining);
        String cdStr = blackoutActive
                ? "§c⚡ 停电中"
                : "§7停电: §e" + formatTime(blackoutCountdown);
        String title = "§6⚡ 停电模式 §f剩余: §l" + timeStr + "§r  " + cdStr;

        g.drawString(font, Component.literal(title),
                (width - font.width(title)) / 2, 0, 0, false);

        // 进度条背景
        g.fill(barX, barY, barX + barW, barY + barH, 0x88333333);
        // 已过时间 (深色表示已消耗)
        int elapsed = 300 - totalTimeRemaining;
        int filledW = (int) ((float) elapsed / 300 * barW);
        g.fill(barX, barY, barX + Math.min(filledW, barW), barY + barH, 0xFF555555);
        // 剩余时间 (绿色)
        int remainingW = barW - filledW;
        if (remainingW > 0) {
            int color = blackoutActive ? 0xFFFF4444 : 0xFF44AA44; // 停电中红色
            g.fill(barX + filledW, barY, barX + barW, barY + barH, color);
        }
        // 停电倒计时标记 (红色小标记)
        if (!blackoutActive && blackoutCountdown > 0) {
            int markerX = barX + (int) ((float) (300 - blackoutCountdown) / 300 * barW);
            g.fill(markerX, barY - 2, markerX + 2, barY + barH + 2, 0xFFFF4444);
        }
        // 60s 预警线
        int warningX = barX + (int) ((float) (300 - 60) / 300 * barW);
        g.fill(warningX, barY - 1, warningX + 1, barY + barH + 1, 0xFFFFFF00);
    }

    private static String formatTime(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }
}
```

- [ ] **Step 2: Register HUD render hook in HabiTrainCoreClient**

Currently there is no HUD render event registration in `HabiTrainCoreClient`. The cleanest approach without a mixin is to use Fabric's `HudRenderCallback`:

```java
// At the end of onInitializeClient(), add:
net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((g, tickDelta) -> {
    BlackoutHudOverlay.render(g);
});
```

Add imports:
```java
import com.habitrain.core.client.gui.BlackoutHudOverlay;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/client/gui/BlackoutHudOverlay.java
git commit -m "feat: add BlackoutHudOverlay for time display"
```

---

### Task 7: Create TACZWeaponBridge

**Files:**
- Create: `src/main/java/com/habitrain/core/game/blackout/TACZWeaponBridge.java`
- Modify: `src/main/java/com/habitrain/core/build.gradle` (add TACZ jar dependency)
- Modify: `src/main/resources/fabric.mod.json` (add tacz dependency)

**Interfaces:**
- Consumes: `BlackoutRoleManager`, `BlackoutMode`, SRE API (game death)
- Produces: `TACZWeaponBridge.register()`, `giveDesertEagle()`, `giveAmmo()`, `addToSREShop()`, `onBulletHitEntity()`

**Note:** TACZ API needs investigation. The exact class names and method calls will depend on TACZ-Refabricated's API surface. Steps below document the approach; adjust based on actual TACZ source.

- [ ] **Step 1: Research TACZ API**

Check TACZ mod structure in `D:\Backup\mc mod\TACZ-Refabricated-1.21.1\` to find:
1. How to get the Desert Eagle item registry ID
2. How to detect a bullet hit entity event (look for event classes, mixin targets, or API methods)
3. How to give items to a player programmatically (standard MC `Inventory.insertStack`)
4. How to register items in an in-game shop

```bash
# Search TACZ for relevant classes
grep -r "Desert\|DesertEagle\|deagle" D:\Backup\mc\mod\TACZ-Refabricated-1.21.1\src --include="*.java" | head -20
grep -r "BulletHit\|ProjectileHit\|onProjectile\|onEntityHit" D:\Backup\mc\mod\TACZ-Refabricated-1.21.1\src --include="*.java" | head -20
```

Expected: Find the Desert Eagle item identifier and bullet hit event/mixin to hook into.

- [ ] **Step 2: Add TACZ jar to build.gradle**

```groovy
// In build.gradle dependencies block, add:
modImplementation files("libs/TACZ-Refabricated-1.21.1-0.7.0-forge1.1.8-hotfix.jar")
```

Copy the TACZ jar:
```bash
cp "D:\Backup\mc mod\TACZ-Refabricated-1.21.1\build\libs\TACZ-Refabricated-1.21.1-0.7.0-forge1.1.8-hotfix.jar" "D:\Backup\mc mod\哈比列车api\libs\"
```

- [ ] **Step 3: Update fabric.mod.json**

Add to `depends`:
```json
"tacz": "*"
```

- [ ] **Step 4: Create TACZWeaponBridge.java**

```java
package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 停电模式 — TACZ 枪械桥接
 *
 * 职责:
 * 1. 将沙漠之鹰 + 子弹上架 SRE 背包商店
 * 2. 拦截 TACZ 子弹击中事件 → 转发为 SRE 游戏死亡
 * 3. 给警长发放武器/弹药
 *
 * TODO:
 * - DesertEagle item ID 需根据 TACZ 实际注册名调整
 * - 子弹命中事件需通过 TACZ 的 Event 或 Mixin 拦截
 * - SRE 商店 API 需研究 SRE 源码
 */
public class TACZWeaponBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("TACZBridge");

    // TACZ item IDs (需确认)
    private static final String TACZ_MOD_ID = "tacz";
    private static final String DESERT_EAGLE_ID = TACZ_MOD_ID + ":desert_eagle";
    private static final String AMMO_ID = TACZ_MOD_ID + ":ammo_50ae"; // 沙漠之鹰弹药

    private static boolean initialized = false;

    /** 在 BlackoutMode.onStart() 中调用 */
    public static void register(ServerPlayer sheriff) {
        if (initialized) return;
        initialized = true;

        // 将沙漠之鹰 + 子弹注册到 SRE 商店
        addToSREShop();

        LOGGER.info("TACZWeaponBridge initialized");
    }

    /** 将沙漠之鹰上架 SRE 背包商店 (仅警长可访问) */
    private static void addToSREShop() {
        // TODO: 研究 SRE 商店 API
        // 思路: 通过 SRE 的 ShopManager/ShopRegistry 添加商品
        // 需找到 SRE 商店注册的方法, 设置购买条件 = isSheriff, 价格 = 50
        LOGGER.info("Adding Desert Eagle to SRE shop: price=50, limit=1/game");
    }

    /** 给玩家沙漠之鹰 (警长购买) */
    public static void giveDesertEagle(ServerPlayer player) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(DESERT_EAGLE_ID));
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            LOGGER.warn("Desert Eagle item not found: {}", DESERT_EAGLE_ID);
            return;
        }
        ItemStack stack = new ItemStack(item, 1);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§a你获得了 TACZ 沙漠之鹰！"));
        LOGGER.info("Gave Desert Eagle to {}", player.getName().getString());
    }

    /** 给玩家子弹 */
    public static void giveAmmo(ServerPlayer player, int count) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(AMMO_ID));
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            LOGGER.warn("Ammo item not found: {}", AMMO_ID);
            return;
        }
        ItemStack stack = new ItemStack(item, count);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /**
     * 拦截 TACZ 子弹击中实体
     * 由 TACZ 的 ProjectileHitEvent / Mixin 回调
     * 检测是否在 blackout 模式 → 触发 SRE 游戏死亡
     */
    public static void onBulletHitEntity(ServerPlayer shooter, LivingEntity target) {
        if (!(target instanceof ServerPlayer targetPlayer)) return;

        // 确认在停电模式中
        var level = targetPlayer.serverLevel();
        var activeMode = com.habitrain.core.api.GameModeRegistry.getActiveForLevel(level);
        if (activeMode.isEmpty() || !(activeMode.get() instanceof BlackoutMode)) return;

        // 检查射击者是否为警长
        if (!BlackoutRoleManager.isSheriff(shooter.getUUID())) return;

        // 检查目标是否存活
        if (!BlackoutRoleManager.isAlive(targetPlayer.getUUID())) return;

        // 淘汰目标 (SRE 游戏死亡)
        eliminatePlayer(targetPlayer, shooter);

        LOGGER.info("{} shot {} with Desert Eagle (blackout mode)", 
            shooter.getName().getString(), targetPlayer.getName().getString());
    }

    /**
     * 淘汰玩家 — 调用 SRE 的角色死亡/淘汰逻辑
     * TODO: 研究 SRE 的淘汰 API
     * 思路: 通过 SREGameWorldComponent.get(level).getRoles() 找到目标角色 → 标记死亡
     */
    private static void eliminatePlayer(ServerPlayer target, ServerPlayer killer) {
        try {
            // 参考现有代码: effectOwnershipTracker / SREGameWorldComponent
            var gameWorld = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(target.serverLevel());
            if (gameWorld == null) return;

            // TODO: 调用 SRE 的角色淘汰方法
            // 类似: gameWorld.getRoles().markAsDead(target.getUUID());
            // 或: gameWorld.getGameLogic().eliminatePlayer(target, killer);

            BlackoutRoleManager.eliminate(target.getUUID());
            target.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c你被警长击杀了！"));
        } catch (Exception e) {
            LOGGER.error("Failed to eliminate player {} via SRE", target.getName().getString(), e);
        }
    }

    public static void reset() { initialized = false; }
}
```

- [ ] **Step 5: Verify build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (TACZ classes at compile time if jar is in libs/)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/TACZWeaponBridge.java
git commit -m "feat: add TACZWeaponBridge (skeleton + SRE death bridge)"
```

---

### Task 8: Add /habi_api command + wire up blackout mode in HabiTrainCore

**Files:**
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java`

**Interfaces:**
- Consumes: `BlackoutMode`, `GameModeRegistry`
- Produces: `/habi_api blackout|stop|list` commands

- [ ] **Step 1: Add /habi_api command + register BlackoutMode**

In `HabiTrainCore.java`, modify `registerCommands()` and `onInitialize()`:

In `onInitialize()`, after existing SRE mode registrations:
```java
// 注册停电模式
GameModeRegistry.register(MOD_ID, "habitrains:blackout", new BlackoutMode());
```

Add the command to `registerCommands()`:
```java
dispatcher.register(Commands.literal("habi_api")
    .requires(source -> source.hasPermission(2))
    .then(Commands.literal("blackout")
        .executes(ctx -> {
            ServerLevel level = ctx.getSource().getLevel();
            try {
                GameModeRegistry.start("habitrain_core:habitrains:blackout", level);
                ctx.getSource().sendSuccess(
                    Component.literal("§a✅ 停电模式已启动！"), true);
            } catch (Exception e) {
                ctx.getSource().sendFailure(
                    Component.literal("§c启动失败: " + e.getMessage()));
            }
            return 1;
        })
    )
    .then(Commands.literal("stop")
        .executes(ctx -> {
            ServerLevel level = ctx.getSource().getLevel();
            GameModeRegistry.stop(level);
            ctx.getSource().sendSuccess(
                Component.literal("§c⏹ 当前游戏模式已停止"), true);
            return 1;
        })
    )
    .then(Commands.literal("list")
        .executes(ctx -> {
            String modes = GameModeRegistry.getAll().stream()
                .map(GameMode::getId)
                .collect(java.util.stream.Collectors.joining("§7, §e"));
            ctx.getSource().sendSuccess(
                Component.literal("§e已注册模式: §e" + modes), true);
            return 1;
        })
    )
);
```

Add imports:
```java
import com.habitrain.core.game.blackout.BlackoutMode;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git commit -am "feat: add /habi_api command + register BlackoutMode"
```

---

### Task 9: Create good tasks (AddCoalTask + RepairWiringTask) in more_tasks

**Files:**
- Create: `src/main/java/com/habitrain/moretasks/game/blackout/AddCoalTask.java`
- Create: `src/main/java/com/habitrain/moretasks/game/blackout/RepairWiringTask.java`

**Interfaces:**
- Consumes: `TaskRegistry`, `TaskCategory.BLACKOUT_GOOD`, `BlackoutTimerSystem.reduceTime()`, `BlackoutTimerSystem.delayBlackout()`
- Produces: Two task definitions registered via `TaskRegistry.register()` with category `BLACKOUT_GOOD`

- [ ] **Step 1: Create AddCoalTask.java**

```java
package com.habitrain.moretasks.game.blackout;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import net.minecraft.world.level.block.Blocks;

import java.awt.Color;

/**
 * 停电模式 — 好人任务: 添加煤炭
 * 效果: 采集煤矿方块 → 总时间减少30秒
 */
public class AddCoalTask {

    public static void register() {
        TaskRegistry.register("habitrain_more_tasks", "add_coal", builder -> builder
            .displayName("添加煤炭")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(1.0f)
            .blockTypeId(20)
            .instinctColor(new Color(50, 50, 50, 200))
            .scanBlocks(Blocks.COAL_BLOCK, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE)
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
                player.sendMessage(net.minecraft.text.Text.literal(
                    "§6【任务】找到煤矿，给锅炉添加煤炭！"));
            })
            .onTick((player, task) -> {
                if (task.isFulfilled()) return;
                // 检测玩家是否右键/破坏了煤矿
                // 注: 实际检测需要 mixin 拦截方块交互, 简化版使用 completionChecker
            })
            .completionChecker((player, task) -> {
                // 由外部交互事件 (mixin 或右键检测) 调用 task.setProgress(1)
                return task.getProgress() >= 1;
            })
            .onComplete((player, task) -> {
                BlackoutTimerSystem.reduceTime(30);
                player.sendMessage(net.minecraft.text.Text.literal(
                    "§a✔ 锅炉火力更旺了！总时间减少30秒！"));
            })
        );
    }
}
```

- [ ] **Step 2: Create RepairWiringTask.java**

```java
package com.habitrain.moretasks.game.blackout;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;

import java.awt.Color;

/**
 * 停电模式 — 好人任务: 维修线路
 * 效果: 右键维修电线 → 推迟停电倒计时 +15秒
 */
public class RepairWiringTask {

    public static void register() {
        TaskRegistry.register("habitrain_more_tasks", "repair_wiring", builder -> builder
            .displayName("维修线路")
            .category(BlackoutMode.BLACKOUT_GOOD)
            .weight(1.0f)
            .blockTypeId(21)
            .instinctColor(new Color(255, 215, 0, 200))  // 金色
            .scanBlockIds("minecraft:redstone_block")
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
                player.sendMessage(net.minecraft.text.Text.literal(
                    "§6【任务】找到线路进行维修，推迟停电！"));
            })
            .onComplete((player, task) -> {
                BlackoutTimerSystem.delayBlackout(15);
                player.sendMessage(net.minecraft.text.Text.literal(
                    "§a✔ 线路已维修！停电推迟15秒！"));
            })
        );
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/moretasks/game/blackout/AddCoalTask.java src/main/java/com/habitrain/moretasks/game/blackout/RepairWiringTask.java
git commit -m "feat: add blackout good tasks (add_coal, repair_wiring)"
```

---

### Task 10: Create bad tasks (SabotageWiringTask + FurnaceExplosionTask) in more_tasks

**Files:**
- Create: `src/main/java/com/habitrain/moretasks/game/blackout/SabotageWiringTask.java`
- Create: `src/main/java/com/habitrain/moretasks/game/blackout/FurnaceExplosionTask.java`

**Interfaces:**
- Consumes: `TaskRegistry`, `TaskCategory.BLACKOUT_BAD`, `BlackoutTimerSystem.triggerBlackout()`, `BlackoutTimerSystem.addTime()`
- Produces: Two task definitions registered with category `BLACKOUT_BAD`

- [ ] **Step 1: Create SabotageWiringTask.java**

```java
package com.habitrain.moretasks.game.blackout;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;

import java.awt.Color;

/**
 * 停电模式 — 坏人任务: 破坏线路
 * 效果: 破坏电线 → 立即触发停电7秒
 */
public class SabotageWiringTask {

    public static void register() {
        TaskRegistry.register("habitrain_more_tasks", "sabotage_wiring", builder -> builder
            .displayName("破坏线路")
            .category(BlackoutMode.BLACKOUT_BAD)
            .weight(1.0f)
            .blockTypeId(22)
            .instinctColor(new Color(255, 0, 0, 200))  // 红色
            .scanBlockIds("minecraft:redstone_wire")
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
                player.sendMessage(net.minecraft.text.Text.literal(
                    "§c【任务】破坏线路，让列车停电！"));
            })
            .onComplete((player, task) -> {
                BlackoutTimerSystem.triggerBlackout();
                player.sendMessage(net.minecraft.text.Text.literal(
                    "§c✔ 线路破坏成功！列车停电了！"));
            })
        );
    }
}
```

- [ ] **Step 2: Create FurnaceExplosionTask.java**

```java
package com.habitrain.moretasks.game.blackout;

import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.game.blackout.BlackoutMode;
import com.habitrain.core.game.blackout.BlackoutTimerSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.awt.Color;

/**
 * 停电模式 — 坏人任务: 熔炉爆炸
 * 效果: 熔炉爆炸 → 总时间 +15秒 + 点燃附近TNT
 */
public class FurnaceExplosionTask {

    public static void register() {
        TaskRegistry.register("habitrain_more_tasks", "furnace_explosion", builder -> builder
            .displayName("熔炉爆炸")
            .category(BlackoutMode.BLACKOUT_BAD)
            .weight(1.0f)
            .blockTypeId(23)
            .instinctColor(new Color(255, 69, 0, 200))  // 橙红色
            .scanBlocks(Blocks.FURNACE, Blocks.BLAST_FURNACE)
            .onAssign((player, task) -> {
                task.setMaxProgress(1);
                player.sendMessage(net.minecraft.text.Text.literal(
                    "§c【任务】引爆熔炉，制造混乱！"));
            })
            .onComplete((player, task) -> {
                // 延长时间
                BlackoutTimerSystem.addTime(15);

                // 点燃附近TNT (半径5格)
                if (player.getServer() != null) {
                    for (var level : player.getServer().getAllLevels()) {
                        igniteNearbyTNT(level, player.blockPosition(), 5);
                    }
                }

                player.sendMessage(net.minecraft.text.Text.literal(
                    "§c✔ 熔炉爆炸了！时间延长15秒！"));
            })
        );
    }

    /** 点燃指定位置附近的TNT方块 */
    private static void igniteNearbyTNT(ServerLevel level, BlockPos center, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.TNT)) {
                        level.destroyBlock(pos, false);
                        level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                            4.0f, ServerLevel.EXPLOSION_BLOCK_INTERACTION);
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/moretasks/game/blackout/SabotageWiringTask.java src/main/java/com/habitrain/moretasks/game/blackout/FurnaceExplosionTask.java
git commit -m "feat: add blackout bad tasks (sabotage_wiring, furnace_explosion)"
```

---

### Task 11: Wire up blackout tasks in HabiTrainMoreTasks

**Files:**
- Modify: `src/main/java/com/habitrain/moretasks/HabiTrainMoreTasks.java`

- [ ] **Step 1: Register all blackout tasks in onInitialize()**

Add to `HabiTrainMoreTasks.onInitialize()`, before the logging line at the end:

```java
// ★ 停电模式任务注册
AddCoalTask.register();
RepairWiringTask.register();
SabotageWiringTask.register();
FurnaceExplosionTask.register();
LOGGER.info("停电模式任务已注册: 4个任务 (2好人 + 2坏人)");
```

Add imports:
```java
import com.habitrain.moretasks.game.blackout.AddCoalTask;
import com.habitrain.moretasks.game.blackout.RepairWiringTask;
import com.habitrain.moretasks.game.blackout.SabotageWiringTask;
import com.habitrain.moretasks.game.blackout.FurnaceExplosionTask;
```

- [ ] **Step 2: Build core mod first, then copy JAR**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build
cp build/libs/*.jar "D:\Backup\mc mod\哈比列车更多修改\libs\"
```

- [ ] **Step 3: Build more_tasks mod**

```bash
cd "D:\Backup\mc mod\哈比列车更多修改"
./gradlew clean build
```

Expected: BUILD SUCCESSFUL for both mods

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/moretasks/HabiTrainMoreTasks.java
git commit -m "feat: wire up blackout tasks in HabiTrainMoreTasks"
```

---

### Task 12: Final build + JAR copy

**Files:** none (build artifacts)

- [ ] **Step 1: Clean build core mod**

```bash
cd "D:\Backup\mc mod\哈比列车api"
./gradlew clean build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Copy core JAR to more_tasks libs**

```bash
cp build/libs/habitrain_core-*.jar "D:\Backup\mc mod\哈比列车更多修改\libs\"
cp build/libs/*.jar "D:\Backup\mc mod\临时\"
```

- [ ] **Step 3: Clean build more_tasks mod**

```bash
cd "D:\Backup\mc mod\哈比列车更多修改"
./gradlew clean build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Copy more_tasks JAR to temp**

```bash
cp build/libs/*.jar "D:\Backup\mc mod\临时\"
```
Expected: `临时` folder contains both JARs

- [ ] **Step 5: Final commit**

```bash
cd "D:\Backup\mc mod\哈比列车api"
git commit -am "chore: final build verification for blackout mode"
```

---

## Self-Review Checklist

- [ ] **Spec coverage:** Each section of the spec is covered by a task:
  - §2 Mode info → Task 8 (commands) + Task 3 (BlackoutMode)
  - §3 Core rules → Task 2 (TimerSystem) + Task 3 (BlackoutMode tick/checkVictory)
  - §4 Code split → All tasks follow the split
  - §5.1 BlackoutMode → Task 3
  - §5.2 TimerSystem → Task 2
  - §5.3 RoleManager → Task 2
  - §5.4 Voting → Task 5
  - §5.5 TACZ bridge → Task 7
  - §6 Network → Task 4
  - §7 TaskCategory → Task 3 (in BlackoutMode constants)
  - §8 Commands → Task 8
  - §9 HUD → Task 6
  - §10 Extensibility → covered by architecture (Task 2 RoleType enum, Task 2/SRE generic elimination)
  - §11 Implementation order → Tasks 1-12 follow this order

- [ ] **Placeholder scan:** No "TBD", "TODO" (only SRE/TACZ API investigation notes as explicit TODO markers), no "implement later" without context
- [ ] **Type consistency:** `BlackoutTimerSystem.reduceTime(30)` referenced the same way in Task 2 and Task 9. `BlackoutRoleManager.isSheriff()` referenced the same way in Task 5 and Task 7. All method signatures match.
