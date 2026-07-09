# Batch 2：Betel 槟榔模块修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement.

**Goal:** 修复 Betel 槟榔模块 8 项问题：性能优化、并发安全、状态收敛、耦合解耦。

---

## 全局约束
1. 每 Task 完成后 `./gradlew clean build`
2. JAR 复制到 `D:\Backup\mc mod\临时\`
3. 禁止访问 `D:\Backup\mc mod\backup\`

---

### Task 2-1: tickPlayer 性能 + 缓存/并发修复

**文件：**
- Modify: `betel/BetelTickEngine.java`
- Modify: `betel/BetelQuestState.java`

**修复 3 个关联问题：**

**S3-001 tickPlayer 性能：**
`BetelTickEngine.java:33-199` — tickPlayer 每 tick 对每玩家重复做：
- `SREGameWorldComponent.KEY.get(player.level())` — 取一次复用
- `BetelNutEntityComponents.ADDICTION.get(player)` — 取一次复用
- `BetelNutConfig.get()` — 取一次复用
- `BuiltInRegistries.ITEM.get(...)` + `getHolder(...)` — 启动期缓存为静态字段
- `new Random()` — 改为 `ThreadLocalRandom.current()` 或共享 `Random` 实例
- `player.level().getGameTime()` 多次调用 — 取一次复用

**S3-004 空 catch 加日志：**
`BetelTickEngine.java:201-210,244-245` — 在 `isGameActive` 和 `clearAddictionForPlayer` 的空 catch 中加入 warn 日志。

**S3-007 单例线程安全 + playerData：**
`BetelQuestState.java:11-28` — instance 已加 volatile（Batch 0 做了）。还需要：
- 确认 DCL 模式完整：`if (instance == null) { synchronized (BetelQuestState.class) { if (instance == null) instance = ... } }`
- `playerData` 从 `HashMap` 改为 `ConcurrentHashMap`

**Commit:** `batch2: betel tick perf, singleton safety, empty catch logging`

---

### Task 2-2: BetelQuestState 状态收敛 + API 封装

**文件：**
- Modify: `betel/BetelQuestState.java`
- Modify: `game/blackout/task/BlackoutBetelQuestTask.java`

**S3-008 PlayerBetelData 字段收敛：**
`BetelQuestState.java:108-123` — 14 个布尔字段密度高。将重叠布尔收敛为显式状态枚举：
```java
public enum AddictionStage { NONE, MILD, MODERATE, SEVERE, CRITICAL }
public enum EffectApplicationState { NONE, DARKNESS_APPLIED, WITHDRAWAL_ACTIVE }
```
补字段级注释说明每个标志的写入点和清零点。

**S3-010 BetelQuestState API 封装：**
- `setRevealUsed(boolean)` / `resetAll()` — 收敛为包级访问或 protected
- `markQuestAssigned()` / `resetEatenStatus()` / `hasPlayerEatenBetelNut()` — 对外暴露的应通过新接口

**A1-2-008 blackout ↔ betel 解耦：**
`BlackoutBetelQuestTask.java` 直接调用 `BetelQuestState.markQuestAssigned`/`resetEatenStatus`/`hasPlayerEatenBetelNut`。改为：
- 由 betel 提供显式的 `BetelTaskFacade` 接口，blackout 依赖接口而非具体类
- 或通过事件机制解耦

**Commit:** `batch2: betel state field convergence, API encapsulation, blackout coupling fix`

---

### Task 2-3: BetelLeafHandler 优化 + 缓存修复

**文件：**
- Modify: `betel/BetelLeafHandler.java`

**S3-002 双遍历合并：**
- `applyHarvestSlowness`（END_SERVER_TICK 回调）和 `tickHarvests`（ModTickHandler 驱动）每 tick 都全量遍历 activeHarvests。有两个方向：
  - **方向 A**：`activeHarvests` 按世界维度分桶（Map<ResourceKey, Set<HarvestTask>>），避免跨世界遍历
  - **方向 B**：移除 BetelLeafHandler 自注册的 END_SERVER_TICK 回调，全部由 ModTickHandler 统一驱动

优先采用方向 B（减少注册点），同时加方向 A 的分桶优化。

**S3-012 静态缓存重试：**
`betelLeafBlock` 缓存已在 Batch 0 部分修复，确保首次查找返回 AIR 时不永久缓存，下次允许重试。

**Commit:** `batch2: betel leaf handler double traversal merge, cache retry`
