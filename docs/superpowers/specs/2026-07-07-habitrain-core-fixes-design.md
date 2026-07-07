# 哈比列车核心 11 项修复与增强 — 设计文档

日期：2026-07-07
状态：待用户审核

## 概述

本次修改针对哈比列车核心 mod（habitrain_core）的停电模式及任务系统，解决用户测试中发现的 11 个问题。
所有修改遵循 SRE 原版行为优先、性能优先、可维护性优先的原则。代码规范、加注释、考虑后期维护。

---

## 第1节：吃饭/喝水任务完成逻辑 + 任务点透视

### 1.1 问题

SRE 原版吃/喝任务完成依赖三个 mixin，本 mod 缺其中两个：
1. `canEat` 强制 true（满饥饿也能吃） — **缺失**，导致满饥饿时 `Player.eat()` 不触发
2. `Item.finishUsingItem` HEAD mixin（覆盖药水） — **缺失**，药水无 FOOD 组件走不到 `Player.eat()`，BlackoutEatMixin 的 PotionItem 分支是死代码
3. `Player.eat` HEAD mixin — 已有（`BlackoutEatMixin.java:17-47`），逻辑正确

任务点透视：SRE 原版 `MapScanner.java:108-125` 检查 `BeveragePlateBlockEntity.getStoredItems()` 内容决定 type 1/2，空盘子不标记。本 mod `MapScannerMixin.java:55-107` 只按方块类型匹配，空盘子也标记，且 food_platter 里放药水会被错误标成 type 39（吃饭）。

### 1.2 修改方案

#### A. 新增 canEat mixin
新建 `src/main/java/com/habitrain/core/game/sre/mixin/BlackoutCanEatMixin.java`
- 目标：`net.minecraft.world.entity.player.Player`
- 方法：`canEat(Z)Z`，HEAD inject cancellable
- 逻辑：若停电模式或 SRE 游戏激活（非 lobby），强制返回 true
- 参考 SRE `PlayerEntityMixin.java:252-259`

#### B. 新增 finishUsingItem mixin
新建 `src/main/java/com/habitrain/core/game/sre/mixin/BlackoutDrinkItemMixin.java`
- 目标：`net.minecraft.world.item.Item`
- 方法：`finishUsingItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;`，HEAD inject
- 逻辑：仅服务端、仅当玩家当前活跃任务为 `blackout_drink` 且未完成时：
  - CocktailItem / PotionItem / HoneyBottleItem → `task.setProgress(task.getMaxProgress())`
- 与 BlackoutEatMixin 的 Player.eat 路径互补：药水走 finishUsingItem，食物走 Player.eat
- 参考 SRE `FoodItemMixin.java:24-43`

#### C. 改 MapScannerMixin 内容检查
修改 `MapScannerMixin.java:55-107`
- 在 `blockToTypeIds` 构建完成后，扫描循环内对 `FoodPlatterBlock`（含 `DrinkTrayBlock` 子类）做特殊处理：
  - 取 `BeveragePlateBlockEntity.getStoredItems()`
  - 空列表 → 跳过（不加缓存）
  - 首个物品是 CocktailItem / PotionItem / HoneyBottleItem → 加 typeId 40（drink）
  - 首个物品有 `DataComponents.FOOD` → 加 typeId 39（eat）
  - 其它 → 跳过
- 其他方块类型保持原按 Block 匹配的逻辑
- 需 import `BeveragePlateBlockEntity`、`FoodPlatterBlock`、`CocktailItem`、`PotionItem`、`HoneyBottleItem`（来自 SRE / vanilla）

#### D. 重建扫描缓存时机
`MapScannerMixin` 当前在 `loadOrScanAndSaveScannerArea` 后一次性扫描并保存。盘子内容可能在游戏中被玩家改变（放食物/取食物）。
- 方案：在 `CustomTaskBlockCache` 增加 `invalidateFoodPlatter(pos)` 方法，盘子方块右键交互时调用（可由新 `FoodPlatterUseMixin` 监听 `Block.useItemOn` 触发，或简单地让 `MapScannerMixin` 在每局开始时重新扫描一遍盘子方块）
- 简化方案：每局开始（SRE `init()` 时）清空 `CustomTaskBlockCache` 中所有 typeId 39/40 的条目并重新扫描盘子方块。新增 `MapScannerMixin.rescanFoodPlatters(level)` 方法

### 1.3 验证

- 满饥饿吃食物 → blackout_eat 完成
- 喝药水/蜂蜜瓶/鸡尾酒 → blackout_drink 完成
- 空盘子不透视
- food_platter 放药水 → 标记为 drink（type 40）
- drink_tray 放食物 → 标记为 eat（type 39）

---

## 第2节：祷告任务接入

### 2.1 问题

`GenerateTaskMixin.addOriginalTasks`（line 181-240）只遍历 `Task.getAvailableTasksList()`（11 个非场景任务），漏掉 `Task.getSceneTasksList()`（7 个场景任务：BREATHE/LIGHT_STOVE/CLEAN_DUST/TRANSPORT/PRAY/PRUNE_BUSH/HARVEST_CROP）。`BUILTIN_SRE_TASK_IDS` 列表里有 `pray` 但只用于排除 DLC 池。

### 2.2 修改方案

修改 `GenerateTaskMixin.java`

#### A. Shadow getEnabledSceneTasks
```java
@Shadow(remap = false)
private Set<String> getEnabledSceneTasks() { throw new AssertionError(); }
```

#### B. addOriginalTasks 末尾追加场景任务循环
镜像 SRE 原版 `SREPlayerTaskComponent.java:439-456`：
- 遍历 `Task.getSceneTasksList()`
- 跳过已分配的（`this.tasks.containsKey(task)`）
- 跳过地图未启用的（`!enabledSceneTasks.contains(task.name())`）
- 跳过被禁用的（`disabledTasks.contains` / `mgr.isOriginalTaskDisabled`）
- 复用现有 mood 权重逻辑（与上方非场景任务一致）
- 加入 `entries` + `total`

#### C. 解决 PRAY 槽位冲突
当前 `GenerateTaskMixin.java:561-568` 用 `Task.PRAY` 作为杀手假任务包装槽位（因为 PRAY 假定永远不被派发）。修复后 PRAY 会被派发，冲突。
- 方案：把杀手假任务槽位从 `Task.PRAY` 改为 `Task.MANIC`（如果存在）或新增一个空枚举槽位 `Task.FAKE_TASK`（若 SRE 允许扩展枚举）
- 备选：在 blackout 模式下跳过场景任务派发（保持 PRAY 为杀手槽位），但用户明确希望接入祷告，所以采用改槽位方案
- 需要确认 SRE `Task` 枚举是否有可用的空槽位。若无，可继续用 PRAY 但在 addOriginalTasks 场景任务循环中跳过 PRAY（只派发其它 6 个场景任务），并通知用户

### 2.3 祷告完成条件（参考）

- 目标方块：`StatueBlock`（SRE ModSceneBlocks.STATUE）
- 动作：6 格内注视雕像
- 持续：100 tick（5 秒）不中断
- 由 SRE `SceneTaskManager.tickPray` 驱动，无需本 mod 额外实现完成检测

### 2.4 验证

- 地图启用 PRAY 场景任务且有雕像 → 玩家可被派发祷告任务
- 注视雕像 5 秒 → 完成
- 杀手假任务文本正常（不显示"祷告..."）

---

## 第3节：杀手透视（确认已用原版 + 待根因验证）

### 3.1 现状

已确认：habitrain_core **没有独立的实体描边/ESP 实现**。杀手玩家完全走 SRE 原版 `InstinctRenderer` + `OnGetInstinctHighlight` 事件链 + `MinecraftClientMixin.shouldEntityAppearGlowing`。本 mod 仅对**警长**做了两处关闭 mixin（`InstinctKillerTeamMixin`、`InstinctSheriffGateMixin`），对杀手玩家零干预。

### 3.2 bug 可能根因（待用户游戏内确认）

1. **身份欺诈**（最可能）：被警长投票选过的杀手显示警察角色（`canUseKiller=false`），SRE 原版 fallback `InstinctRenderer.java:1100-1128` 看 `canUseKiller()` 判队友 → 返回绿色。`BlackoutMode.java:475` 的"身份欺诈"机制骗过 SRE 原版。
2. **缓存陈旧**：`SREClient.cachedHighLightMap` 按键切换刷新，游戏开始后未刷新可能保留旧值
3. 其它未明场景

### 3.3 修改方案（视根因而定）

#### 情况 A：身份欺诈（确认后执行）
新建 `src/main/java/com/habitrain/core/client/BlackoutInstinctOverride.java`
- 在 `HabiTrainCoreClient.onInitializeClient` 早期注册 `OnGetInstinctHighlight.EVENT` 监听器
- 仅停电模式激活时生效（`BlackoutMode.isActive(level)`）
- 用 `BlackoutRoleManager.getFaction(selfPlayer)` 判定自己阵营
- 用 `BlackoutRoleManager.getFaction(level, targetPlayer)` 判定目标阵营（不用 `canUseKiller`）
- 自己 BAD 且目标 BAD → 返回 `TMMRoles.KILLER.color()`（暗红 0xC13838）
- 返回 -1（不干预）给其它情况，让 SRE 原版处理
- 同步注册 `OnRenderRoleName.RENDER_PLAYER_ROLE` / `RENDER_PLAYER_COHORT` 让"同伙"红字按停电阵营判定，远处也能识别队友
- **不删** `InstinctKillerTeamMixin` / `InstinctSheriffGateMixin`（警长关闭逻辑保留）

#### 情况 B/C：根因未确认前不动
保留现状，等用户游戏内观察后再定。

### 3.4 验证

- 普通杀手玩家看普通杀手队友 → 红框（SRE 原版，本就有）
- 被票选伪装成警察的杀手 → 红框（仅情况 A 修复后）
- 警长 → 无透视（保留现有 mixin）

---

## 第4节：性能优化

### 4.1 热点

| # | 文件 | 频率 | 优化 |
|---|---|---|---|
| 1 | `CustomTaskBlockRendererMixin.java:198` | 每帧 | 缓存 isGameRunning + 缓存 BlockState 到 CustomTaskBlockCache + 节流渲染 |
| 2 | `MapScannerMixin.java:91-107` | 扫描时 | 分块扫描（每 tick 50k 方块） |
| 3 | `FixTaskRendererMixin.java:35` | 每 tick | 缓存 isKiller/role 至游戏开始/角色变更 |
| 4 | `InstinctColorMixin.java:35` | 每帧每方块 | 缓存 block-type-per-position |
| 5 | `HabiTrainCoreClient.java:270` | 每 20 tick | 光影检测降频到 100 tick |

### 4.2 修改方案

#### A. CustomTaskBlockCache 存 Block
修改 `CustomTaskBlockCache.java`：
- value 改为 `Map<Integer, Block>`（typeId → Block）或新增并行 `Map<BlockPos, Block>` 缓存
- `MapScannerMixin` 扫描时一次性记录 `blockState.getBlock()` 存入
- `CustomTaskBlockRendererMixin` 渲染时不重查 `level.getBlockState(pos)`，直接读缓存
- 新增 `invalidate(pos)` 用于盘子内容变更时清缓存

#### B. isGameRunning 缓存
新增 `ClientGameStateCache`（volatile 字段 + TTL）：
- `isGameRunning` 缓存 10 tick（0.5s）
- `isBlackoutActive` 同步缓存
- 在 SRE `OnGameStartedClient` / `OnGameFinishedClient` 事件强制刷新

#### C. 节流渲染
`CustomTaskBlockRendererMixin`：
- 新增 `frameCounter`，每 2 帧渲染一次（描边是静态位置，无需每帧）
- 或仅在摄像机移动超过阈值时重渲染

#### D. MapScanner 分块
`MapScannerMixin.afterLoadOrScanAndSaveScannerArea`：
- 拆为迭代器，每 tick 处理 50000 方块
- 用 `ServerTickEvents.END_SERVER_TICK` 推进扫描进度
- 扫描期间 `CustomTaskBlockCache` 标记 `scanning=true`，渲染器跳过

#### E. FixTaskRendererMixin 缓存
- `isKiller` / `getRole(self)` 在游戏开始/角色变更时缓存到 `ClientGameStateCache`
- tick 内直接读缓存

#### F. 光影检测降频
`HabiTrainCoreClient.java:148`：
- `shaderMonitorTick % 20` 改 `% 100`（5 秒一次）

#### G. mixin required:false
`habitrain_core.mixins.json` / `habitrain_core.client.mixins.json`：
- 字符串目标 SRE 内部类的 mixin 设 `required: false`（FixTaskRendererMixin 等）
- 防 SRE 重命名导致启动崩溃

### 4.3 验证

- `/spark profile` 对比修改前后帧时间
- idle 状态（无游戏）low 帧提升
- 游戏中帧率稳定

---

## 第5节：崩溃（SRE 原版 NPE）

### 5.1 现状

崩溃堆栈：
```
NPE: MinecraftServer.method_3760() return value of PlayerBodyEntity.method_5682() is null
  at PlayerBodyEntity.method_5797(PlayerBodyEntity.java:96)
  at PlayerBodyEntity.method_5476(PlayerBodyEntity.java:80)
  at class_761.handler$beo000$entityculling$renderEntity
```
**SRE 原版 `PlayerBodyEntity.java:96` NPE**，`getServer()` 返回 null（客户端级别的玩家尸体无服务端引用）却仍调用其方法。调用链经过 entityculling 的 renderEntity hook。**不是 habitrain_core 引起的。**

### 5.2 修改方案

#### A. 治标（可选）
新建 `src/main/java/com/habitrain/core/client/mixin/PlayerBodyEntityMixin.java`
- 目标：`io.wifi.starrailexpress.content.entity.PlayerBodyEntity`
- 方法：`method_5797`（映射名 `tick` 或具体方法），HEAD inject
- 若 `getServer() == null` 直接 return
- `required: false` 防 SRE 改名

#### B. 治本
- 反馈给 SRE 作者修 `PlayerBodyEntity.java:96` 加 null 检查
- 重建 SRE jar 放入 `libs/`（SRE 源码 2026-07-07 被改动，可能含修复）

### 5.3 验证

- 玩家死亡后尸体存在时不崩溃
- entityculling 开启时不崩溃

---

## 第6节：供电池任务同步完成（不做无差别替换）

### 6.1 问题

`RestorePowerTask.forceAssignMaintainPowerToAllGood`（line 85-122）在任一好人完成 `restore_power` 时**无差别移除所有 GOOD 玩家的当前任务**（line 94 `mgr.removeActiveTask(uuid)`）并分配 `maintain_power`。玩家正在做 add_coal 中途任务消失。

`BlackoutMode.forceAssignRestorePowerToAllGood`（line 303-339）在永久停电开始时同样无差别覆盖。

### 6.2 用户需求

> 只同步完成，如果当前玩家不是这个任务则不做变化。

即：
- 玩家 A 完成 `add_coal` → 同步完成其它 GOOD 玩家中**正在做 `add_coal` 且未完成**的（fire onComplete 给奖励 + 给时间 + 标记完成）
- 正在做 `repair_wiring` / `maintain_power` / 其它任务的玩家 → **完全不动**
- 完成后让自然刷新机制派下一个任务（不强制派 maintain_power）

### 6.3 修改方案

新建 `src/main/java/com/habitrain/core/game/blackout/task/SupplyTaskSyncHelper.java`

```java
/**
 * 同步完成所有 GOOD 玩家中正在做同一供电池任务且未完成的玩家。
 * 不影响正在做其它任务的玩家。
 */
public static void syncCompletion(ServerLevel level, UUID completerUuid, String fullId) {
    for (ServerPlayer p : aliveGoodPlayers(level)) {
        if (p.getUUID().equals(completerUuid)) continue;
        TaskInstance task = TaskManager.getInstance().getActiveTask(p.getUUID());
        if (task == null || !fullId.equals(task.getFullId())) continue;  // 只同步同任务
        if (task.isFulfilled()) continue;
        // fire onComplete 给奖励 + 时间效果
        task.setFulfilled(true);
        task.getDefinition().onComplete(p, task);
        // 调用时间影响 helper（见第9节）
        BlackoutTaskHelper.applyTimeImpact(level, fullId);
        TaskManager.getInstance().removeActiveTask(p.getUUID());
    }
}
```

#### A. 改 RestorePowerTask.forceAssignMaintainPowerToAllGood
- **删除** line 85-122 的无差别 `removeActiveTask` + 强制派 `maintain_power`
- 改为：调用 `SupplyTaskSyncHelper.syncCompletion(level, completerUuid, "habitrain_core:restore_power")` 同步完成所有 restore_power 玩家
- 让自然刷新机制给所有人派下一个任务

#### B. 改 BlackoutMode.forceAssignRestorePowerToAllGood
- 保留"永久停电开始时给所有 GOOD 派 restore_power"的逻辑（这是合理的，因为停电了所有人都需要恢复供电）
- 派发前对正在做 add_coal/repair_wiring 的玩家**先同步完成 + 给奖励**（用 `SupplyTaskSyncHelper.syncCompletion`），再派 restore_power
- 不要直接清空

#### C. 供电池任务 onComplete 接入同步
`AddCoalTask`、`RepairWiringTask`、`MaintainPowerTask` 的 `onComplete` 末尾追加：
```java
SupplyTaskSyncHelper.syncCompletion(level, player.getUUID(), "habitrain_core:add_coal");
```

### 6.4 验证

- 玩家 A 完成 add_coal → 其它做 add_coal 的玩家也完成并拿到奖励
- 玩家 B 正在做 repair_wiring → 不受影响，继续做
- restore_power 完成 → 所有做 restore_power 的同步完成，做其它的不动

---

## 第7节：通用任务道具回收机制

### 7.1 现状

- 唯一给物理道具处：`HabiTrainCore.giveRandomBackpackItem`（line 585-660），仅 `search_backpack` 调用
- 无任何回收逻辑
- `onRemove` 只在失败路径调（`SREPlayerTaskComponentMixin.java:144`），成功路径不调（line 152-157）
- `search_backpack` 注册处无 `onRemove` 回调

### 7.2 修改方案（通用机制）

#### A. TaskInstance 新增 grantedItems
修改 `TaskInstance.java`：
```java
private final List<ItemStack> grantedItems = new ArrayList<>();
public void addGrantedItem(ItemStack stack) { grantedItems.add(stack); }
public List<ItemStack> getGrantedItems() { return grantedItems; }
```

#### B. TaskDefinition 新增 onReclaim
修改 `TaskDefinition.java` + `Builder`：
```java
private BiConsumer<Player, TaskInstance> onReclaim;
public Builder onReclaim(BiConsumer<Player, TaskInstance> cb) { ... }
public void onReclaim(Player p, TaskInstance t) { if (onReclaim != null) onReclaim.accept(p, t); }
```
区别于 `onRemove`（清效果），`onReclaim` 专用于回收道具，只在取消/隐藏路径调用。

#### C. NBT 标签标记任务道具
`ItemReclaimHelper.tagGrantedItem(stack, fullId)`：
- 给 ItemStack 加 `DataComponents.CUSTOM_DATA`，NBT 中 `habitrain_grant = fullId`
- 给 `giveRandomBackpackItem` 返回的 stack 打标签
- 调用方将返回的 stack 存入 `task.addGrantedItem(stack)`

#### D. ItemReclaimHelper.reclaim(player, fullId)
```java
public static void reclaim(Player player, String fullId) {
    Inventory inv = player.getInventory();
    // 扫主背包 + 副手
    for (ItemStack stack : inv.items) if (matchesGrant(stack, fullId)) stack.shrink(stack.getCount());
    // 副手
    ItemStack offhand = inv.offhand.get(0);
    if (matchesGrant(offhand, fullId)) offhand.shrink(offhand.getCount());
}

private static boolean matchesGrant(ItemStack stack, String fullId) {
    if (stack.isEmpty()) return false;
    var data = stack.get(DataComponents.CUSTOM_DATA);
    return data != null && data.contains("habitrain_grant") 
        && fullId.equals(data.get("habitrain_grant").asString());
}
```

#### E. 所有取消路径调用 onReclaim
- `SREPlayerTaskComponentMixin.habitrain$onInit`（line 49-63）：清空前 fire onReclaim
- `SREPlayerTaskComponentMixin.habitrain$onClear`（line 65-81）：清空前 fire onReclaim
- `SREPlayerTaskComponentMixin.handleMainTaskDone` 失败路径（line 137-159）：已有 onRemove，补 onReclaim
- `RestorePowerTask.forceAssignMaintainPowerToAllGood`（第6节改后此处已不无差别移除）
- `BackpackSearchHandler` 超时（line 80-82）：补 onReclaim
- `BlackoutMode.forceAssignRestorePowerToAllGood`：派发前对被替换任务 fire onReclaim
- **成功路径不调用 onReclaim**（玩家保留道具作为奖励）

#### F. search_backpack 接入作为示例
`HabiTrainCore.java:449-482` 注册处追加：
```java
.onReclaim((player, task) -> ItemReclaimHelper.reclaim(player, "habitrain_core:search_backpack"))
```
`giveRandomBackpackItem` 改 `public static`，返回 `ItemStack`（已打标签），调用方存入 `task.addGrantedItem`。

### 7.3 验证

- 完成 search_backpack → 拿道具 → 道具有 `habitrain_grant=search_backpack` NBT
- 任务被取消/隐藏 → 道具消失
- 任务正常完成 → 道具保留
- 玩家原有同类型道具（无标签）不被误回收

---

## 第8节：风精灵隐身修复

### 8.1 问题

`ExtraEffectRole.serverTick`（`ExtraEffectRole.java:52-62`）每 20 tick 才重施 INVISIBILITY，且仅在 `getDuration() <= 21` 时。角色重分配（如警长投票 `BlackoutRoleManager.setSheriff` 触发 `GameUtils.resetPlayer` → `RoleUtils.removeAllEffects`）清掉所有效果后，最长 1 秒空窗才会重施 → 用户感知"一闪即失"。

### 8.2 修改方案

新建 `src/main/java/com/habitrain/core/game/sre/mixin/ExtraEffectRoleMixin.java`
- 目标：`io.wifi.starrailexpress.api.ExtraEffectRole`
- 方法：`serverTick(ServerPlayer)`，HEAD inject
- 逻辑：若该角色的 `playerEffects` 包含 INVISIBILITY 且玩家当前无 INVISIBILITY 效果（或 duration < 21），**立即重施**，不等 `% 20 == 0` 边界
- `required: false` 防 SRE 改名

伪代码：
```java
@Inject(method = "serverTick", at = @At("HEAD"), remap = false)
private void habitrain$immediateInvisibilityReapply(ServerPlayer player, CallbackInfo ci) {
    for (var eff : this.playerEffects) {
        if (eff.getEffect() == MobEffects.INVISIBILITY) {
            var current = player.getEffect(MobEffects.INVISIBILITY);
            if (current == null || current.getDuration() < 21) {
                player.addEffect(getNewEffectInstance(eff));  // 立即重施
            }
        }
    }
}
```

### 8.3 验证

- 风精灵角色被警长投票切换后 → 隐身立即重施（无 1 秒空窗）
- 普通情况下隐身持续（不依赖 20 tick 边界）

---

## 第9节：停电倒计时自适应刷新概率

### 9.1 用户需求

- 倒计时 < 1 分钟 → 大增供电池（add_coal/repair_wiring/maintain_power）刷新权重
- 倒计时 > 3 分钟 → 几乎不刷供电池任务
- 自适应检测任务增减时间，未来改 delta 自动适配
- 写教程方便维护

### 9.2 修改方案

#### A. TaskDefinition 新增 timeImpact
`TaskDefinition.java` + `Builder`：
```java
public record TimeImpact(TimeAxis axis, int deltaSeconds) {
    public enum TimeAxis { MAINTENANCE_OR_COUNTDOWN, TOTAL_TIME, RESTORE_POWER, TRANSIENT }
}
private TimeImpact timeImpact;
public Builder timeImpact(TimeAxis axis, int deltaSeconds) { ... }
public TimeImpact getTimeImpact() { return timeImpact; }
```

#### B. TaskTimeImpactRegistry
新建 `src/main/java/com/habitrain/core/game/blackout/TaskTimeImpactRegistry.java`
- `Map<String, TimeImpact>` fullId → impact
- 任务注册时自动登记（TaskRegistry.register 末尾调用）
- `applyTimeImpact(level, fullId)` 方法：读注册表，调用对应 `BlackoutTimerSystem` 方法

#### C. 改各任务 onComplete 用 applyTimeImpact
- `MaintainPowerTask.java:30` `delayMaintenanceOrCountdown(level, 80)` → `BlackoutTaskHelper.applyTimeImpact(level, "habitrain_core:maintain_power")`
- `RepairWiringTask.java:41` 同理
- `AddCoalTask.java:57` `reduceTime(level, 30)` → `applyTimeImpact(...)`
- `SabotageWiringTask.java:34`、`FurnaceExplosionTask.java:39-40` 同理
- 注册时声明 `.timeImpact(MAINTENANCE_OR_COUNTDOWN, 80)` 等

#### D. 反曲线 computeUrgencyMultiplier
改 `GenerateTaskMixin.java:621-637`：
```java
private static float computeUrgencyMultiplier(TaskDefinition def, ServerLevel level) {
    TimeImpact impact = def.getTimeImpact();
    if (impact == null || impact.axis() != MAINTENANCE_OR_COUNTDOWN || impact.deltaSeconds() <= 0) {
        return 1.0f;  // 非供电池任务，不调整
    }
    int remaining = getRemainingForAxis(level, impact.axis());  // 倒计时 or 维护时间
    int delta = impact.deltaSeconds();
    int lowThreshold = (int)(delta * 0.75);   // 自适应下限
    int highThreshold = delta * 3;             // 自适应上限
    if (lowThreshold < 30) lowThreshold = 30;  // 保底 30s
    if (highThreshold < 180) highThreshold = 180;
    
    if (remaining <= lowThreshold) return DYNAMIC_WEIGHT_CAP;  // 4.0 大增
    if (remaining >= highThreshold) return 0.05f;               // 几乎不刷
    // smoothstep 插值
    float t = (float)(remaining - lowThreshold) / (highThreshold - lowThreshold);
    t = t * t * (3 - 2 * t);  // smoothstep
    return DYNAMIC_WEIGHT_CAP * (1 - t) + 0.05f * t;
}
```

阈值从 `delta` 派生：如 maintain_power delta=80s → low=60s, high=240s。改 delta 自动适配。
当前 `maintain_power` delta=80, `repair_wiring` delta=40，分别用各自 delta 派生阈值。

#### E. 涉及文件
- `TaskDefinition.java`、`TaskRegistry.java`
- 新建 `TaskTimeImpactRegistry.java`、`BlackoutTaskHelper.java`（applyTimeImpact）
- 改 `GenerateTaskMixin.java`（computeUrgencyMultiplier）
- 改 `MaintainPowerTask.java`、`RepairWiringTask.java`、`AddCoalTask.java`、`SabotageWiringTask.java`、`FurnaceExplosionTask.java`、`RestorePowerTask.java`

#### F. 教程文档
更新 `docs/superpowers/guides/blackout-task-writing-guide.md` 新增章节：
"## 任务时间影响与自适应刷新概率"
覆盖：
1. 声明 `.timeImpact(TimeAxis, deltaSeconds)` 的位置和含义
2. `BlackoutTaskHelper.applyTimeImpact(level, fullId)` 调用模式（替代硬编码 delayMaintenanceOrCountdown）
3. 概率曲线公式（smoothstep 反曲线 + 自适应阈值派生）
4. 维护约定：时间 delta 必须在注册时声明，不要在 onComplete 写魔法数字
5. 示例：新增一个 "+50s 供电任务" 的完整写法
6. 修改 delta 时自动影响刷新概率曲线（无需改概率逻辑代码）

同步更新 `docs/superpowers/specs/2026-07-06-blackout-fixes-design.md` §2.1 表格（已过时）。

### 9.3 验证

- 倒计时 30s → maintain_power 权重 = 4.0（大增）
- 倒计时 200s → maintain_power 权重 ≈ 0.05（几乎不刷）
- 倒计时 100s → smoothstep 中间值
- 改 maintain_power delta 从 80 到 50 → 阈值自动调整为 low=37, high=150
- 供电池外任务（如 blackout_eat）权重不受影响

---

## 第10节：翻背包给道具 + 删弹窗

### 10.1 问题

- `blackout_search_backpack`（停电模式）`onComplete` 不给道具，只给金币/情绪
- 完成弹窗 `SubtitleNotifier.sendTop` 在 `BlackoutSearchBackpackTask.java:51-56`
- 原版 `search_backpack` 给道具（`giveRandomBackpackItem` 按角色分池）

### 10.2 修改方案

#### A. giveRandomBackpackItem 改 public static
`HabiTrainCore.java:585-660`：
- 改为 `public static ItemStack giveRandomBackpackItem(ServerPlayer player)`
- 返回给的 ItemStack（已打 `habitrain_grant` 标签）
- 调用方存入 `task.addGrantedItem`

#### B. blackout_search_backpack 接入
`BlackoutSearchBackpackTask.java:43-57` onComplete 追加：
```java
ItemStack granted = HabiTrainCore.giveRandomBackpackItem(serverPlayer);
task.addGrantedItem(granted);
```
注册处追加 `.onReclaim((p, t) -> ItemReclaimHelper.reclaim(p, "habitrain_core:blackout_search_backpack"))`。

#### C. 删完成弹窗
`BlackoutSearchBackpackTask.java:51-56` 删除 `SubtitleNotifier.sendTop(...)` 调用。
保留 `grantRewards`（金币/情绪）。

#### D. 清查其它仍带弹窗的停电任务
对照 `docs/superpowers/specs/2026-07-06-blackout-fixes-design.md:55-63`，逐个检查并删除：
- `RepairWiringTask.java:43-48` sendTop
- `SabotageWiringTask.java:36-41` sendTop
- `FurnaceExplosionTask.java:45` sendTop
- `RestorePowerTask.java:50-55` sendTop
- 其它对照清单

### 10.3 验证

- 停电模式翻背包完成 → 拿到随机道具（撬棍/手铐/毒药等按角色分池）
- 道具有 `habitrain_grant=blackout_search_backpack` NBT
- 无完成弹窗
- 任务取消 → 道具回收

---

## 第11节：SRE DLC 更新

### 11.1 现状

- SRE 4.3.0，源码 2026-07-07 被改动（版本号未变）
- `habitrain_core/libs/star_rail_express-4.3.0.jar` 2026-07-05 构建（旧于源码 2 天）
- 改动：`AreasWorldComponent.bannedRoles` 新字段、WraithAssassin 修 floating 执行、Amon 修 Shift+G
- **无破坏性 API 变更**，habitrain_core 依赖的所有 SRE API 签名保留

### 11.2 修改方案

#### A. 重建 SRE jar
- 用户从 `D:\Backup\mc mod\哈比列车dlc\StarRailExpress-master` 构建 `./gradlew build`
- 把产物 `star_rail_express-4.3.0.jar` 复制到 `D:\Backup\mc mod\哈比列车api\libs\`
- 获取 WraithAssassin/Amon 修复

#### B. 可选：用新 bannedRoles API
- `SREDisableManager.setMapBannedRoles()` / `clearMapBannedRoles()`
- 在停电模式 `BlackoutMode.onGameStart` 调用，让停电模式也尊重地图级角色禁用
- 非必需，按需实现

### 11.3 验证

- habitrain_core 编译通过（API 无破坏）
- 运行时 WraithAssassin/Amon 行为正常

---

## 实施顺序与依赖

| 阶段 | 任务 | 依赖 |
|---|---|---|
| 1 | 第1节 吃饭/喝水（A canEat + B finishUsingItem + C MapScanner 内容 + D 重建扫描） | 无 |
| 2 | 第2节 祷告接入（场景任务遍历 + PRAY 槽位冲突解决） | 无 |
| 3 | 第7节 通用回收机制（A grantedItems + B onReclaim + C NBT + D reclaim helper + E 取消路径） | 无 |
| 4 | 第9节 自适应刷新概率（A timeImpact + B registry + C onComplete 接入 + D 反曲线 + F 教程） | 无 |
| 5 | 第6节 供电池同步完成（SupplyTaskSyncHelper + 改 RestorePowerTask + 接入 onComplete） | 依赖第9节 applyTimeImpact |
| 6 | 第10节 翻背包给道具 + 删弹窗 + 接入回收 | 依赖第7节、第9节 |
| 7 | 第8节 风精灵隐身 mixin | 无 |
| 8 | 第4节 性能优化 | 无（可与上述并行） |
| 9 | 第5节 崩溃 null guard mixin | 无 |
| 10 | 第3节 杀手透视（待根因确认） | 待用户游戏内观察 |
| 11 | 第11节 重建 SRE jar | 用户操作 |

---

## 风险与缓解

1. **SRE mixin 字符串目标脆弱**：设 `required: false` 防启动崩溃
2. **PRAY 槽位冲突**：若无可用空枚举，跳过 PRAY 派发只接入其它 6 个场景任务，通知用户
3. **CustomTaskBlockCache 缓存 Block 增内存**：限制缓存大小 + 失效机制
4. **NBT 标签兼容性**：玩家死亡掉落时道具标签保留（vanilla 行为），回收时仍能匹配
5. **onReclaim 在成功路径不调**：道具作为奖励保留，符合预期

---

## 备注

- 所有新文件遵循现有包结构与命名约定
- 代码加中文注释说明意图
- mixin 配置文件同步更新（habitrain_core.mixins.json / habitrain_core.client.mixins.json）
- 完成后跑 `./gradlew build` 验证编译
- README.md / AGENTS.md 视需要更新