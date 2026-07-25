# Batch 3：Client GUI + 渲染 实施计划

**Goal:** 2 个上帝类拆分 + GUI 配置查询优化 + 渲染性能 + 杂项修复

---

## 全局约束
1. 每 Task 完成后 `./gradlew clean build` + JAR 到 `临时\`
2. 禁止访问 `D:\Backup\mc mod\backup\`

---

### Task 3-1: CustomTaskBlockRendererMixin 上帝类拆分

466行 → 5个职责类 + mixin 委派 (~100行)

**Commit:** `batch3: split CustomTaskBlockRendererMixin god class`

### Task 3-2: HabiTrainCoreClient 上帝类拆分

332行 → 5个职责类 + 装配器 (~50行)

**Commit:** `batch3: split HabiTrainCoreClient god class`

### Task 3-3: 渲染性能 + GUI 优化

Color 常量、keySet 合并、ConfigQueryService 接入（快照Map）、Tab 懒初始化、GlobalTabScreen 移构建、TaskColorPicker 收敛、反射缓存、SharedGuiKit 颜色常量

**Commit:** `batch3: render perf, config query interface, gui init fixes`
