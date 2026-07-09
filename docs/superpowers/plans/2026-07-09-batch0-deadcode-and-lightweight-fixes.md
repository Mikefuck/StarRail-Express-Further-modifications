# Batch 0：死代码清理 + 轻量优化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 安全删除 40+ 死代码/类/方法，并完成 ~20 项轻量优化。零功能影响。

**Architecture:** 每项操作前 grep 确认无调用方，每删除/修改一项后最小化构建验证。按"删整类→删方法→清理调用→轻量优化"顺序执行，避免残留引用导致编译失败。

**Tech Stack:** Java 21 / Fabric 1.21.1 / Gradle

## 全局约束

1. **每项删除前必须 grep 确认无外部调用**（IDEA: Ctrl+Shift+F / grep -r "目标名称" src/）
2. **每项修改后如果文件被删/方法被删，检查所有 import 和调用处**，确保无残留引用
3. **每 Task 完成后运行 `./gradlew clean build`**
4. **构建成功后 JAR 复制到 `D:\Backup\mc mod\临时\`**
5. **禁止访问 `D:\Backup\mc mod\backup\`**

---

## 文件结构总览

### 被删除的完整文件（4 个）
| 文件 | 原因 |
|------|------|
| `api/GameModeLifecycle.java` | S1-002 死枚举，全仓 0 引用 |
| `game/blackout/task/BlackoutEatHandler.java` | S8-004 空 register + Map 永不写入 |
| `game/blackout/task/BlackoutDrinkHandler.java` | S8-004 同上 |
| `client/gui/GlobalSettingsScreen.java` | S10-001 被 GlobalTabScreen 取代 |

### 被修改的文件（~45 个）
详见各 Task。

---

### Task 0-1: 删除 4 个完整死类

**文件：**
- Delete: `src/main/java/com/habitrain/core/api/GameModeLifecycle.java`
- Delete: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutEatHandler.java`
- Delete: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutDrinkHandler.java`
- Delete: `src/main/java/com/habitrain/core/client/gui/GlobalSettingsScreen.java`

- [ ] **Step 1: Grep 确认无引用**

```bash
grep -r "GameModeLifecycle" src/ --include="*.java" | grep -v "GameModeLifecycle.java"  # 应返回空
grep -r "BlackoutEatHandler" src/ --include="*.java" | grep -v "BlackoutEatHandler.java"  # 记录调用方以便 Task 0-2 清理
grep -r "BlackoutDrinkHandler" src/ --include="*.java" | grep -v "BlackoutDrinkHandler.java"
grep -r "GlobalSettingsScreen" src/ --include="*.java" | grep -v "GlobalSettingsScreen.java"
```

- [ ] **Step 2: 删除 4 个文件**

```bash
rm "src/main/java/com/habitrain/core/api/GameModeLifecycle.java"
rm "src/main/java/com/habitrain/core/game/blackout/task/BlackoutEatHandler.java"
rm "src/main/java/com/habitrain/core/game/blackout/task/BlackoutDrinkHandler.java"
rm "src/main/java/com/habitrain/core/client/gui/GlobalSettingsScreen.java"
```

> **注意**：BlackoutEatHandler/BlackoutDrinkHandler 被引用的位置（Task.onRemove + GameLifecycleHandler）将在 Task 0-2 中清理。先删文件会让这些引用变成编译错误——这是预期的，后续 Task 会修复。如果一次删完怕漏，可以分两步：先清调用，再删文件。但推荐"删文件→编译报错→逐个修"的方式更保险。

- [ ] **Step 3: 清理残留 import 和调用（BlackoutEatHandler）**

在以下文件中移除引用：
- `BlackoutEatTask.java:33` — 删除 `BlackoutEatHandler.clearState(player)` 调用
- `BlackoutDrinkTask.java:33` — 删除 `BlackoutDrinkHandler.clearState(player)` 调用
- `GameLifecycleHandler.java` — 删除对 EatHandler/DrinkHandler 的 clearAll 调用

```java
// BlackoutEatTask.java — 在 onRemove 方法中删除此行：
// BlackoutEatHandler.clearState(player);

// BlackoutDrinkTask.java — 在 onRemove 方法中删除此行：
// BlackoutDrinkHandler.clearState(player);

// GameLifecycleHandler.java — 在 handleGameEnd 中删除对 EatHandler/DrinkHandler 的 clearAll 调用
```

- [ ] **Step 4: 构建验证**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew clean build
```

如果有编译错误，说明有未清理的引用——检查每个错误并移除对应的 import/调用，然后重新构建。

- [ ] **Step 5: 复制 JAR**

```bash
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/临时/"
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "batch0: delete 4 dead classes (GameModeLifecycle, EatHandler, DrinkHandler, GlobalSettingsScreen)"
```

---

### Task 0-2: 删除核心/API 包死方法

**文件：**
- Modify: `src/main/java/com/habitrain/core/config/ConfigStore.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigRepository.java`
- Modify: `src/main/java/com/habitrain/core/task/TaskManager.java`
- Modify: `src/main/java/com/habitrain/core/task/SlownessReapplyManager.java`
- Modify: `src/main/java/com/habitrain/core/task/GameLifecycleHandler.java`
- Modify: `src/main/java/com/habitrain/core/config/TaskConfigEntry.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java`
- Modify: `src/main/java/com/habitrain/core/task/BackpackQuestState.java`

- [ ] **Step 1: 删除 `ConfigStore.calculateCurrentBoost()` 方法**

```java
// 删除整个方法（~259 行附近）
// public float calculateCurrentBoost(ConfigRepository repository) { ... }
```

- [ ] **Step 2: 合并 `ConfigRepository.setTaskConfig` 与 `putTaskConfig`**

```java
// 删除 setTaskConfig 方法体，改为调用 putTaskConfig：
public void setTaskConfig(String fullId, TaskConfigEntry entry) {
    putTaskConfig(fullId, entry);
}
// 或直接删除 setTaskConfig 并让 ConfigManager 层决定是否 save
```

- [ ] **Step 3: 删除 `TaskManager.getAvailableTasks(String, TaskCategory)`**

删除整个方法（~125 行附近），功能已被 TaskPoolBuilder 覆盖。

- [ ] **Step 4: 删除 `SlownessReapplyManager.unregister(ResourceKey, UUID)` 和 `clearAll(ResourceKey)`**

删除这两个重载方法，只保留 `unregisterAllLevels` 和无参 `clearAll()`。

- [ ] **Step 5: 删除 `GameLifecycleHandler.register()` 空方法 + 调用处**

```java
// 删除以下空方法：
// public static void register() { LOGGER.info("GameLifecycleHandler registered"); }
// 
// 并在 HabiTrainCore.java:439 删除对 GameLifecycleHandler.register() 的调用
```

- [ ] **Step 6: 删除 `TaskConfigEntry.getEffectiveGoldReward()`、`getEffectiveEmotionReward()`、`getEffectiveRefreshWeight()`**

删除 3 个方法（~86-94 行）。

- [ ] **Step 7: 修改 `TaskConfigEntry.disabledMaps` 的 fromJson 逻辑**

```java
// 在 fromJson 中将：
// entry.disabledMaps = ... (填充字段)
// 改为仅打 warn 日志，不填充字段
```

- [ ] **Step 8: 删除 `ConfigManager.getGameModeConfig()` 转发方法**

```java
// 删除以下方法：
// public GameModeConfigScope getGameModeConfig(String gameModeId) { ... }
// 底层 ConfigRepository 改为 getOrCreate 风格
```

- [ ] **Step 9: 修复 `BackpackQuestState` 初始化**

```java
// 择优一：移除 DCL 懒加载，只有 init() 方式
// 或：移除 init()，只用 DCL getInstance()
```

- [ ] **Step 10: 构建验证 + 复制 JAR + Commit**

```bash
./gradlew clean build
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/临时/"
git add -A
git commit -m "batch0: delete dead methods in core/api/config/task packages"
```

---

### Task 0-3: 删除 Blackout/SRE/Betel 包死方法

**文件：**
- Modify: `BlackoutTimerSystem.java` — onTimeWarning 回调 + 多个死 getter
- Modify: `BlackoutTickCoordinator.java` — onSreGameStarted/Ended
- Modify: `BlackoutRoleManager.java` — getRoleId/getAllSheriffs/getRandomGoodNonSheriff
- Modify: `SheriffVoteBroadcaster.java` — resetCache
- Modify: `BlackoutTaskHelper.java` — advanceOnLook/resolveTargets
- Modify: `MaintainPowerHandler.java` — tickCheck + MaintainPowerTask.onTick 调用
- Modify: `SRETrainTaskWrapper.java` — toNbt()
- Modify: `TaskEnumHelper.java` — isCustomTaskSupported
- Modify: `MinigameRewardMixin.java` — 捕获字段 + HEAD 注入 + ModifyArg
- Modify: `FactionFilter.java` — 冗余赋值
- Modify: `SREGameModeBase.java` — 重复 Javadoc
- Modify: `BlackoutVoteState.java` — maxSelections
- Modify: `BetelTickEngine.java` — clearHechengTianxiaData
- Modify: `BetelQuestState.java` — setFoodRestriction/hasActiveHarvest/hasActiveHarvestInWorld/lastKnownLastEatTime

- [ ] **Step 1: 逐个删除上述死方法**

操作模式统一：
1. 打开文件
2. 删除指定方法（用 `grep "方法名" src/` 先确认引用）
3. 保存

**关键位置：**
- `BlackoutTimerSystem.java:72` — 删除 `onTimeWarning` 字段 + tickSecond 中 `s.onTimeWarning.run()` 分支
- `SREGameModeBase.java:158` — 删除两段重复 Javadoc 中的一段
- `MinigameRewardMixin.java:22-23` — 删除 2 个 `@Shadow` 字段 + `@Inject` HEAD 方法，保留 `@Inject` RETURN
- `MinigameRewardMixin.java:25` — 删除 `@ModifyArg overrideTokenReward` 空透传方法
- `FactionFilter.java:45` — 删除 else 分支中 `currentIsFakeTask = false`（与初值相同）
- `BetelQuestState.java:114` — 删除 `lastKnownLastEatTime` 字段 + `BetelTickEngine.java:171-173` 的赋值

- [ ] **Step 2: 构建验证**

```bash
./gradlew clean build
```

如果编译报错，检查被删方法是否被其他地方引用，修复后重试。

- [ ] **Step 3: 复制 JAR + Commit**

```bash
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/临时/"
git add -A
git commit -m "batch0: delete dead methods in blackout/sre/betel packages"
```

---

### Task 0-4: 删除 Client/GUI 包死代码

**文件：**
- Modify: `SharedGuiKit.java` — drawPanel, drawStatusPill
- Modify: `LiveConfigAccess.java` — isRemoteLocked
- Modify: `ShaderWhitelistScreen.java` — 空 if 分支, 无用 import
- Modify: `BlackoutSheriffVoteState.java` — getTotalSeconds, getTimerText
- Modify: `BlackoutWelcomeRenderer.java` — getRoleName
- Modify: `BlackoutHudOverlay.java` — setVisible
- Modify: `ConfigRootScreen.java` — font(), isEditable()
- Modify: `BlackoutVoteState.java` — maxSelections 字段（已在 Task 0-3）
- Modify: `MinigameEditScreen.java` — 滚动恒等式分支

- [ ] **Step 1: 逐个删除上述死方法/字段**

- `SharedGuiKit.java:31` — 删除 `drawPanel` 方法
- `SharedGuiKit.java:45` — 删除 `drawStatusPill` 方法
- `LiveConfigAccess.java:30` — 删除 `isRemoteLocked` 方法
- `ShaderWhitelistScreen.java:334` — 删除空 if 分支体
- `ShaderWhitelistScreen.java:3` — 删除无用 `import com.habitrain.core.HabiTrainCore`
- `BlackoutSheriffVoteState.java:49,81` — 删除 `getTotalSeconds` + `getTimerText`
- `BlackoutWelcomeRenderer.java:33` — 删除 `getRoleName`
- `BlackoutHudOverlay.java:39` — 删除 `setVisible`
- `ConfigRootScreen.java:184` — 删除 `font()` + `isEditable()` 访问器
- `MinigameEditScreen.java:320` — 删除 `scrollOffset = Mth.clamp(scrollOffset + (my - contentTop) * 0, 0, maxScroll);` 整行

- [ ] **Step 2: 构建验证**

```bash
./gradlew clean build
```

- [ ] **Step 3: 复制 JAR + Commit**

```bash
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/临时/"
git add -A
git commit -m "batch0: delete dead code in client/gui packages"
```

---

### Task 0-5: 轻量优化——渲染/客户端

**文件：**
- Modify: `BlackoutHudOverlay.java`
- Modify: `CustomTaskBlockRendererMixin.java`
- Modify: `SubtitleHUDPrefixFixMixin.java`
- Modify: `MapScannerMixin.java`
- Modify: `MinigameEditScreen.java`

- [ ] **Step 1: `BlackoutHudOverlay.java` — totalDuration 重置逻辑**

```java
// 旧（L26-28）：if (total > totalDuration) totalDuration = total;
// 新：totalDuration = total;   // 直接以服务端 total 重置
```

- [ ] **Step 2: `BlackoutHudOverlay.java` — cachedEndTimeTick sentinel**

```java
// 旧：long cachedEndTimeTick = 0;  // 0 与"未设置"混用
// 新：long cachedEndTimeTick = -1; // -1 显式表示未设置
// 并修改 getLocalCountdown 中 > 0 判断为 >= 0
```

- [ ] **Step 3: `CustomTaskBlockRendererMixin.java:251` — 合并冗余守卫**

```java
// 旧：
// if (blockTypeId < 12) return;
// if (blockTypeId == 12) return;
// 新：
// if (blockTypeId <= 12) return;
```

- [ ] **Step 4: `SubtitleHUDPrefixFixMixin.java:26` — 提取魔法数字**

```java
// 旧：new SubtitleEntry(..., 12, 18, ...)
// 新：在类顶定义常量
// private static final int SUBTITLE_OFFSET_X = 12;
// private static final int SUBTITLE_OFFSET_Y = 18;
```

- [ ] **Step 5: `MapScannerMixin.java:64-122` — 合并二次遍历**

```java
// 在 L64-91 首循环中同时记录 blackout_eat/blackout_drink 的 typeId
// 删除 L119-122 的第二次 TaskRegistry.getAll() 遍历
```

- [ ] **Step 6: `BlackoutVictoryChecker.java:122-166` — 复用 getPlayer**

```java
// 旧：两次调用 level.getServer().getPlayerList().getPlayer(uuid)
// 新：ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
//     之后复用 player 引用
```

- [ ] **Step 7: `BlackoutSheriffVoteState.java:29` — removeIf 优化**

```java
// 旧：selectedTargetIds.removeIf(id -> candidates.stream().noneMatch(entry -> entry.playerId().equals(id)));
// 新：Set<UUID> candidateIds = candidates.stream().map(Entry::playerId).collect(Collectors.toSet());
//     selectedTargetIds.removeIf(id -> !candidateIds.contains(id));
```

- [ ] **Step 8: `MinigameEditScreen.java:296` — saveBtn/resetBtn 位置前移**

将 `saveBtn.setX(...)` / `resetBtn.setX(...)` 移到 `super.render` 之前（从 L300-301 移到 L295 之前）。

- [ ] **Step 9: 构建验证**

```bash
./gradlew clean build
```

- [ ] **Step 10: 复制 JAR + Commit**

```bash
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/临时/"
git add -A
git commit -m "batch0: render perf optimization + sentinel fix + guard merge"
```

---

### Task 0-6: 轻量优化——Config/Betel/命名

**文件：**
- Modify: `BetelQuestState.java` — var shadow, instance volatile
- Modify: `BetelTickEngine.java` — 提取魔法数字为命名常量
- Modify: `LootHelper.java` — roleType 4/5 魔法数字
- Modify: `BetelLeafHandler.java` — 静态缓存重试
- Modify: `SREGameModeBase.java` — 空 catch 加日志
- Modify: `BlackoutTimerSystem.java` — 字段重命名 ticks→seconds
- Modify: `BlackoutWelcomeRenderer.java` — 删除 startWelcome 未用参数
- Modify: `MinigameEditScreen.java` — commitFields try/catch
- Modify: 多文件 Logger 命名

- [ ] **Step 1: BetelQuestState 修复**

```java
// L76：局部变量改名
// 旧：var instance = FabricLoader.getInstance().getGameInstance();
// 新：var gameInstance = FabricLoader.getInstance().getGameInstance();

// L11：instance 加 volatile
// 旧：private static BetelQuestState instance;
// 新：private static volatile BetelQuestState instance;
```

- [ ] **Step 2: 提取魔法数字（BetelTickEngine + LootHelper）**

`BetelTickEngine.java` 中提取：
```java
private static final int ADDICTION_STAGE_THRESHOLD_1 = 80;
private static final int ADDICTION_STAGE_THRESHOLD_2 = 60;
private static final int ADDICTION_STAGE_THRESHOLD_3 = 40;
private static final int ADDICTION_STAGE_THRESHOLD_4 = 20;
private static final int WITHDRAWAL_TICK_THRESHOLD = 600;
private static final int MIN_WITHDRAWAL_VALUE = 1;
private static final int MAX_WITHDRAWAL_VALUE = 25;
private static final int DARKNESS_DURATION_TICKS = 600;
```

`LootHelper.java` 中提取：
```java
private static final int ROLE_TYPE_GOOD_POLICE = 4;
private static final int ROLE_TYPE_BAD = 5;
// 冷却时间常量
```

- [ ] **Step 3: `BetelLeafHandler.java:40` — 静态缓存重试**

```java
// 将 isBetelLeafBlock 从"首次查到后永久缓存"改为：
// 1. 如果首次查找返回 AIR，不设置 blockChecked=true，允许下次重试
// 2. 或改为在服务器启动完成事件中预热查找
```

- [ ] **Step 4: `SREGameModeBase.java:173,193` — 空 catch 加日志**

```java
// 旧：catch (Exception ignored) {}
// 新：catch (Exception e) { LOGGER.debug("isAnySreGameRunning failed: {}", e.getMessage()); }
```

- [ ] **Step 5: `BlackoutTimerSystem.java:23` — 字段重命名**

```java
// 旧：private static final int TRANSIENT_TICKS = 140;
// 新：private static final int TRANSIENT_SECONDS = 140;
// 并补注释说明单位
```

- [ ] **Step 6: `BlackoutWelcomeRenderer.java:24` — 删除未用参数**

```java
// 旧：public static void startWelcome(String roleName, String subtitle, String goal, int killers, int targets)
// 新：public static void startWelcome(String roleName, String subtitle, String goal)
// 同时更新调用端 HabiTrainCoreClient.java:263-265 移除 payload.killerCount()/targetCount()
```

- [ ] **Step 7: `MinigameEditScreen.java:198` — commitFields 加 try/catch**

```java
// 将 Integer.parseInt/Float.parseInt 包裹 try/catch
// 参考 TaskSaveController.parseNumFields(L27-40) 的做法
```

- [ ] **Step 8: Logger 命名统一**

检查以下文件的 `Logger.getLogger("ConfigManager")` 改为自身类名：
- `ConfigStore.java:21` — `ConfigStore.class.getSimpleName()`
- `ConfigSync.java:11` — `ConfigSync.class.getSimpleName()`
- `MinigameEnforcement.java:12` — `MinigameEnforcement.class.getSimpleName()`

- [ ] **Step 9: `BlackoutPhoneHandler/OverlayTypes/HornVoteHandler` — 三处重复缓存**

```java
// 将 street_phone 和 horn 的静态缓存统一到 BlackoutOverlayTypes 或新建 BlockCache 工具类
// 删除 BlackoutPhoneHandler.cachedStreetPhone
// 删除 BlackoutHornVoteHandler.cachedHorn
// 删除 BlackoutOverlayTypes.cachedStreetPhone → 保留为唯一源
```

- [ ] **Step 10: 构建验证 + 复制 JAR + Commit**

```bash
./gradlew clean build
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/临时/"
git add -A
git commit -m "batch0: extract constants, fix naming, add exception logging, unify loggers"
```

---

## 验证总清单

Batch 0 全部完成后，执行以下验证：

- [ ] 构建通过：`./gradlew clean build` 返回 BUILD SUCCESSFUL
- [ ] JAR 已复制：`build/libs/habitrain_core-*.jar` → `D:\Backup\mc mod\临时\`
- [ ] 被删文件无残留引用：`grep -r "GameModeLifecycle\|BlackoutEatHandler\|BlackoutDrinkHandler\|GlobalSettingsScreen" src/ --include="*.java"` 返回空
- [ ] 被删方法无残留调用：`grep -r "calculateCurrentBoost\|getAvailableTasks\|drawStatusPill\|setVisible\|isRemoteLocked" src/ --include="*.java"` 只命中定义行（在删除前）
- [ ] Logger 名检查：ConfigStore/ConfigSync/MinigameEnforcement 不再使用 "ConfigManager"
