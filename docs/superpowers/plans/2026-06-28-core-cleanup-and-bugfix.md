# 哈比列车核心 — 旧包清理与 Bug 修复 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除旧 `com.habitrain.taskapi.*` 包、迁移所有引用到新 `com.habitrain.core.*` API、修复渲染/HUD/任务逻辑 Bug

**Architecture:** 一次删除 33 个旧文件 + 2 个 mixin JSON，然后在 `com.habitrain.core.*` 中修复所有旧引用，同时修复任务完成清理和 HUD 显示。companion 模组同步迁移。

**Tech Stack:** Fabric 1.21.1，Java 21，Minecraft Modding

## 全局约束

- 删除 `com.habitrain.taskapi.*` 所有源文件和旧 mixin JSON
- `HabiTaskCategory` 枚举由 `TaskCategory` 类中的静态常量替代
- `TaskDefinition.originalCategory` 替换为 `TaskCategory category`
- 所有旧包引用必须指向 `com.habitrain.core.*` 中的新类
- companion 模组 `habitrain_more_tasks` 同步更新
- 槟榔模组不需要改动

---

## 文件清单

### 删除（35 个文件）

```
删除 Java 源文件（33 个）:
src/main/java/com/habitrain/taskapi/HabiTrainTaskAPI.java
src/main/java/com/habitrain/taskapi/api/HabiTaskCategory.java
src/main/java/com/habitrain/taskapi/api/HabiTaskDefinition.java
src/main/java/com/habitrain/taskapi/api/HabiTaskInstance.java
src/main/java/com/habitrain/taskapi/api/HabiTaskRegistry.java
src/main/java/com/habitrain/taskapi/api/EffectOwnershipTracker.java
src/main/java/com/habitrain/taskapi/impl/HabiTaskManager.java
src/main/java/com/habitrain/taskapi/impl/TaskEnumHelper.java
src/main/java/com/habitrain/taskapi/impl/config/HabiConfigManager.java
src/main/java/com/habitrain/taskapi/impl/config/HabiTaskConfigEntry.java
src/main/java/com/habitrain/taskapi/impl/mixin/GenerateTaskMixin.java
src/main/java/com/habitrain/taskapi/impl/mixin/ServerTickMixin.java
src/main/java/com/habitrain/taskapi/impl/mixin/MapScannerMixin.java
src/main/java/com/habitrain/taskapi/impl/mixin/RoleMethodDispatcherMixin.java
src/main/java/com/habitrain/taskapi/impl/mixin/NunchuckCooldownMixin.java
src/main/java/com/habitrain/taskapi/client/HabiTrainTaskAPIClient.java
src/main/java/com/habitrain/taskapi/client/ActiveCustomTaskCache.java
src/main/java/com/habitrain/taskapi/client/CustomTaskStore.java
src/main/java/com/habitrain/taskapi/client/gui/HabiConfigScreen.java
src/main/java/com/habitrain/taskapi/client/gui/HabiTaskListScreen.java
src/main/java/com/habitrain/taskapi/client/gui/HabiTaskEditScreen.java
src/main/java/com/habitrain/taskapi/client/gui/HabiGlobalSettingsScreen.java
src/main/java/com/habitrain/taskapi/client/gui/HabiModMenuIntegration.java
src/main/java/com/habitrain/taskapi/client/gui/ShaderWhitelistScreen.java
src/main/java/com/habitrain/taskapi/client/mixin/HudCustomTaskMixin.java
src/main/java/com/habitrain/taskapi/client/mixin/InstinctColorMixin.java
src/main/java/com/habitrain/taskapi/client/mixin/CustomTaskBlockRendererMixin.java
src/main/java/com/habitrain/taskapi/client/mixin/StarRailExpressTitleScreenMixin.java
src/main/java/com/habitrain/taskapi/client/mixin/InstinctCacheFixMixin.java
src/main/java/com/habitrain/taskapi/impl/network/ActiveCustomTaskPayload.java
src/main/java/com/habitrain/taskapi/impl/network/ConfigUpdateC2SPayload.java
src/main/java/com/habitrain/taskapi/impl/network/ShaderConfigSyncS2CPayload.java
src/main/java/com/habitrain/taskapi/impl/network/ShaderPackInfoC2SPayload.java
src/main/java/com/habitrain/taskapi/impl/network/TaskConfigSyncPayload.java

删除 mixin JSON（2 个）:
src/main/resources/habitrain_taskapi.mixins.json
src/main/resources/habitrain_taskapi.client.mixins.json
```

### 修改（9 个文件）

```
com.habitrain.core 内（8 个）:
  api/TaskCategory.java              — 添加静态常量
  api/TaskDefinition.java            — originalCategory → TaskCategory
  api/TaskInstance.java              — getName() 返回 displayName
  game/sre/mixin/GenerateTaskMixin.java — 旧引用 → 新 API
  task/TaskManager.java              — HabiTaskCategory → TaskCategory + 添加清理方法
  game/sre/SREGameModeBase.java      — HabiTaskCategory → TaskCategory
  client/mixin/HudCustomTaskMixin.java — CustomTaskStore → ActiveTaskCache
  client/mixin/CustomTaskBlockRendererMixin.java — 大厅阶段不渲染 DLC 方块

companion 内（1 个）:
  com/habitrain/moretasks/HabiTrainMoreTasks.java — HabiTaskCategory → TaskCategory
```

---

### Task 1：删除旧包文件

**文件：**
- 删除：35 个文件（见上方删除清单）

**接口：**
- 消耗：无
- 产出：干净的 `com.habitrain.core.*` 目录，不再有旧包

- [ ] **Step 1: 删除 33 个旧 Java 源文件**

使用 PowerShell 批量删除：

```powershell
Get-ChildItem -Path "src/main/java/com/habitrain/taskapi" -Recurse -File | Remove-Item
Remove-Item -Recurse -Path "src/main/java/com/habitrain/taskapi" -Force
```

验证：
```powershell
Test-Path "src/main/java/com/habitrain/taskapi"
# 应返回 False
```

- [ ] **Step 2: 删除 2 个旧 mixin JSON**

```powershell
Remove-Item "src/main/resources/habitrain_taskapi.mixins.json" -Force
Remove-Item "src/main/resources/habitrain_taskapi.client.mixins.json" -Force
```

- [ ] **Step 3: 暂不构建（后续任务修复引用后统一构建）**

---

### Task 2：添加 TaskCategory 常量并更新 TaskDefinition

**文件：**
- 修改：`src/main/java/com/habitrain/core/api/TaskCategory.java`
- 修改：`src/main/java/com/habitrain/core/api/TaskDefinition.java`

**接口：**
- 消耗：无
- 产出：`TaskCategory.MURDER`/`REPAIR`/`ALL`/`CUSTOM` 常量，`TaskDefinition.Builder.category(TaskCategory)` 方法

- [ ] **Step 1: 给 TaskCategory 添加静态常量**

在 `com.habitrain.core.api.TaskCategory` 类的 `id`、`displayName`、`gameModeId` 字段之后的下方添加：

```java
// ========== 标准分类常量（替代旧的 HabiTaskCategory 枚举） ==========
public static final TaskCategory MURDER = new TaskCategory("sre:murder", "谋杀模式", "sre:base");
public static final TaskCategory REPAIR = new TaskCategory("sre:repair", "修机模式", "sre:base");
public static final TaskCategory ALL    = new TaskCategory("sre:all", "通用任务", "sre:base");
public static final TaskCategory CUSTOM = new TaskCategory("sre:custom", "自定义任务", "sre:base");
```

- [ ] **Step 2: 删除 TaskDefinition 中旧的 HabiTaskCategory 引用**

将 `TaskDefinition.java` 中的：

```java
import com.habitrain.taskapi.api.HabiTaskCategory;
```

改为：

```java
// 删除该行 — TaskCategory 在 com.habitrain.core.api 中，同包无需 import
```

- [ ] **Step 3: 替换字段**

将字段：

```java
private final HabiTaskCategory originalCategory;
```

改为：

```java
private final TaskCategory category;
```

- [ ] **Step 4: 替换构造函数中的赋值**

将：

```java
this.originalCategory = builder.originalCategory;
```

改为：

```java
this.category = builder.category;
```

- [ ] **Step 5: 替换 getter**

将：

```java
public HabiTaskCategory getOriginalCategory() { return originalCategory; }
```

改为：

```java
public TaskCategory getCategory() { return category; }
```

- [ ] **Step 6: 替换 Builder 字段和默认值**

将：

```java
private HabiTaskCategory originalCategory = HabiTaskCategory.ALL;
```

改为：

```java
private TaskCategory category = TaskCategory.ALL;
```

- [ ] **Step 7: 替换 Builder 方法**

将：

```java
public Builder originalCategory(HabiTaskCategory cat) { this.originalCategory = cat; return this; }
```

改为：

```java
public Builder category(TaskCategory cat) { this.category = cat; return this; }
```

- [ ] **Step 8: 修复 TaskInstance.getName()**

将 `src/main/java/com/habitrain/core/api/TaskInstance.java` 中的：

```java
public String getName() { return definition.getTaskId(); }
```

改为：

```java
public String getName() { return definition.getDisplayName(); }
```

---

### Task 3：修复 GenerateTaskMixin 中的旧引用

**文件：**
- 修改：`src/main/java/com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java`

**接口：**
- 消耗：`TaskCategory` 常量（Task 2）、`ConfigManager`（新包）
- 产出：可编译的 GenerateTaskMixin

- [ ] **Step 1: 替换 import 语句**

将文件顶部的 import：

```java
import com.habitrain.taskapi.api.HabiTaskCategory;
import com.habitrain.taskapi.impl.config.HabiConfigManager;
import com.habitrain.taskapi.impl.config.HabiTaskConfigEntry;
```

改为：

```java
// 删除以上三行
// HabiTaskCategory → TaskCategory（同包，无需额外 import）
// HabiConfigManager → ConfigManager
import com.habitrain.core.config.ConfigManager;
// HabiTaskConfigEntry → TaskConfigEntry
import com.habitrain.core.config.TaskConfigEntry;
```

- [ ] **Step 2: 替换方法中的 HabiTaskCategory 引用**

所有 `HabiTaskCategory` 出现处改为 `TaskCategory`：

```java
// 例如: addDlcTasks 参数
private float addDlcTasks(List<Map.Entry<Object, Float>> entries,
                          TaskManager mgr, String mapName,
                          TaskCategory currentCategory, Set<String> disabledTasks) {

// getAvailableDlcTasks 参数
private List<TaskDefinition> getAvailableDlcTasks(TaskManager mgr, String mapName, TaskCategory currentCategory) {
```

- [ ] **Step 3: 替换 getEffectiveWeight 方法**

将：

```java
private float getEffectiveWeight(TaskDefinition def) {
    var entry = HabiConfigManager.getInstance().getTaskConfig(def.getFullId());
    if (entry != null && entry.refreshWeight >= 0f) {
        return entry.refreshWeight;
    }
    return def.getWeight() > 0 ? def.getWeight() : 1.0f;
}
```

改为：

```java
private float getEffectiveWeight(TaskDefinition def) {
    TaskConfigEntry entry = ConfigManager.getInstance().getTaskConfig(def.getFullId());
    if (entry != null && entry.refreshWeight >= 0f) {
        return entry.refreshWeight;
    }
    return def.getWeight() > 0 ? def.getWeight() : 1.0f;
}
```

- [ ] **Step 4: 替换 isTaskMapEnabled 方法**

将：

```java
private boolean isTaskMapEnabled(String fullId, String mapName) {
    HabiTaskConfigEntry entry = HabiConfigManager.getInstance().getTaskConfig(fullId);
    if (entry == null) return true;
    if (!entry.enabled) return false;
    ...
```

改为：

```java
private boolean isTaskMapEnabled(String fullId, String mapName) {
    TaskConfigEntry entry = ConfigManager.getInstance().getTaskConfig(fullId);
    if (entry == null) return true;
    if (!entry.enabled) return false;
    ...
```

- [ ] **Step 5: 替换 getTargetRatio 方法**

将：

```java
private float getTargetRatio() {
    return HabiConfigManager.getInstance().getDlcProbabilityTarget();
}
```

改为：

```java
private float getTargetRatio() {
    return ConfigManager.getInstance().getDlcProbabilityTarget();
}
```

---

### Task 4：修复 TaskManager、SREGameModeBase、HudCustomTaskMixin

**文件：**
- 修改：`src/main/java/com/habitrain/core/task/TaskManager.java`
- 修改：`src/main/java/com/habitrain/core/game/sre/SREGameModeBase.java`
- 修改：`src/main/java/com/habitrain/core/client/mixin/HudCustomTaskMixin.java`

**接口：**
- 消耗：`TaskCategory` 常量（Task 2）
- 产出：干净的引用，`TaskManager.clearAllActiveTasks()` 方法

- [ ] **Step 1: 替换 TaskManager 中的旧引用**

将 import：

```java
import com.habitrain.taskapi.api.HabiTaskCategory;
```

改为删除该行。

将 `getCurrentGameModeCategory` 返回类型和所有 `HabiTaskCategory.ALL`/`REPAIR`/`MURDER` 引用改为 `TaskCategory`：

```java
public TaskCategory getCurrentGameModeCategory(Player player) {
    if (player == null || player.level() == null) return TaskCategory.ALL;
    try {
        SREGameWorldComponent gameWorld = SREGameWorldComponent.KEY.get(player.level());
        if (gameWorld == null || gameWorld.getGameMode() == null) return TaskCategory.ALL;
        String modeId = gameWorld.getGameMode().identifier.toString();
        if (modeId.contains("repair_escape") || modeId.contains("repair")) {
            return TaskCategory.REPAIR;
        }
        return TaskCategory.MURDER;
    } catch (Exception e) {
        return TaskCategory.ALL;
    }
}
```

将 `getAvailableTasks` 方法中的 `HabiTaskCategory` 改为 `TaskCategory`：

```java
public List<TaskDefinition> getAvailableTasks(String mapName, TaskCategory currentCategory) {
    List<TaskDefinition> available = new ArrayList<>();
    ConfigManager config = ConfigManager.getInstance();

    for (TaskDefinition def : TaskRegistry.getAll()) {
        TaskConfigEntry entry = config.getTaskConfig(def.getFullId());
        boolean mapEnabled = isTaskEnabledForMap(entry, mapName);
        if (!mapEnabled) continue;

        TaskCategory cat = def.getCategory();
        boolean categoryMatch = (cat == TaskCategory.ALL
            || cat == TaskCategory.CUSTOM
            || cat == currentCategory);
        if (!categoryMatch) continue;

        available.add(def);
    }
    return available;
}
```

- [ ] **Step 2: 给 TaskManager 添加 clearAllActiveTasks()**

在 `removeActiveTask` 方法之后添加：

```java
/** 清空所有玩家的活跃任务（游戏结束时调用） */
public void clearAllActiveTasks() {
    activeCustomTasks.clear();
}
```

- [ ] **Step 3: 修复 TaskManager.handleTaskCompletion()**

在方法末尾添加清理：

```java
public void handleTaskCompletion(ServerPlayer player, TaskInstance instance) {
    TaskDefinition def = instance.getDefinition();

    if (player.level() instanceof ServerLevel sl) {
        GameModeRegistry.getActiveForLevel(sl).ifPresent(gm ->
            gm.onTaskComplete(player, instance));
    }

    if (def.canDirectlyWin()) {
        triggerDirectWin(player, instance);
    }

    // ★ 任务完成 → 清理活跃任务，防止已完成任务残留在渲染器中
    removeActiveTask(player.getUUID());
}
```

- [ ] **Step 4: 替换 SREGameModeBase 中的旧引用**

将 import：

```java
import com.habitrain.taskapi.api.HabiTaskCategory;
```

改为删除该行。所有 `HabiTaskCategory.MURDER` → `TaskCategory.MURDER`，`HabiTaskCategory.REPAIR` → `TaskCategory.REPAIR`，`HabiTaskCategory.ALL` → `TaskCategory.ALL`。

- [ ] **Step 5: 在 SREGameModeBase 中添加游戏结束清理**

在 `onCleanup()` 或 `onEnd()` 方法中添加（如果不存在则添加）：

```java
@Override
public void onEnd(ServerLevel level, WinResult result) {
    // 游戏结束 → 清空所有活跃 DLC 任务
    TaskManager.getInstance().clearAllActiveTasks();
}

@Override
public void onCleanup(ServerLevel level) {
    // 清理现场时也确保活跃任务被清空
    TaskManager.getInstance().clearAllActiveTasks();
}
```

- [ ] **Step 6: 修复 HudCustomTaskMixin**

将 `src/main/java/com/habitrain/core/client/mixin/HudCustomTaskMixin.java` 中的内容替换为：

```java
package com.habitrain.core.client.mixin;

import com.habitrain.core.client.cache.ActiveTaskCache;
import io.wifi.starrailexpress.cca.SREPlayerTaskComponent;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 Mixin - 从同步数据中捕获自定义任务信息到 ActiveTaskCache
 * (移除了对旧 CustomTaskStore 的依赖)
 */
@Mixin(SREPlayerTaskComponent.class)
public class HudCustomTaskMixin {

    @Inject(method = "readFromSyncNbt", at = @At("TAIL"), remap = false)
    private void habitrain$onReadSyncNbt(CompoundTag tag, HolderLookup.Provider lookup, CallbackInfo ci) {
        if (tag.contains("tasks", Tag.TAG_LIST)) {
            for (Tag element : tag.getList("tasks", Tag.TAG_COMPOUND)) {
                if (element instanceof CompoundTag compound && compound.contains("customId")) {
                    String cid = compound.getString("customId");
                    if (!cid.isEmpty()) {
                        ActiveTaskCache.setActiveTask(cid);
                        return;
                    }
                }
            }
        }
        // 没有找到自定义任务 → 清空缓存
        ActiveTaskCache.clear();
    }
}
```

---

### Task 5：修复 CustomTaskBlockRendererMixin（大厅不渲染 DLC 方块）

**文件：**
- 修改：`src/main/java/com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java`

**接口：**
- 消耗：`SREGameWorldComponent`（SRE 的 API）
- 产出：大厅旁观模式不再渲染自定义任务方块

- [ ] **Step 1: 在 renderAllCustomTaskBlocks 中添加游戏状态检查**

在 `renderAllCustomTaskBlocks` 方法顶部添加大厅检测：

```java
/**
 * 旁观/创造模式：渲染所有已注册的自定义任务方块（类型 ≥12）
 * ★ 如果游戏未运行（大厅阶段），不渲染任何 DLC 自定义方块（type ≥ 12）
 *   只让 SRE 原版渲染器处理原版方块（type 1-11）
 */
private static void renderAllCustomTaskBlocks(WorldRenderContext renderContext) {
    // ★ 大厅阶段（无活跃游戏）→ 不渲染 DLC 自定义方块
    if (!isGameRunning()) {
        return;
    }

    Map<Integer, Color> typeColors = buildTypeColorMap();
    if (typeColors.isEmpty()) return;

    // ... 后续代码不变
}
```

- [ ] **Step 2: 添加 isGameRunning 工具方法**

在 `buildTypeColorMap` 方法之前添加：

```java
/**
 * 检测 SRE 游戏是否正在运行
 * 大厅阶段返回 false，游戏进行中返回 true
 */
private static boolean isGameRunning() {
    var instance = Minecraft.getInstance();
    if (instance == null || instance.level == null) return false;
    try {
        var gameWorld = SREGameWorldComponent.KEY.get(instance.level);
        return gameWorld != null && gameWorld.isRunning();
    } catch (Exception e) {
        return false;
    }
}
```

在文件顶部添加 import：

```java
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
```

（如果尚未引入）

---

### Task 6：修复 ConfigScreen 中的旧引用

**文件：**
- 修改：`src/main/java/com/habitrain/core/client/gui/ConfigScreen.java`

**接口：**
- 消耗：`TaskCategory` 常量（Task 2）
- 产出：可编译的 ConfigScreen（功能保持原有 4 模式布局，后续 ModMenu 重写中再彻底 redesign）

- [ ] **Step 1: 替换 import**

将：

```java
import com.habitrain.taskapi.api.HabiTaskCategory;
```

改为删除该行。`TaskListScreen` 的 import 可能导致编译错误，改为 `com.habitrain.core.api.TaskCategory`：

```java
import com.habitrain.core.api.TaskCategory;
```

- [ ] **Step 2: 替换 ModeCard 和 MODE_CARDS**

将 `ModeCard` 中的 `HabiTaskCategory category` → `TaskCategory category`。

将 `MODE_CARDS` 列表中的每个 `HabiTaskCategory.MURDER` → `TaskCategory.MURDER`，依此类推。

- [ ] **Step 3: 替换 getTasksForMode 方法**

将：

```java
private List<TaskDefinition> getTasksForMode(HabiTaskCategory category) {
    List<TaskDefinition> result = new ArrayList<>();
    for (var def : TaskRegistry.getAll()) {
        if (def.getOriginalCategory() == category || def.getOriginalCategory() == HabiTaskCategory.ALL) {
            if (category == HabiTaskCategory.ALL && def.getOriginalCategory() != HabiTaskCategory.ALL) continue;
            if (category == HabiTaskCategory.CUSTOM && def.getOriginalCategory() != HabiTaskCategory.CUSTOM) continue;
            result.add(def);
        }
    }
    ...
}
```

改为：

```java
private List<TaskDefinition> getTasksForMode(TaskCategory category) {
    List<TaskDefinition> result = new ArrayList<>();
    for (var def : TaskRegistry.getAll()) {
        TaskCategory cat = def.getCategory();
        if (cat == category || cat == TaskCategory.ALL) {
            if (category == TaskCategory.ALL && cat != TaskCategory.ALL) continue;
            if (category == TaskCategory.CUSTOM && cat != TaskCategory.CUSTOM) continue;
            result.add(def);
        }
    }
    ...
}
```

- [ ] **Step 4: 相同修改应用到 TaskListScreen.java 和 TaskEditScreen.java**

修改 `src/main/java/com/habitrain/core/client/gui/TaskListScreen.java`：

将 import `com.habitrain.taskapi.api.HabiTaskCategory` → 删除。

将构造函数参数 `HabiTaskCategory category` → `TaskCategory category`，存储的类型也改为 `TaskCategory`。

将内部所有 `HabiTaskCategory.ALL` → `TaskCategory.ALL`。

修改 `src/main/java/com/habitrain/core/client/gui/TaskEditScreen.java`：

将 import `com.habitrain.taskapi.api.HabiTaskCategory` → 删除（该类不直接使用 HabiTaskCategory，仅通过 TaskDefinition 获取类别信息）。

---

### Task 7：编译验证核心模组

**文件：**
- 无修改

- [ ] **Step 1: 运行编译**

```powershell
cd "D:/Backup/mc mod/哈比列车api"
./gradlew clean build 2>&1
```

预期结果：BUILD SUCCESSFUL

如果编译失败，阅读错误信息定位未修复的旧引用并修复。常见遗漏：

- `getOriginalCategory()` → `getCategory()` 未替换
- `HabiTaskCategory` 出现在某个文件中未修改
- `HabiConfigManager` 或 `HabiTaskConfigEntry` 还可以出现在其他文件中

- [ ] **Step 2: 验证 JAR 内容不再包含旧包文件**

```powershell
$jar = Get-ChildItem -Path "build/libs" -Filter "*.jar" | Select-Object -First 1
if ($jar) {
    $containsOld = & jar tf $jar.FullName | Select-String "taskapi"
    if ($containsOld) {
        Write-Host "⚠ 警告: JAR 中仍包含旧包文件:"
        $containsOld
    } else {
        Write-Host "✓ JAR 已干净，不含旧包"
    }
}
```

---

### Task 8：迁移 Companion 模组

**文件：**
- 修改：`D:\Backup\mc mod\哈比列车更多修改\src\main\java\com\habitrain\moretasks\HabiTrainMoreTasks.java`

**接口：**
- 消耗：`TaskCategory` 常量
- 产出：可编译的 companion 模组

- [ ] **Step 1: 替换 import**

将：

```java
import com.habitrain.taskapi.api.HabiTaskCategory;
```

改为：

```java
import com.habitrain.core.api.TaskCategory;
```

- [ ] **Step 2: 替换所有 `.originalCategory(HabiTaskCategory.XXX)`**

所有出现处：

```java
.originalCategory(HabiTaskCategory.MURDER)
```

改为：

```java
.category(TaskCategory.MURDER)
```

（共 4 处：test_grass、pet_cat、search_backpack、look_my_eyes）

- [ ] **Step 3: 将更新后的 `habitrain_core` JAR 复制到 companion 的 libs/**

```powershell
Copy-Item "D:/Backup/mc mod/哈比列车api/build/libs/habitrain_core-*.jar" "D:/Backup/mc mod/哈比列车更多修改/libs/" -Force
```

- [ ] **Step 4: 编译 companion 模组**

```powershell
cd "D:/Backup/mc mod/哈比列车更多修改"
./gradlew clean build 2>&1
```

预期结果：BUILD SUCCESSFUL

- [ ] **Step 5: 复制两个 JAR 到临时目录**

```powershell
Copy-Item "D:/Backup/mc mod/哈比列车api/build/libs/habitrain_core-*.jar" "D:/Backup/mc mod/临时/" -Force
Copy-Item "D:/Backup/mc mod/哈比列车更多修改/build/libs/habitrain_more_tasks-*.jar" "D:/Backup/mc mod/临时/" -Force
```
