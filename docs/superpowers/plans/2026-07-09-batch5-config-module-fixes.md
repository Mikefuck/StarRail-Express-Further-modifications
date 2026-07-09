# Batch 5：Config 配置模块修复实施计划

**Goal:** ConfigManager 接口抽取、性能优化、耦合解耦。共 6 项，需在 Batch 3（Client GUI）之前完成。

---

## 全局约束
1. 每 Task 完成后 `./gradlew clean build`
2. JAR 复制到 `D:\Backup\mc mod\临时\`
3. 禁止访问 `D:\Backup\mc mod\backup\`

---

### Task 5-1: ConfigQueryService 接口抽取

**这是 Batch 3 的前置依赖。**

**文件：**
- Create: `config/ConfigQueryService.java` — 只读接口
- Modify: `config/ConfigManager.java` — 实现接口
- Modify: `config/ConfigRepository.java` — 配合接口调整

**接口定义：**
```java
public interface ConfigQueryService {
    TaskConfigEntry getTaskConfig(String fullId);
    MinigameConfigEntry getMinigameConfig(String mgId);
    boolean isTaskEnabled(String fullId, String mapName);
    boolean isMapAllowed(String fullId, String mapName);
    ConfigData globalConfig();
    ConfigData getCachedGlobalConfig();
    float getDlcWeightBoost();
    boolean isMinigameTokenReplaceEnabled();
    boolean canEditRemoteConfigs();
    // 只读方法，不包含 set/save
}
```

ConfigManager 实现此接口（已有这些 getter）。后续 Batch 3 的 GUI Tab 改为依赖 `ConfigQueryService` 接口而非 `ConfigManager` 具体类。

**Commit:** `batch5: extract ConfigQueryService interface`

---

### Task 5-2: buildJsonRoot 脏标记 + 批量提交

**文件：**
- Modify: `config/ConfigStore.java`
- Modify: `config/ConfigManager.java`

**改动：**
1. 在 ConfigStore 中引入 `dirty` 布尔标记
2. 所有 setter 只置 dirty=true，不立即 save
3. 新增 `commit()` 方法：仅在 dirty=true 时执行 buildJsonRoot + save
4. ConfigManager 现有 setter 改为 `set + commit` 模式
5. 提供 `batchCommit()` 用于批量操作后一次性写入

**Commit:** `batch5: config dirty flag and batch commit`

---

### Task 5-3: Config/SRE 耦合隔离 + -1 哨兵 + 缓存

**文件：**
- Modify: `config/ConfigStore.java` — ConfigStore/MinigameEnforcement 的 SRE 直接依赖
- Modify: `config/TaskConfigEntry.java` — -1 sentinel 改为 Optional

**说明：**
- S4-010: ConfigStore 和 MinigameEnforcement 直接 import SRE DLC 具体类。抽取 SRE 访问到单独适配层
- S4-008: -1 哨兵改为 `OptionalInt`/`OptionalFloat` 或显式 `hasX` 标志

**Commit:** `batch5: config coupling fix, optional sentinel`

---

### Task 5-4: TaskPoolBuilder cache + GameModeRegistry 缓存

**文件：**
- Modify: `task/TaskPoolBuilder.java`
- Modify: `api/GameModeRegistry.java`

**说明：**
- S2-003: TaskPoolBuilder.CACHE 接入游戏结束/模式切换失效；invalidate(String) 方法接入调用路径或删除
- S1-006: getActiveForLevel fallback 缓存被动激活结果

**Commit:** `batch5: pool cache invalidation, registry caching`
