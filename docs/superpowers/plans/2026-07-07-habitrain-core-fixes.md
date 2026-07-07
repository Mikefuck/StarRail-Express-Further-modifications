# 哈比列车核心 11 项修复实施计划

> **For agentic workers:** 本计划用 checkbox 跟踪进度。按 spec `docs/superpowers/specs/2026-07-07-habitrain-core-fixes-design.md` 执行。

**Goal:** 修复停电模式 11 个问题（吃饭/喝水/祷告/性能/崩溃/供电池同步/道具回收/风精灵隐身/自适应概率/翻背包/杀手透视）

**Tech Stack:** Fabric 1.21.1, Java 21, Mixin, SRE DLC 4.3.0

## Global Constraints
- 不修改 SRE DLC 源码（只读参考）
- 所有 mixin 字符串目标设 `required: false`
- 代码加中文注释
- 每个任务完成后跑 `./gradlew build` 验证
- 无测试源集，验证靠编译 + 游戏内

---

## Task 1: 吃饭/喝水修复（Issue 1）

**Files:**
- Create: `src/main/java/com/habitrain/core/game/sre/mixin/BlackoutCanEatMixin.java`
- Create: `src/main/java/com/habitrain/core/game/sre/mixin/BlackoutDrinkItemMixin.java`
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/MapScannerMixin.java`
- Modify: `src/main/resources/habitrain_core.mixins.json`

- [ ] Step 1: 新建 BlackoutCanEatMixin（canEat 强制 true）
- [ ] Step 2: 新建 BlackoutDrinkItemMixin（finishUsingItem HEAD 拦截药水）
- [ ] Step 3: 改 MapScannerMixin 加盘子内容检查
- [ ] Step 4: 注册到 mixins.json
- [ ] Step 5: build 验证

## Task 2: 祷告任务接入（Issue 2）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java`

- [ ] Step 1: Shadow getEnabledSceneTasks
- [ ] Step 2: addOriginalTasks 末尾加场景任务遍历循环（跳过 PRAY 避免槽位冲突，先接入其它6个场景任务，PRAY 单独通知用户）
- [ ] Step 3: build 验证

## Task 3: 性能优化（Issue 4）

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java`
- Modify: `src/main/java/com/habitrain/core/client/cache/CustomTaskBlockCache.java`
- Modify: `src/main/java/com/habitrain/core/client/HabiTrainCoreClient.java`
- Modify: `src/main/java/com/habitrain/core/client/mixin/FixTaskRendererMixin.java`
- Modify: `src/main/resources/habitrain_core.client.mixins.json`

- [ ] Step 1: CustomTaskBlockCache 存 Block（避免渲染时 getBlockState）
- [ ] Step 2: CustomTaskBlockRendererMixin 读缓存 Block 而非 getBlockState
- [ ] Step 3: 节流渲染（每 2 帧渲染一次）
- [ ] Step 4: 光影检测降频 20→100 tick
- [ ] Step 5: mixin required:false 给字符串目标
- [ ] Step 6: build 验证

## Task 4: 崩溃缓解（Issue 5）

**Files:**
- Create: `src/main/java/com/habitrain/core/client/mixin/PlayerBodyEntityMixin.java`
- Modify: `src/main/resources/habitrain_core.client.mixins.json`

- [ ] Step 1: 新建 PlayerBodyEntityMixin（method_5797 null guard）
- [ ] Step 2: 注册到 mixins.json，required:false
- [ ] Step 3: build 验证

## Task 5: 供电池同步完成（Issue 6）

**Files:**
- Create: `src/main/java/com/habitrain/core/game/blackout/task/SupplyTaskSyncHelper.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/RestorePowerTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutMode.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/AddCoalTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/RepairWiringTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/MaintainPowerTask.java`

- [ ] Step 1: 新建 SupplyTaskSyncHelper.syncCompletion
- [ ] Step 2: 改 RestorePowerTask.forceAssignMaintainPowerToAllGood（删除无差别 removeActiveTask，改为 syncCompletion）
- [ ] Step 3: 改 BlackoutMode.forceAssignRestorePowerToAllGood（先同步完成再派 restore_power）
- [ ] Step 4: 各供电池任务 onComplete 调 syncCompletion
- [ ] Step 5: build 验证

## Task 6: 通用道具回收机制（Issue 7）

**Files:**
- Modify: `src/main/java/com/habitrain/core/api/TaskInstance.java`
- Modify: `src/main/java/com/habitrain/core/api/TaskDefinition.java`
- Create: `src/main/java/com/habitrain/core/api/ItemReclaimHelper.java`
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/SREPlayerTaskComponentMixin.java`
- Modify: `src/main/java/com/habitrain/core/task/BackpackSearchHandler.java`
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java`（giveRandomBackpackItem 改 public static + 打 NBT 标签）

- [ ] Step 1: TaskInstance 加 grantedItems 字段
- [ ] Step 2: TaskDefinition 加 onReclaim 回调
- [ ] Step 3: 新建 ItemReclaimHelper（tagGrantedItem + reclaim + matchesGrant）
- [ ] Step 4: giveRandomBackpackItem 改 public static + 返回 ItemStack + 打标签
- [ ] Step 5: 所有取消路径调用 onReclaim
- [ ] Step 6: build 验证

## Task 7: 风精灵隐身（Issue 8）

**Files:**
- Create: `src/main/java/com/habitrain/core/game/sre/mixin/ExtraEffectRoleMixin.java`
- Modify: `src/main/resources/habitrain_core.mixins.json`

- [ ] Step 1: 新建 ExtraEffectRoleMixin（serverTick HEAD 立即重施 INVISIBILITY）
- [ ] Step 2: 注册到 mixins.json，required:false
- [ ] Step 3: build 验证

## Task 8: 自适应刷新概率（Issue 9）

**Files:**
- Modify: `src/main/java/com/habitrain/core/api/TaskDefinition.java`
- Create: `src/main/java/com/habitrain/core/game/blackout/TaskTimeImpactRegistry.java`
- Create: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutTaskHelper.java`（applyTimeImpact）
- Modify: `src/main/java/com/habitrain/core/game/sre/mixin/GenerateTaskMixin.java`（反曲线 computeUrgencyMultiplier）
- Modify: 各停电任务 onComplete 用 applyTimeImpact
- Update: `docs/superpowers/guides/blackout-task-writing-guide.md`

- [ ] Step 1: TaskDefinition 加 TimeImpact record + Builder.timeImpact
- [ ] Step 2: 新建 TaskTimeImpactRegistry
- [ ] Step 3: 新建 BlackoutTaskHelper.applyTimeImpact
- [ ] Step 4: 各任务注册时声明 timeImpact + onComplete 用 applyTimeImpact
- [ ] Step 5: 改 computeUrgencyMultiplier 反曲线 smoothstep
- [ ] Step 6: 写教程文档
- [ ] Step 7: build 验证

## Task 9: 翻背包给道具 + 删弹窗（Issue 10）

**Files:**
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/BlackoutSearchBackpackTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/RepairWiringTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/SabotageWiringTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/FurnaceExplosionTask.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/task/RestorePowerTask.java`

- [ ] Step 1: blackout_search_backpack 调 giveRandomBackpackItem
- [ ] Step 2: 删 blackout_search_backpack 弹窗
- [ ] Step 3: 接入 onReclaim
- [ ] Step 4: 清查并删除其它停电任务弹窗
- [ ] Step 5: build 验证

## Task 10: 杀手透视（Issue 3，待根因确认）

- [ ] 等用户游戏内确认绿框队友是否为被警长投票选过的杀手
- [ ] 若确认身份欺诈：新建 BlackoutInstinctOverride 注册 OnGetInstinctHighlight + OnRenderRoleName
- [ ] build 验证

## Task 11: 最终验证

- [ ] ./gradlew build 全量验证
- [ ] 列修复总结报告给用户