# 七宗罪角色 + 七美德修饰符 — 设计规格

**日期:** 2026-07-15  
**项目:** habitrain_core（`D:\Backup\mc mod\哈比列车api`）  
**状态:** 已定稿（待实现计划）  
**架构方案:** A — 扩展现有 hub（`HabiRoles` / 新建 `HabiModifiers`），不平行注册表、不迁入 noelles 上游仓

## 1. 背景与目标

在 `habitrain_core` 中新增：

1. **七宗罪**七个 SRE 角色（傲慢、嫉妒、暴怒、贪婪、暴食、色欲、懒惰）  
2. **七美德**修饰符框架（六新建 + 慷慨只关联上游 `noellesroles:generous`）

**模式范围:** SRE 谋杀模式 **与** Blackout **双支持**。

**非目标（本规格）:**

- 不把角色注册进 noellesroles / StarRailExpress 源码仓  
- 不重注册 `stupid_express:lovers` / `noellesroles:generous`  
- P0–P2 不实现完整贪婪匿名交易（P3 单独）  
- 不为 Blackout 新建第三套任务经济

## 2. 已确认决策

| 议题 | 决定 |
|------|------|
| 总体架构 | 方案 A：扩展现有 hub + `sins/` / `modifier/` 子包 |
| 游戏模式 | SRE 谋杀 + Blackout 双支持 |
| Blackout 独立中立 | **不占** GOOD/BAD 存活数 |
| 暴怒 Blackout | **不占**数；杀手阵营胜利时 **个人胜共享** |
| 慷慨美德 | 只关联上游 `generous`，不新建同效果修饰符 |
| 交付 | 整包规划，分阶段实现与验收 |
| 互斥 | `setDefaultMax(1)` + 两两 `addTwoWayOpposingRole` + 确认阶段硬裁 |
| 自定义胜 | SRE：`CustomWinnerRole` + `AllowGameEnd`；Blackout：扩展 `BlackoutVictoryChecker` + 阵营同步 |

## 3. 包结构与注册骨架

### 3.1 布局

```
com.habitrain.core.game.sre.role/
  HabiRoles.java                 // init 编排 + 现有角色
  sins/
    SevenSins.java               // 七罪 registerRole + clique + skills
    SevenSinsMutex.java          // OnGamePlayerRolesConfirm / 色欲候选
    component/
      PrideComponent.java
      EnvyComponent.java
      WrathComponent.java
      GreedComponent.java
      GluttonyComponent.java
      LustComponent.java
      SlothComponent.java
    shop/SevenSinShops.java
    item/GreedPouchItem.java     // P3
    trade/…                      // P3
    win/SinVictoryHooks.java     // SRE + Blackout 共用判定
  HabiComponents.java            // +7 CCA keys + clearAll
  HabiRoleEvents.java            // 或转发 SevenSinEvents

com.habitrain.core.game.sre.modifier/
  HabiModifiers.java
  VirtueGroup.java
  virtue/
    HumilityVirtue.java
    MercyVirtue.java
    TaskTimeVirtues.java         // patience / diligence
    TemperanceVirtue.java
    ChastityVirtue.java
```

### 3.2 角色 ID 与 SRE 标志

Namespace: `habitrain_core`。Lang / UI 使用 **path only**。

| 罪 | path | SRE 阵营标志 | 基类 |
|----|------|--------------|------|
| 傲慢·路西法 | `sin_pride` | 独立中立 `setNeutrals(true)` | `CustomWinnerRole` |
| 嫉妒·利维坦 | `sin_envy` | 杀手 `canUseKiller` | `NormalRole` |
| 暴怒·撒旦 | `sin_wrath` | `setNeutrals` + `setNeutralForKiller(true)` | `NormalRole`（可轻量自定义胜） |
| 贪婪·玛门 | `sin_greed` | 独立中立 | `CustomWinnerRole` |
| 暴食·别西卜 | `sin_gluttony` | 好人 innocent | `NormalRole` |
| 色欲·阿斯蒙蒂斯 | `sin_lust` | 独立中立 | `CustomWinnerRole` |
| 懒惰·贝露菲格露 | `sin_sloth` | 独立中立 | `CustomWinnerRole` |

通用链：

- `setDefaultMax(1)`
- 商店一律 **`getShopEntries()` 覆盖**（不依赖会被 noelles `shopRegister().clear()` 的 `customEntries`）
- 有状态的罪：`setComponentKey(KEY)` **必须在** `TMMRoles.registerRole` 入表前
- 透视 / 看时间 / 初始物 / 商店内容按玩法稿（见 §5）

### 3.3 初始化顺序

```
HabiRoles.init()
  → 现有角色
  → SevenSins.init()       // register + opposing clique + skills
  → SevenSinsMutex.init()  // confirm 钩子
HabiModifiers.init()       // 美德（在 HabiTrainCore 中紧接 HabiRoles）
SinVictoryHooks.init()     // AllowGameEnd + Blackout 挂钩
```

CCA：七个 id 写入 `fabric.mod.json` → `custom.cardinal-components` 与 `HabiComponents`。

## 4. 七选一互斥与色欲候选

### 4.1 三层互斥

| 层 | 机制 | 作用 |
|----|------|------|
| L1 | 每罪 `setDefaultMax(1)` | 单罪不刷多个 |
| L2 | 七罪两两 `addTwoWayOpposingRole` | `removeOpposingJobs` 减负 |
| L3 | `OnGamePlayerRolesConfirm` | **契约层**：最终 map 至多 1 个罪 |

仅 L1/L2 不足以覆盖跨池（杀手嫉妒 + 中立傲慢等）同时入选，故 L3 必做。

### 4.2 L3 算法（`SevenSinsMutex`）

1. `SIN_ROLES` 扫描分配 map  
2. `count ≤ 1` → 色欲校验  
3. `count > 1` → 按玩家 UUID 排序保留第一个罪，其余改为 **同 `getRoleType()` 非罪替补**  
4. 写回 map  

### 4.3 色欲候选

确认阶段允许保留色欲，当且仅当：

1. 上游真正恋人修饰符（`stupid_express:lovers` / `SEModifiers.LOVERS`）本局可启用；且  
2. 分配人数 ≥ 2  

否则将色欲替换为同类型非罪角色。  
**禁止**给色欲或他人批量添加真正恋人修饰符；色欲只读上游恋人状态。

## 5. 七宗罪机制摘要

统一约定：

- 技能仅服务端；客户端负责 UI / 高亮 / 滤镜 / 提示  
- 武器击杀拦截优先 `AllowPlayerDeathWithKiller`；需要时扩展 `MeleeImmuneKillMixin` reason 表  
- **`forceDeath` 与未登记特殊伤害一律不拦**  
- 常规武器 reason **显式白名单**（`SinDeathReasons`）

### 5.1 傲慢 `sin_pride`

- 无商店、无初始物；可透视、不可看时间  
- G：60s CD，复制目标角色 **基础** 商店快照（商品 + 基础价），不复制余额/折扣/动态价/已购  
- 半径 8 内其他存活 ≥3 → 发光 + 常规武器免疫；击杀他人后破防 5s  
- 存活时阻止好人/杀手因灭队提前结束；成为最后存活者 → 独立胜  

### 5.2 嫉妒 `sin_envy`

- 杀手店：刀 200、枪 300、狂暴 500、开锁 150、关灯 150  
- G：90s 标记准星玩家  
- 对 **标记目标**：仅当嫉妒金币 ≤ 目标金币时可击杀；成功则随机夺合法物，否则夺最多 100 金  
- 掠夺排除：钥匙、任务物、职业绑定、不可转移  
- 未标记目标攻击不受此限；随杀手胜  

### 5.3 暴怒 `sin_wrath`

- 无商店；初始假刀假枪；不可透视、可看时间  
- 仅 **好人阵营** 有效武器致死攻击推进阶段（拳/环境不计）  
- 前 5 次取消死亡并递进：禁锢 3s → 红滤镜+棒球棍 → 黑白滤镜 → 黑暗失明 → 迷幻+失智  
- 其后每次 +1 速度（上限 5）；速度 5 后再被合法攻击才死  
- 每击杀一人降低愤怒阶段；失智后击杀数独立累计，满 5 力竭 `forceDeath`  
- 阶段效果由组件维持，奶/蜜不能解除组件状态  
- Blackout：不占数；杀手胜时个人胜共享  

### 5.4 贪婪 `sin_greed`（P3 深做）

- 店：开锁 100；初始绑定收纳袋（UUID）  
- 目标种类数：`ceil(开局玩家数 × 2.5)` 整局固定  
- 袋内不同 item id 计数（忽略数量/耐久/NBT）；无额外白名单  
- 失袋（丢弃/转移/偷）→ 立即死亡  
- 匿名交易：双确认容器；世界组件记录每 itemId 成交次数 0–3；卖 30+30n，买 max(30,300-30n)  
- 达目标 → 立即独立胜  

### 5.5 暴食 `sin_gluttony`

- 动态可食用店 @5；奶 300、蜜 100  
- 确认真实消耗后随机正面效果，重复升级，达上限再抽到 → 本局永久  
- 免疫并清除 **登记的普通负面**；不自动清槟榔/迷幻/诅咒/职业/滤镜  
- 随好人胜  

### 5.6 色欲 `sin_lust`

- 店：开锁 300；可透视、可看时间  
- 一阶段：只读高亮真正恋人；双恋人 8 格内视线通时蓄能 30s（分开暂停不清空）  
- 二阶段：一次技能给除己外存活加 **欲望标记**（非 LOVERS，不殉情、不配对）  
- 任意真正恋人胜利且色欲存活 → 胜者替换为色欲（SRE `AllowGameEnd`；Blackout 预留同一劫持 API）  

### 5.7 懒惰 `sin_sloth`

- 安全时正常；安全时结束沉睡，护盾 `ceil(存活/2)`  
- 沉睡禁移动/跳/用物/攻击/互动/GUI/聊天/语音  
- 破盾：醒 + 狂暴 10s，仅可攻击本轮攻击者（高亮 UUID）  
- 整局一次主动醒：吞盾 + 类超级亡命徒爆炸（己不死）+ 狂暴 30s 可打全员；狂暴中每杀 2 人 +1 盾；再睡时盾 `max(1, 积累)`，清空攻击者名单  
- 普通阵营胜触发时若懒惰存活 → 改为懒惰胜  

## 6. Blackout 存活计数与双模式胜利

### 6.1 分类

| 标签 | 角色 | Blackout 阵营 | 计入 GOOD/BAD |
|------|------|---------------|---------------|
| `INDEPENDENT_SIN` | 傲/贪/色/懒 | 不写 GOOD/BAD | 否 |
| `KILLER_SHARE_SIN` | 暴怒 | 不写 GOOD/BAD | 否；杀手胜共享个人胜 |
| `TRUE_KILLER_SIN` | 嫉妒 | BAD | 是 |
| `TRUE_GOOD_SIN` | 暴食 | GOOD | 是 |

修改 `BlackoutRoleManager.syncFactionsFromSreRoles`：独立/共享罪 **不得** 默认为 GOOD。

### 6.2 `BlackoutVictoryChecker` 顺序（概念）

1. 傲慢最后存活 → 傲慢胜  
2. 贪婪收集完成 → 贪婪胜（也可在放入时即时触发）  
3. 傲慢存活且场上仍有其他人 → 禁止因 good==0 / bad==0 提前结束（计时归零策略：仍可 GOOD，但若仅剩傲慢走 1）  
4. 使用 **已排除独立/共享罪** 的 good/bad 原逻辑  
5. `endGame` 前接管：懒惰存活 → 懒惰胜；色欲劫持恋人胜（预留）；暴怒在杀手胜时加入个人胜列表  

### 6.3 SRE

- 独立罪：`CustomWinnerRole` + 需要时 `RoleUtils.customWinnerWin`  
- 傲慢挡结束 / 色欲与懒惰劫持：`AllowGameEnd`  
- 嫉妒/暴食随阵营；暴怒 `neutralForKiller`  

共用 `SinVictoryHooks` 判定，双端只换结算写入方式。

## 7. 七美德修饰符

### 7.1 总则

- 新建 `HabiModifiers`；`HabiTrainCore.onInitialize` 在 `HabiRoles.init()` 后调用  
- 美德组 `VIRTUE_GROUP`：每名玩家最多持有 **一个** 七美德成员  
- **耐心 ↔ 勤勉** 绝对互斥  
- 慷慨：解析/引用 `noellesroles:generous`，加入组，**不** `registerModifier`  
- 运行时状态不放在 `SREModifier` 单例上；用 CCA 或局内 map，局终清空  

### 7.2 Path 与效果

| 美德 | path | 效果要点 |
|------|------|----------|
| 谦卑 | `virtue_humility` | 完成任务时附近提示「谢谢」，不全服广播 |
| 宽容 | `virtue_mercy` | `civilianOnly`；整局首次被 **好人** 击杀取消死并消耗修饰符 |
| 耐心 | `virtue_patience` | 任务 **有效交互** 时间 ×150%；不影响纯等待/自动完成 |
| 勤勉 | `virtue_diligence` | 有效交互时间 ×70%；与耐心互斥 |
| 慷慨 | *(上游)* `noellesroles:generous` | 只关联 |
| 节制 | `virtue_temperance` | 同条目重复购买价 = max(原价×50%, 上次×90%)；每玩家独立，局终清空；叠价顺序：基础 → 节制 → DynamicShop |
| 贞洁 | `virtue_chastity` | 免疫原版中毒 + **登记** 毒效果/毒死；不免疫槟榔/迷幻/诅咒/职业专属 |

Lang：`announcement.star.modifier.<path>`、`info.screen.modifier.<path>`（+ `.simple`）。

## 8. 分阶段交付

| 阶段 | 范围 | 验收焦点 |
|------|------|----------|
| **P0 骨架** | 七罪注册+互斥+CCA 表；美德定义+组；`SinVictoryHooks` 空壳；Blackout 不占数同步 | ≤1 罪；force 角色不崩；阵营数正确 |
| **P1** | 嫉妒、暴食、傲慢完整机制 | 技能/商店/死亡/傲慢胜与挡结束 |
| **P4 美德**（可与 P1 并行） | 六美德效果 mixin/事件 | `/forcemodifier` 行为与互斥 |
| **P2** | 暴怒、懒惰、色欲 | 阶段机/沉睡输入封/恋人劫持 |
| **P3** | 贪婪袋 + 匿名交易 | 失袋死、价封顶、成交原子性 |

每阶段：`./gradlew clean build`，jar 复制到 `D:\Backup\mc mod\临时\`。

## 9. 风险

| 风险 | 缓解 |
|------|------|
| 跨池双罪 | L3 硬裁 |
| Blackout 中立当 GOOD | 改 sync + 用例 |
| 误拦强制死 | reason 白名单 + forceDeath 放行 |
| 色欲污染 lovers | 自有标记；只读上游 |
| 暴怒滤镜缺失 | P2 可用 Immersive/药水近似再迭代 |
| 贪婪经济刷价 | P3 单独；成交二次校验 |
| 任务 mixin 冲突 | 扩展现有 `SREPlayerTaskComponentMixin` 或明确 priority |
| 类膨胀 | 子包拆分，init 只编排 |

## 10. 关键复用（现有代码）

| 能力 | 位置 |
|------|------|
| 角色注册模板 | `HabiRoles` / 四复杂角色组件 |
| 死亡取消 | `HabiRoleEvents` + `AllowPlayerDeathWithKiller`；`MeleeImmuneKillMixin` |
| 商店覆盖 | `getShopEntries()` + `HabiRoleShops` 模式 |
| Blackout 胜负 | `BlackoutVictoryChecker`、`BlackoutRoleManager` |
| 任务直胜参考 | `TaskManager.triggerDirectWin` |
| 互斥 API | SRE `addTwoWayOpposingRole`；确认事件 `OnGamePlayerRolesConfirm` |
| 自定义胜 API | `CustomWinnerRole`、`AllowGameEnd`、`RoleUtils.customWinnerWin` |
| 恋人只读 | `SEModifiers.LOVERS` + `LoversComponent`（不重新注册） |
| 护盾爆炸参考 | `SuperLooseEndPlayerComponent` |
| 语音禁言参考 | `TrainVoicePlugin` / `MicrophonePacketEvent` |
| 修饰符脚手架 | `.claude/skills/adding-habitrain-modifier`；`HMLModifiers` + `WorldModifierComponent` |

## 11. 玩法文案来源

角色开局介绍与详细数值以 Mike 提供的设计稿为准（本规格机制与之对齐）。实现时 lang 写入：

- `announcement.star.role.<path>` / `announcement.star.goals.<path>`  
- `info.screen.roleid.<path>`  
- `skill.habitrain_core.*`  
- 美德 modifier 键见 §7.2  

## 12. 规格自检记录

- 无 TBD 占位；贪婪交易明确属 P3  
- 独立中立不占数 与 暴怒共享个人胜 一致贯穿 §6  
- 色欲不添加真正恋人 与 §4.3 / §5.6 一致  
- 慷慨不重复注册 与 §7 一致  
- 范围聚焦 habitrain_core；上游仅只读复用  
