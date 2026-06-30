# 停电模式重设计 — 游戏循环、投票、角色介绍、任务系统修复

> **日期**: 2026-06-30
> **项目**: 哈比列车核心 (HabiTrain Core) — 停电模式 (habitrains:blackout)
> **状态**: 设计已批准

---

## 1. 概述

对现有停电模式 `BlackoutMode` 进行系统性重设计，核心变更包括：

| # | 模块 | 问题 |
|---|------|------|
| 1 | 停电循环 | 现有 120s 循环停电机制不符合预期；停电后灯因 SRE 的 `BlackoutDetails` 随机时长逐个恢复 |
| 2 | 时间系统 Bug | 停电期间 `tickSecond()` 在 `blackoutActive` 时 return，导致 `totalTimeRemaining` 不递减 |
| 3 | 胜利条件 | 现有逻辑: 5分钟到 → 杀手胜利。新需求: 总时间归零 → 好人胜利 |
| 4 | 投票系统 | 聊天提示不出现；P 键在所有模式下可打开；VoteScreen 不能 ESC 退出 |
| 5 | 角色介绍 GUI | 现有自定义 GUI 需替换为 SRE 原版 `RoleIntroduceScreen` |
| 6 | 任务过滤 | `filterAvailableTasks` 不过滤，SRE 旧任务出现在停电模式中 |

---

## 2. 新游戏循环设计

### 2.1 核心流程

```
开局（灯亮，300s 对局倒计时，120s 停电倒计时）
  │
  ├─ [杀手 破坏线路] → 7s 短暂停电 + 停电倒计时 -15s
  │                       (不触发永久停电回调)
  │
  └─ 停电倒计时 120→0 → 🔴 第一次永久停电
      (BlackoutMode.triggerSREBlackout 被设为 "永久模式")
      │
      ├─ [好人 维修线路] → 推迟停电倒计时（破坏已发生，不适用）
      │
      └─ [好人 维修发电] → ✅ 恢复供电（设定 60s 维护期）
                              │
                              ├─ [好人 维护供电]    → 维护期 +15s
                              ├─ [杀手 破坏线路]    → 维护期 -15s + 7s 短暂停电
                              │
                              └─ 维护期 60→0 → 🔴 第二次永久停电(不可逆)
                                                   │
                                                   ├─ 好人做任务减少总时间 → 提前结束
                                                   └─ 杀手击杀所有好人    → 游戏结束
```

### 2.2 状态变量重构

`BlackoutTimerSystem` 新增/修改状态：

| 字段 | 类型 | 说明 |
|------|------|------|
| `totalTimeRemaining` | int | 300s 对局倒计时，任务可增减。归零 → 好人胜 |
| `blackoutCountdown` | int | 120s → 第一次永久停电倒计时。之后转入"维护期"模式 |
| `permanentBlackoutActive` | boolean | 是否处于永久停电状态（第一次或第二次） |
| `canRestorePower` | boolean | 第一次永久停电 = true。第二次永久停电 = false |
| `maintenanceTime` | int | 恢复供电后的维护期 (60s)，任务可增减 |
| `transientBlackoutActive` | boolean | 杀手破坏线路造成的 7s 短暂停电（不影响永久状态） |
| `transientBlackoutTicks` | int | 短暂停电剩余 tick 数 (140 tick = 7s) |

### 2.3 停电与供电转换逻辑

```
tickSecond():
  if (transientBlackoutActive):
    // 短暂停电（杀手破坏）— 不影响永久黑暗状态
    transientBlackoutTicks--
    if (transientBlackoutTicks <= 0):
      // 只恢复视觉效果（失明等），不恢复灯光
      // 或如果是第一次永久停电也触发，则不管
    return  // 短暂停电期间主游戏时间依然走

  if (permanentBlackoutActive):
    // 永久黑暗 — 总时间正常递减
    totalTimeRemaining--
    if (canRestorePower):
      // 检查好人是否完成了"维修发电"任务
      // 由好人任务回调触发 restorePower()，而非 tick 中轮询
    return

  // 正常灯亮状态
  totalTimeRemaining--
  blackoutCountdown--
  
  if (blackoutCountdown <= 0):
    // 触发第一次永久停电
    permanentBlackoutActive = true
    canRestorePower = true
    triggerSREPermanentBlackout()  // 调用 SRE WorldBlackoutComponent
  
  if (totalTimeRemaining <= 0):
    // 好人胜利
```

### 2.4 恢复供电流程

```java
// 由好人任务（"维修发电"）的 onComplete 回调调用
restorePower() {
    if (!permanentBlackoutActive || !canRestorePower) return;
    
    // 恢复灯光：调用 SRE WorldBlackoutComponent.reset()
    endSREBlackout();
    
    permanentBlackoutActive = false;
    canRestorePower = false;  // 只有第一次可恢复
    maintenanceTime = 60;
    broadcast("§a供电已恢复！维护期 60 秒");
    broadcast("§7请在 60 秒内尽可能做任务维持供电");
}

// 维护期 tick
tickMaintenance() {
    if (permanentBlackoutActive || maintenanceTime <= 0) return;
    
    maintenanceTime--;
    
    if (maintenanceTime <= 0) {
        // 第二次永久停电（不可逆）
        permanentBlackoutActive = true;
        canRestorePower = false;
        triggerSREPermanentBlackout();
        broadcast("§c备用电源耗尽！列车再次陷入黑暗...");
        broadcast("§e好人无法再恢复供电，但做任务可减少总时间提前结束对局");
    }
}
```

---

## 3. 时间 Bug 修复 — 停电期间 5 分钟不走

### 3.1 根因

`BlackoutTimerSystem.tickSecond()` 原有逻辑：

```java
// 停电中：计时
if (blackoutActive) {
    blackoutElapsedTicks++;
    if (blackoutElapsedTicks >= 140) { // 7秒 × 20 tick
        // 恢复供电...
    }
    return; // ← BUG: 跳出函数，不执行 totalTimeRemaining--
}

// 正常状态：更新主计时器
totalTimeRemaining--;
```

停电期间因为 `return`，`totalTimeRemaining--` 永远不执行，导致 5 分钟倒计时冻结。

### 3.2 修复方案

在新的双状态系统（短暂停电 + 永久停电）中，**两种停电状态下都要走 `totalTimeRemaining--`**。仅在 `transientBlackoutActive` 时不触发永久停电计时，但倒计时照常。

```java
tickSecond() {
    if (currentLevel == null) return;
    
    // === 总时间倒计时（所有状态下都走）===
    totalTimeRemaining--;
    
    // === 胜利检查 ===
    if (totalTimeRemaining <= 0) {
        // 好人胜利
        return;
    }
    
    // === 短暂停电处理（杀手破坏线路）===
    if (transientBlackoutActive) {
        transientBlackoutTicks--;
        if (transientBlackoutTicks <= 0) {
            endTransientBlackout();
        }
    }
    
    // === 永久停电处理 ===
    if (permanentBlackoutActive) {
        if (canRestorePower && maintenanceTime > 0) {
            tickMaintenance();
        }
        return;
    }
    
    // === 正常灯亮状态：管理停电倒计时 ===
    if (maintenanceTime > 0) {
        tickMaintenance();
    } else {
        blackoutCountdown--;
        if (blackoutCountdown <= 0) {
            triggerFirstPermanentBlackout();
        }
    }
}
```

### 3.3 胜利条件变更

| 条件 | 旧 | 新 |
|------|----|----|
| 总时间归零 | 杀手胜利 | **好人胜利** |
| 好人全灭 | 好人胜利 | **杀手胜利**（保留） |
| 杀手全灭 | 好人胜利 | 好人胜利（保留） |
| 5 分钟到 | 杀手胜利（旧代码第 283 行） | **移除**（不再需要，因为总时间归零即为好人胜利） |

---

## 4. 投票系统修复

### 4.1 问题清单

| # | 问题 | 原因 |
|---|------|------|
| 1 | 1 分钟聊天提示不出现 | 未知，需加日志排查。可能是 SRE 游戏激活延迟或条件判断未触发 |
| 2 | P 键在游戏外/非停电模式也可打开 | `BlackoutKeyHandler` 不检查模式和投票状态 |
| 3 | VoteScreen 无退出按钮/ESC 无效 | `shouldCloseOnEsc() = false` 且无关闭按钮 |

### 4.2 修复详情

**4.2.1 VoteScreen — 添加关闭功能**

```java
// 1. ESC 可关闭
@Override
public boolean shouldCloseOnEsc() { return true; }

// 2. 添加关闭按钮 (init 中)
addRenderableWidget(Button.builder(
    Component.literal("✕ 关闭"),
    btn -> onClose()
).bounds(width - 60, 5, 50, 18).build());
```

**4.2.2 BlackoutKeyHandler — 限制 P 键范围**

通过 `ClientPlayNetworking` 或 S2C 包的状态缓存来检测：
- 当前是否在停电模式中
- 投票窗口是否开放

```java
while (VOTE_KEY.consumeClick()) {
    if (client.player != null && client.screen == null
            && BlackoutHudOverlay.isBlackoutModeActive()  // 停电模式激活
            && BlackoutHudOverlay.isVotingOpen()) {        // 投票窗口开放
        client.setScreen(new VoteScreen());
    }
}
```

当投票窗口未开放时打开 VoteScreen，显示提示：
```java
public VoteScreen(boolean votingOpen) { ... }
// 如果 votingOpen=false，渲染时在屏幕中央显示：
// "§e当前不在投票时间"
// "[关闭]"
```

**4.2.3 排查聊天提示**

在 `BlackoutMode.onTick()` 和 `BlackoutVotingEngine.openVoting()` 添加 `LOGGER.info` 日志输出，定位提示不出现的原因。

---

## 5. 角色介绍 GUI — 复用 SRE 原版 RoleIntroduceScreen

### 5.1 原理

SRE 的角色注册表 `TMMRoles.ROLES` (`HashMap<ResourceLocation, SRERole>`) 是 `public static` 字段。`RoleIntroduceScreen` 从 `Noellesroles.getAllRolesSorted()` → `TMMRoles.ROLES.values()` 读取数据。

### 5.2 实现方案

在核心 API 模组 `HabiTrainCoreClient.onInitializeClient()` 中，注册三个停电模式角色到 SRE 注册表：

```java
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.SRE;

// 平民
public static final SRERole BLACKOUT_CIVILIAN = TMMRoles.registerRole(
    new NormalRole(SRE.id("blackout_civilian"), 0x55FF55, true, false,
        SRERole.MoodType.HAPPY, 200, true));

// 杀手
public static final SRERole BLACKOUT_KILLER = TMMRoles.registerRole(
    new NormalRole(SRE.id("blackout_killer"), 0xFF5555, false, true,
        SRERole.MoodType.ANGRY, 200, true));

// 警长
public static final SRERole BLACKOUT_SHERIFF = TMMRoles.registerRole(
    new NormalRole(SRE.id("blackout_sheriff"), 0xFFFF55, true, true,
        SRERole.MoodType.HAPPY, 200, true));
```

`BlackoutKeyHandler` 中 U 键改为打开 SRE 原版 `RoleIntroduceScreen`：

```java
while (ROLE_INTRO_KEY.consumeClick()) {
    if (client.player != null && client.screen == null
            && BlackoutHudOverlay.isBlackoutModeActive()) {
        client.setScreen(new org.agmas.noellesroles.client.screen.RoleIntroduceScreen());
    }
}
```

### 5.3 新增可扩展性

后续添加新角色只需再次调用 `TMMRoles.registerRole()`，`RoleIntroduceScreen` 自动显示。可在 `fabric.mod.json` 中添加语言文件条目翻译角色名及描述。

---

## 6. 任务系统修复

### 6.1 任务过滤

`BlackoutMode.filterAvailableTasks()` 改为只返回停电模式分类的任务：

```java
@Override
public List<TaskDefinition> filterAvailableTasks(List<TaskDefinition> tasks, ServerPlayer player) {
    return tasks.stream()
        .filter(t -> {
            TaskCategory cat = t.getCategory();
            return BLACKOUT_GOOD.equals(cat) || BLACKOUT_BAD.equals(cat);
        })
        .toList();
}
```

### 6.2 任务效果调整

| 任务 | ID | 阵营 | 分类 | 新效果 |
|------|----|------|------|--------|
| 维修线路 | `repair_wiring` | 好人 | `BLACKOUT_GOOD` | 第一次永久停电时→**恢复供电**；维护期 **+15s** |
| 添加煤炭 | `add_coal` | 好人 | `BLACKOUT_GOOD` | 总时间 **-30s**（加速好人胜利） |
| 维护供电 | `maintain_power` | 好人 | `BLACKOUT_GOOD` | 维护期 **+15s**（新增任务） |
| 破坏线路 | `sabotage_wiring` | 杀手 | `BLACKOUT_BAD` | 7s 短暂停电 + 停电倒计时/维护期 **-15s** |
| 熔炉爆炸 | `furnace_explosion` | 杀手 | `BLACKOUT_BAD` | 总时间 **+15s** + 引爆 TNT |

---

## 7. 文件变更清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `api/src/.../blackout/BlackoutTimerSystem.java` | **重写** | 新增永久停电/维护期/短暂停电状态机；修复 5 分钟冻结 bug |
| `api/src/.../blackout/BlackoutMode.java` | **修改** | 适配新 Timer；胜利条件改为总时间归零=好人胜；triggerSREBlackout 区分永久/短暂 |
| `api/src/.../blackout/BlackoutVotingEngine.java` | **修改** | 添加日志；加 `isVotingOpen()` 查询接口 |
| `api/src/.../client/gui/VoteScreen.java` | **修改** | ESC 关闭；加关闭按钮；支持"非投票时间"提示 |
| `api/src/.../client/BlackoutKeyHandler.java` | **修改** | P 键：限停电模式+投票窗口内；U 键：打开原版 RoleIntroduceScreen |
| `api/src/.../client/gui/BlackoutHudOverlay.java` | **修改** | 添加 `isBlackoutModeActive()` 和 `isVotingOpen()` 静态状态 |
| `api/src/.../client/HabiTrainCoreClient.java` | **修改** | 注册停电角色进 SRE 角色系统 |
| `api/src/.../client/gui/BlackoutRoleIntroduceScreen.java` | **删除** | 不再需要，由 SRE 原版 RoleIntroduceScreen 替代 |
| `moretasks/src/.../game/blackout/AddCoalTask.java` | **修改** | 效果改为总时间 -30s（适配新循环） |
| `moretasks/src/.../game/blackout/RepairWiringTask.java` | **修改** | 效果改为增加维护期/推迟停电倒计时 |
| `moretasks/src/.../game/blackout/SabotageWiringTask.java` | **修改** | 效果改为短暂停电7s + 维护期 -15s |
| `moretasks/src/.../game/blackout/FurnaceExplosionTask.java` | **修改** | 效果改为总时间 +15s + TNT 爆炸 |

---

## 8. 实施顺序

| # | 阶段 | 涉及文件 |
|---|------|---------|
| 1 | **重写 BlackoutTimerSystem** | 新状态机 + 修复倒计时冻结 bug + 胜利条件变更 |
| 2 | **修改 BlackoutMode** | 适配新 Timer 接口，回调区分短暂/永久停电 |
| 3 | **修改 VoteScreen + BlackoutKeyHandler** | ESC 关闭、P 键限制、模式检测 |
| 4 | **角色 GUI** | 注册停电角色进 SRE + U 键改打开原版 RoleIntroduceScreen |
| 5 | **任务过滤** | `filterAvailableTasks` 过滤 |
| 6 | **RepairWiringTask 改为恢复供电** | 修改 companion mod 中维修线路的 onComplete 回调 |
| 7 | **其余任务效果调整** | AddCoalTask(-30s)、SabotageWiringTask(-15s+短暂停电)、FurnaceExplosionTask(+15s) |
| 8 | **新增维护供电任务 maintain_power** | 新任务注册到 companion mod |
| 9 | **测试与 Bug 修复** | 运行游戏验证每个环节 |

---

## 9. 未包含的范围

- 本设计**不包含**对 SRE 本体代码的修改
- 本设计**不包含**对 BlackoutHudOverlay 视觉样式的修改（仅添加静态状态查询）
- 本设计**不包含**对 TACZWeaponBridge 的变更
