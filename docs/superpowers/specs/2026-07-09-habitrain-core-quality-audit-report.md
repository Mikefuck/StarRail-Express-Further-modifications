# HabiTrain Core 质量审查报告 (2026-07-09)

## 0. 元信息

| 项 | 值 |
|----|-----|
| 项目 | `哈比列车api` / `habitrain_core` |
| 审查设计 | `docs/superpowers/specs/2026-07-09-habitrain-core-quality-audit-design.md` |
| HEAD 基线 | `b019d0e` |
| 功能基线 | `3e44f72` + design commit |
| WIP | 10 modified + 1 untracked（§5） |
| 范围 | 全量 `src/main/java`（~159 文件） |
| 维度 | 性能 / 死逻辑 / 标识 / 耦合 |
| 方法 | 方案 A 十切片 + 历史对账 + HEAD/WIP + S0/S1 二次读码核实 |
| 本阶段 | 只出报告，不改业务代码 |

---

## 1. 执行摘要

### 1.1 计数（去重后主 finding）

| 严重度 | 约数 | 代表问题 |
|--------|------|----------|
| **S0** | 4 | 小游戏 token 恒 0；HEAD 死亡不 eliminate；结算 `getFaction` 默认 GOOD；跨局 TaskManager/Restore 泄漏 |
| **S1** | 22+ | 计时 HUD 单位错误；force onComplete；同步任务仍发奖；二次永久停电仍派 restore；瞬时假停电；tick 扇出；配置 clear+put 等 |
| **S2** | 25+ | 死 sheriff 栈、AABB 未落地、config 门控缺失、canAssign/tag、API 边界、命名空间 |
| **S3** | 12+ | AGENTS 过时、i18n、PRAY skip、空壳、冗余 flag |

### 1.2 Top 10（建议 R0 优先）

1. **[S0] S6-01** `MinigameRewardMixin` 无条件 `return 0` — 全模式 token 经济损坏  
2. **[S0] B2-01** HEAD 击杀不 `eliminate` — 胜负可卡（**WIP 已修，应合并**）  
3. **[S0] B2-06** `getFaction` 缺省 GOOD + `eliminate` 删 faction — 死人杀手可被标个人胜利  
4. **[S0/S1] L1-01/L1-05** Blackout 结束不清理 TaskManager / RestorePower 静态态 — 跨局污染  
5. **[S1] B2-07** Timer `endTimeTick`：服务端 `getTickCount()+秒`，客户端 `getGameTime()` — 倒计时失真  
6. **[S1] B2-02** 永久停电 force 换任务调 `onComplete` — 白嫖奖+时间副作用  
7. **[S1] B2-08** `SupplyTaskSyncHelper` 对同步者仍 `onComplete` — 违背「仅完成者有奖」  
8. **[S1] B2-09** 二次永久停电仍 `forceAssignRestorePower`  
9. **[S1] T3-07/T4-02** LookMyEyes 全玩家扫描 + 任意对局全员 BetelTick  
10. **[S1] N5-05** 配置 JSON 全量 clear+put — 可抹服配置  

### 1.3 与历史关系（一句话）

07-07 perf **大部分落地**但 blackout LookMyEyes 与 tick 扇出仍在；07-02 P0 大体完成、P1 静态化未做；07-08/09 overhaul **产品路径闭环**；20-issue 中 **token/同步奖/瞬时真断电/二次停电/config 门控** 有明确缺口或回归。

---

## 2. 分切片发现

### 2.1 生命周期与静态状态

#### [S0][耦合] L1-01: Blackout 结束不清理 TaskManager
- **位置:** `BlackoutMode.onEnd/onCleanup`；对比 `SREGameModeBase` 调 `clearAllActiveTasks()`
- **证据:** Blackout 结束只 broadcast/reset 投票商店等；无 TaskManager。
- **影响:** active/fake/rotation 跨局泄漏。
- **建议:** cleanup 统一 clear（并尽量 onRemove/reclaim）。
- **基线:** HEAD  

#### [S1][死逻辑] L1-02: `dlcTaskCounts` 永不重置
- **位置:** `TaskManager`；`clearDlcTaskCounts` 无调用；`clearAllActiveTasks` 不碰 counts  
- **影响:** 长时服反重复权重永久偏斜。  
- **基线:** HEAD  

#### [S1][死逻辑] L1-05: `RestorePowerHandler` 局终不清
- **位置:** `restoreCompleted`/`activeStates`；`GameLifecycleHandler` 清理列表无 Restore；仅 `triggerSREPermanentBlackout` 时 `resetCompleted`
- **影响:** 跨局恢复供电交互被挡 / 状态残留。  
- **基线:** HEAD  

#### [S1][耦合] L1-03: STOPPING/onCleanup 与 handler/Slowness 契约不全
- **位置:** STOPPING 清 Role/Timer/Vote/Hire/Shop；不清 TaskManager、Slowness、多数 handler、Horn（STOPPING）、BeAlone  
- **建议:** 单一 `BlackoutRuntime.clear(level)`。  
- **基线:** HEAD  

#### [S2][耦合] L1-04: RoleManager 不在 onCleanup clear
- **位置:** clear 在 onPreStart/STOPPING；onCleanup 只 `restoreVigilanteRoleMaxes`  
- **影响:** 局间窗口读到旧阵营。  
- **基线:** HEAD  

#### [S2][耦合] L1-06: Blackout 静态 manager 集群（07-02 A1 未完成）
- **基线:** HEAD  

**静态矩阵（摘要）** 见 §3.2。

---

### 2.2 Blackout 核心玩法

#### [S0][死逻辑] B2-01: HEAD 死亡不 eliminate
- **位置:** HEAD 无 `BlackoutDeathHandler`；WIP 已加 OnPlayerDeath→eliminate+victory  
- **影响:** 击杀后仍占存活计数，胜负可卡。  
- **建议:** 合并 WIP；`needs-ingame` 验证非 `GameUtils.killPlayer` 死亡源。  
- **基线:** HEAD 坏 / WIP 修  

#### [S0][正确性] B2-06: 淘汰后 `getFaction` 默认 GOOD
- **位置:** `BlackoutRoleManager.getFaction` → `getOrDefault(..., GOOD)`；`eliminate` 删除 factions 条目；`SREBlackoutGameMode.finalizeGame` 用 getFaction 判个人胜  
- **证据:**
```61:63:src/main/java/com/habitrain/core/game/blackout/BlackoutRoleManager.java
    public static Faction getFaction(ServerLevel level, UUID playerId) {
        return getOrCreate(level).factions.getOrDefault(playerId, Faction.GOOD);
    }
```
```118:121:src/main/java/com/habitrain/core/game/blackout/sre/SREBlackoutGameMode.java
                BlackoutRoleManager.Faction f = BlackoutRoleManager.getFaction(world, p.getUUID());
                boolean didWin = (winner != null && f == winner);
```
- **影响:** 已死杀手在好人胜局被标个人胜利。  
- **建议:** factionHistory 或从 roleHistory 推导；未知 UUID 勿默认 GOOD。  
- **基线:** 两者  

#### [S1][正确性] B2-07: 倒计时 endTimeTick 单位/时钟不一致
- **位置:** `BlackoutSyncManager.tickSecond` 用 `server.getTickCount() + 秒`；`BlackoutHudOverlay.getLocalCountdown` 用 `level.getGameTime()` 相减  
- **证据:** Sync L23-28；HUD L41-45。countdown 为**秒**却直接加到 tick 计数。  
- **影响:** 本地停电/维护倒计时与相位标记失真。  
- **建议:** 统一 `level.getGameTime()` + `seconds * 20L`。  
- **基线:** 两者  
- **标签:** needs-ingame  

#### [S1][经济] B2-02: forceAssign 调 `onComplete`
- **位置:** `BlackoutVictoryChecker.forceAssignRestorePowerToAllGood` L131-133  
- **影响:** 未完成任务白嫖奖励与时间轴副作用。  
- **建议:** onRemove/reclaim/fail only。  
- **基线:** HEAD  

#### [S1][经济] B2-08: 供电池同步完成仍发全员奖
- **位置:** `SupplyTaskSyncHelper.syncCompletion` 对同步者 `onComplete`  
- **证据:** 文件注释写「同步完成并拿到奖励」；与 Mike 20-issue 决策「synced 无奖」冲突。  
- **建议:** 同步仅 clear/fulfill，奖励只给 completer；时间效果全局只一次。  
- **基线:** HEAD  
- **标签:** needs-mike-decision（若改口保留同步奖）  

#### [S1][正确性] B2-09: 二次永久停电仍强制派 restore_power
- **位置:** NORMAL→FIRST 与 MAINTENANCE→SECOND 均调同一 `onPermanentStart` → `triggerSREPermanentBlackout` → 总是 forceAssign  
- **建议:** SECOND 跳过 restore 派发，只保断电。  
- **基线:** HEAD  

#### [S1][正确性] B2-10: 瞬时停电未接 SRE 真断电
- **位置:** `triggerTransientBlackout` 仅内部 flag+广播，不调 `SREWorldBlackoutComponent`  
- **对比:** 永久路径会 `triggerBlackout`  
- **建议:** 瞬时也走 SRE 组件并自动恢复（TRANSIENT_TICKS=140）。  
- **基线:** HEAD  

#### [S2][死逻辑] B2-03: 警长自动投票整栈死代码
- **位置:** auto-vote 注释禁用；client sheriff GUI 不可达；payload 仍注册  
- **基线:** HEAD  

#### [S2][标识] B2-04: 空 `onKillerReal/FakeTaskComplete`  
- **基线:** HEAD  

#### [S2][WIP 设计] B2-05: 雇警含杀手
- **位置:** WIP `getRandomHireTarget`  
- **副作用:** sheriffCount cap、`buy_gun`、revolver fallback  
- **标签:** needs-mike-decision  
- **基线:** WIP  

---

### 2.3 任务引擎

#### [S1][性能] T3-07: BlackoutLookMyEyes 每 tick `players()`
- **对比:** Builtin AABB 已修；blackout 端口未跟  
- **基线:** HEAD  

#### [S1][死逻辑] T3-01: Eat/Drink Handler 空壳  
- **基线:** HEAD  

#### [S1][死逻辑] T3-02: Pool 缓存烤入 once-per-game  
- **位置:** `TaskPoolBuilder` key 无 once 状态；assign 不 invalidate  
- **基线:** HEAD  

#### [S1][耦合] T3-06: Handler clearAll 全局 wipe Slowness  
- **基线:** HEAD  

#### [S2][死逻辑] T3-16: 池在 loadFromJson/applySync/setAll 不 invalidate（#16 残留）  
- **基线:** HEAD  

#### [S2][玩法] T3-17: 无 `hasBlockForTypeId` 门控（#18）  
- **基线:** HEAD  

#### [S2][玩法] T3-18: 发煤/火把等未 tagGrantedItem（#19）  
- **基线:** HEAD  

#### [S2][标识] T3-11: `raed_book`  
- **基线:** HEAD  

---

### 2.4 Tick 预算

#### [S1][性能] T4-01: ≥8 路 END_SERVER_TICK  
#### [S1][性能] T4-02: 任意对局全员 BetelTick+ExtraSlot  
#### [S2][性能] T4-04: 理智检测扫 level.players  

**Tick 总图** 见 §3.1。

---

### 2.5 网络与权限

#### [S1][耦合] N5-05: Config 全量 clear+put  
#### [S1][安全] N5-06: 编解码上限不一致（CustomTaskBlock 等）  
#### [S2][性能] N5-03: 放逐票每秒全服完整列表  
#### [S2][性能] N5-04: JOIN 叠 TaskConfig+FullConfig  
#### [S2][权限] N5-01: 玩法 C2S 边沿弱校验  
#### [S3][文档] N5-08: AGENTS 仍列已删 BlackoutStatusPayload  

---

### 2.6 SRE / mixin

#### [S0][正确性] S6-01: MinigameRewardMixin 代币恒 0
```34:40:src/main/java/com/habitrain/core/game/sre/mixin/MinigameRewardMixin.java
    private int habitrain$overrideTokenReward(int originalReward) {
        try {
            return 0;
        } catch (Throwable t) {
            return originalReward;
        }
    }
```
- gold/emotion 仅在有 entry 时附加；**token 永不恢复**。与「replaceToken 默认关」决策冲突。  
- **基线:** HEAD · **标签:** needs-ingame  

#### [S1][性能] S6-02: MapScanner 二遍全体积  
#### [S1][耦合] S6-03: GenerateTask 总替换  
#### [S2] S6-05 CanEat 非 blackout 限定 · S6-08 死 onSre* 方法 · S6-06 雨全局 intentional  

#### [S2][配置] S6-13: 双截棍 CD / 隐身 reapply 无 config 门控（#12/#13）  
- **基线:** HEAD  

---

### 2.7 Client

#### [S1][死逻辑] C7-01: 警长投票 GUI 不可达（且服务端已停 auto）  
#### [S2] C7-02 双 blackoutModeActive · C7-03 死 GlobalSettings · C7-04 habitrain_taskapi · C7-05 每帧全 cache · C7-07 客户端读 TaskManager  

---

### 2.8 Config / Betel

#### [S1][耦合] C8-01: 强制 `enableAddictionSystem=true`  
#### [S1] C8-02/T4-02 Betel 全员 tick  
#### [S1] C8-04 可变 config 集合泄漏  
#### [S2] C8-05 API→TaskPoolBuilder · C8-06 java.awt.Color 客户端  

---

### 2.9 历史对账（完整表）

| 源+ID | 摘要 | 现状 | 证据要点 | 并入 |
|-------|------|------|----------|------|
| 07-07 T1 | 删 Engine/StatusPayload | 已修 | 源码无类；AGENTS 过时 | 文档 S3 |
| 07-07 T2 | look_my_eyes AABB | **部分** | Builtin 已修；Blackout 未修 | **T3-07** |
| 07-07 T3 | Slowness 合并 | 部分 | 有 Manager；多 tick+全局 clear | T3-06/T4 |
| 07-07 T4–T11 | Iris/拆分/缓存/diff | 大体已修 | 类存在 | 残留另报 |
| 07-02 P0 | 扣款/命令/charset/长度 | 大体已修 | 抽样 | N5-06 残留 |
| 07-02 A1 | 去静态 | **未修** | static Map | L1-06 |
| 07-02 A2–A6 | API/awt/volatile… | 大部分已修 | | C8-05/06 残留 |
| 07-02 P2 i18n | literal 中文 | 未修 | 大量 literal | S3 |
| 07-08 M1–M4 | 电话/汽笛/雨/语音 | 已修+WIP | | §5 |
| 07-09 C2 死亡 eliminate | | **WIP 已修** | DeathHandler | B2-01 |
| 20-#2/#3 同步奖 | 同步无奖 | **未对齐** | SupplyTaskSyncHelper | **B2-08** |
| 20-#5/#6 瞬时真断电 | | **未对齐** | 仅 flag | **B2-10** |
| 20-#7 二次停电 | 不派 restore | **未对齐** | 同回调 forceAssign | **B2-09** |
| 20-#4 牛奶 | | 部分 | 无 MilkBucket | S2 |
| 20-#12/#13/#14 | config 门控 | 未修/回归 | Mixin 硬编码 | S6-01/S6-13 |
| 20-#16 池 invalidate | load/sync | 部分 | 仅 setTaskConfig/freeze | T3-16 |
| 20-#18/#19 | canAssign/tag | 部分/未修 | | T3-17/18 |
| 20-#8 PRAY | | 未修 | skip | S3 |
| AGENTS payload | | 文档回归 | StatusPayload | S3 |

---

### 2.10 HEAD vs WIP

| 文件 | 行为摘要 | 风险 | 建议 |
|------|----------|------|------|
| `BlackoutDeathHandler` 新 | 死亡 eliminate+胜负 | 正确修复 | **合并** |
| `BlackoutPoliceHireService` | 杀手可雇；禁自雇；lock；force GOOD；+200+枪 | 设计漂移(已批)+cap/buy_gun | 确认后合并 |
| `BlackoutRoleManager` | hire target；eliminate→exile 清票 | 正确 | 合并 |
| `BlackoutExileVoteManager` | 死人清票；防重入 | 正确 | 合并 |
| Horn/Mode/Phone/SREGameModeBase/HabiTrainCore | clearAll、存活门、语音 STARTING/断线 | 正确 | 合并 |
| PhoneHireScreen / Weather javadoc | 文案/注释 | 无 | 随手 |

**WIP 新副作用：** killer-sheriff 占 cap、`buy_gun`、once_revolver 缺失 fallback 满左轮。

---

## 3. 跨切片主题

### 3.1 Tick 预算总图

| 源 | 频率 | 成本 | 门控 |
|----|------|------|------|
| ModTickHandler | 每 tick | 中 | 部分 |
| Blackout 1s 工作 | 1 Hz | 中 | 活跃 |
| Betel+ExtraSlot 全员 | 每 tick | **高** | 任意对局 |
| 多 handler END_TICK | 每 tick | 中 | 本地 map |
| PerPlayerTaskTicker | 每玩家 | **高** | SRE |
| LookMyEyes onTick | 每 tick | 中高 | 任务中 |
| 客户端渲染 cache | 每帧 | 中 | blackout |

### 3.2 静态状态矩阵（关键行）

| 持有者 | 局终 cleanup | STOPPING | 跨局风险 |
|--------|--------------|----------|----------|
| RoleManager | 否（下局 preStart） | 是 | 中 |
| Timer/Vote/Hire/Shop | 是 | 是 | 低 |
| TaskManager | **否** | **否** | **高** |
| RestorePower | **否** | **否** | **高** |
| Slowness/Handlers | 依赖 lifecycle 边沿 | 否 | 中高 |
| BeAlone counters | 否 | 否 | 中 |

### 3.3 注册面 · 3.4 重复簇 · 3.5 边界
- 入口上帝类 `HabiTrainCore`；24 mixin；C2S 内联  
- Handler 家族 / 双投票栈 / 双 broadcast / 双 GUI 常量  
- Blackout→SRE CCA 直连；api→task；client→TaskManager；强制 BetelNutConfig  

---

## 4. 历史清单对账表

见 **§2.9**。

---

## 5. HEAD vs WIP

见 **§2.10**。

---

## 6. 修复分批骨架

| 批次 | 内容 |
|------|------|
| **R0 正确性** | S6-01 token；合并 WIP 死亡/exile/voice；B2-06 faction 历史；L1-01/02/05 统一 clear；B2-02 force onComplete；B2-08 同步奖；B2-09 二次停电；B2-10 瞬时真断电；B2-07 计时单位；T3-02 once cache |
| **R1 性能** | T3-07 AABB；T4-01/02 tick+betel 门控；N5-03/04 广播与 JOIN；S6-02 mapscan；C7-05 渲染 |
| **R2 死代码** | B2-03/C7-01 删 sheriff 死栈；T3-01 空 handler；死 GlobalSettings；空 hooks；AGENTS 表 |
| **R3 边界去重** | L1 统一 runtime clear；T3-06 按源 slowness；handler 模板；C8-05 API 回调；client 单 session flag |
| **R4 标识文档** | 命名空间；ARGB；config 门控字段+ModMenu；canAssign/tag；i18n；raed_book 显示 |

**合 WIP 前建议 Mike 确认：**  
1) killer-sheriff 是否占 `police≤killer`  
2) 是否允许 `buy_gun`  
3) 同步供电池是否仍发奖  
4) 强制 betel 成瘾是否保持  

每批：`./gradlew clean build` + JAR → `临时\`。

---

## 7. 不在范围 / 不修

- 纯风格、行数多  
- DLC 源码改动  
- 已删除 Engine/StatusPayload（仅文档）  
- 无证据臆测  
- 已批雇警语义本身（只报副作用）  

---

## 附录

### A. 命名空间
`habitrain_core` · `habitrains:blackout` · registry `habitrain_core:habitrains:blackout` · `sre:blackout` · 遗留 `habitrain_taskapi`

### B. 本会话直接核实的 S0/S1
MinigameReward `return 0` · Blackout 无 TaskManager clear · dlcTaskCounts · force onComplete · LookMyEyes players() · 空 Eat/Drink · Pool cache · 全员 betel · 强制成瘾 · sheriff auto 关 · getFaction 默认 GOOD · endTimeTick 单位 · SupplyTaskSync onComplete · transient 无 SRE 组件 · 二次停电同回调 · Restore 不清  

### C. 局限
未 20 人实机压测；SRE 内部时序未全反编译；20-issue 以代码+memory 为准。

---

**下一步：** 确认本报告 → writing-plans 生成  
`docs/superpowers/plans/2026-07-09-habitrain-core-quality-remediation.md` → 按 R0–R4 实施。
