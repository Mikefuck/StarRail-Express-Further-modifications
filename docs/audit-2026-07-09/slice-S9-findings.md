# 切片 S9 — client 核心 + mixin 审计发现

审计日期：2026-07-09
审计范围：`com.habitrain.core.client`（含 12 个 client mixin）
独立审查：仅基于源代码事实，未参考任何既有审计/计划/报告。

## 文件覆盖确认表

| # | 文件（相对 src/main/java） | 已读 | 行数 |
|---|---|---|---|
| 1 | com/habitrain/core/client/HabiTrainCoreClient.java | 是 | 332 |
| 2 | com/habitrain/core/client/BlackoutKeyHandler.java | 是 | 53 |
| 3 | com/habitrain/core/client/InstinctColorHelper.java | 是 | 49 |
| 4 | com/habitrain/core/client/cache/ActiveTaskCache.java | 是 | 127 |
| 5 | com/habitrain/core/client/network/PayloadSenders.java | 是 | 61 |
| 6 | com/habitrain/core/client/util/ClientSubtitleNotifier.java | 是 | 38 |
| 7 | com/habitrain/core/client/util/TaskTextNormalizer.java | 是 | 81 |
| 8 | com/habitrain/core/client/mixin/SubtitleHUDPrefixFixMixin.java | 是 | 34 |
| 9 | com/habitrain/core/client/mixin/StarRailExpressTitleScreenMixin.java | 是 | 156 |
| 10 | com/habitrain/core/client/mixin/HudCustomTaskMixin.java | 是 | 38 |
| 11 | com/habitrain/core/client/mixin/InstinctCacheFixMixin.java | 是 | 58 |
| 12 | com/habitrain/core/client/mixin/BlackoutTimeRendererMixin.java | 是 | 36 |
| 13 | com/habitrain/core/client/mixin/BlackoutLimitedInventoryScreenMixin.java | 是 | 32 |
| 14 | com/habitrain/core/client/mixin/InstinctKillerTeamMixin.java | 是 | 31 |
| 15 | com/habitrain/core/client/mixin/InstinctSheriffGateMixin.java | 是 | 38 |
| 16 | com/habitrain/core/client/mixin/FixTaskRendererMixin.java | 是 | 73 |
| 17 | com/habitrain/core/client/mixin/PlayerBodyEntityMixin.java | 是 | 45 |
| 18 | com/habitrain/core/client/mixin/InstinctColorMixin.java | 是 | 77 |
| 19 | com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java | 是 | 466 |

12 个 client mixin 全部命中 mixin 配置 `habitrain_core.client.mixins.json`（`"required": true`）。

## 发现汇总

按严重度排列。仅列真实确认或证据充分的问题。

### S1-001 性能 · CustomTaskBlockRendererMixin 每帧每块分配 Color 对象（热路径）
- 文件：com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java
- 行：358
- evidence：`renderConstantOverlaysIfBlackout` 在 for 循环内对每个街机电话方块每帧执行 `renderCustomOverlay(context, pos, new java.awt.Color(0xFFFFD700, true), 5.0f)`，未复用常量 Color。该方法在生存+停电模式分支（264）与旁观分支（232）均被调用，每帧调用一次。
- impact：停电模式进行中、地图存在多个 street_phone 方块时，每帧为每个该类方块分配一个 `java.awt.Color` 对象，进入渲染热路径每帧 GC 压力。方块越多越明显。
- direction：将金色 Color 提升为 `static final` 常量复用，避免每帧分配。
- severity：S1

### S1-002 死逻辑 · invalidateGameRunningCache 永不调用
- 文件：com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java
- 行：172-175
- evidence：`private static void invalidateGameRunningCache()` 定义并注释"在 SRE 游戏开始/结束事件时调用"，但全仓库 grep 无任何调用点（仅定义处命中）。`isGameRunning()` 缓存靠 500ms TTL 自然过期，事件强制刷新从未被接线。
- impact：游戏开始/结束瞬间的缓存状态最多滞后 500ms，依赖强制刷新的预期失效不会发生；同时是误导性死代码（注释承诺行为未实现）。
- direction：要么在游戏开始/结束事件处接线调用，要么删除该死方法与其注释承诺。
- severity：S1（死逻辑致缓存失效承诺缺失 + 死代码）

### S1-003 标识 · 12 个 client mixin 全部 required=true，任一 SRE 目标缺失即阻断客户端启动
- 文件：com/habitrain/core/client/mixin/*.java + resources/habitrain_core.client.mixins.json
- 行：mixins.json `"required": true`；全部 12 个 mixin target 外部 SRE/noellesroles 类
- evidence：`habitrain_core.client.mixins.json` 设 `"required": true`，且 12 个 client mixin 几乎全部 @Mixin 外部模组类（SRE 的 SubtitleHUD/TimeRenderer/SREClient/PlayerBodyEntity/LimitedInventoryScreen/InstinctRenderer/TaskBlockOverlayRenderer/noellesroles 的 TaskBlockOverlayRenderer、FixTaskRendererMixin 的两个内部类 targets）。`FixTaskRendererMixin`（22-29 行注释自述脆弱）与 `StarRailExpressTitleScreenMixin`（大量 @Shadow 私有字段）任一字段/类被上游重命名都会导致应用失败，`required:true` 下整个客户端 mixin 阶段失败 → 客户端启动崩溃。
- impact：SRE / noellesroles 升级重命名任一目标类或字段时，客户端启动直接崩溃，且因 `required:true` 无降级路径。PlayerBodyEntityMixin 单独注明 `required:false`（28-29）以缓解，但其余 11 个均未设 require=false，整体仍是强阻断耦合。
- direction：对脆弱目标（@Shadow 私有字段、内部类 targets）评估 `require=0`/`require=false` 降级；或对 mixins.json 改 `required:false`，将非关键 mixin 失败降为警告。
- severity：S1（API 泄露实现/强阻断耦合）

### S2-001 性能 · CustomTaskBlockRendererMixin 渲染热路径内多路方法调用与逐块 blockState 回退
- 文件：com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java
- 行：293-303（主循环）、438-449（旁观循环）
- evidence：两个渲染循环对 `CustomTaskBlockCache.keySet()` 遍历，每位置调用 `get(pos)`+`getBlockAt(pos)`，缓存未命中时 `level.getBlockState(pos).getBlock()`。`keySet()` 每帧迭代全部缓存位置，且常量透视分支（352）在生存路径中也会再迭代一次 keySet（renderConstantOverlaysIfBlackout 在 250/264/232 多处调用）。
- impact：缓存方块数较多时，每帧多次遍历 keySet + 每位置 Block 查询；`renderConstantOverlaysIfBlackout` 在生存主流程中被调用两次（250 与 264 行各一次）重复迭代同一缓存。
- direction：合并常量透视与任务透视遍历为单次 keySet 迭代；缓存命中 Block 缺失率评估是否仍需 getBlockState 回退。
- severity：S2

### S2-002 死逻辑/标识 · blockTypeId==12 守卫与 <12 守卫等价冗余
- 文件：com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java
- 行：251-253、270-272
- evidence：`if (blockTypeId < 12) return; if (blockTypeId == 12) return;` 两处出现（生存分支 251-253、多人分支 270-272）。`< 12` 与 `== 12` 串联等价于 `<= 12`，第二个守卫不增加新信息。
- impact：冗余分支降低可读性，维护者易误以为 12 有特殊语义（实际与 0-11 同处理）。
- direction：合并为 `if (blockTypeId <= 12) return;` 并补注释说明 12 排除原因。
- severity：S2

### S2-003 标识 · SubtitleHUDPrefixFixMixin 硬编码魔法数字 12/18
- 文件：com/habitrain/core/client/mixin/SubtitleHUDPrefixFixMixin.java
- 行：26-28
- evidence：`new SubtitleEntry(normalizedMain, subText, durationTicks, 12, 18, color, typewriter, screenPosition)` 中 12 与 18 为位置/偏移常量，无命名、无注释，直接硬编码入构造调用。
- impact：维护者无法判断 12/18 含义（屏幕位置 x/y 偏移？），与同方法已有参数 `screenPosition` 语义混淆；SRE 升级 SubtitleEntry 构造签名变更时无法快速定位。
- direction：提取为命名常量（如 `SUBTITLE_OFFSET_X/Y`）并注释含义。
- severity：S2

### S2-004 耦合 · InstinctColorHelper.getOverrideColors 直接返回可变内部 Map
- 文件：com/habitrain/core/client/InstinctColorHelper.java
- 行：24-26
- evidence：`getOverrideColors()` 直接 `return overrideColors;` 暴露内部可变 HashMap。`InstinctColorMixin`（66 行）在每帧 render 重定向内 `.get(type)` 读取该 Map，而 `rebuildOverrides()`（38）会 `clear()` 同一 Map。
- impact：API 泄露内部可变状态；调用方可在不知情下修改缓存。当前调用顺序（HEAD rebuild 后再 redirect）规避了并发，但返回可变 Map 是实现泄露，未来新增读取方可能踩到 rebuild 中途状态。
- direction：返回不可变视图/拷贝，或改提供 `getOverride(int type)` 查询方法。
- severity：S2

### S2-005 耦合 · HabiTrainCoreClient 上帝类职责密度
- 文件：com/habitrain/core/client/HabiTrainCoreClient.java
- 行：58-290（onInitializeClient 单方法）
- evidence：`onInitializeClient` 在单一方法内注册 9 个 S2C receiver、JOIN/DISCONNECT、tick 监测、save 回调、快捷键、HUD、4 个 blackout receiver、SRE 游戏结束事件、回放商店 bootstrap，并持有 4 个静态可变字段（lastSentShaderPack/monitoringShaderPack/shaderMonitorTick/cachedIrisClass）。光影反射检测（301-330）也内联于此类。
- impact：单一类横跨网络、配置同步、光影监测、HUD、按键、商店初始化多职责，静态可变状态网（5 个 static 字段跨 JOIN/DISCONNECT/tick 多处读写），难测试、难维护。
- direction：拆分为多个注册器（NetworkReceivers/ShaderMonitor/HudRegistrar 等），静态状态收敛到专门 holder 类。
- severity：S2

### S2-006 标识/死逻辑 · ActiveTaskCache 双写路径竞争
- 文件：com/habitrain/core/client/HabiTrainCoreClient.java + client/mixin/HudCustomTaskMixin.java
- 行：HabiTrainCoreClient 81-92；HudCustomTaskMixin 20-37
- evidence：活跃自定义任务有两处写入 `ActiveTaskCache`：①`ActiveTaskPayload` receiver（HabiTrainCoreClient 89 调 setActiveTask），②`HudCustomTaskMixin` 拦截 SRE 同步 NBT（30 调 setActiveTask）。两条路径无协调，且 HudCustomTaskMixin 遇到无 tasks 列表时早返回（23-24 行）不清缓存，而 ActiveTaskPayload 的 clear 分支（84-85）才清。
- impact：同步 NBT 部分同步（无 tasks 键）时缓存保留旧值；两条写入顺序/时序未定义，极端情况下 stale activeTaskFullId 导致渲染错误方块描边。
- direction：明确单一权威写入源；部分同步时是否清缓存语义统一。
- severity：S2

### S3-001 标识 · StarRailExpressTitleScreenMixin 原始类型 List 字段
- 文件：com/habitrain/core/client/mixin/StarRailExpressTitleScreenMixin.java
- 行：39
- evidence：`@Shadow private List menuEntries;` 使用无泛型原始 `List`，且注释写 `List<MenuEntry>` 说明语义但未在类型上表达。
- impact：失去类型安全，下游 `menuEntries.get(i)` 返回 Object 需反射操作（setEntryPos 用反射 setInt），与"已用 @Shadow 访问字段"风格不一致。
- direction：若 SRE 上游字段有泛型则对齐；否则维持但补强注释。
- severity：S3

### S3-002 标识 · BlackoutKeyHandler.getOpenVoteKey 返回未初始化时 null
- 文件：com/habitrain/core/client/BlackoutKeyHandler.java
- 行：15-19
- evidence：`openVoteKey` 为静态字段初始为 null，`getOpenVoteKey()` 直接返回。`BlackoutHudOverlay`（116 行）在渲染期调用该方法；若 `register()` 尚未执行则返回 null，调用方需自行判空。
- impact：调用顺序假设隐式（register 必须先于 HUD 使用），缺 null 防御可能 NPE，但实际初始化时序保证 register 先于 render。
- direction：在 getOpenVoteKey 文档注明前置条件，或返回不可变哨兵。
- severity：S3

### S3-003 性能 · detectCurrentShaderPack 每 30 秒反射 getMethod 未缓存
- 文件：com/habitrain/core/client/HabiTrainCoreClient.java
- 行：301-330
- evidence：`cachedIrisClass` 缓存了 Class，但每次调用仍 `irisClass.getMethod("getIrisConfig")` / `irisConfig.getClass().getMethod("areShadersEnabled")` / `getMethod("getShaderPackName")` 三次反射查找。频率每 600 tick（30 秒）一次。
- impact：非热路径，30 秒一次反射查找开销极小，属轻微。
- direction：可缓存 Method 对象；收益有限，低优先。
- severity：S3