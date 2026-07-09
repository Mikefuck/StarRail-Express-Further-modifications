# 切片 A1-2 耦合/架构专项发现（2026-07-09）

范围：全仓横切，聚焦上帝类 / 循环引用 / @Shadow 对外部 mod 的耦合与版本升级风险。

## 文件覆盖确认表

| 文件（相对路径） | 已读 | 说明 |
|---|---|---|
| HabiTrainCore.java | 是 | 主入口上帝类候选 |
| client/HabiTrainCoreClient.java | 是 | 客户端入口上帝类候选 |
| client/mixin/CustomTaskBlockRendererMixin.java | 是 | 渲染上帝类候选 |
| client/mixin/FixTaskRendererMixin.java | 是 | @Shadow 私有字段 |
| client/mixin/StarRailExpressTitleScreenMixin.java | 是 | @Shadow 字段 + 反射 MenuEntry |
| client/mixin/SubtitleHUDPrefixFixMixin.java | 是 | @Shadow 抽象方法 |
| client/mixin/InstinctColorMixin.java | 是 | require=0 已缓解 |
| game/sre/mixin/GenerateTaskMixin.java | 是 | 上帝类候选 + @Shadow 私有 |
| game/sre/mixin/SREPlayerTaskComponentMixin.java | 是 | @Shadow 私有 |
| game/sre/mixin/RoleMethodDispatcherMixin.java | 是 | @Shadow 私有静态 |
| game/sre/mixin/BlackoutShopMixin.java | 是 | @Shadow @Final |
| game/sre/mixin/ExtraEffectRoleMixin.java | 是 | required:false 已缓解 |
| game/sre/mixin/MinigameTaskAssignmentMixin.java | 是 | @Shadow 私有 |
| game/sre/mixin/NunchuckCooldownMixin.java | 是 | 字符串 target 无 require |
| game/sre/mixin/MapScannerMixin.java | 是 | 无 @Shadow |
| game/sre/mixin/MinigameRewardMixin.java | 是 | 字符串 target |
| game/blackout/task/BlackoutBetelQuestTask.java | 是 | betel 反向依赖 |
| game/blackout/BlackoutMode.java | 是 | 协调器委托，非上帝类 |
| task/TaskManager.java | 是 | 单例 + 静态可变状态 |
| config/ConfigManager.java | 是 | 单例 |
| habitrain_core.mixins.json | 是 | required:true |
| habitrain_core.client.mixins.json | 是 | required:true |

## 依赖图（本切片观测）

- sre → blackout：`GenerateTaskMixin`/`SREPlayerTaskComponentMixin` import `game.blackout.*`；`BlackoutMode.onStart` 反向 import SRE（`SREGameModes`/`SREGameWorldComponent`/`GameUtils`/`GameConstants`），形成 sre↔blackout 双向耦合。
- betel → blackout.task：`BlackoutBetelQuestTask`（blackout.task 包）import `betel.BetelQuestState`，停电任务依赖槟榔子系统状态。
- client → server 具体类：`FixTaskRendererMixin`/`CustomTaskBlockRendererMixin`（client 包）import `task.TaskManager` 单例，运行期直读其内部 Map。
- api 包：未发现被实现包反向 import，api 边界保持干净。
- 外部 mod 命名空间分布：SRE=`io.wifi.starrailexpress.*`（cca/api/client/content/event/game/util/index/network）、noellesroles=`org.agmas.noellesroles.*`、subtitle/加载器=`net.exmo.sre.*`、voicechat=`de.maxhenkel.voicechat.*`、betel=`betel.nut.*`、Iris=`net.irisshaders.iris.Iris`（反射）、ReplayMod（反射）。

## @Shadow 汇总与外部 mod 归属

| Mixin 文件 | 目标类 | 外部 mod | @Shadow 字段/方法 | require 缓解 |
|---|---|---|---|---|
| CustomTaskBlockRendererMixin | TaskBlockOverlayRenderer | noellesroles | private static getCombinedAABB | 无 |
| FixTaskRendererMixin | HudMoodRenderer$TaskRenderer / MoodRenderer$TaskRenderer | SRE | private Component text | 无 |
| StarRailExpressTitleScreenMixin | StarRailExpressTitleScreen | net.exmo.sre | showChangelog, menuEntries, menuBaseX/Y, menuMaxScroll, menuViewportTop/Bottom | 无 |
| SubtitleHUDPrefixFixMixin | SubtitleHUD | net.exmo.sre | enqueue(abstract) | 无 |
| BlackoutShopMixin | SREPlayerShopComponent | SRE | @Final Player player | 无 |
| ExtraEffectRoleMixin | ExtraEffectRole | SRE | @Final ArrayList playerEffects, getNewEffectInstance | 注释 required:false（但 json required:true） |
| GenerateTaskMixin | SREPlayerTaskComponent | SRE | player, tasks, timesGotten, playerMoodComponent, getDisabledTasks, getEnabledSceneTasks, createTaskInstance | 无 |
| MinigameTaskAssignmentMixin | SREPlayerMinigameTaskComponent | SRE | targetMinigameId, getPlayer | 无 |
| RoleMethodDispatcherMixin | RoleMethodDispatcher | SRE | private static getCurrentRole | 无 |
| SREPlayerTaskComponentMixin | SREPlayerTaskComponent | SRE | player, parallelTaskGenerated, playerMoodComponent, tasks, generateParallelTask | 无 |

注：`ExtraEffectRoleMixin` 与 `InstinctColorMixin` 自身有 `require=0`/`required:false` 意图，但全局 mixin json 仍声明 `required:true`，启动期 refmap 解析阶段对目标类缺失仍会触发失败，单条注入点的 require 只缓解运行期注入失败，不缓解目标类/字段缺失导致的 MixinApplyError。

## 发现清单（见 StructuredOutput）

A1-2-001 ~ A1-2-010，按严重度排序。