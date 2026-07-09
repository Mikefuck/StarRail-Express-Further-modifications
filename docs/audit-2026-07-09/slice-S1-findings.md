# 切片 S1 审计留档 — 根包 + API 面

审计日期：2026-07-09
审计员：独立审计（从零读源码，未参考仓库内任何既有报告/计划）
范围：`com.habitrain.core` 根包 4 个类 + `com.habitrain.core.api` 9 个类，共 13 个文件。

## 文件覆盖确认表

| # | 文件（绝对路径） | 已读 | 行数 |
|---|---|---|---|
| 1 | `src/main/java/com/habitrain/core/HabiTrainCore.java` | ✅ | 442 |
| 2 | `src/main/java/com/habitrain/core/ModTickHandler.java` | ✅ | 53 |
| 3 | `src/main/java/com/habitrain/core/BuiltinTaskRegistrar.java` | ✅ | 268 |
| 4 | `src/main/java/com/habitrain/core/LootHelper.java` | ✅ | 96 |
| 5 | `src/main/java/com/habitrain/core/api/GameMode.java` | ✅ | 84 |
| 6 | `src/main/java/com/habitrain/core/api/GameModeLifecycle.java` | ✅ | 17 |
| 7 | `src/main/java/com/habitrain/core/api/GameModeRegistry.java` | ✅ | 149 |
| 8 | `src/main/java/com/habitrain/core/api/ItemReclaimHelper.java` | ✅ | 97 |
| 9 | `src/main/java/com/habitrain/core/api/TaskCategory.java` | ✅ | 42 |
| 10 | `src/main/java/com/habitrain/core/api/TaskDefinition.java` | ✅ | 222 |
| 11 | `src/main/java/com/habitrain/core/api/TaskInstance.java` | ✅ | 142 |
| 12 | `src/main/java/com/habitrain/core/api/TaskRegistry.java` | ✅ | 56 |
| 13 | `src/main/java/com/habitrain/core/api/WinResult.java` | ✅ | 37 |

辅助确认：跨包调用关系用 Grep 全仓验证（api 包未反向 import 实现包，API 边界良好；GameModeLifecycle/grantedItems/getElapsedTicks/getCustomTaskId 等无调用方）。

## 维度说明

四维审查：性能 / 死逻辑死代码 / 标识不清 / 耦合架构。

## 发现汇总

共 9 条：S1 ×2，S2 ×4，S3 ×3，S0 ×0。

| ID | 文件 | 维度 | 严重度 | 概述 |
|---|---|---|---|---|
| S1-001 | ModTickHandler.java | 死逻辑 | S1 | anyGameActive 与 hasActiveGame 双布尔始终同值，冗余变量 |
| S1-002 | api/GameModeLifecycle.java | 死逻辑 | S1 | GameModeLifecycle 枚举全仓无任何引用，死类型 |
| S1-003 | api/TaskInstance.java | 死逻辑 | S2 | grantedItems/addGrantedItem/getGrantedItems 写入从不被读取，回收走 NBT 标签 |
| S1-004 | api/TaskInstance.java | 死逻辑 | S2 | getElapsedTicks()/getCustomTaskId() 无调用方 |
| S1-005 | HabiTrainCore.java | 死逻辑 | S2 | 两处空 catch 吞异常（shop.balance、conn.setGroup） |
| S1-006 | api/GameModeRegistry.java | 性能 | S2 | getActiveForLevel fallback 每次新建流遍历全部注册模式 |
| S1-007 | BuiltinTaskRegistrar.java | 性能 | S1 | look_my_eyes onTick 每tick AABB+getEntitiesOfClass 实体扫描与对象分配 |
| S1-008 | LootHelper.java | 标识 | S3 | roleType 魔法数字 4/5、双节棍冷却 1000/200 未命名常量化 |
| S1-009 | HabiTrainCore.java | 耦合 | S3 | 主入口类 442 行职责密度过高，配置/网络/命令/生命周期/语音/槟榔/音效全集中 |

## 发现详情

### S1-001  ModTickHandler.java — 冗余双布尔
- 行号：26-35, 44
- 证据：`tickMoreMods` 内 `boolean anyGameActive=false; boolean hasActiveGame=false;` 在同一 `if (BetelTickEngine.isGameActive(world))` 分支里同时置 `anyGameActive=true; hasActiveGame=true;`，二者值恒等；`anyGameActive` 喂给 `GameLifecycleHandler.tickGameEndCheck`，`hasActiveGame` 用于 line 44 提前 return。
- 影响：两个变量语义完全重叠，后续维护若只改一个会引入隐性不一致；属确定性冗余分支。
- 方向：合并为单一布尔，保留语义清晰命名。
- 严重度：S1（确定性冗余，热路径每 tick 执行）。

### S1-002  api/GameModeLifecycle.java — 整个枚举无引用
- 行号：7-17
- 证据：全仓 Grep `GameModeLifecycle` 仅命中本文件定义；`PRE_START/START/TICK/.../CLEANUP` 无任何 switch/引用。GameMode 接口直接用 default 方法钩子，本枚举未被调度器使用。
- 影响：API 包导出公开类型，DLC 可见却永不被框架调用，造成“框架内部调度”的误导性 API 文档。
- 方向：删除或改为包私有内部用；若保留需在文档说明仅作枚举占位。
- 严重度：S1（API 面死类型，DLC 误用风险）。

### S1-003  api/TaskInstance.java — grantedItems 写而不读
- 行号：34, 49-53
- 证据：`grantedItems` 列表经 `addGrantedItem` 写入（BuiltinTaskRegistrar:175、BlackoutSearchBackpackTask:57 调用），但全仓 Grep `getGrantedItems()` 仅命中定义处；ItemReclaimHelper.reclaim 实际靠 NBT `habitrain_grant` 标签扫描背包，不读该列表。
- 影响：维护者会以为回收依赖此清单；实际为死数据，徒增 TaskInstance 内存与 copy 开销（addGrantedItem 还做 stack.copy()）。
- 方向：移除 grantedItems 链路或让回收真正消费它，二选一。
- 严重度：S2（死数据 + 每 onComplete 一次 copy 分配）。

### S1-004  api/TaskInstance.java — getElapsedTicks / getCustomTaskId 无调用
- 行号：44, 115
- 证据：全仓 Grep `getElapsedTicks` 仅定义行；`getCustomTaskId()` 仅定义行与 SRETrainTaskWrapper 覆盖行（覆盖里用的是 `instance.getFullId()` 而非 `instance.getCustomTaskId()`）。
- 影响：公开 API 上暴露无消费方方法；elapsedTicks 字段仍被 tick 累加与 toNbt 序列化，但 getter 无人读，属半死代码。
- 方向：评估是否删除 getter，或澄清调用方。
- 严重度：S2。

### S1-005  HabiTrainCore.java — 空 catch 吞异常
- 行号：331-334, 397, 403
- 证据：
  - L331-334：`try { var shop = SREPlayerShopComponent.KEY.get(player); if (shop!=null) balance=shop.balance; } catch (Exception ignored) {}` — shop 取余额失败静默置 0。
  - L394-397 与 L399-403：`conn.setGroup(tempGroup)` 两处 `catch (Exception ignored) {}`。
- 影响：组件缺失/连接异常被吞，电话 GUI 余额显示 0 误导玩家，临时群组静默失败计数不符；问题难定位。
- 方向：至少 log.warn 记录，非预期异常不应静默忽略。
- 严重度：S2。

### S1-006  api/GameModeRegistry.java — fallback 流式遍历
- 行号：126-134
- 证据：`getActiveForLevel` 在 `ACTIVE_MODES` 未命中时走 `REGISTRY.values().stream().filter(m -> m.isActive(level)).findFirst()`，每次调用新建流并调用所有注册模式 `isActive(level)`。
- 影响：事件路径（onPlayerJoin、onTaskComplete、UseBlock、death 等）每次调用都分配流对象并触发各模式 isActive 实现；模式数与调用频率增长时累计开销。
- 方向：缓存“被动激活”结果或限定 fallback 只在显式标记 passive 的模式上检查。
- 严重度：S2（非每 tick，但高频事件路径）。

### S1-007  BuiltinTaskRegistrar.java — look_my_eyes onTick 实体扫描
- 行号：194-233
- 证据：look_my_eyes 任务 onTick 每服务端 tick：`new AABB(...)`、`serverLevel.getEntitiesOfClass(ServerPlayer.class, searchBox, p->...)`（一次区块实体查询+predicate），并对 nearby 逐个 `getEyePosition/normalize/dot`；searchBox 半径 3 格。
- 影响：当玩家被分配该任务时，每 tick 一次实体查询 + 多个 Vec3 分配；任务持续 60 tick 上限内持续开销；多玩家并发分配时叠加。
- 方向：按 tick 节流（如每 5 tick 检测一次）或用更轻量的距离/朝向初筛减少 getEntitiesOfClass 调用。
- 严重度：S1（每 tick 实体查询+分配，热路径）。

### S1-008  LootHelper.java — roleType 魔法数字
- 行号：31, 48, 77
- 证据：`if (roleType == 4)` / `else if (roleType == 5)` 直接比较裸数字；双节棍冷却 `(roleType==4)?1000:200` 亦为裸字面量。无常量命名注释 4/5 各代表什么角色。
- 影响：角色码语义不可读，跨文件改动易错；与 BlackoutRoleManager 等处角色枚举无对齐关系可见。
- 方向：用命名常量或枚举替换 4/5 与冷却阈值。
- 严重度：S3。

### S1-009  HabiTrainCore.java — 主入口类职责密度
- 行号：66-442（整类）
- 证据：单类 442 行内承担：配置加载、3 个 GameMode 注册、16 个网络包注册、命令注册（/instantgroup、/habi_api）、6 个生命周期事件回调、4 个 C2S payload 接收器、音效注册、槟榔系统初始化、内置任务/停电任务注册触发。onInitialize 体内直接散布 20+ 个全限定名静态调用。
- 影响：单一职责违反；任一子系统改动都改本类；测试与替换困难；C2S 接收器逻辑（聘请警察、投票）内联在主类，耦合 BlackoutPoliceHireService/ExileVoteManager/SREPlayerShopComponent 等多个实现包。
- 方向：按子系统拆出 NetworkHandler/CommandRegistrar/LifecycleWiring 等协作类，主类仅做编排。
- 严重度：S3（架构可维护性，非功能 bug）。

## 未发现问题的维度说明

- API 边界（专属检查点“api 包被实现包反向 import”）：已验证 `api` 包下 9 个文件均无 `import com.habitrain.core.task/game/betel/network/...`，API 层不反向依赖实现包。边界良好，无 finding。
- TaskDefinition vs TaskInstance 命名语义：定义/实例区分清晰，无明显歧义。
- BuiltinTaskRegistrar 一次性加载：`register()` 在 onInitialize 调用一次，cat 方块解析有懒缓存，无重复全量加载问题。
- LootHelper 辅助方法：仅一个公共方法 `giveRandomBackpackItem`，有真实调用方（BuiltinTaskRegistrar、BlackoutSearchBackpackTask），无未调用辅助方法。