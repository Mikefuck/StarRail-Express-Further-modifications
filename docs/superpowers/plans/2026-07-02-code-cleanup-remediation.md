# 代码清理修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复代码审查发现的 Bug、架构问题和代码质量问题，分 P0/P1/P2 三阶段推进

**Architecture:** P0 修复功能正确性缺陷 → P1 加固架构 → P2 清理代码。每阶段完成后 `./gradlew build` 验证编译通过

**Tech Stack:** Fabric 1.21.1, Java 21, Gradle

## Global Constraints

- 所有用户可见字符串保持中文，不在此计划中做 i18n 改造（P2 阶段处理）
- 所有 `java.awt.Color` 替换为 `int` ARGB 格式
- 所有网络 ByteBuf 操作使用 `StandardCharsets.UTF_8`
- 每阶段完成后必须 `./gradlew build` 验证编译通过

---

## P0 — Bugfix

### Task 1: 修复 TACZWeaponBridge 扣款顺序 (B1)

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/TACZWeaponBridge.java`

- [ ] **Step 1: 读取当前文件**

- [ ] **Step 2: 交换 buyDesertEagle 的扣款和添加逻辑**

将：
```java
shop.addToBalance(-PRICE);
ItemStack stack = new ItemStack(TACZItems.DESERT_EAGLE.get(), 1);
player.getInventory().add(stack);
```
改为：
```java
ItemStack stack = new ItemStack(TACZItems.DESERT_EAGLE.get(), 1);
if (!player.getInventory().add(stack)) {
    player.drop(stack, false);
}
shop.addToBalance(-PRICE);
```

- [ ] **Step 3: 同样修复 buyAmmo**

将：
```java
shop.addToBalance(-ammoPrice);
player.getInventory().add(ammoStack);
```
改为：
```java
if (!player.getInventory().add(ammoStack)) {
    player.drop(ammoStack, false);
}
shop.addToBalance(-ammoPrice);
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/TACZWeaponBridge.java
git commit -m "fix: swap fund deduction and inventory add order in TACZWeaponBridge"
```

---

### Task 2: 修复 `/habi_api` 双重注册权限覆盖 (B2)

**Files:**
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java`

- [ ] **Step 1: 读取 HabiTrainCore.java，找到命令注册部分 (L134-162)**

- [ ] **Step 2: 将两次 `literal("habi_api")` 合并为一次**

原代码：
```java
dispatcher.register(CommandManager.literal("habi_api")
    .requires(source -> source.hasPermission(2))
    .then(CommandManager.literal("blackout"))
    .then(CommandManager.literal("list"))
);
dispatcher.register(CommandManager.literal("habi_api")
    .then(CommandManager.literal("buy_gun"))
    .then(CommandManager.literal("buy_ammo"))
);
```
改为：
```java
dispatcher.register(CommandManager.literal("habi_api")
    .then(CommandManager.literal("blackout").requires(source -> source.hasPermission(2)))
    .then(CommandManager.literal("list").requires(source -> source.hasPermission(2)))
    .then(CommandManager.literal("buy_gun"))
    .then(CommandManager.literal("buy_ammo"))
);
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew build`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/HabiTrainCore.java
git commit -m "fix: merge habi_api command to single root node"
```

---

### Task 3: 修复 GenerateTaskMixin @Overwrite (B4)

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java`

- [ ] **Step 1: 读取 GenerateTaskMixin.java**

- [ ] **Step 2: 将 @Overwrite 改为 @Inject(cancellable=true)**

将：
```java
@Overwrite
private void generateTaskInternal(/* 参数 */) {
    // ... 完整方法体
}
```
改为：
```java
@Inject(method = "generateTaskInternal", at = @At("HEAD"), cancellable = true)
private void onGenerateTaskInternal(/* 参数 */, CallbackInfo ci) {
    // ... 原方法体
    ci.cancel();
}
```
注意保留方法签名完全一致，仅在末尾加 `CallbackInfo ci` 参数并加 `ci.cancel()`。

- [ ] **Step 3: 编译验证**

Run: `./gradlew build`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java
git commit -m "fix: replace @Overwrite with @Inject(cancellable=true) in GenerateTaskMixin"
```

---

### Task 4: 修复 ByteBuf 平台默认字符集 (B5)

**Files:**
- Modify: `src/main/java/com/habitrain/core/network/TaskConfigPayload.java`
- Modify: `src/main/java/com/habitrain/core/network/ShaderInfoPayload.java`
- Modify: `src/main/java/com/habitrain/core/network/ShaderConfigPayload.java`
- Modify: `src/main/java/com/habitrain/core/network/ConfigUpdatePayload.java`
- Modify: `src/main/java/com/habitrain/core/network/ActiveTaskPayload.java`

- [ ] **Step 1: 读取所有5个网络Payload文件**

- [ ] **Step 2: 在每个文件中，查找并替换所有 `getBytes()` 为 `getBytes(StandardCharsets.UTF_8)`**

模式：`string.getBytes()` → `string.getBytes(StandardCharsets.UTF_8)`
模式：`new String(bytes)` → `new String(bytes, StandardCharsets.UTF_8)`

- [ ] **Step 3: 确保每个文件有 `import java.nio.charset.StandardCharsets;`**

- [ ] **Step 4: 编译验证**

Run: `./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/network/TaskConfigPayload.java src/main/java/com/habitrain/core/network/ShaderInfoPayload.java src/main/java/com/habitrain/core/network/ShaderConfigPayload.java src/main/java/com/habitrain/core/network/ConfigUpdatePayload.java src/main/java/com/habitrain/core/network/ActiveTaskPayload.java
git commit -m "fix: specify UTF-8 charset in all ByteBuf serialization"
```

---

### Task 5: 添加网络解码长度限制 (B6)

**Files:**
- Modify: `src/main/java/com/habitrain/core/network/ShaderInfoPayload.java`
- Modify: `src/main/java/com/habitrain/core/network/ShaderConfigPayload.java`
- Modify: `src/main/java/com/habitrain/core/network/ConfigUpdatePayload.java`
- Modify: `src/main/java/com/habitrain/core/network/ActiveTaskPayload.java`

- [ ] **Step 1: 读取并修改 ShaderInfoPayload.java**

在解码器中，`buf.readInt()` 作为数组长度分配前，加：
```java
int len = Math.min(buf.readInt(), 65536);
```

- [ ] **Step 2: 同样修改 ShaderConfigPayload.java、ConfigUpdatePayload.java、ActiveTaskPayload.java**

每个文件的读长度处加 `Math.min(len, MAX_LENGTH)`。

在类级别或文件顶部定义：
```java
private static final int MAX_STRING_LENGTH = 65536;
private static final int MAX_JSON_LENGTH = 1048576;
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew build`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/network/ShaderInfoPayload.java src/main/java/com/habitrain/core/network/ShaderConfigPayload.java src/main/java/com/habitrain/core/network/ConfigUpdatePayload.java src/main/java/com/habitrain/core/network/ActiveTaskPayload.java
git commit -m "fix: add max length limits to network payload decoding"
```

---

## P1 — 架构加固

### Task 6: 修复 ConfigManager suppressCallback 无 try-finally (A5)

**Files:**
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java`

- [ ] **Step 1: 读取 ConfigManager.java L380-393**

- [ ] **Step 2: 在 `applySyncData` 中加 try-finally**

```java
suppressCallback = true;
try {
    this.taskConfigs.clear();
    this.taskConfigs.putAll(data.taskConfigs());
    this.dlcProbabilityTarget = data.dlcProbabilityTarget();
} finally {
    suppressCallback = false;
}
```

- [ ] **Step 3: 同样修复 `applySyncFromJson` 中的 suppressCallback**

- [ ] **Step 4: 编译验证**

Run: `./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/config/ConfigManager.java
git commit -m "fix: wrap suppressCallback in try-finally in ConfigManager"
```

---

### Task 7: 修复 Engine 回退路径不通知 GameMode (A4)

**Files:**
- Modify: `src/main/java/com/habitrain/core/task/Engine.java`

- [ ] **Step 1: 读取 Engine.java L49-53**

- [ ] **Step 2: 在回退路径中补充 GameMode 通知**

原代码：
```java
// fallback path
TaskDefinition def = ...;
TaskInstance instance = def.createInstance(player.getUUID());
def.onAssign(player, instance);
mgr.setActiveTask(player.getUUID(), instance);
```
改为：
```java
TaskDefinition def = ...;
TaskInstance instance = def.createInstance(player.getUUID());
def.onAssign(player, instance);
gameMode.onTaskAssign((ServerPlayer) player, instance);  // 补充这一行
mgr.setActiveTask(player.getUUID(), instance);
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew build`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/task/Engine.java
git commit -m "fix: add missing gameMode.onTaskAssign() in Engine fallback path"
```

---

### Task 8: 修复单例线程安全 (A6)

**Files:**
- Modify: `src/main/java/com/habitrain/core/task/Engine.java`
- Modify: `src/main/java/com/habitrain/core/task/TaskManager.java`
- Modify: `src/main/java/com/habitrain/core/task/BackpackQuestState.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java`

- [ ] **Step 1: 读取所有4个文件的单例代码**

- [ ] **Step 2: 为每个单例加 `volatile` 关键字**

```java
private static volatile Engine INSTANCE;
```

- [ ] **Step 3: 为每个 getInstance() 加双检锁**

```java
public static Engine getInstance() {
    if (INSTANCE == null) {
        synchronized (Engine.class) {
            if (INSTANCE == null) {
                INSTANCE = new Engine();
            }
        }
    }
    return INSTANCE;
}
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/task/Engine.java src/main/java/com/habitrain/core/task/TaskManager.java src/main/java/com/habitrain/core/task/BackpackQuestState.java src/main/java/com/habitrain/core/config/ConfigManager.java
git commit -m "fix: add volatile + double-checked locking to all singletons"
```

---

### Task 9: 补缺失注解 (A7)

**Files:** 所有混合文件和核心类

- [ ] **Step 1: 在所有 Mixin 类中搜索缺少 `@Unique` 的辅助方法**

检查以下文件：
- `StarRailExpressTitleScreenMixin.java`
- `InstinctColorMixin.java`
- `InstinctCacheFixMixin.java`
- `HudCustomTaskMixin.java`
- `FixTaskRendererMixin.java`
- `CustomTaskBlockRendererMixin.java`
- `NunchuckCooldownMixin.java`

为所有 `@Inject`、`@Redirect`、`@ModifyArg` 的回调方法加 `@Unique` 注解。

- [ ] **Step 2: 在可能返回 null 的方法上加 `@Nullable`**

检查 `TaskManager.getActiveTask()`、`Engine.generateTask()`、`TaskInstance.fromNbt()` 等。

- [ ] **Step 3: 编译验证**

Run: `./gradlew build`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/
git commit -m "fix: add missing @Unique and @Nullable annotations"
```

---

### Task 10: 替换 java.awt.Color 为 int ARGB (A3)

**Files:**
- Modify: `src/main/java/com/habitrain/core/api/TaskDefinition.java`
- Modify: `src/main/java/com/habitrain/core/config/TaskConfigEntry.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/SabotageWiringTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/RepairWiringTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/MaintainPowerTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/FurnaceExplosionTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/AddCoalTask.java`

- [ ] **Step 1: 读取 TaskDefinition.java**

将 `java.awt.Color` 字段改为 `int color`，getter 调用的地方改为解析 ARGB。

- [ ] **Step 2: 读取 TaskConfigEntry.java**

将 `getColor()` 返回 `Color` 改为返回 `int`（ARGB）。

- [ ] **Step 3: 修改5个Blackout任务文件**

每个文件中的 `import java.awt.Color` 移除，改为 `int` 常量。

- [ ] **Step 4: 编译验证**

Run: `./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/api/TaskDefinition.java src/main/java/com/habitrain/core/config/TaskConfigEntry.java src/main/java/com/habitrain/core/game/blackout/task/
git commit -m "refactor: replace java.awt.Color with int ARGB"
```

---

### Task 11: 修复 API 层泄露实现 (A2)

**Files:**
- Modify: `src/main/java/com/habitrain/core/api/TaskInstance.java`

- [ ] **Step 1: 读取 TaskInstance.java**

- [ ] **Step 2: 剥离对内部包的依赖**

将 `TaskInstance` 中对 `SREPlayerTaskComponent.TrainTask` 的依赖改为抽象接口或适配器模式。

具体方案：创建一个 `api/TaskInstance.java` 内部的抽象层，将对 SRE 的依赖后移到 `game/sre/` 包的适配器。

（此任务较复杂，需要仔细分析接口关系）

- [ ] **Step 3: 编译验证**

Run: `./gradlew build`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/habitrain/core/api/TaskInstance.java
git commit -m "refactor: decouple TaskInstance from internal SRE dependencies"
```

---

### Task 12: Blackout 系统静态转实例 (A1)

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutTimerSystem.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/TACZWeaponBridge.java`

- [ ] **Step 1: 读取所有4个文件**

- [ ] **Step 2: `BlackoutRoleManager` — 所有静态字段改为实例字段，移除 `getInstance()`，构造函数接受 `BlackoutMode` 参数**

- [ ] **Step 3: `BlackoutTimerSystem` — 同样改为实例化，构造函数接受参数**

- [ ] **Step 4: `TACZWeaponBridge` — 改为实例方法，非静态**

- [ ] **Step 5: `BlackoutMode` 持有以上三个类的实例，在 `onPreStart` 中初始化**

- [ ] **Step 6: 编译验证**

Run: `./gradlew build`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/habitrain/core/game/blackout/
git commit -m "refactor: change Blackout systems from static global to instance-based"
```

---

## P2 — 代码清理

### Task 13: 消除魔法数字

**Files:** 多处

- [ ] **Step 1: 在 `HabiTrainCore.java` 中为 `blockTypeId` 值定义命名常量**

```java
public static final int BLOCK_TYPE_GRASS = 12;
public static final int BLOCK_TYPE_CAT = 13;
public static final int BLOCK_TYPE_BETEL = 14;
public static final int BLOCK_TYPE_BACKPACK = 15;
public static final int BLOCK_TYPE_LOOK_MY_EYES = -1;
```

- [ ] **Step 2: 在 `NunchuckCooldownMixin.java` 中为 `roleType != 4` 定义常量**

```java
private static final int KILLER_ROLE_TYPE = 4;
```

- [ ] **Step 3: 在 `BlackoutTimerSystem.java` 为硬编码常量加命名**

```java
private static final int TRANSIENT_PENALTY_SECONDS = -15;
private static final int MAX_BLACKOUT_COUNTDOWN = 300;
```

- [ ] **Step 4: 搜索替换键码 256 → `GLFW.GLFW_KEY_ESCAPE`**

- [ ] **Step 5: 编译验证**

Run: `./gradlew build`

- [ ] **Step 6: Commit**

```bash
git commit -m "refactor: extract magic numbers to named constants"
```

---

### Task 14: 消除代码重复

- [ ] **Step 1: 创建共享颜色数组常量类**

将重复的 `COLORS`/`COLOR_NAMES` 抽取到 `client/gui/SharedGuiConstants.java`。

- [ ] **Step 2: 合并 `broadcast()` 方法**

将 `BlackoutTimerSystem.broadcast()` 和 `BlackoutMode.broadcast()` 合并为一个。

- [ ] **Step 3: 合并 `ConfigManager.toJsonString()` 和 `save()` 的重复 JSON 构建**

- [ ] **Step 4: 编译验证 → Commit**

---

### Task 15: 移除死代码

- [ ] **Step 1: 删除 `EffectOwnershipTracker.releaseAll()`（从未被调用）**

- [ ] **Step 2: 删除未播放的 `backpack_search` 音效注册**

- [ ] **Step 3: 删除 lang 文件中的 `key.habitrain.blackout.vote`**

- [ ] **Step 4: 删除 `TaskBalancer.calcDlcPercent()`**

- [ ] **Step 5: 删除 `GlobalSettingsScreen` 中的过期回放提示文本**

- [ ] **Step 6: `TaskConfigEntry.disabledMaps` 标记 `@Deprecated(forRemoval=true)`**

- [ ] **Step 7: 删除 mixins.json 中的空 `client:[]`/`server:[]` 数组**

- [ ] **Step 8: 编译验证 → Commit**

---

### Task 16: 杂项清理

- [ ] **Step 1: 补 `look_my_eyes.ogg` 音效文件或删除对应注册**

- [ ] **Step 2: 宠物猫任务在 yuushya 缺失时输出 warn 日志**

- [ ] **Step 3: 离线按钮改为标准 `ButtonWidget` + `addRenderableWidget()`**

- [ ] **Step 4: `ServerTickMixin` 重命名为 `SREPlayerTaskComponentMixin`**

- [ ] **Step 5: 编译验证 → Commit**
