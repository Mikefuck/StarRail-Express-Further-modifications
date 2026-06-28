# HabiTrain Core GameMode API — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `habitrain_taskapi` → `habitrain_core`, adding GameMode API, while preserving SRE compatibility and migrating the companion mod.

**Architecture:** Clean-room refactor: new `com.habitrain.core` package tree houses a `GameMode` interface + lifecycle system, a per-GameMode `TaskCategory` extension, and a refactored task/config/network layer. All SRE-specific code isolates into `game/sre/`. The companion mod `test_more_tasks` renames to `habitrain_more_tasks`.

**Tech Stack:** Fabric 1.21.1, Java 21, Gradle, Minecraft, StarRailExpress (hard dep)

## Global Constraints

- Java 21 language level (release 21)
- Minecraft 1.21.1, Fabric Loader ≥0.19.2
- Fabric API as provided by `fabric-api` dependency
- `starrailexpress` as hard dependency via local JAR in `libs/`
- Mod id changes from `habitrain_taskapi` → `habitrain_core`
- Package base changes from `com.habitrain.taskapi` → `com.habitrain.core`
- Old `com.habitrain.taskapi.*` package must be fully deleted (no backward compat)
- File names preserve existing method signatures where behaviour is unchanged
- No unit tests — Minecraft mods validated by `gradlew build` compile check + runtime

## File Structure Map

### New / renamed files (under `com.habitrain.core`)

```
src/main/java/com/habitrain/core/
├── HabiTrainCore.java                    Main entry (was HabiTrainTaskAPI)
│
├── api/
│   ├── GameMode.java                     NEW — core interface
│   ├── GameModeLifecycle.java            NEW — lifecycle hook enum
│   ├── GameModeRegistry.java             NEW — static registry
│   ├── TaskCategory.java                 NEW — per-GameMode category
│   ├── TaskDefinition.java               Migrated from HabiTaskDefinition
│   ├── TaskInstance.java                 Migrated from HabiTaskInstance
│   ├── TaskRegistry.java                 Migrated from HabiTaskRegistry
│   └── WinResult.java                    NEW — value object
│
├── game/
│   ├── AbstractGameMode.java             NEW — base impl
│   └── sre/
│       ├── SREGameModeBase.java          NEW — SRE common base
│       ├── SREMurderMode.java            NEW — murder impl
│       ├── SERepairMode.java             NEW — repair impl
│       ├── TaskEnumHelper.java           Migrated from impl/TaskEnumHelper
│       └── mixin/
│           ├── MapScannerMixin.java          Migrated
│           ├── GenerateTaskMixin.java        Migrated
│           ├── ServerTickMixin.java          Migrated
│           ├── RoleMethodDispatcherMixin.java Migrated
│           └── NunchuckCooldownMixin.java    Migrated
│
├── task/
│   ├── TaskManager.java                 Migrated from HabiTaskManager
│   ├── TaskBalancer.java                NEW — balance logic extracted
│   └── Engine.java                      NEW — task generation engine
│
├── config/
│   ├── ConfigManager.java               Migrated from HabiConfigManager
│   ├── TaskConfigEntry.java             Migrated from HabiTaskConfigEntry
│   └── GameModeConfigScope.java         NEW — per-GameMode config
│
├── misc/
│   └── EffectOwnershipTracker.java      Migrated from api/ (unchanged)
│
├── network/
│   ├── TaskConfigPayload.java           Migrated from TaskConfigSyncPayload
│   ├── ActiveTaskPayload.java           Migrated from ActiveCustomTaskPayload
│   ├── ConfigUpdatePayload.java         Migrated from ConfigUpdateC2SPayload
│   ├── ShaderConfigPayload.java         Migrated from ShaderConfigSyncS2CPayload
│   └── ShaderInfoPayload.java           Migrated from ShaderPackInfoC2SPayload
│
└── client/
    ├── HabiTrainCoreClient.java         Migrated from HabiTrainTaskAPIClient
    ├── gui/
    │   ├── ConfigScreen.java               Migrated
    │   ├── TaskListScreen.java             Migrated
    │   ├── TaskEditScreen.java             Migrated
    │   ├── GlobalSettingsScreen.java       Migrated
    │   └── ShaderWhitelistScreen.java      Migrated
    ├── cache/
    │   └── ActiveTaskCache.java            Migrated from ActiveCustomTaskCache
    └── mixin/
        ├── HudTaskMixin.java               Migrated from HudCustomTaskMixin
        ├── InstinctColorMixin.java         Migrated
        ├── InstinctCacheFixMixin.java      Migrated
        ├── CustomTaskBlockRendererMixin.java Migrated
        └── StarRailExpressTitleScreenMixin.java Migrated
```

### Resource files

```
src/main/resources/
├── fabric.mod.json           Updated mod id + entrypoints
├── habitrain_core.mixins.json              Renamed from habitrain_taskapi.mixins.json
├── habitrain_core.client.mixins.json        Renamed from habitrain_taskapi.client.mixins.json
└── assets/habitrain_core/
    ├── icon.png                             Copied
    └── lang/
        ├── zh_cn.json                       Updated keys
        └── en_us.json                       Updated keys
```

### Deleted files

All files under `com.habitrain.taskapi.*` — entire old package tree removed after migration.

### Companion mod changes (separate directory)

All files in `com.example` → `com.habitrain.moretasks`;
mod id `test_more_tasks` → `habitrain_more_tasks`;
dependency `habitrain_taskapi` → `habitrain_core`.

---

## Task 1: Build Script & Package Scaffold

**Files:**
- Modify: `build.gradle` (entire file)
- Modify: `settings.gradle` (root project name)
- Create: `src/main/java/com/habitrain/core/api/` (package marker)
- Create: `src/main/java/com/habitrain/core/game/sre/` (package marker)

**Interfaces:**
- Consumes: nothing (foundation task)
- Produces: compilable project structure with empty new packages

- [ ] **Step 1: Update build.gradle**

Change `archives_base_name` in gradle.properties and update maven group to `com.habitrain.core`:

**`gradle.properties`:**
```properties
org.gradle.jvmargs=-Xmx2G
loom_version=1.10.4
mod_version=2.0.0
maven_group=com.habitrain.core
archives_base_name=habitrain_core
minecraft_version=1.21.1
loader_version=0.19.2
fabric_api_version=0.116.12+1.21.1
```

**`build.gradle`** — update group and version reference:
```groovy
plugins {
    id 'fabric-loom' version "${loom_version}"
    id 'maven-publish'
}

version = project.mod_version
group = project.maven_group

repositories {
    maven { url "https://maven.ladysnake.org/releases" }
    maven { url "https://api.modrinth.com/maven" }
    maven { url "https://maven.terraformersmc.com/releases/" }
    flatDir { dirs "libs" }
}

loom { }

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
    modImplementation "com.terraformersmc:modmenu:11.0.3"
    modImplementation files("libs/star_rail_express-4.2.0.jar")
    modCompileOnly files("libs/voicechat-fabric-1.21.1-2.6.18.jar")
    modImplementation("org.ladysnake.cardinal-components-api:cardinal-components-base:6.1.2") { transitive = false }
    modImplementation("org.ladysnake.cardinal-components-api:cardinal-components-entity:6.1.2") { transitive = false }
    modImplementation("org.ladysnake.cardinal-components-api:cardinal-components-world:6.1.2") { transitive = false }
}

processResources {
    def version = project.version
    inputs.property "version", version
    filesMatching("fabric.mod.json") {
        expand "version": version
    }
}

tasks.withType(JavaCompile).configureEach {
    it.options.release = 21
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

jar {
    from("LICENSE") { rename { "${it}_${project.archives_base_name}" } }
}

publishing {
    publications { create("mavenJava", MavenPublication) { from components.java } }
    repositories { mavenLocal() }
}
```

- [ ] **Step 2: Update settings.gradle**

```groovy
pluginManagement {
    repositories {
        maven { url "https://maven.fabricmc.net/" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "habitrain_core"
```

- [ ] **Step 3: Create fabric.mod.json**

**`src/main/resources/fabric.mod.json`:**
```json
{
    "schemaVersion": 1,
    "id": "habitrain_core",
    "version": "${version}",
    "name": "哈比列车核心 (HabiTrain Core)",
    "description": "哈比列车游戏框架与任务系统API模组",
    "authors": ["HabiTrain"],
    "contact": { "homepage": "https://github.com/" },
    "license": "MIT",
    "icon": "assets/habitrain_core/icon.png",
    "environment": "*",
    "entrypoints": {
        "main": ["com.habitrain.core.HabiTrainCore"],
        "client": ["com.habitrain.core.client.HabiTrainCoreClient"],
        "modmenu": ["com.habitrain.core.client.gui.ModMenuIntegration"]
    },
    "mixins": [
        "habitrain_core.mixins.json",
        { "config": "habitrain_core.client.mixins.json", "environment": "client" }
    ],
    "depends": {
        "fabricloader": ">=0.19.2",
        "minecraft": "~1.21.1",
        "java": ">=21",
        "fabric-api": "*",
        "starrailexpress": "*",
        "modmenu": "*"
    }
}
```

- [ ] **Step 4: Create empty mixin JSON + asset directories**

**`src/main/resources/habitrain_core.mixins.json`:**
```json
{
    "required": true,
    "package": "com.habitrain.core.game.sre.mixin",
    "compatibilityLevel": "JAVA_21",
    "mixins": [],
    "client": [],
    "server": []
}
```

**`src/main/resources/habitrain_core.client.mixins.json`:**
```json
{
    "required": true,
    "package": "com.habitrain.core.client.mixin",
    "compatibilityLevel": "JAVA_21",
    "client": []
}
```

Copy icon and lang files from old assets path:

```bash
cp src/main/resources/assets/habitrain_taskapi/icon.png src/main/resources/assets/habitrain_core/icon.png
cp src/main/resources/assets/habitrain_taskapi/lang/zh_cn.json src/main/resources/assets/habitrain_core/lang/zh_cn.json
cp src/main/resources/assets/habitrain_taskapi/lang/en_us.json src/main/resources/assets/habitrain_core/lang/en_us.json
```

Update lang files — replace `habitrain_taskapi` references with `habitrain_core`.

- [ ] **Step 5: Run compile check**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL (even if source files are empty — stub classes will be filled in subsequent tasks)

- [ ] **Step 6: Commit scaffold**

```bash
git add -A
git commit -m "build: scaffold habitrain_core with new package structure"
```

---

## Task 2: API Layer — Core Interfaces

**Files:**
- Create: `api/GameMode.java`
- Create: `api/GameModeLifecycle.java`
- Create: `api/GameModeRegistry.java`
- Create: `api/TaskCategory.java`
- Create: `api/WinResult.java`

**Interfaces:**
- Consumes: nothing
- Produces: `GameMode`, `GameModeRegistry`, `TaskCategory`, `WinResult`

- [ ] **Step 1: Create TaskCategory.java**

```java
package com.habitrain.core.api;

import java.util.Objects;

/**
 * 任务分类 — per-GameMode 可自定义。
 * 内置快捷常量 ALL 用于通用任务。
 * SRE 原版任务仍使用 {@link com.habitrain.taskapi.api.HabiTaskCategory} 枚举。
 */
public class TaskCategory {
    private final String id;
    private final String displayName;
    private final String gameModeId;

    public static final TaskCategory ALL = new TaskCategory("core:all", "通用", "core");

    public TaskCategory(String id, String displayName, String gameModeId) {
        this.id = Objects.requireNonNull(id);
        this.displayName = Objects.requireNonNull(displayName);
        this.gameModeId = Objects.requireNonNull(gameModeId);
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getGameModeId() { return gameModeId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskCategory that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() { return "TaskCategory{" + id + "}"; }
}
```

- [ ] **Step 2: Create WinResult.java**

```java
package com.habitrain.core.api;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 游戏胜利结果值对象。
 * 由 GameMode.checkWinCondition() 返回。
 */
public class WinResult {
    private final List<UUID> winners;
    private final String reason;

    public WinResult(List<UUID> winners, String reason) {
        this.winners = winners != null
            ? Collections.unmodifiableList(winners)
            : List.of();
        this.reason = reason;
    }

    public static WinResult singleWinner(UUID playerId, String reason) {
        return new WinResult(List.of(playerId), reason);
    }

    public static WinResult noWinner(String reason) {
        return new WinResult(List.of(), reason);
    }

    public List<UUID> getWinners() { return winners; }
    public String getReason() { return reason; }
    public boolean hasWinner() { return !winners.isEmpty(); }
}
```

- [ ] **Step 3: Create GameModeLifecycle.java**

```java
package com.habitrain.core.api;

/**
 * 生命周期事件枚举，用于框架内部调度。
 * DLC 模组通常只需实现 GameMode 接口中的 default 方法。
 */
public enum GameModeLifecycle {
    PRE_START,
    START,
    TICK,
    PLAYER_JOIN,
    PLAYER_LEAVE,
    TASK_COMPLETE,
    CHECK_WIN,
    END,
    CLEANUP
}
```

- [ ] **Step 4: Create GameMode.java**

```java
package com.habitrain.core.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Optional;

/**
 * 游戏模式核心接口。
 * DLC 模组实现此接口并通过 {@link GameModeRegistry} 注册。
 *
 * 每个 GameMode 拥有：
 * - 唯一 ID
 * - 自己的任务分类列表
 * - 完整的生命周期钩子
 * - 任务行为拦截能力
 */
public interface GameMode {

    /** 唯一标识，例如 "sre:murder"、"my_mod:arena" */
    String getId();

    /** 人类可读的名称 */
    String getDisplayName();

    /** 此模式拥有的所有任务分类（含继承自 ALL 的分类） */
    List<TaskCategory> getTaskCategories();

    /** 检查此模式当前是否在给定世界中激活 */
    boolean isActive(ServerLevel level);

    // ========== 生命周期钩子 ==========

    /** 准备阶段（加载地图、分配角色） */
    default void onPreStart(ServerLevel level) {}

    /** 游戏正式开始 */
    default void onStart(ServerLevel level) {}

    /** 每 tick 更新 */
    default void onTick(ServerLevel level) {}

    /** 玩家加入游戏 */
    default void onPlayerJoin(ServerPlayer player) {}

    /** 玩家离开游戏 */
    default void onPlayerLeave(ServerPlayer player) {}

    /** 任务完成时触发 */
    default void onTaskComplete(ServerPlayer player, TaskInstance task) {}

    /** 检查胜利条件，返回非空 Optional 表示游戏结束 */
    default Optional<WinResult> checkWinCondition(ServerLevel level) {
        return Optional.empty();
    }

    /** 游戏结束 */
    default void onEnd(ServerLevel level, WinResult result) {}

    /** 清理现场（重置世界状态等） */
    default void onCleanup(ServerLevel level) {}

    // ========== 任务行为拦截 ==========

    /** 分配任务前的过滤逻辑 */
    default List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player) {
        return tasks;
    }

    /** 任务分配时调用 */
    default void onTaskAssign(ServerPlayer player, TaskInstance task) {}

    /** 任务 tick 时调用 */
    default void onTaskTick(ServerPlayer player, TaskInstance task) {}

    /** 进度变化时调用 */
    default void onTaskProgressChange(ServerPlayer player, TaskInstance task, int oldProgress) {}

    /** 覆盖任务的完成检测。返回 non-empty Optional 则替代任务自己的 checker。 */
    default Optional<Boolean> overrideCompletionCheck(ServerPlayer player, TaskInstance task) {
        return Optional.empty();
    }
}
```

- [ ] **Step 5: Create GameModeRegistry.java**

```java
package com.habitrain.core.api;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

/**
 * 游戏模式注册中心。
 * DLC 模组在 onInitialize() 中通过 register() 注册自定义 GameMode。
 * 注册表在模组加载完成后冻结。
 */
public class GameModeRegistry {
    private static final Map<String, GameMode> REGISTRY = new LinkedHashMap<>();
    private static boolean frozen = false;

    public static void register(String modId, String modeId, GameMode mode) {
        if (frozen) {
            throw new IllegalStateException("GameMode registry is frozen! Register modes during mod initialization only.");
        }
        String fullId = modId + ":" + modeId;
        if (REGISTRY.containsKey(fullId)) {
            throw new IllegalArgumentException("GameMode '" + fullId + "' is already registered!");
        }
        REGISTRY.put(fullId, mode);
        HabiTrainCore.LOGGER.info("Registered GameMode: {} ({})", fullId, mode.getDisplayName());
    }

    public static GameMode get(String fullId) {
        return REGISTRY.get(fullId);
    }

    public static Collection<GameMode> getAll() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    public static Set<String> getAllIds() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
    }

    /**
     * 查找在指定世界激活的 GameMode。
     * 如果多个模式同时激活，返回第一个匹配的。
     */
    public static Optional<GameMode> getActiveForLevel(ServerLevel level) {
        return REGISTRY.values().stream()
                .filter(m -> m.isActive(level))
                .findFirst();
    }

    public static boolean isRegistered(String fullId) {
        return REGISTRY.containsKey(fullId);
    }

    public static int size() { return REGISTRY.size(); }

    public static void freeze() { frozen = true; }

    public static boolean isFrozen() { return frozen; }
}
```

- [ ] **Step 6: Create AbstractGameMode.java**

```java
package com.habitrain.core.game;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.WinResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Optional;

/**
 * GameMode 骨架实现。
 * 子类只需实现 getId()、getDisplayName()、getTaskCategories()、isActive()。
 * 生命周期钩子按需覆盖，默认均为空操作。
 */
public abstract class AbstractGameMode implements GameMode {

    @Override
    public void onPreStart(ServerLevel level) {}

    @Override
    public void onStart(ServerLevel level) {}

    @Override
    public void onTick(ServerLevel level) {}

    @Override
    public void onPlayerJoin(ServerPlayer player) {}

    @Override
    public void onPlayerLeave(ServerPlayer player) {}

    @Override
    public void onTaskComplete(ServerPlayer player, TaskInstance task) {}

    @Override
    public Optional<WinResult> checkWinCondition(ServerLevel level) {
        return Optional.empty();
    }

    @Override
    public void onEnd(ServerLevel level, WinResult result) {}

    @Override
    public void onCleanup(ServerLevel level) {}

    @Override
    public List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player) {
        return tasks;
    }

    @Override
    public void onTaskAssign(ServerPlayer player, TaskInstance task) {}

    @Override
    public void onTaskTick(ServerPlayer player, TaskInstance task) {}

    @Override
    public void onTaskProgressChange(ServerPlayer player, TaskInstance task, int oldProgress) {}

    @Override
    public Optional<Boolean> overrideCompletionCheck(ServerPlayer player, TaskInstance task) {
        return Optional.empty();
    }
}
```

- [ ] **Step 7: Compile check**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "api: add GameMode, GameModeRegistry, TaskCategory, WinResult interfaces"
```

---

## Task 3: API Layer — Task Definition & Instance

**Files:**
- Create: `api/TaskDefinition.java` (migrate from `HabiTaskDefinition` + add new fields)
- Create: `api/TaskInstance.java` (migrate from `HabiTaskInstance` + add new fields)

**Interfaces:**
- Consumes: `TaskCategory` (from Task 2)
- Produces: `TaskDefinition`, `TaskInstance`

- [ ] **Step 1: Create TaskDefinition.java**

```java
package com.habitrain.core.api;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;

import java.awt.Color;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * 任务定义 — 取代 HabiTaskDefinition。
 * 新增: timeLimit、canRepeat、tags 等扩展字段。
 */
public class TaskDefinition {

    private final String modId;
    private final String taskId;
    private final String fullId;
    private final String displayName;

    // 分类支持：SRE 原版枚举 OR 自定义 TaskCategory
    private final HabiTaskCategory originalCategory;
    private final String gameModeId;
    private final TaskCategory customCategory;

    private final float weight;
    private final int blockTypeId;
    private final Color instinctColor;
    private final boolean canDirectlyWin;
    private final Set<Block> scanBlocks;
    private final Set<String> scanBlockIds;

    // 新增字段
    private final int timeLimit;           // 0 = 不限时
    private final boolean canRepeat;
    private final boolean shareProgress;
    private final List<String> tags;

    // 回调函数
    private final BiConsumer<Player, TaskInstance> onAssignHandler;
    private final BiConsumer<Player, TaskInstance> onCompleteHandler;
    private final BiConsumer<Player, TaskInstance> onRemoveHandler;        // NEW
    private final BiConsumer<Player, TaskInstance> onFailHandler;          // NEW
    private final BiFunction<Player, TaskInstance, Boolean> completionChecker;
    private final BiConsumer<Player, TaskInstance> tickHandler;
    private final BiPredicate<Player, TaskInstance> canAssignPredicate;
    private final ProgressUpdateHandler onProgressUpdateHandler;           // NEW

    @FunctionalInterface
    public interface ProgressUpdateHandler {
        void onProgressUpdate(Player player, TaskInstance task, int oldProgress);
    }

    private TaskDefinition(Builder builder) {
        this.modId = builder.modId;
        this.taskId = builder.taskId;
        this.fullId = builder.modId + ":" + builder.taskId;
        this.displayName = builder.displayName;
        this.originalCategory = builder.originalCategory;
        this.gameModeId = builder.gameModeId;
        this.customCategory = builder.customCategory;
        this.weight = builder.weight;
        this.blockTypeId = builder.blockTypeId;
        this.instinctColor = builder.instinctColor;
        this.canDirectlyWin = builder.canDirectlyWin;
        this.scanBlocks = builder.scanBlocks;
        this.scanBlockIds = builder.scanBlockIds;
        this.timeLimit = builder.timeLimit;
        this.canRepeat = builder.canRepeat;
        this.shareProgress = builder.shareProgress;
        this.tags = builder.tags;
        this.onAssignHandler = builder.onAssignHandler;
        this.onCompleteHandler = builder.onCompleteHandler;
        this.onRemoveHandler = builder.onRemoveHandler;
        this.onFailHandler = builder.onFailHandler;
        this.completionChecker = builder.completionChecker;
        this.tickHandler = builder.tickHandler;
        this.canAssignPredicate = builder.canAssignPredicate;
        this.onProgressUpdateHandler = builder.onProgressUpdateHandler;
    }

    // --- Getters ---
    public String getFullId() { return fullId; }
    public String getModId() { return modId; }
    public String getTaskId() { return taskId; }
    public String getDisplayName() { return displayName; }
    public HabiTaskCategory getOriginalCategory() { return originalCategory; }
    public String getGameModeId() { return gameModeId; }
    public TaskCategory getCustomCategory() { return customCategory; }
    public float getWeight() { return weight; }
    public int getBlockTypeId() { return blockTypeId; }
    public Color getInstinctColor() { return instinctColor; }
    public boolean canDirectlyWin() { return canDirectlyWin; }
    public Set<Block> getScanBlocks() { return scanBlocks; }
    public Set<String> getScanBlockIds() { return scanBlockIds; }
    public int getTimeLimit() { return timeLimit; }
    public boolean canRepeat() { return canRepeat; }
    public boolean isShareProgress() { return shareProgress; }
    public List<String> getTags() { return tags; }

    // --- Callback dispatch ---
    public void onAssign(Player player, TaskInstance instance) { if (onAssignHandler != null) onAssignHandler.accept(player, instance); }
    public void onComplete(Player player, TaskInstance instance) { if (onCompleteHandler != null) onCompleteHandler.accept(player, instance); }
    public void onRemove(Player player, TaskInstance instance) { if (onRemoveHandler != null) onRemoveHandler.accept(player, instance); }
    public void onFail(Player player, TaskInstance instance) { if (onFailHandler != null) onFailHandler.accept(player, instance); }
    public boolean checkCompletion(Player player, TaskInstance instance) { if (completionChecker != null) return completionChecker.apply(player, instance); return instance.isFulfilled(); }
    public void onTick(Player player, TaskInstance instance) { if (tickHandler != null) tickHandler.accept(player, instance); }
    public boolean canAssign(Player player, TaskInstance instance) { if (canAssignPredicate != null) return canAssignPredicate.test(player, instance); return true; }
    public void onProgressUpdate(Player player, TaskInstance instance, int oldProgress) { if (onProgressUpdateHandler != null) onProgressUpdateHandler.onProgressUpdate(player, instance, oldProgress); }

    // --- Builder ---
    public static class Builder {
        private final String modId;
        private final String taskId;
        private String displayName;
        private HabiTaskCategory originalCategory = HabiTaskCategory.ALL;
        private String gameModeId = "sre:base";
        private TaskCategory customCategory;
        private float weight = 1.0f;
        private int blockTypeId = -1;
        private Color instinctColor = new Color(200, 200, 200, 180);
        private boolean canDirectlyWin = false;
        private Set<Block> scanBlocks = Set.of();
        private Set<String> scanBlockIds = Set.of();
        private int timeLimit = 0;
        private boolean canRepeat = false;
        private boolean shareProgress = false;
        private List<String> tags = List.of();

        private BiConsumer<Player, TaskInstance> onAssignHandler;
        private BiConsumer<Player, TaskInstance> onCompleteHandler;
        private BiConsumer<Player, TaskInstance> onRemoveHandler;
        private BiConsumer<Player, TaskInstance> onFailHandler;
        private BiFunction<Player, TaskInstance, Boolean> completionChecker;
        private BiConsumer<Player, TaskInstance> tickHandler;
        private BiPredicate<Player, TaskInstance> canAssignPredicate;
        private ProgressUpdateHandler onProgressUpdateHandler;

        public Builder(String modId, String taskId) {
            this.modId = modId;
            this.taskId = taskId;
            this.displayName = taskId;
        }

        public Builder displayName(String name) { this.displayName = name; return this; }
        public Builder originalCategory(HabiTaskCategory cat) { this.originalCategory = cat; return this; }
        public Builder customCategory(TaskCategory cat) { this.customCategory = cat; return this; }
        public Builder gameMode(String gameModeId) { this.gameModeId = gameModeId; return this; }
        public Builder weight(float w) { this.weight = w; return this; }
        public Builder blockTypeId(int id) { this.blockTypeId = id; return this; }
        public Builder instinctColor(Color c) { this.instinctColor = c; return this; }
        public Builder canDirectlyWin(boolean v) { this.canDirectlyWin = v; return this; }
        public Builder scanBlocks(Block... blocks) { this.scanBlocks = Set.of(blocks); return this; }
        public Builder scanBlockIds(String... ids) { this.scanBlockIds = Set.of(ids); return this; }
        public Builder timeLimit(int seconds) { this.timeLimit = seconds; return this; }
        public Builder canRepeat(boolean v) { this.canRepeat = v; return this; }
        public Builder shareProgress(boolean v) { this.shareProgress = v; return this; }
        public Builder tags(String... t) { this.tags = List.of(t); return this; }

        public Builder onAssign(BiConsumer<Player, TaskInstance> h) { this.onAssignHandler = h; return this; }
        public Builder onComplete(BiConsumer<Player, TaskInstance> h) { this.onCompleteHandler = h; return this; }
        public Builder onRemove(BiConsumer<Player, TaskInstance> h) { this.onRemoveHandler = h; return this; }
        public Builder onFail(BiConsumer<Player, TaskInstance> h) { this.onFailHandler = h; return this; }
        public Builder completionChecker(BiFunction<Player, TaskInstance, Boolean> h) { this.completionChecker = h; return this; }
        public Builder onTick(BiConsumer<Player, TaskInstance> h) { this.tickHandler = h; return this; }
        public Builder canAssign(BiPredicate<Player, TaskInstance> h) { this.canAssignPredicate = h; return this; }
        public Builder onProgressUpdate(ProgressUpdateHandler h) { this.onProgressUpdateHandler = h; return this; }

        public TaskDefinition build() { return new TaskDefinition(this); }
    }
}
```

- [ ] **Step 2: Create TaskInstance.java**

Migrate from `HabiTaskInstance` — the class now implements an internal interface pattern. Core logic unchanged, but adds timeLimit tracking and progress update callback dispatch.

```java
package com.habitrain.core.api;

import com.habitrain.core.task.TaskManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 任务运行时实例 — 取代 HabiTaskInstance。
 * 新增: 计时器支持、进度回调分发。
 */
public class TaskInstance {

    private final TaskDefinition definition;
    private boolean fulfilled = false;
    private int progress = 0;
    private int maxProgress = 1;

    // 限时任务计时 (tick 数)
    private int elapsedTicks = 0;
    private boolean failed = false;

    public TaskInstance(TaskDefinition definition) {
        this.definition = definition;
    }

    public TaskDefinition getDefinition() { return definition; }
    public String getFullId() { return definition.getFullId(); }
    public int getProgress() { return progress; }
    public int getMaxProgress() { return maxProgress; }
    public int getElapsedTicks() { return elapsedTicks; }
    public boolean isFulfilled() { return fulfilled; }
    public boolean isFailed() { return failed; }

    public void setProgress(int progress) {
        int old = this.progress;
        this.progress = progress;
        if (old != progress) {
            definition.onProgressUpdate(null, this, old);
        }
    }

    public void setMaxProgress(int maxProgress) { this.maxProgress = maxProgress; }
    public void setFulfilled(boolean fulfilled) { this.fulfilled = fulfilled; }

    public void markFailed() {
        this.failed = true;
        this.fulfilled = true;
    }

    /**
     * 每个服务端 tick 调用一次。
     * 处理: tick 回调 → 计时器 → 完成检测 → 超时检测。
     */
    public void tick(Player player) {
        if (fulfilled) return;

        // 限时检测
        if (definition.getTimeLimit() > 0) {
            elapsedTicks++;
            if (elapsedTicks >= definition.getTimeLimit() * 20) {
                markFailed();
                definition.onFail(player, this);
                return;
            }
        }

        // 调用 tick 回调
        definition.onTick(player, this);

        // 完成检测
        if (definition.checkCompletion(player, this)) {
            this.fulfilled = true;
            if (player instanceof ServerPlayer sp) {
                definition.onComplete(sp, this);
                TaskManager.getInstance().handleTaskCompletion(sp, this);
            }
        }
    }

    public String getName() { return definition.getTaskId(); }
    public String getCustomTaskId() { return definition.getFullId(); }

    public CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("customId", definition.getFullId());
        nbt.putString("customName", definition.getDisplayName());
        nbt.putBoolean("fulfilled", this.fulfilled);
        nbt.putBoolean("failed", this.failed);
        nbt.putInt("progress", this.progress);
        nbt.putInt("maxProgress", this.maxProgress);
        nbt.putInt("elapsedTicks", this.elapsedTicks);
        return nbt;
    }

    public static TaskInstance fromNbt(CompoundTag nbt) {
        String customId = nbt.getString("customId");
        TaskDefinition def = TaskRegistry.get(customId);
        if (def == null) return null;

        TaskInstance instance = new TaskInstance(def);
        instance.fulfilled = nbt.getBoolean("fulfilled");
        instance.failed = nbt.getBoolean("failed");
        instance.progress = nbt.getInt("progress");
        instance.maxProgress = nbt.getInt("maxProgress");
        instance.elapsedTicks = nbt.getInt("elapsedTicks");
        return instance;
    }
}
```

- [ ] **Step 3: Create TaskRegistry.java**

```java
package com.habitrain.core.api;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 任务注册中心 — 取代 HabiTaskRegistry。
 * 新增: 按 GameMode 查询。
 */
public class TaskRegistry {
    private static final Map<String, TaskDefinition> REGISTRY = new LinkedHashMap<>();
    private static boolean frozen = false;

    public static void register(TaskDefinition definition) {
        if (frozen) throw new IllegalStateException("Task registry is frozen!");
        String fullId = definition.getFullId();
        if (REGISTRY.containsKey(fullId)) throw new IllegalArgumentException("Task '" + fullId + "' is already registered!");
        REGISTRY.put(fullId, definition);
    }

    public static TaskDefinition register(String modId, String taskId, Consumer<TaskDefinition.Builder> builder) {
        TaskDefinition.Builder b = new TaskDefinition.Builder(modId, taskId);
        builder.accept(b);
        TaskDefinition def = b.build();
        register(def);
        return def;
    }

    public static Collection<TaskDefinition> getAll() { return Collections.unmodifiableCollection(REGISTRY.values()); }
    public static TaskDefinition get(String fullId) { return REGISTRY.get(fullId); }
    public static Set<String> getAllIds() { return Collections.unmodifiableSet(REGISTRY.keySet()); }
    public static boolean isRegistered(String fullId) { return REGISTRY.containsKey(fullId); }
    public static int size() { return REGISTRY.size(); }

    /** 按 GameMode ID 查询属于某个模式的所有任务 */
    public static List<TaskDefinition> getByGameMode(String gameModeId) {
        return REGISTRY.values().stream()
                .filter(def -> gameModeId.equals(def.getGameModeId()))
                .collect(Collectors.toList());
    }

    /** 按 SRE 原版分类查询 */
    public static List<TaskDefinition> getByOriginalCategory(HabiTaskCategory category) {
        return REGISTRY.values().stream()
                .filter(def -> def.getOriginalCategory() == category
                        || def.getOriginalCategory() == HabiTaskCategory.ALL)
                .toList();
    }

    public static void freeze() { frozen = true; }
    public static boolean isFrozen() { return frozen; }
}
```

- [ ] **Step 4: Compile check**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL (TaskDefinition references HabiTaskCategory from old package — this is OK, old package still exists at this point)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "api: add TaskDefinition, TaskInstance, TaskRegistry with new fields"
```

---

## Task 4: Task Engine — Manager, Balancer, Engine

**Files:**
- Create: `task/TaskManager.java`
- Create: `task/TaskBalancer.java`
- Create: `task/Engine.java`

**Interfaces:**
- Consumes: `TaskRegistry`, `TaskDefinition`, `TaskInstance`, `GameModeRegistry`, `ConfigManager`
- Produces: central task orchestration

- [ ] **Step 1: Create TaskBalancer.java**

Extract the auto-balance formula into its own class:

```java
package com.habitrain.core.task;

/**
 * 自动平衡计算器 — 从原 HabiConfigManager 中提取的纯逻辑。
 *
 * target × originalCount
 * boost = ─────────────────────
 * (1-target) × dlcCount
 */
public class TaskBalancer {

    private TaskBalancer() {}

    public static float calcBoost(float target, long dlcCount, long origCount) {
        if (dlcCount <= 0 || origCount <= 0) return 1.0f;
        if (target <= 0f) return 0f;
        if (target >= 0.85f) return 10f;
        float boost = (target / (1f - target)) * ((float) origCount / (float) dlcCount);
        return Math.max(0.0f, Math.min(10.0f, boost));
    }

    public static float calcDlcPercent(float boost, long dlcCount, long origCount) {
        float dlcTotal = boost * dlcCount;
        float grand = dlcTotal + origCount;
        return grand > 0 ? dlcTotal / grand : 0;
    }
}
```

- [ ] **Step 2: Create Engine.java**

Task assignment engine — weighted random selection with GameMode awareness:

```java
package com.habitrain.core.task;

import com.habitrain.core.api.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务分配引擎。
 * 根据当前 GameMode、地图、配置过滤后做加权随机选择。
 */
public class Engine {

    private static Engine INSTANCE;
    public static Engine getInstance() {
        if (INSTANCE == null) INSTANCE = new Engine();
        return INSTANCE;
    }

    /**
     * 为玩家生成一个随机任务。
     *
     * @param player    目标玩家
     * @param gameMode  当前活跃的 GameMode（null = 只用 SRE 原版分类）
     * @return 生成的实例，或 null（无可用任务）
     */
    public TaskInstance generateTask(Player player, GameMode gameMode) {
        List<TaskDefinition> pool = buildTaskPool(player, gameMode);
        if (pool.isEmpty()) return null;

        // 加权随机
        float totalWeight = 0;
        for (TaskDefinition def : pool) totalWeight += def.getWeight();
        if (totalWeight <= 0) return null;

        float rand = player.getRandom().nextFloat() * totalWeight;
        for (TaskDefinition def : pool) {
            rand -= def.getWeight();
            if (rand <= 0) {
                TaskInstance instance = new TaskInstance(def);
                def.onAssign(player, instance);
                if (gameMode != null) gameMode.onTaskAssign((ServerPlayer) player, instance);
                return instance;
            }
        }

        // fallback — 取最后一项
        TaskDefinition last = pool.get(pool.size() - 1);
        TaskInstance instance = new TaskInstance(last);
        last.onAssign(player, instance);
        return instance;
    }

    /**
     * 构建当前可用的任务池。
     */
    public List<TaskDefinition> buildTaskPool(Player player, GameMode gameMode) {
        // 如果有 GameMode，优先使用它的过滤
        List<TaskDefinition> all = new ArrayList<>(TaskRegistry.getAll());

        if (gameMode != null) {
            all = gameMode.filterAvailableTasks(all, (ServerPlayer) player);
        }

        // 过滤掉不能分配的
        return all.stream()
                .filter(def -> def.canAssign(player, null))
                .collect(Collectors.toList());
    }
}
```

- [ ] **Step 3: Create TaskManager.java**

Central runtime manager — retains active task tracking from HabiTaskManager:

```java
package com.habitrain.core.task;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.*;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.sre.TaskEnumHelper;
import io.wifi.starrailexpress.cca.SREGameRoundEndComponent;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务管理器 — 取代 HabiTaskManager。
 * 管理活跃自定义任务跟踪、任务完成处理。
 */
public class TaskManager {
    private static TaskManager INSTANCE;

    public static TaskManager getInstance() {
        if (INSTANCE == null) INSTANCE = new TaskManager();
        return INSTANCE;
    }

    private final Map<UUID, TaskInstance> activeCustomTasks = new HashMap<>();

    public TaskInstance getActiveTask(UUID playerUuid) { return activeCustomTasks.get(playerUuid); }
    public void setActiveTask(UUID playerUuid, TaskInstance task) { activeCustomTasks.put(playerUuid, task); }
    public void removeActiveTask(UUID playerUuid) { activeCustomTasks.remove(playerUuid); }

    public boolean hasTaskWithId(UUID playerUuid, String fullId) {
        TaskInstance existing = activeCustomTasks.get(playerUuid);
        return existing != null && existing.getFullId().equals(fullId);
    }

    /**
     * 处理任务完成 — 移除了自动录制逻辑。
     * 触发 GameMode 的 onTaskComplete 回调。
     */
    public void handleTaskCompletion(ServerPlayer player, TaskInstance instance) {
        TaskDefinition def = instance.getDefinition();

        // 通知活跃 GameMode
        if (player.level() instanceof ServerLevel sl) {
            GameModeRegistry.getActiveForLevel(sl).ifPresent(gm ->
                gm.onTaskComplete(player, instance));
        }

        // 直接获胜
        if (def.canDirectlyWin()) {
            triggerDirectWin(player, instance);
        }
    }

    private void triggerDirectWin(ServerPlayer player, TaskInstance instance) {
        try {
            SREGameRoundEndComponent roundEnd =
                    SREGameRoundEndComponent.KEY.get(player.level());
            if (roundEnd != null) {
                roundEnd.CustomWinnerID = instance.getDefinition().getModId()
                        + "_" + instance.getDefinition().getTaskId() + "_win";
                roundEnd.CustomWinnerPlayers.add(player.getUUID());
                roundEnd.setWinStatus(
                        io.wifi.starrailexpress.game.GameUtils.WinStatus.CUSTOM);
                roundEnd.sync();
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("Failed to trigger direct win: " + instance.getFullId(), e);
        }
    }
}
```

- [ ] **Step 4: Compile check**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "task: add TaskManager, TaskBalancer, Engine"
```

---

## Task 5: SRE Integration — GameMode Implementations

**Files:**
- Create: `game/sre/TaskEnumHelper.java` (migrate from `impl/TaskEnumHelper`)
- Create: `game/sre/SREGameModeBase.java`
- Create: `game/sre/SREMurderMode.java`
- Create: `game/sre/SERepairMode.java`

**Interfaces:**
- Consumes: `AbstractGameMode`, `TaskCategory`, `HabiTaskCategory`
- Produces: SRE-specific GameMode wrappers

- [ ] **Step 1: Migrate TaskEnumHelper.java**

Copy from `com.habitrain.taskapi.impl.TaskEnumHelper` to `com.habitrain.core.game.sre.TaskEnumHelper`, update package. Logic is unchanged — it dynamically resolves `SREPlayerTaskComponent.Task.CUSTOM` via reflection.

- [ ] **Step 2: Create SREGameModeBase.java**

```java
package com.habitrain.core.game.sre;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.*;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.task.TaskManager;
import com.habitrain.core.api.HabiTaskCategory;
import com.habitrain.core.api.TaskRegistry;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.compat.TrainVoicePlugin;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * SRE 游戏模式公共基类。
 * 所有 SRE 相关的语音群组、原版任务注册、游戏事件处理集中在此。
 */
public abstract class SREGameModeBase extends AbstractGameMode {

    // 大厅语音群组
    private static Group LOBBY_GROUP = null;
    private static final UUID LOBBY_GROUP_ID = UUID.randomUUID();
    private static final Map<UUID, Integer> pendingVoiceJoins = new HashMap<>();
    private static final int MAX_VOICE_JOIN_RETRIES = 200;

    // 游戏结束语音群组恢复
    private static boolean pendingGameEndGroupJoin = false;

    protected final List<TaskCategory> taskCategories = new ArrayList<>();

    protected SREGameModeBase() {
        registerSREEvents();
    }

    /** 注册原版 SRE 任务到系统 */
    protected void registerBuiltinTasks(String modeId) {
        // Murder mode tasks
        registerBuiltin("sleep", "睡觉", HabiTaskCategory.MURDER, 1.0f, 4);
        registerBuiltin("eat", "进食", HabiTaskCategory.MURDER, 1.0f, 1);
        registerBuiltin("drink", "喝水", HabiTaskCategory.MURDER, 1.0f, 2);
        registerBuiltin("exercise", "锻炼", HabiTaskCategory.MURDER, 1.0f, 5);
        registerBuiltin("raed_book", "阅读", HabiTaskCategory.MURDER, 1.0f, 6);
        registerBuiltin("bathe", "洗澡", HabiTaskCategory.MURDER, 1.0f, 3);
        registerBuiltin("toilet", "上厕所", HabiTaskCategory.MURDER, 1.0f, 8);
        registerBuiltin("chair", "坐椅子", HabiTaskCategory.MURDER, 1.0f, 9);
        registerBuiltin("note_block", "音符盒", HabiTaskCategory.MURDER, 1.0f, 10);
        registerBuiltin("meditate", "冥想", HabiTaskCategory.MURDER, 1.0f, -1);
        registerBuiltin("outside", "外出", HabiTaskCategory.MURDER, 1.0f, -1);
        registerBuiltin("breathe", "呼吸新鲜空气", HabiTaskCategory.MURDER, 1.0f, -1);

        // Repair mode tasks
        registerBuiltin("repair_wire", "修复线路", HabiTaskCategory.REPAIR, 1.0f, -1);
        registerBuiltin("repair_panel", "修复面板", HabiTaskCategory.REPAIR, 1.0f, -1);

        // Shared tasks
        registerBuiltin("vending_machine", "售货机", HabiTaskCategory.ALL, 0.5f, 11);
    }

    private void registerBuiltin(String id, String displayName, HabiTaskCategory category,
                                  float weight, int blockTypeId) {
        TaskRegistry.register(new TaskDefinition.Builder(HabiTrainCore.MOD_ID, id)
                .displayName(displayName)
                .originalCategory(category)
                .gameMode("sre:base")
                .weight(weight)
                .blockTypeId(blockTypeId)
                .build()
        );
    }

    // ========== SRE 事件注册 ==========

    private void registerSREEvents() {
        OnGameStarted.EVENT.register(serverLevel -> {
            // 清除待加入语音群组的队列
            if (!pendingVoiceJoins.isEmpty()) {
                pendingVoiceJoins.clear();
                HabiTrainCore.LOGGER.info("[VoiceGroup] 游戏开始，已清理待加入语音群组的队列");
            }
        });

        OnGameEnd.EVENT.register((serverLevel, gameWorldComponent) -> {
            // 标记下一 tick 处理语音群组恢复
            pendingGameEndGroupJoin = true;
            HabiTrainCore.LOGGER.info("[VoiceGroup] 游戏结束，标记待处理");
        });
    }

    // ========== 语音群组管理 ==========

    protected static void addPlayerToLobbyGroup(MinecraftServer server, UUID playerUUID) {
        if (TrainVoicePlugin.isVoiceChatMissing() || TrainVoicePlugin.SERVER_API == null) return;

        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
        VoicechatConnection connection = api.getConnectionOf(playerUUID);
        if (connection == null) return;

        try {
            if (LOBBY_GROUP == null) {
                LOBBY_GROUP = api.groupBuilder()
                        .setId(LOBBY_GROUP_ID)
                        .setName("LobbyChat")
                        .setPersistent(true)
                        .setType(Group.Type.OPEN)
                        .setHidden(false)
                        .build();
            }
            connection.setGroup(LOBBY_GROUP);
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("[VoiceGroup] 添加玩家到语音群组失败", e);
        }
    }

    protected static void processPendingVoiceJoins(MinecraftServer server) {
        if (pendingVoiceJoins.isEmpty()) return;
        Iterator<Map.Entry<UUID, Integer>> it = pendingVoiceJoins.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            UUID playerId = entry.getKey();
            if (server.getPlayerList().getPlayer(playerId) == null) { it.remove(); continue; }
            if (entry.getValue() <= 0) { it.remove(); continue; }
            addPlayerToLobbyGroup(server, playerId);
            entry.setValue(entry.getValue() - 1);
            if (server.getPlayerList().getPlayer(playerId) != null) {
                // connected successfully
                it.remove();
            }
        }
    }

    protected static void processGameEndGroupJoin(MinecraftServer server) {
        if (!pendingGameEndGroupJoin) return;
        pendingGameEndGroupJoin = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            addPlayerToLobbyGroup(server, player.getUUID());
        }
    }
}
```

- [ ] **Step 3: Create SREMurderMode.java**

```java
package com.habitrain.core.game.sre;

import com.habitrain.core.api.HabiTaskCategory;
import com.habitrain.core.api.TaskCategory;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public class SREMurderMode extends SREGameModeBase {

    public static final String MODE_ID = "sre:murder";

    public SREMurderMode() {
        taskCategories.add(new TaskCategory("sre:murder", "谋杀模式", MODE_ID));
        taskCategories.add(TaskCategory.ALL);
    }

    @Override
    public String getId() { return MODE_ID; }

    @Override
    public String getDisplayName() { return "经典列车谋杀案"; }

    @Override
    public List<TaskCategory> getTaskCategories() { return taskCategories; }

    @Override
    public boolean isActive(ServerLevel level) {
        try {
            SREGameWorldComponent gw = SREGameWorldComponent.KEY.get(level);
            if (gw == null || gw.getGameMode() == null) return false;
            String modeId = gw.getGameMode().identifier.toString();
            return !modeId.contains("repair");
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Create SERepairMode.java**

```java
package com.habitrain.core.game.sre;

import com.habitrain.core.api.HabiTaskCategory;
import com.habitrain.core.api.TaskCategory;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public class SERepairMode extends SREGameModeBase {

    public static final String MODE_ID = "sre:repair";

    public SERepairMode() {
        taskCategories.add(new TaskCategory("sre:repair", "修机模式", MODE_ID));
        taskCategories.add(TaskCategory.ALL);
    }

    @Override
    public String getId() { return MODE_ID; }

    @Override
    public String getDisplayName() { return "修复逃脱模式"; }

    @Override
    public List<TaskCategory> getTaskCategories() { return taskCategories; }

    @Override
    public boolean isActive(ServerLevel level) {
        try {
            SREGameWorldComponent gw = SREGameWorldComponent.KEY.get(level);
            if (gw == null || gw.getGameMode() == null) return false;
            String modeId = gw.getGameMode().identifier.toString();
            return modeId.contains("repair");
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 5: Compile check**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "game: add SREGameModeBase, SREMurderMode, SERepairMode"
```

---

## Task 6: SRE Mixins Migration

**Files:**
- Create: `game/sre/mixin/MapScannerMixin.java` (migrate)
- Create: `game/sre/mixin/GenerateTaskMixin.java` (migrate)
- Create: `game/sre/mixin/ServerTickMixin.java` (migrate)
- Create: `game/sre/mixin/RoleMethodDispatcherMixin.java` (migrate)
- Create: `game/sre/mixin/NunchuckCooldownMixin.java` (migrate)
- Modify: `habitrain_core.mixins.json` (register server mixins)

**Interfaces:**
- Consumes: SREGameModeBase (for voice group tick handling)
- Produces: working SRE mixins at new package path

- [ ] **Step 1: Migrate each mixin file**

For each mixin under `com.habitrain.taskapi.impl.mixin`, copy to `com.habitrain.core.game.sre.mixin` and update:
- Package declaration
- Any references to `HabiTaskManager`, `HabiConfigManager` → `TaskManager`, `ConfigManager`
- Any direct references to old API classes → new API classes

**MapScannerMixin.java** — changes: references `HabiTaskDefinition` → `TaskDefinition`, `HabiTaskRegistry` → `TaskRegistry`

**GenerateTaskMixin.java** — changes: `HabiTaskManager` → `TaskManager`, `HabiTaskInstance` → `TaskInstance`

**ServerTickMixin.java** — changes: delegates to SREGameModeBase.processPendingVoiceJoins() and SREGameModeBase.processGameEndGroupJoin() instead of the old static methods on HabiTrainTaskAPI

**RoleMethodDispatcherMixin.java** — changes: `HabiConfigManager` → `ConfigManager`

**NunchuckCooldownMixin.java** — no API changes needed, just package update

- [ ] **Step 2: Update habitrain_core.mixins.json**

```json
{
    "required": true,
    "package": "com.habitrain.core.game.sre.mixin",
    "compatibilityLevel": "JAVA_21",
    "mixins": [
        "MapScannerMixin",
        "GenerateTaskMixin",
        "ServerTickMixin",
        "RoleMethodDispatcherMixin",
        "NunchuckCooldownMixin"
    ],
    "client": [],
    "server": []
}
```

- [ ] **Step 3: Compile check**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "game: migrate SRE mixins to new package"
```

---

## Task 7: Config System

**Files:**
- Create: `config/TaskConfigEntry.java` (migrate from `HabiTaskConfigEntry`)
- Create: `config/ConfigManager.java` (migrate from `HabiConfigManager`, remove autoReplay)
- Create: `config/GameModeConfigScope.java`

**Interfaces:**
- Consumes: `TaskRegistry`
- Produces: `ConfigManager.getInstance()`, `TaskConfigEntry`

- [ ] **Step 1: Create TaskConfigEntry.java**

Migrate from `HabiTaskConfigEntry` — identical fields, no auto-replay. Package changes only, class body is a direct copy with no behavioural change.

- [ ] **Step 2: Create GameModeConfigScope.java**

```java
package com.habitrain.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * per-GameMode 配置作用域。
 * DLC 模组可将任意自定义配置存入此处。
 */
public class GameModeConfigScope {
    private final String gameModeId;
    private boolean enabled = true;
    private final Map<String, JsonElement> customSettings = new HashMap<>();

    public GameModeConfigScope(String gameModeId) {
        this.gameModeId = gameModeId;
    }

    public String getGameModeId() { return gameModeId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public JsonElement getSetting(String key) { return customSettings.get(key); }
    public void setSetting(String key, JsonElement value) { customSettings.put(key, value); }
    public Map<String, JsonElement> getCustomSettings() { return customSettings; }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        if (!customSettings.isEmpty()) {
            JsonObject custom = new JsonObject();
            for (Map.Entry<String, JsonElement> e : customSettings.entrySet()) {
                custom.add(e.getKey(), e.getValue());
            }
            obj.add("customSettings", custom);
        }
        return obj;
    }

    public static GameModeConfigScope fromJson(String gameModeId, JsonObject json) {
        GameModeConfigScope scope = new GameModeConfigScope(gameModeId);
        if (json.has("enabled")) scope.enabled = json.get("enabled").getAsBoolean();
        if (json.has("customSettings")) {
            JsonObject custom = json.getAsJsonObject("customSettings");
            for (Map.Entry<String, JsonElement> e : custom.entrySet()) {
                scope.customSettings.put(e.getKey(), e.getValue());
            }
        }
        return scope;
    }
}
```

- [ ] **Step 3: Create ConfigManager.java**

Migrate from `HabiConfigManager` with these changes:
- Remove all `autoReplayRecording` fields, getters, setters, and JSON I/O
- Add `gameModes` section management
- Save/load handles `gameModes` JSON block
- Class/field names updated to reflect new naming

Key structural changes in the JSON load method (compared to original):

```java
// In load(), after loading tasks, add:
if (root.has("gameModes")) {
    JsonObject modes = root.getAsJsonObject("gameModes");
    for (var entry : modes.entrySet()) {
        String modeId = entry.getKey();
        JsonObject modeCfg = entry.getValue().getAsJsonObject();
        gameModeConfigs.put(modeId, GameModeConfigScope.fromJson(modeId, modeCfg));
    }
}

// In save(), add gameModes block:
JsonObject gameModes = new JsonObject();
for (Map.Entry<String, GameModeConfigScope> e : gameModeConfigs.entrySet()) {
    gameModes.add(e.getKey(), e.getValue().toJson());
}
root.add("gameModes", gameModes);
```

Otherwise the class body is a direct copy from `HabiConfigManager`, replacing:
- `HabiTrainTaskAPI.LOGGER` → `HabiTrainCore.LOGGER`
- `HabiTaskConfigEntry` → `TaskConfigEntry`
- Config file path: `habitrain_taskapi.json` → `habitrain_core.json`
- Add `private final Map<String, GameModeConfigScope> gameModeConfigs = new HashMap<>();`

- [ ] **Step 4: Compile check**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "config: add ConfigManager, GameModeConfigScope, remove autoReplay"
```

---

## Task 8: Network Payloads

**Files:**
- Create: `network/TaskConfigPayload.java` (migrate from `TaskConfigSyncPayload`)
- Create: `network/ActiveTaskPayload.java` (migrate from `ActiveCustomTaskPayload`)
- Create: `network/ConfigUpdatePayload.java` (migrate from `ConfigUpdateC2SPayload`)
- Create: `network/ShaderConfigPayload.java` (migrate from `ShaderConfigSyncS2CPayload`)
- Create: `network/ShaderInfoPayload.java` (migrate from `ShaderPackInfoC2SPayload`)

**Interfaces:**
- Consumes: `ConfigManager`
- Produces: Fabric networking registration

- [ ] **Step 1–5: Copy each payload class**

For each payload:
1. Copy source from old package to new `com.habitrain.core.network`
2. Update package declaration
3. Update any old class references (e.g. `HabiConfigManager` → `ConfigManager`)
4. Keep the Fabric networking registration code identical (PayloadType, codec, sending methods)

**ActiveTaskPayload.java** — rename from `ActiveCustomTaskPayload`:
- Class name change only — all fields and logic identical
- The word "Custom" removed from method names for generality:
  - `ActiveCustomTaskPayload.sendToPlayer()` → `ActiveTaskPayload.sendToPlayer()`
  - `ActiveCustomTaskPayload.clearForPlayer()` → `ActiveTaskPayload.clearForPlayer()`

**ConfigUpdatePayload.java** — rename from `ConfigUpdateC2SPayload`:
- Remove "C2S" suffix from class name
- All logic identical

- [ ] **Step 6: Compile check**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew build 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "network: migrate all payloads to new package"
```

---

## Task 9: Main Class — HabiTrainCore

**Files:**
- Create: `HabiTrainCore.java` (replace HabiTrainTaskAPI)
- The instantgroup command impl stays (refactored to use new API references)
- Voice group tick handling stays (delegates to SREGameModeBase static methods)

**Interfaces:**
- Consumes: all previous tasks
- Produces: the main mod entry point

- [ ] **Step 1: Create HabiTrainCore.java**

```java
package com.habitrain.core;

import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.game.sre.SREGameModeBase;
import com.habitrain.core.game.sre.SREMurderMode;
import com.habitrain.core.game.sre.SERepairMode;
import com.habitrain.core.network.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import io.wifi.starrailexpress.compat.TrainVoicePlugin;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 哈比列车核心 — 主入口类。
 * 职责: 配置初始化、GameMode注册、网络包注册、生命周期事件转发。
 */
public class HabiTrainCore implements ModInitializer {
    public static final String MOD_ID = "habitrain_core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("哈比列车核心 (HabiTrain Core) 初始化中...");

        // 1. 配置系统
        ConfigManager.getInstance().load();

        // 2. 注册内置 GameMode（SRE 模式）
        GameModeRegistry.register(MOD_ID, "sre:murder", new SREMurderMode());
        GameModeRegistry.register(MOD_ID, "sre:repair", new SERepairMode());

        // 3. 注册原版任务 (由 SREGameModeBase 构造时触发)
        //    两个模式的基类构造中都会调用 registerBuiltinTasks，
        //    暂存 flag 防重复:
        if (!originalTasksRegistered) {
            new SREMurderMode(); // triggers registerBuiltinTasks internal
            originalTasksRegistered = true;
        }

        // 4. 注册网络包
        TaskConfigPayload.register();
        ActiveTaskPayload.register();
        ConfigUpdatePayload.register();
        ShaderConfigPayload.register();
        ShaderInfoPayload.register();

        // 5. 注册 /instantgroup 命令
        registerCommands();

        // 6. 注册生命周期事件
        registerLifecycleEvents();

        // 冻结注册表 (禁止 DLC 在运行时注册)
        GameModeRegistry.freeze();
        TaskRegistry.freeze();

        LOGGER.info("哈比列车核心 初始化完成！已注册 {} 个 GameMode, {} 个任务",
                GameModeRegistry.size(), TaskRegistry.size());
    }

    private boolean originalTasksRegistered = false;

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("instantgroup")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> executeInstantGroup(ctx, 128))
                    .then(Commands.argument("range", IntegerArgumentType.integer(1, 512))
                            .executes(ctx -> executeInstantGroup(ctx,
                                    IntegerArgumentType.getInteger(ctx, "range")))
                    )
            );
        });
    }

    private void registerLifecycleEvents() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ConfigManager.getInstance().load();
            LOGGER.info("配置已加载，共 {} 个已注册任务", TaskRegistry.size());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SREGameModeBase.processPendingVoiceJoins(server);
            SREGameModeBase.processGameEndGroupJoin(server);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();

            // 玩家在大厅时加入语音群组
            try {
                var gameLevel = server.getLevel(net.minecraft.world.level.Level.OVERWORLD);
                if (gameLevel != null) {
                    var gameWorld = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(gameLevel);
                    if (gameWorld != null && !gameWorld.isRunning()) {
                        SREGameModeBase.addPlayerToLobbyGroup(server, player.getUUID());
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[VoiceGroup] 添加大厅玩家到语音群组失败", e);
            }

            // 同步配置
            TaskConfigPayload.sendToPlayer(player);
            ShaderConfigPayload.sendToPlayer(player);
        });

        // C2S 配置更新接收器
        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                if (!player.hasPermissions(4)) {
                    player.sendSystemMessage(Component.literal("§c你没有权限修改服务端配置（需要 OP 权限）"));
                    return;
                }
                ConfigManager.getInstance().loadFromJsonString(payload.getConfigJson());
                ConfigManager.getInstance().save();
                LOGGER.info("玩家 {} 通过 ModMenu 更新了服务端配置", player.getName().getString());

                if (context.server().isSingleplayer()) return;
                TaskConfigPayload.broadcastToAll(context.server());
                ShaderConfigPayload.broadcastToAll(context.server());
            });
        });

        // C2S 光影包信息接收器
        ServerPlayNetworking.registerGlobalReceiver(ShaderInfoPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                if (player == null) return;
                ConfigManager cfg = ConfigManager.getInstance();
                if (!cfg.isShaderWhitelistEnabled()) return;
                String shaderPackName = payload.getShaderPackName();
                if (shaderPackName.isEmpty()) return;
                boolean allowed = cfg.getShaderWhitelist().stream()
                        .anyMatch(name -> name.equalsIgnoreCase(shaderPackName));
                if (!allowed) {
                    player.connection.disconnect(Component.literal(
                            "§c✖ 未授权的光影包\n\n" +
                            "§7你使用的光影包 §e" + shaderPackName + " §7不在服务器白名单中。\n" +
                            "§7请更换为允许的光影包后重新加入。\n\n" +
                            "§7如需帮助，请联系服务器管理员。"));
                }
            });
        });
    }

    // ========== /instantgroup 命令实现 ==========

    private static int executeInstantGroup(CommandContext<CommandSourceStack> context, int range) {
        CommandSourceStack source = context.getSource();
        ServerPlayer sender = source.getPlayer();
        if (sender == null) { source.sendFailure(Component.literal("§c此命令只能由玩家执行")); return 0; }
        if (TrainVoicePlugin.isVoiceChatMissing() || TrainVoicePlugin.SERVER_API == null) {
            source.sendFailure(Component.literal("§c语音聊天系统未就绪")); return 0;
        }
        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
        if (api.getConnectionOf(sender.getUUID()) == null) {
            source.sendFailure(Component.literal("§c你的语音连接尚未就绪")); return 0;
        }

        MinecraftServer srv = source.getServer();
        Vec3 senderPos = sender.position();
        List<ServerPlayer> nearby = new ArrayList<>();
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            if (p.getUUID().equals(sender.getUUID())) continue;
            if (p.distanceToSqr(sender) <= (double) range * range) nearby.add(p);
        }
        if (nearby.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§e附近 " + range + " 格内没有其他玩家"), true);
            return 0;
        }

        Group tempGroup;
        try {
            tempGroup = api.groupBuilder()
                    .setId(UUID.randomUUID()).setName("临时群组")
                    .setPersistent(false).setType(Group.Type.OPEN).setHidden(false).build();
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c创建语音群组失败")); return 0;
        }

        try {
            VoicechatConnection conn = api.getConnectionOf(sender.getUUID());
            if (conn != null) conn.setGroup(tempGroup);
        } catch (Exception ignored) {}

        int count = 0;
        for (ServerPlayer p : nearby) {
            try {
                VoicechatConnection conn = api.getConnectionOf(p.getUUID());
                if (conn != null) { conn.setGroup(tempGroup); count++; }
            } catch (Exception ignored) {}
        }
        final int finalCount = count;
        source.sendSuccess(() -> Component.literal("§a已将 §e" + finalCount + " §a名附近玩家加入临时语音群组（范围: " + range + " 格）"), true);
        Component notify = Component.literal("§7[语音] §a你已被加入临时语音群组");
        for (ServerPlayer p : nearby) {
            if (api.getConnectionOf(p.getUUID()) != null) p.sendSystemMessage(notify);
        }
        LOGGER.info("[InstantGroup] {} 执行 /instantgroup {}，{} 名玩家", sender.getName().getString(), range, count);
        return count;
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
```

Note: The original task registration needs careful handling to avoid double-registration. Since both SREMurderMode and SERepairMode share the same base `registerBuiltinTasks()`, the actual implementation should either:
1. Have a static boolean guard in SREGameModeBase
2. Or register tasks in HabiTrainCore.onInitialize() directly, not in the mode constructors

The simplest approach: Add a `static boolean builtinTasksRegistered` flag in SREGameModeBase and check it before registering.

```java
// In SREGameModeBase:
private static boolean builtinTasksRegistered = false;

protected void registerBuiltinTasks() {
    if (builtinTasksRegistered) return;
    builtinTasksRegistered = true;
    // ... registration code ...
}
```

- [ ] **Step 2: Compile check**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew build 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "core: add HabiTrainCore main class with GameMode registration and lifecycle"
```

---

## Task 10: Client Init + GUI Screens

**Files:**
- Create: `client/HabiTrainCoreClient.java` (migrate from `HabiTrainTaskAPIClient`)
- Create: `client/cache/ActiveTaskCache.java` (migrate from `ActiveCustomTaskCache`)
- Create: `client/gui/ModMenuIntegration.java` (migrate from `HabiModMenuIntegration`)
- Create: `client/gui/ConfigScreen.java` (migrate)
- Create: `client/gui/TaskListScreen.java` (migrate)
- Create: `client/gui/TaskEditScreen.java` (migrate)
- Create: `client/gui/GlobalSettingsScreen.java` (migrate)
- Create: `client/gui/ShaderWhitelistScreen.java` (migrate)
- Modify: `habitrain_core.client.mixins.json`

**Interfaces:**
- Consumes: all API classes
- Produces: working ModMenu config screens

- [ ] **Step 1–8: Migrate each client file**

For each file:
1. Copy from old `com.habitrain.taskapi.client.*` to `com.habitrain.core.client.*`
2. Update package declaration
3. Update old class references:
   - `HabiTrainTaskAPI` → `HabiTrainCore`
   - `HabiTaskRegistry` → `TaskRegistry`
   - `HabiTaskDefinition` → `TaskDefinition`
   - `HabiTaskInstance` → `TaskInstance`
   - `HabiConfigManager` → `ConfigManager`
   - `HabiTaskConfigEntry` → `TaskConfigEntry`
   - `ActiveCustomTaskCache` → `ActiveTaskCache`
   - `ActiveCustomTaskPayload` → `ActiveTaskPayload`
   - `TaskConfigSyncPayload` → `TaskConfigPayload`
   - `ConfigUpdateC2SPayload` → `ConfigUpdatePayload`
   - `ShaderConfigSyncS2CPayload` → `ShaderConfigPayload`
   - `ShaderPackInfoC2SPayload` → `ShaderInfoPayload`
4. Remove auto-replay toggle from GlobalSettingsScreen if present

**HabiTrainCoreClient.java** — remove the `setOnSaveCallback` section's auto-replay references. The shader monitoring logic stays identical.

**ConfigScreen.java** — the main config screen's 2×2 grid layout stays identical. Only import paths change.

- [ ] **Step 9: Update client mixins JSON**

```json
{
    "required": true,
    "package": "com.habitrain.core.client.mixin",
    "compatibilityLevel": "JAVA_21",
    "client": [
        "HudTaskMixin",
        "InstinctColorMixin",
        "InstinctCacheFixMixin",
        "CustomTaskBlockRendererMixin",
        "StarRailExpressTitleScreenMixin"
    ]
}
```

- [ ] **Step 10: Migrate client mixins**

Same process — copy from `com.habitrain.taskapi.client.mixin` to `com.habitrain.core.client.mixin`, update packages and old class references.

- [ ] **Step 11: Compile check**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew build 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "client: migrate all client classes, GUI screens, and mixins"
```

---

## Task 11: Misc — EffectOwnershipTracker Migration

**Files:**
- Create: `misc/EffectOwnershipTracker.java` (migrate from `api/EffectOwnershipTracker`)

- [ ] **Step 1: Copy to new package**

Copy `com.habitrain.taskapi.api.EffectOwnershipTracker` → `com.habitrain.core.misc.EffectOwnershipTracker`
- Update package declaration only
- All logic remains identical

- [ ] **Step 2: Compile check + commit**

```bash
git add -A
git commit -m "misc: move EffectOwnershipTracker to misc package"
```

---

## Task 12: Delete Old Package

**Files:**
- Delete: All files under `src/main/java/com/habitrain/taskapi/` (the old package tree)
- Delete: `src/main/resources/habitrain_taskapi.mixins.json`
- Delete: `src/main/resources/habitrain_taskapi.client.mixins.json`
- Delete: `src/main/resources/habitrain_taskapi.replay.mixins.json`

- [ ] **Step 1: Remove old source**

```bash
rm -rf src/main/java/com/habitrain/taskapi
rm -f src/main/resources/habitrain_taskapi.mixins.json
rm -f src/main/resources/habitrain_taskapi.client.mixins.json
rm -f src/main/resources/habitrain_taskapi.replay.mixins.json
rm -rf src/main/resources/assets/habitrain_taskapi
```

- [ ] **Step 2: Compile check**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew build 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL (no references to old package remain)

If there are compilation errors, fix any missed references first (usually import statements in migrated files that still point to the old package).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: delete old com.habitrain.taskapi package"
```

---

## Task 13: Companion Mod — Build + Config

**Files:**
- Modify: `D:\Backup\mc mod\哈比列车更多修改\build.gradle`
- Modify: `D:\Backup\mc mod\哈比列车更多修改\settings.gradle`
- Modify: `D:\Backup\mc mod\哈比列车更多修改\gradle.properties`
- Modify: `D:\Backup\mc mod\哈比列车更多修改\src\main\resources\fabric.mod.json`

**Interfaces:**
- Consumes: `habitrain_core` as dependency (replaces `habitrain_taskapi`)
- Produces: compilable companion mod with new identity

- [ ] **Step 1: Update gradle.properties**

```properties
org.gradle.jvmargs=-Xmx2G
loom_version=1.10.4
mod_version=2.0.0
maven_group=com.habitrain.moretasks
archives_base_name=habitrain_more_tasks
minecraft_version=1.21.1
loader_version=0.19.2
fabric_api_version=0.116.12+1.21.1
yarn_mappings=1.21.1+build.1
```

- [ ] **Step 2: Update settings.gradle**

```groovy
rootProject.name = "habitrain_more_tasks"
```

- [ ] **Step 3: Update build.gradle**

Change dependency from `files("libs/habitrain_taskapi-1.0.0.jar")` to `files("libs/habitrain_core-2.0.0.jar")`.

- [ ] **Step 4: Update fabric.mod.json**

```json
{
    "schemaVersion": 1,
    "id": "habitrain_more_tasks",
    "version": "${version}",
    "name": "HabiTrain More Tasks",
    "description": "哈比列车更多任务 - 包含槟榔任务等更多任务模组",
    "authors": ["HabiTrain"],
    "license": "CC0-1.0",
    "icon": "assets/habitrain_more_tasks/icon.png",
    "environment": "*",
    "entrypoints": {
        "main": ["com.habitrain.moretasks.HabiTrainMoreTasks"]
    },
    "depends": {
        "fabricloader": ">=0.19.2",
        "minecraft": "~1.21.1",
        "java": ">=21",
        "fabric-api": "*",
        "habitrain_core": ">=2.0.0",
        "betel-nut-mod": "*"
    }
}
```

- [ ] **Step 5: Move resource assets**

```bash
mv "src/main/resources/assets/test_more_tasks" "src/main/resources/assets/habitrain_more_tasks"
```

- [ ] **Step 6: Commit**

```bash
cd "D:/Backup/mc mod/哈比列车更多修改"
git add -A
git commit -m "build: rename mod to habitrain_more_tasks, update dependencies"
```

---

## Task 14: Companion Mod — Source Migration

**Files:**
- Rename + modify all `.java` files in `D:\Backup\mc mod\哈比列车更多修改\src\main\java`

**Java file changes:**

| Old path | New path |
|----------|----------|
| `com/example/TemplateMod.java` | `com/habitrain/moretasks/HabiTrainMoreTasks.java` |
| `com/example/betelquest/BetelQuestMod.java` | `com/habitrain/moretasks/BetelQuestMod.java` |
| `com/example/betelquest/BetelQuestDefinition.java` | `com/habitrain/moretasks/BetelQuestDefinition.java` |
| `com/example/betelquest/BetelQuestState.java` | `com/habitrain/moretasks/BetelQuestState.java` |
| `com/example/betelquest/BetelLeafHandler.java` | `com/habitrain/moretasks/BetelLeafHandler.java` |
| `com/example/betelquest/BackpackQuestState.java` | `com/habitrain/moretasks/BackpackQuestState.java` |
| `com/example/betelquest/BackpackSearchHandler.java` | `com/habitrain/moretasks/BackpackSearchHandler.java` |
| `com/example/betelquest/GameLifecycleHandler.java` | `com/habitrain/moretasks/GameLifecycleHandler.java` |

- [ ] **Step 1: Move all Java files**

```bash
mkdir -p src/main/java/com/habitrain/moretasks
# Move all files
git mv src/main/java/com/example/TemplateMod.java src/main/java/com/habitrain/moretasks/HabiTrainMoreTasks.java
git mv src/main/java/com/example/betelquest/*.java src/main/java/com/habitrain/moretasks/
rm -rf src/main/java/com/example
```

- [ ] **Step 2: Update imports in all files**

**HabiTrainMoreTasks.java (was TemplateMod.java):**
- `package com.example;` → `package com.habitrain.moretasks;`
- `import com.habitrain.taskapi.api.HabiTaskCategory;` → `import com.habitrain.core.api.HabiTaskCategory;`
- `import com.habitrain.taskapi.api.HabiTaskRegistry;` → `import com.habitrain.core.api.TaskRegistry;`
- All `HabiTaskRegistry.register(...)` calls change to `TaskRegistry.register(...)` (same signature)
- `import com.habitrain.taskapi.api.EffectOwnershipTracker;` → `import com.habitrain.core.misc.EffectOwnershipTracker;`

**BetelQuestDefinition.java:**
- Package: `com.example.betelquest` → `com.habitrain.moretasks`
- `HabiTaskCategory` → `com.habitrain.core.api.HabiTaskCategory`
- `HabiTaskInstance` → `com.habitrain.core.api.TaskInstance`
- `HabiTaskRegistry` → `com.habitrain.core.api.TaskRegistry`

**GameLifecycleHandler.java:**
- Package: `com.example.betelquest` → `com.habitrain.moretasks`
- `EffectOwnershipTracker` → `com.habitrain.core.misc.EffectOwnershipTracker`

All other files: package update only.

- [ ] **Step 3: Compile check**

```bash
cd "D:/Backup/mc mod/哈比列车更多修改"
./gradlew build 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: migrate sources to com.habitrain.moretasks, update API imports"
```

---

## Task 15: Final Verification

- [ ] **Step 1: Build core mod**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew clean build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Build companion mod**

```bash
cd "D:/Backup/mc mod/哈比列车更多修改"
./gradlew clean build 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify old package fully deleted**

```bash
ls -la "D:/Backup/mc mod/哈比列车api/src/main/java/com/habitrain/taskapi" 2>&1
```

Expected: `No such file or directory`

- [ ] **Step 4: Verify auto-replay code removed**

```bash
grep -r "autoReplay\|replay start\|replay stop\|ServerReplay" "D:/Backup/mc mod/哈比列车api/src/main/java/" 2>/dev/null
```

Expected: no matches

- [ ] **Step 5: Final commit on core mod**

```bash
cd "D:/Backup/mc mod/哈比列车api"
git add -A
git commit -m "chore: final cleanup and verification"
```

---

## Self-Review Checklist

**1. Spec coverage:**
- ✅ GameMode interface (Task 2, Step 4)
- ✅ GameModeRegistry (Task 2, Step 5)
- ✅ TaskCategory (Task 2, Step 1) — per-GameMode categories
- ✅ WinResult (Task 2, Step 2)
- ✅ TaskDefinition migrated + new fields (Task 3, Step 1)
- ✅ TaskInstance migrated + timeLimit support (Task 3, Step 2)
- ✅ TaskRegistry added gameMode query (Task 3, Step 3)
- ✅ SREMurderMode + SERepairMode implementations (Task 5, Steps 3-4)
- ✅ SREGameModeBase voice group management (Task 5, Step 2)
- ✅ ConfigManager + GameModeConfigScope (Task 7)
- ✅ Network payloads renamed (Task 8)
- ✅ HabiTrainCore main class (Task 9)
- ✅ Client GUI / mixins migrated (Task 10)
- ✅ EffectOwnershipTracker moved (Task 11)
- ✅ Old package deleted (Task 12)
- ✅ Companion mod renamed + migrated (Tasks 13-14)
- ❌ Auto-replay explicitly removed: Task 7 (ConfigManager), Task 9 (HabiTrainCore), spec Section 6.2
- Companion mod: tasks 13-14 cover all changes

**2. Placeholder scan:** All steps have concrete file paths, code, or commands. No "TBD", "TODO", or "implement later".

**3. Type consistency:**
- `TaskDefinition` uses `HabiTaskCategory.originalCategory()` for SRE compatibility (defined in Task 3) — consistent with spec section 5.1
- `TaskInstance.tick()` dispatches to `TaskManager.handleTaskCompletion()` (defined in Task 4) — consistent
- `SREMurderMode.isActive()` uses `SREGameWorldComponent` check — matches original logic
- `ConfigManager` file path is `habitrain_core.json` (was `habitrain_taskapi.json`) — consistent across Task 7
- No auto-replay references survive — verified in Task 15

No gaps found.
