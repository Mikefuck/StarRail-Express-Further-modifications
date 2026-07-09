# Batch 4：SRE+Network 模块修复实施计划

**Goal:** GenerateTaskMixin 上帝类拆分 + Network payload 安全 + mixin 耦合降级 + 命名清理

---

## 全局约束
1. 每 Task 完成后 `./gradlew clean build`
2. JAR 复制到 `D:\Backup\mc mod\临时\`
3. 禁止访问 `D:\Backup\mc mod\backup\`

---

### Task 4-1: GenerateTaskMixin 上帝类拆分

**文件：**
- Modify: `game/sre/mixin/GenerateTaskMixin.java`
- Create: `game/sre/TaskWeightCalculator.java`
- Create: `game/sre/DlcTaskPoolBuilder.java`
- Create: `game/sre/TaskSelector.java`
- Create: `game/sre/DlcTaskTracker.java`

将 GenerateTaskMixin (435行) 的 5 个职责拆出到独立类。Mixin 仅保留注入入口 + @Shadow 字段，方法体委派到新类。

**Commit:** `batch4: split GenerateTaskMixin god class`

### Task 4-2: SRE 性能修复

**文件：**
- Modify: `game/sre/mixin/SREPlayerTaskComponentMixin.java` — PerPlayerTaskTicker 复用
- Modify: `game/sre/TaskWeightCurves.java` — shouldIncludeOriginalTasks 缓存
- Modify: `BuiltinTaskRegistrar.java` — look_my_eyes 节流

**Commit:** `batch4: sre performance fixes`

### Task 4-3: SRE 耦合 + 静态状态

**文件：**
- Modify: `game/sre/SREGameModeBase.java` — 静态状态网收敛
- Modify: `game/sre/SREWeatherController.java` — 按 level 隔离
- Modify: `game/sre/mixin/SREPlayerTaskComponentMixin.java` — @Shadow 评估
- Modify: `SRETrainTaskWrapper.java` — getType CUSTOM→SLEEP 修复

**Commit:** `batch4: sre coupling and static state fixes`

### Task 4-4: Mixin 配置降级

**文件：**
- Modify: `resources/habitrain_core.mixins.json`
- Modify: `resources/habitrain_core.client.mixins.json`
- Modify: `game/sre/mixin/MinigameRewardMixin.java` — 字符串 target required=false

对脆弱目标（@Shadow 私有字段、内部类 targets）加 `require=0` 或 `@Pseudo`。

**Commit:** `batch4: mixin required downgrade for fragile targets`

### Task 4-5: 客户端→服务端耦合修复

**文件：**
- Modify: `client/mixin/FixTaskRendererMixin.java` — 改经 ActiveTaskCache 而非直读 TaskManager

**Commit:** `batch4: fix client->server TaskManager direct coupling`

### Task 4-6: Network payload 安全

**文件：**
- Modify: `network/ShaderConfigPayload.java` — count 上限 + len 负值保护
- Modify: `network/CustomTaskBlockPayload.java` — entryCount/setCount 上限
- Modify: `network/BlackoutSheriffVotePayload.java` — size 上限
- Modify: `network/BlackoutVotePayload.java` — size 上限

**Commit:** `batch4: network payload size validation`

### Task 4-7: 命名/常量清理

**文件：**
- Modify: 资源目录 + `TaskEditScreen.java` — habitrain_taskapi → habitrain_core
- Modify: `BlackoutAnnouncePayload.java` — 32767 魔法数字
- Modify: `BlackoutVotePayload.java` + receviver — purpose 枚举化
- Modify: `BlackoutEatMixin.java`, `BlackoutDrinkItemMixin.java` — 任务 ID 常量
- Modify: `SREWeatherController.java` — 降雨参数配置化
- Modify: `FactionFilter.java` — isParallelCall 重命名

**Commit:** `batch4: naming and constant cleanup`
