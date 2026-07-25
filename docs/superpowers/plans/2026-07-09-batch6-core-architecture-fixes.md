# Batch 6：核心/架构横切 实施计划

**Goal:** HabiTrainCore 上帝类拆分 + SERVER_STOPPING 清理 + 耦合修复 + 死代码收尾

---

## 全局约束
1. 每 Task 完成后 `./gradlew clean build` + JAR 到 `临时\`
2. 禁止访问 `D:\Backup\mc mod\backup\`

---

### Task 6-1: HabiTrainCore 上帝类拆分

442行 → CommandRegistrar / NetworkRegistrar / LifecycleEventsRegistrar / C2SReceiverRegistrar / VoiceGroupService

**Commit:** `batch6: split HabiTrainCore god class`

### Task 6-2: SERVER_STOPPING 清理 + 核心死代码

SERVER_STOPPING 补齐全部 manager clearAll + dlcTaskCounts 清理 + EffectOwnershipTracker 清理 + anyGameActive 合并 + grantedItems 删除 + getter 删除 + 空 catch 日志 + BackpackQuestState

**Commit:** `batch6: server stopping cleanup, dead code removal`

### Task 6-3: 耦合修复 + 命名

GameLifecycleHandler 观察者模式、TaskManager SRE 抽象、BlackoutMode.onStart SRE 抽象、_win 常量、命名空间统一、InstinctColorHelper 不可变视图

**Commit:** `batch6: coupling fixes, naming consistency`
