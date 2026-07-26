# API 运行契约修复设计

日期：2026-07-26  
项目：HabiTrain Core（Fabric 1.21.1）  
范围：方案 A——在保持公开 API 签名兼容的前提下，使已公开契约与实际运行行为一致

## 1. 目标

本次修复解决 API 参考手册审核中确认的主要运行问题：

1. `GameMode` 已公开的胜利检查和任务钩子没有被通用引擎派发。
2. `filterAvailableTasks` 的玩家相关结果被不含玩家维度的任务池缓存复用。
3. `TaskInstance` 在首次 tick 前没有任务归属玩家，导致 `onAssign` 中的进度更新缺少玩家上下文。
4. `OptionVoteApi.cast` 与 `ModeMapVoteApi.cancel` 的布尔返回值不能表示操作是否成功。
5. `WinResult` 和 `VoteResult` 只包装原集合，没有创建防御性快照。
6. API 参考手册把尚未接入引擎的元数据字段描述成了已生效能力。

## 2. 兼容边界

- 不修改 `GameMode`、`TaskDefinition`、`TaskInstance`、投票 API 的现有公开方法签名。
- 不自动实现 `canRepeat`、`shareProgress`、`customCategory` 或 `TimeImpact` 的新玩法语义。
- 不修改停电模式的任务轮换、奖励、时间增减规则和原版 SRE 任务节奏。
- 不修改角色覆盖系统及其当前未提交改动。
- 新接通的钩子按其现有接口语义执行；已有 DLC 无需改接口即可收到回调。

## 3. 游戏模式钩子派发

### 3.1 胜利检查

`GameModeRegistry.tickAll` 在显式启动模式的 `onTick(level)` 返回后执行
`checkWinCondition(level)`。若返回 `WinResult`，且该模式仍是当前维度的显式活动模式，
则调用 `GameModeRegistry.stop(level, result)`。

模式若在自己的 `onTick` 中已经停止或切换，注册表会再次核对当前活动模式，避免重复结束。
被 `isActive` 被动识别、但没有通过 `GameModeRegistry.start` 启动的模式不自动执行 tick 或胜利检查，
保持现有生命周期边界。

### 3.2 任务分配

`TaskDefinition.onAssign(player, instance)` 成为任务定义回调和游戏模式分配钩子的统一派发入口：

1. 先把玩家绑定到 `TaskInstance`。
2. 执行任务定义的 `onAssign`。
3. 当玩家是服务端玩家且所在维度存在活动模式时，执行
   `GameMode.onTaskAssign(player, instance)`。

停电电话商店中现有的手动 `BlackoutMode.onTaskAssign` 调用会移除，避免重复派发。
现有三个任务实例分配路径都已调用 `TaskDefinition.onAssign`，因此无需逐路径复制钩子代码。

### 3.3 tick、进度与完成检查

`TaskInstance.tick(player)` 的顺序为：

1. 绑定当前玩家并处理超时。
2. 执行 `TaskDefinition.onTick`。
3. 执行活动模式的 `GameMode.onTaskTick`。
4. 计算任务定义自己的完成结果。
5. 查询 `GameMode.overrideCompletionCheck`；非空结果覆盖第 4 步。
6. 完成时执行任务定义的 `onComplete`。

`TaskInstance.setProgress` 在任务定义的 `onProgressUpdate` 之后调用
`GameMode.onTaskProgressChange`。玩家上下文使用当前 tick 玩家，tick 外则使用分配时绑定的归属玩家。
从 NBT 恢复的实例在重新 tick 前没有归属玩家；恢复后的首次 tick 会重新绑定。

## 4. 玩家相关任务过滤

任务池缓存只保存由模式、地图和分类决定的静态候选集合，不再把
`GameMode.filterAvailableTasks` 的结果写入共享缓存。

每次 `TaskPoolBuilder.getPool` 返回前，若存在服务端玩家和活动模式，就对缓存候选集合执行一次
`filterAvailableTasks(candidates, player)`。这样：

- 不同玩家不会共享第一次构建缓存时的过滤结果。
- 过滤器可以查看完整候选列表，而不是被逐任务传入单元素列表。
- `TaskDefinition.canAssign(player)` 继续在缓存外逐次执行，负责实时的单任务玩家资格判断。

模式过滤器不得返回 `null`；若第三方实现返回 `null`，引擎按空列表处理并记录警告，
避免任务生成路径出现空指针异常。

## 5. 投票 API 与值对象

### 5.1 通用投票

内部 `OptionVoteManager.cast` 改为返回布尔值：

- 当前投票存在、投票 ID 匹配，且选项有效或明确弃票时返回 `true`。
- 无活动投票、玩家/投票 ID 无效或选项不存在时返回 `false`。

`OptionVoteApi.cast` 直接返回该结果，不再用“投票是否仍活动”冒充投票成功状态。

### 5.2 模式/地图投票

`ModeMapVoteOrchestrator.cancel` 仅在模式/地图投票会话确实运行时取消其底层投票并返回 `true`；
没有会话时返回 `false`，且不得误取消其他通用投票。`ModeMapVoteApi.cancel` 透传该结果。

`getSnapshot` 保持现有行为：非空维度始终返回快照，空闲时 phase 为 `IDLE`；
仅传入空维度时返回 `Optional.empty()`。

### 5.3 结果快照

`WinResult` 使用 `List.copyOf`，`VoteResult` 使用 `Map.copyOf` 创建防御性快照。
构造完成后修改调用方原始集合不会影响结果对象。空集合与 `null` 输入继续保持现有兼容行为。

## 6. 暂不实现的字段

以下字段继续作为声明或元数据保留，手册会明确当前引擎不自动执行其语义：

- `TaskDefinition.canRepeat`
- `TaskDefinition.shareProgress`
- `TaskDefinition.customCategory`
- `TaskDefinition.tags`
- `TaskDefinition.TimeImpact` 的实际时间修改

自定义分类参与任务池时仍使用 `category(customCategory)`；每玩家动态资格仍使用
`canAssign(player)`；共享进度和时间效果由任务回调或模式逻辑显式实现。

## 7. 错误处理

- 保持任务定义回调和游戏模式钩子现有的异常传播方式，不静默吞掉第三方逻辑错误。
- 胜利检查仅在 `onTick` 正常返回且模式仍活动时执行。
- 玩家过滤器返回 `null` 时降级为空候选并记录模式 ID，防止服务器 tick 崩溃。
- 模式/地图投票取消不会影响不属于该编排器的投票。

## 8. 测试与验证

1. 为结果对象补充防御性快照测试。
2. 为可独立测试的投票返回值与过滤辅助逻辑补充单元测试；需要 Minecraft 运行对象的路径以
   编译验证和静态调用链检查为主。
3. 使用 `rg -a` 确认所有公开任务钩子都有通用调用方，且停电商店没有重复分配钩子。
4. 检查 `API参考手册.md` 的代码围栏、引用文件和公开类型覆盖。
5. 运行 `./gradlew test --rerun-tasks`。
6. 运行 `./gradlew build`，并确认生成 JAR 已复制到 `D:\Backup\mc mod\临时`。

## 9. 文档同步

更新 `docs/API参考手册.md`：

- 描述新接通的 `GameMode` 钩子、顺序和显式模式限制。
- 修正玩家过滤缓存、任务归属玩家、投票返回值和集合快照说明。
- 明确元数据字段与 `TimeImpact` 不会自动产生玩法效果。
- 补充 `SpawnInfoPatch.MutableSpawnInfoPatch` 完整字段和其他已确认的参考缺口。

