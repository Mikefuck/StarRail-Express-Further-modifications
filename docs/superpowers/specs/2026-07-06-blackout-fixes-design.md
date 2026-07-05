# 停电模式修复与增强 — 设计文档

日期：2026-07-06
状态：待用户审核

## 概述

本次修改针对哈比列车核心 mod 的停电模式，解决用户测试中发现的 5 个问题：

1. 草方块和 `trainmurdermystery:camera` 的穿墙透视仍存在，需移除
2. 任务完成时的弹窗提示过多，需移除非关键任务的完成弹窗；同时移除非关键任务的供电时间奖励
3. 槟榔树叶方块（`betel-nut-mod:betel_palm_leaves`）在停电模式任务中透视失效
4. 停电模式专属任务（`blackout_` 前缀）刷新概率过低
5. 停电模式吃饭/喝水任务无法完成

## 第1节：移除草方块和 camera 透视

### 1.1 草方块（typeId=12）

草方块透视来自本 mod 自定义任务 `test_grass`（`HabiTrainCore.java:347-389`），走 `CustomTaskBlockRendererMixin` 的自定义渲染路径（typeId≥12）。

**修改方案**：在 `CustomTaskBlockRendererMixin` 中跳过 typeId=12 的渲染：

- `habitrain$renderCustomTaskBlocks`（line 193，生存模式）：当 active task 的 `blockTypeId == 12` 时 early-return（line 218 之前增加判断）
- `renderAllCustomTaskBlocks`（line 269，旁观/创造模式）：在 line 283 的 `if (type >= 12)` 判断中追加 `&& type != 12` 条件

涉及文件：`src/main/java/com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java`

### 1.2 camera 方块（typeId=12，与物资箱共享）

`trainmurdermystery:camera` 通过 SRE 原版 `MapScanner`（`org/agmas/noellesroles/utils/MapScanner.java:130-132`）扫描，因 `CameraBlock` 实现了 `TaskInstinctShowableInterface` 但未覆盖 `taskInstinctId()`，使用接口默认值 12，与 `SupplyCrateBlock` 共享 typeId 12。渲染发生在 SRE 原版 `TaskBlockOverlayRenderer.render` 的 `case 12` 分支（line 403-410），**不在**本 mod 的 `CustomTaskBlockRendererMixin` 处理范围内。

不能简单禁用 typeId 12 的渲染（会误伤物资箱）。方案：新建客户端 Mixin 拦截 `case 12` 分支的 `renderBlockOverlay` 调用，在调用前检查 pos 处方块是否为 `CameraBlock`，若是则跳过。

**新建文件**：`src/main/java/com/habitrain/core/client/mixin/CameraBlockOverlayMixin.java`

- Mixin 目标：`org.agmas.noellesroles.client.TaskBlockOverlayRenderer`
- 注入方法：`render(WorldRenderContext)`（static 方法）
- 策略：`@Redirect` 拦截 `case 12` 分支中对 `renderBlockOverlay` 的调用。由于 `renderBlockOverlay` 是同文件内被多个 case 复用的 static 方法，`@Redirect` 需要精确定位到 `case 12` 的调用点。Mixin 的 `@Redirect` 可通过方法签名匹配，但因多个 case 调用同一方法签名，需配合 `@At` 的 `ordinal` 参数或 `slice` 限定到 `case 12` 分支。
- **替代策略（更可靠）**：若 `@Redirect` 精确定位困难，改用 `@Inject` 注入 `render` 方法，在 `case 12` 渲染前通过 `@At("INVOKE")` + `ordinal` 定位，或在 `render` 方法 TAIL 前提前清除 `NoellesrolesClient.taskBlocks` 中 `CameraBlock` 位置的条目。但提前清除会影响其他 case。
- **最终采用方案**：`@Redirect` 拦截 `renderBlockOverlay`，在 redirect 方法中检查 `pos` 处方块是否为 `CameraBlock`，若是则直接 return（不渲染），否则调用原方法。由于所有 case 的 `renderBlockOverlay` 调用都会被 redirect，需在 redirect 内通过当前 typeId 判断是否为 case 12（可从 `NoellesrolesClient.taskBlocks.get(pos)` 获取 typeId 验证是否为 12），只有 typeId=12 且方块为 CameraBlock 时跳过，其他 case 正常放行。

`CameraBlock` 类引用：`io.wifi.starrailexpress.content.block.CameraBlock`，在 SRE 的 `libs/` JAR 中，本 mod 已依赖 SRE，可直接 import。

### 1.3 影响范围

- 草方块透视：仅 `test_grass` 任务（MURDER 池），不影响其他任务
- camera 透视：仅 `CameraBlock`，不影响 `SupplyCrateBlock`（物资箱）的紫色透视
- 旁观/创造模式下也移除这两者的透视

## 第2节：移除任务完成弹窗 + 移除非关键任务时间奖励

### 2.1 需修改的7个非关键任务

| 任务文件 | fullId | 当前 onComplete 内容 |
|---|---|---|
| `BlackoutSearchBackpackTask.java` | `blackout_search_backpack` | `delayMaintenanceOrCountdown(15)` + `grantRewards` + `sendTop` |
| `BlackoutPetCatTask.java` | `blackout_pet_cat` | `delayMaintenanceOrCountdown(15)` + `grantRewards` + `sendTop` |
| `BlackoutBetelQuestTask.java` | `blackout_betel_quest` | `delayMaintenanceOrCountdown(15)` + `grantRewards` + `sendTop` |
| `BlackoutEatTask.java` | `blackout_eat` | `delayMaintenanceOrCountdown(10)` + `grantRewards` + `sendTop` |
| `BlackoutDrinkTask.java` | `blackout_drink` | `delayMaintenanceOrCountdown(10)` + `grantRewards` + `sendTop` |
| `BlackoutBeAloneTask.java` | `blackout_be_alone` | `delayMaintenanceOrCountdown(20)` + `grantRewards` + `sendTop` |
| `BlackoutLookMyEyesTask.java` | `blackout_look_my_eyes` | `delayMaintenanceOrCountdown(15)` + `grantRewards` + `sendTop` |

### 2.2 修改方式

对每个任务的 `onComplete` 回调：

- **删除** `BlackoutTimerSystem.delayMaintenanceOrCountdown(...)` 调用（移除时间奖励）
- **删除** `SubtitleNotifier.sendTop(...)` 完成消息调用（移除完成弹窗）
- **保留** `BlackoutTaskHelper.grantRewards(...)` 调用（保留金币+精神奖励）
- **保留** 其他逻辑（如 `BlackoutSearchBackpackTask` 的 `markCompleted`、`stopSearching`、`removeEffect`；`BlackoutBeAloneTask` 的 `tickCounters.remove`）

任务开始和进行中的 `SubtitleNotifier.sendTop` 调用（`onAssign` 中）**保留不动**。

### 2.3 保留的3个关键任务

- `MaintainPowerTask`（维持供电，+25s）：`onComplete` 原样保留
- `AddCoalTask`（添煤，-15s）：`onComplete` 原样保留
- `RepairWiringTask`（修理线路，无时间效果）：`onComplete` 原样保留

### 2.4 影响范围

- 7个非关键任务完成时不再增加供电时间，玩家仍获得50金币+0.5情绪奖励
- 任务完成时不再显示顶部字幕弹窗（如"翻找背包完成！供电时间增加15秒"）
- 任务开始和进行中的提示保留

## 第3节：槟榔树叶透视 — MapScanner 支持多 typeId

### 3.1 根因

`MapScannerMixin`（`src/main/java/com/habitrain/core/game/sre/mixin/MapScannerMixin.java:55-87`）使用 `Map<Block, Integer> blockToTypeId` 存储扫描结果，key 仅为 `Block`。当两个任务扫描同一方块时，后注册的覆盖先注册的：

- `BlackoutBetelQuestTask`（typeId=36，`HabiTrainCore.java:576` 注册）
- `BetelQuestDefinition`（typeId=14，`HabiTrainCore.java:702` 注册，后于36）

最终 `betel_palm_leaves → 14`，生存模式渲染器（`CustomTaskBlockRendererMixin:244-253`）用 `entry.getValue() == blockTypeId` 严格相等匹配，active 任务 typeId=36 与扫描值 14 不等 → 不渲染。

### 3.2 修复方案：自定义任务方块多 typeId 索引

为自定义任务方块（typeId≥12）建立独立的多 typeId 索引结构，与 SRE 的 `GameUtils.taskBlocks`（单 typeId，服务 SRE 原版 1-11 类型）解耦：

#### 3.2.1 新建 `CustomTaskBlockCache`

**新建文件**：`src/main/java/com/habitrain/core/game/sre/CustomTaskBlockCache.java`

```java
public class CustomTaskBlockCache {
    // 服务端持有：扫描后填充，同步前读取
    // 客户端持有：接收 payload 后填充，渲染时读取
    private static final Map<BlockPos, Set<Integer>> BLOCK_TYPE_IDS = new ConcurrentHashMap<>();
    
    public static void put(BlockPos pos, int typeId) { ... }  // 添加 typeId 到 pos 的集合
    public static Set<Integer> get(BlockPos pos) { ... }
    public static void clear() { ... }
    public static Map<BlockPos, Set<Integer>> snapshot() { ... }  // 用于网络同步
    public static void loadFromSnapshot(Map<BlockPos, Set<Integer>> data) { ... }
    public static boolean isEmpty() { ... }
    public static Set<BlockPos> keySet() { ... }
}
```

#### 3.2.2 修改 `MapScannerMixin`

- `blockToTypeId` 改为 `Map<Block, Set<Integer>>`
- Phase 1（line 55-87）：对每个任务的 scanBlocks/scanBlockIds，`blockToTypeId.computeIfAbsent(block, k -> new HashSet<>()).add(blockTypeId)`
- Phase 2（line 94-109）：
  - SRE 原版类型（1-11）：仍存入 `GameUtils.taskBlocks`（保持原版兼容）
  - 自定义类型（≥12）：存入 `CustomTaskBlockCache.put(pos, typeId)`
  - 判断方式：`Set<Integer> typeIds = blockToTypeId.get(block); if (typeIds != null) { for (int t : typeIds) { if (t < 12) GameUtils.taskBlocks.put(pos, t); else CustomTaskBlockCache.put(pos, t); } }`

注：当前代码中 `blockToTypeId` 只收集 typeId≥12（line 60 `if (blockTypeId < 12) continue;`），所以所有 typeIds 都 ≥12。

**决定**：自定义类型（≥12）**仅写入 `CustomTaskBlockCache`**，不再写入 `GameUtils.taskBlocks`。`GameUtils.taskBlocks` 保留给 SRE 原版 1-11 类型（由 SRE 原版扫描器填充，本 mod 不干预）。渲染器改读 `CustomTaskBlockCache`。这样彻底解耦，避免单值和多值索引混用造成歧义。

#### 3.2.3 新建 `CustomTaskBlockPayload`（S2C）

**新建文件**：`src/main/java/com/habitrain/core/network/CustomTaskBlockPayload.java`

- 实现 `CustomPacketPayload` 接口
- 携带 `Map<BlockPos, Set<Integer>>` 数据（序列化为 NBT 或紧凑格式）
- `StreamCodec` 编解码
- `register()` 方法注册 S2C 接收器

#### 3.2.4 同步时机

- **服务器启动/地图扫描后**：`MapScannerMixin.afterLoadOrScanAndSaveScannerArea` 执行完毕后，发送 `CustomTaskBlockPayload` 给所有在线玩家
- **玩家加入时**：在 `ServerPlayConnectionEvents.JOIN` 中发送（`HabiTrainCore.java:215-237`，与 `TaskConfigPayload.sendToPlayer(player)` 并列）

#### 3.2.5 修改 `CustomTaskBlockRendererMixin`

- 生存模式（line 244-253）：从 `NoellesrolesClient.taskBlocks` 改为 `CustomTaskBlockCache`，匹配改为 `set.contains(blockTypeId)`
- 旁观/创造模式（line 281-294）：从 `NoellesrolesClient.taskBlocks` 改为 `CustomTaskBlockCache`，对每个 pos 的 `Set<Integer>` 遍历，查找 `typeColors` 中存在的 typeId
- 保留 `TaskInstinctShowableInterface` 跳过逻辑

#### 3.2.6 客户端接收器

在 `HabiTrainCoreClient`（或 `CustomTaskBlockPayload.register()`）中注册客户端接收器，接收后调用 `CustomTaskBlockCache.loadFromSnapshot(data)`。

### 3.3 影响范围

- 槟榔树叶方块在停电模式（typeId=36）和普通模式（typeId=14）下都能正确透视
- 任何两个 DLC 任务扫描同一方块时不再互相覆盖
- SRE 原版 1-11 类型渲染不受影响（仍走 `GameUtils.taskBlocks`）

## 第4节：提高停电模式专属任务刷新概率

### 4.1 现状

所有 `blackout_` 前缀任务的 `weight` 默认为 1.0f（在各任务文件中硬编码）。在 `GenerateTaskMixin` 的加权随机选择中，DLC 任务权重 = `getEffectiveWeight(def) × autoBoost`，其中 `getEffectiveWeight` 优先使用 `TaskConfigEntry.refreshWeight`（若 ≥0），否则用 `TaskDefinition.getWeight()`（默认 1.0f）。

### 4.2 修改方案

将 `blackout_` 前缀任务的 `TaskDefinition.Builder.weight()` 从 1.0f 提高到 **3.0f**。

涉及文件（每个文件的 `.weight(1.0f)` 改为 `.weight(3.0f)`）：

- `BlackoutEatTask.java:25` → `.weight(3.0f)`
- `BlackoutDrinkTask.java:25` → `.weight(3.0f)`
- `BlackoutSearchBackpackTask.java:29` → `.weight(3.0f)`
- `BlackoutBetelQuestTask.java:26` → `.weight(3.0f)`
- `BlackoutPetCatTask.java:34` → `.weight(3.0f)`
- `BlackoutBeAloneTask.java:34` → `.weight(3.0f)`
- `BlackoutLookMyEyesTask.java:27` → `.weight(3.0f)`
- `AddCoalTask.java` → `.weight(3.0f)`
- `RepairWiringTask.java` → `.weight(3.0f)`
- `MaintainPowerTask.java` → `.weight(3.0f)`

不修改全局 `dlcProbabilityTarget`（避免影响非停电模式的 DLC 任务概率）。

注：坏任务（`SabotageWiringTask`、`FurnaceExplosionTask`）是否也提高？用户只说"停电模式专属任务"，坏任务也是停电专属。**本次也提高坏任务权重**，保持好人/坏人任务池内权重平衡。

- `SabotageWiringTask.java` → `.weight(3.0f)`
- `FurnaceExplosionTask.java` → `.weight(3.0f)`

### 4.3 影响范围

- 停电模式任务（good + bad 池）在加权随机中权重变为原来的 3 倍
- 不影响普通 SRE 模式（那些任务不在 MURDER 池，且 `BlackoutMode.filterAvailableTasks` 会过滤）
- 用户仍可通过 ModMenu 的 `refreshWeight` 字段进一步调整单个任务权重

## 第5节：修复吃饭/喝水任务无法完成

### 5.1 根因

用户报告"能正常吃但任务不完成"，排除了槟榔食物限制拦截。真正根因在检测机制本身：

**SRE 原版**（`PlayerEntityMixin.java:258-275`）：
- Mixin 注入 `Player.eat(Level, ItemStack, FoodProperties)` HEAD
- 在食物**真正被消耗**时直接 `eatTask.fulfilled = true`
- 多任务并存（`Map<Task, TrainTask>`），按 `Task.EAT` 枚举 key 必命中

**停电模式**（`BlackoutEatHandler.java`）：
- 用 `UseItemCallback`（右键按下时触发）+ `END_SERVER_TICK` 轮询 `isUsingItem()` 状态
- **单 active task 模型**：`TaskManager.getActiveTask(uuid)` 只返回一个任务，若当前 active 不是 `blackout_eat`，Handler 直接 return（line 66-69），`eatingTracked` 永不写入
- `isUsingItem()` 状态同步依赖客户端，时序脆弱

### 5.2 修复方案：改用 SRE 原版逻辑

新建服务端 Mixin 注入 `Player.eat()`，在食物真正被消耗时推进任务，不依赖 `getActiveTask` 返回唯一任务。

#### 5.2.1 新建 `BlackoutEatMixin`

**新建文件**：`src/main/java/com/habitrain/core/game/sre/mixin/BlackoutEatMixin.java`

- Mixin 目标：`net.minecraft.world.entity.player.Player`
- 注入方法：`eat(Level, ItemStack, FoodProperties)` HEAD
- 逻辑：
  ```java
  @Inject(method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/food/FoodProperties;)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"))
  private void habitrain$onEat(Level world, ItemStack stack, FoodProperties food, CallbackInfoReturnable<ItemStack> cir) {
      if (world.isClientSide()) return;
      if (!(this instanceof ServerPlayer serverPlayer)) return;
      // 吃饭任务：检查玩家是否有活跃的 blackout_eat 任务
      TaskInstance task = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
      if (task != null && "habitrain_core:blackout_eat".equals(task.getFullId())
              && !task.isFulfilled() && task.getProgress() < task.getMaxProgress()) {
          task.setProgress(task.getMaxProgress());
      }
      // 喝水任务：检查玩家是否有活跃的 blackout_drink 任务
      TaskInstance drinkTask = TaskManager.getInstance().getActiveTask(serverPlayer.getUUID());
      if (drinkTask != null && "habitrain_core:blackout_drink".equals(drinkTask.getFullId())
              && !drinkTask.isFulfilled() && drinkTask.getProgress() < drinkTask.getMaxProgress()) {
          // SRE 原版用 instanceof PotionItem || HoneyBottleItem 判定喝水
          if (stack.getItem() instanceof PotionItem || stack.getItem() instanceof HoneyBottleItem) {
              drinkTask.setProgress(drinkTask.getMaxProgress());
          }
      }
  }
  ```

注：`getActiveTask` 返回唯一任务，吃饭和喝水不会同时活跃。这里分两个 if 检查，实际只会命中一个。

#### 5.2.2 删除旧的 Handler 逻辑

- **删除** `BlackoutEatHandler.java` 中的 `UseItemCallback` 注册和 `END_SERVER_TICK` 轮询逻辑
- **删除** `BlackoutDrinkHandler.java` 中的 `UseItemCallback` 注册和 `END_SERVER_TICK` 轮询逻辑
- 保留 `clearState`/`clearAll` 方法（`onRemove` 仍调用）
- 在 `HabiTrainCore.java:572,574` 删除 `BlackoutEatHandler.register()` 和 `BlackoutDrinkHandler.register()` 调用（或保留 register 但让它成为空方法）

实际上 `BlackoutEatHandler.register()` 注册了 `UseItemCallback` 和 `END_SERVER_TICK`。若 Mixin 方案生效，这两个回调应删除以避免冗余。可将 `BlackoutEatHandler.register()` 改为空方法，或直接在 `HabiTrainCore` 中删除 `.register()` 调用行。`onRemove` 中的 `clearState` 调用可保留（清理空 map 无害）。

**推荐**：保留 `BlackoutEatHandler`/`BlackoutDrinkHandler` 文件但将 `register()` 改为空方法体（保留 clearState 供 onRemove 调用），避免删除文件导致 import 链断裂。

#### 5.2.3 喝水判定标准

采用 SRE 原版判定：`stack.getItem() instanceof PotionItem || stack.getItem() instanceof HoneyBottleItem`（`PlayerEntityMixin.java:267`）。

这比旧的硬编码白名单（POTION/MILK_BUCKET/HONEY_BOTTLE/GLASS_BOTTLE/SUSPICIOUS_STEW）更准确，与 SRE 原版行为一致。`SUSPICIOUS_STEW` 在 SRE 原版中走 `eatFood()`（吃饭）而非 `drinkCocktail()`（喝水），本 mod 也应如此。

#### 5.2.4 Mixin 配置

在 `src/main/resources/habitrain_core.mixins.json` 中注册 `BlackoutEatMixin`。

### 5.3 影响范围

- 吃饭任务：玩家吃任意有 FOOD 组件的物品时完成（与 SRE 原版一致）
- 喝水任务：玩家喝药水/蜂蜜瓶时完成（与 SRE 原版一致）
- 不再依赖 `isUsingItem()` 状态轮询，消除时序竞态
- 不再受单 active task 模型限制（Mixin 在 `Player.eat` 触发时检查当前 active task 即可，因吃饭和喝水不会同时活跃）
- 旧的 `UseItemCallback` + `END_SERVER_TICK` 逻辑移除，避免冗余

## 验证方式

由于本项目无测试，验证靠 `./gradlew build`（编译 + processResources）和游戏内运行。

构建命令：
```powershell
./gradlew build
```

游戏内验证项：
1. 草方块和 camera 不再有穿墙透视，物资箱紫色透视保留
2. 7个非关键任务完成时无顶部弹窗，供电时间不增加，金币/精神奖励正常
3. 槟榔树叶方块在停电模式任务中正确透视
4. 停电模式任务刷新更频繁
5. 吃饭/喝水任务能正常完成

## 文件变更清单

### 修改文件
1. `src/main/java/com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java` — 跳过 typeId=12 渲染
2. `src/main/java/com/habitrain/core/game/sre/mixin/MapScannerMixin.java` — `Map<Block, Set<Integer>>` + 写入 CustomTaskBlockCache
3. `src/main/java/com/habitrain/core/client/mixin/CustomTaskBlockRendererMixin.java` — 渲染器改读 CustomTaskBlockCache（同文件 #1）
4. `src/main/java/com/habitrain/core/game/blackout/task/BlackoutSearchBackpackTask.java` — 删除时间奖励和完成弹窗
5. `src/main/java/com/habitrain/core/game/blackout/task/BlackoutPetCatTask.java` — 同上
6. `src/main/java/com/habitrain/core/game/blackout/task/BlackoutBetelQuestTask.java` — 同上
7. `src/main/java/com/habitrain/core/game/blackout/task/BlackoutEatTask.java` — 同上 + weight 3.0
8. `src/main/java/com/habitrain/core/game/blackout/task/BlackoutDrinkTask.java` — 同上 + weight 3.0
9. `src/main/java/com/habitrain/core/game/blackout/task/BlackoutBeAloneTask.java` — 同上 + weight 3.0
10. `src/main/java/com/habitrain/core/game/blackout/task/BlackoutLookMyEyesTask.java` — 同上 + weight 3.0
11. `src/main/java/com/habitrain/core/game/blackout/task/AddCoalTask.java` — weight 3.0
12. `src/main/java/com/habitrain/core/game/blackout/task/RepairWiringTask.java` — weight 3.0
13. `src/main/java/com/habitrain/core/game/blackout/task/MaintainPowerTask.java` — weight 3.0
14. `src/main/java/com/habitrain/core/game/blackout/task/SabotageWiringTask.java` — weight 3.0
15. `src/main/java/com/habitrain/core/game/blackout/task/FurnaceExplosionTask.java` — weight 3.0
16. `src/main/java/com/habitrain/core/game/blackout/task/BlackoutEatHandler.java` — register() 改为空方法
17. `src/main/java/com/habitrain/core/game/blackout/task/BlackoutDrinkHandler.java` — register() 改为空方法
18. `src/main/java/com/habitrain/core/HabiTrainCore.java` — 发送 CustomTaskBlockPayload
19. `src/main/resources/habitrain_core.mixins.json` — 注册 BlackoutEatMixin 和 CameraBlockOverlayMixin

### 新建文件
1. `src/main/java/com/habitrain/core/client/mixin/CameraBlockOverlayMixin.java` — camera 透视移除
2. `src/main/java/com/habitrain/core/game/sre/CustomTaskBlockCache.java` — 多 typeId 索引
3. `src/main/java/com/habitrain/core/network/CustomTaskBlockPayload.java` — S2C 同步包
4. `src/main/java/com/habitrain/core/game/sre/mixin/BlackoutEatMixin.java` — 吃饭/喝水完成检测

### 删除文件
无