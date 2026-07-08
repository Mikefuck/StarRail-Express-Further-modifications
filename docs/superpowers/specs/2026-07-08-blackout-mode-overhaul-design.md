# 停电模式改造设计规格书

日期：2026-07-08
项目：哈比列车api（Fabric 1.21.1）
状态：已批准

## 改造总览

本次改造覆盖四个模块：

1. **电话雇佣警察** — 取消开局自动警长投票，改为 2 分钟后通过电话方块花费 300 金币雇佣警察
2. **汽笛放逐投票** — 通过汽笛方块二次拉动后花费 500 金币发起放逐投票
3. **人数不足 8 人下雨** — 对局内活跃人数 < 8 时强制主世界下雨
4. **Simple Voice Chat 大厅群组修复** — 重试队列确保新玩家在 voicechat 就绪后加入大厅群组

## 设计决策记录

| 问题 | 决策 |
|------|------|
| Q1: 电话透视渲染方式 | 即使玩家无活跃任务，电话方块也保持高亮 |
| Q2: 警察 ≤ 杀手约束 | 聘请后 sheriffCount + 1 不得超过 killerCount |
| Q3: 放逐候选人范围 | 所有对局内存活玩家（含发起者） |
| Q4: 多维度天气 | 只考虑主世界，当前无其他维度 |
| Q5: 对局结束语音群组恢复 | 惰性入队模式：pendingVoiceJoins 为空时再入队所有人 |
| Q6: SREBlackoutGameMode | 无需修改；disableAllVigilanteRoles() 已是正确行为 |

---

## 1. 电话雇佣警察

### 1.1 停用自动警长投票

**修改点：**

- `BlackoutSheriffVoteManager`
  - 移除或注释 `tickSecond(...)` 中 `VOTE_OPEN_DELAY_SECONDS = 60` 后的 `startVote(...)` 调用
  - 类保留作为兼容/引用
- `BlackoutTickCoordinator`
  - 删除第 65-66 行 `BlackoutSheriffVoteManager.tickSecond(level).ifPresent(...)` 调用
- `BlackoutMode.onPreStart/onCleanup`
  - `BlackoutSheriffVoteManager.reset(level)` 保留（清理旧状态），
  - 追加 `BlackoutPoliceHireService.reset(level)` 和 `BlackoutExileVoteManager.reset(level)`

### 1.2 新增文件：`BlackoutPoliceHireService.java`

路径：`src/main/java/com/habitrain/core/game/blackout/BlackoutPoliceHireService.java`

**关键常量：**

```java
private static final int UNLOCK_SECONDS = 120;
private static final int HIRE_COST = 300;
```

**状态（按 dimension 隔离）：**

- `gameStartTick`：记录游戏开始的游戏刻数
- `hasHired: Set<UUID>`：本局已雇佣过的玩家

**校验顺序（重复校验，服务端不信任客户端）：**

1. 必须处于停电模式对局中
2. 电话功能已解锁（游戏开始 ≥ 120 秒）
3. 发起者本局未雇佣过警察
4. 发起者金币余额 ≥ 300
5. `killerCount > 0`
6. `sheriffCount + 1 <= killerCount`
7. 有存活、好人阵营、非警察的候选玩家

**成功流程：**

1. 从候选好人中随机选择目标
2. 获取随机警察职业 `BlackoutRoleManager.getRandomPoliceRole(...)`
3. 扣除发起者 300 金币 `SREPlayerShopComponent.KEY.get(player).addToBalance(-HIRE_COST)`
4. 标记发起者本局已雇佣
5. 调用 `BlackoutRoleManager.setSheriff(level, targetId, policeRole, null)`
6. 给目标发送 `BlackoutAnnouncePayload`，让其看到新警察职业介绍
7. 全图顶部提示："收到<举报者>举报，<新警长>警长前来调查"

**失败提示（顶部提示，非聊天栏）：**

- 未解锁：`报警线路尚未接通`
- 金币不足：`话费不足，需要300`
- 本局已雇佣：`你本局已经拨打过110`
- 警察会多于杀手：`当前警力已足够，无法继续聘请`
- 无候选好人：`当前没有可转职的好人`
- 无警察职业池：`当前警察职业池为空`

### 1.3 交互与 GUI

**新增文件：**

- `BlackoutPhoneHandler.java` — 监听 `yuushya:street_phone` 右键
- `BlackoutPhoneOpenPayload.java` (S2C) — 打开 GUI 所需状态
- `BlackoutHirePolicePayload.java` (C2S) — 客户端发起聘请
- `BlackoutPhoneHireScreen.java` — 客户端 GUI

**交互流程：**

1. 服务端 `UseBlockCallback` 监听 `yuushya:street_phone` 右键
2. 只处理主手、服务端、停电模式对局内、玩家存活
3. 发送 `BlackoutPhoneOpenPayload` 给玩家，携带解锁剩余秒数、余额、雇佣状态、警察数、杀手数
4. 客户端 GUI 复用 `BlackoutSheriffVoteScreen` 的居中面板风格
5. 中间一个按钮：可点击时显示"拨打 110"，不可点击时置灰
6. 按钮下方固定显示："花费300话费拨打110"
7. 点击按钮发送 `BlackoutHirePolicePayload`
8. 服务端收到 C2S 后再次完整校验（不信任客户端状态）

### 1.4 电话常量透视

**新增文件：** `BlackoutOverlayTypes.java`

路径：`src/main/java/com/habitrain/core/game/blackout/BlackoutOverlayTypes.java`

```java
public class BlackoutOverlayTypes {
    public static final int STREET_PHONE = 90;
}
```

**`MapScannerMixin` 修改：**

- 扫描区域时额外识别 `yuushya:street_phone`
- 调用 `CustomTaskBlockCache.put(pos, STREET_PHONE, block)` 存入缓存

**`CustomTaskBlockRendererMixin` 修改：**

- 新增常量方块渲染通道 `renderConstantOverlays(...)`
- 在生存模式分支中，如果停电模式激活，无条件渲染 `STREET_PHONE` 方块
- 颜色使用醒目的蓝黄色或金色，避免与现有任务颜色混淆
- 大厅阶段不渲染

---

## 2. 汽笛放逐投票

### 2.1 新增文件：`BlackoutHornVoteHandler.java`

路径：`src/main/java/com/habitrain/core/game/blackout/BlackoutHornVoteHandler.java`

**交互逻辑：**

- `UseBlockCallback` 监听 `trainmurdermystery:horn`
- 只处理服务端、主手、停电模式对局内、玩家存活

**第一次拉动：**

- 不扣钱
- 发送 MC 原生标题："再次拉动发动投票"
- 记录该玩家进入确认窗口，10 秒内有效
- 玩家死亡/淘汰时清除确认窗口

**第二次拉动：**

- 校验余额 ≥ 500
- 校验当前没有进行中的放逐投票
- 扣除 500 金币
- 调用 `BlackoutExileVoteManager.startVote(level, initiator)`

**失败提示：**

- 金币不足：顶部提示 `发动投票需要500`
- 已有投票：顶部提示 `当前已有投票正在进行`
- 不在对局：不处理或提示 `当前不在停电对局中`

### 2.2 新增文件：`BlackoutExileVoteManager.java`

路径：`src/main/java/com/habitrain/core/game/blackout/BlackoutExileVoteManager.java`

**状态（按 dimension 隔离）：**

- `active: boolean`
- `remainingSeconds: int`
- `candidateOrder: List<UUID>`
- `votesByVoter: Map<UUID, UUID>`
- `initiatorId: UUID`

**投票时长：** 15 秒

**候选人：** 所有对局内存活玩家（含发起者）

**投票规则：**

- 每名存活玩家最多投 1 票
- 无人投票 → 不放逐
- 最高票唯一 → 放逐
- 平票 → 从最高票并列玩家中随机选 1 人放逐

**结算流程：**

1. `active = false`
2. 无人投票 → 全图顶部提示 `无人投票，本轮无人被放逐`
3. 选中玩家：
   - `GameUtils.killPlayer(target, true, null, ResourceLocation.fromNamespaceAndPath("habitrain_core", "exile_vote"))`
   - `BlackoutRoleManager.eliminate(level, target.getUUID())`
   - 胜负检查（防止放逐最后一个杀手或好人后不结算）
   - 全图顶部提示 `投票结束，<玩家> 被放逐`

**死亡原因本地化：**

`assets/habitrain_core/lang/zh_cn.json`：
```json
"death_reason.habitrain_core.exile_vote": "被放逐"
```

`assets/habitrain_core/lang/en_us.json`：
```json
"death_reason.habitrain_core.exile_vote": "Exiled"
```

### 2.3 通用投票 GUI

**新增文件：**

- `BlackoutVotePayload.java`（S2C）— 通用投票状态：purpose、active、remainingSeconds、totalSeconds、maxSelections、title、candidates
- `BlackoutVoteCastPayload.java`（C2S）— purpose、targetPlayerId
- `BlackoutVoteState.java`（客户端）— 通用投票客户端状态
- `BlackoutVoteScreen.java`（客户端）— 从旧 `BlackoutSheriffVoteScreen` 抽取

**设计要点：**

- `purpose` 区分 `EXILE` 和未来可能的其他投票类型
- 放逐投票标题显示"放逐投票"
- 列表显示玩家名和票数
- 每行可点击投票（1 人选 1 票）
- 服务端广播投票状态，客户端自动打开 GUI
- 玩家可关闭 GUI，V 键可重新打开当前投票

---

## 3. 人数不足 8 人下雨

### 新增文件：`SREWeatherController.java`

路径：`src/main/java/com/habitrain/core/game/sre/SREWeatherController.java`

**挂载点：** `ModTickHandler.tickMoreMods(server)` 中每 20 tick 检查一次

**判定逻辑：**

- 只在 SRE 对局运行中生效（大厅阶段不触发）
- 检测主世界活跃人数 < 8
- 停电模式中使用 `BlackoutRoleManager.getAllAlive(level).size()`

**天气控制：**

```java
// 强制下雨（20*60=1200 tick = 1 分钟降雨）
level.setWeatherParameters(0, 1200, true, false);
```

**状态跟踪：**

- `forcedRainByLowPlayers: boolean` — 标记是否由本机制触发的雨
- 只清理由本机制触发的雨，避免误清自然天气

**恢复条件（人数 ≥ 8 或对局结束）：**

```java
level.setWeatherParameters(1200, 0, false, false);
forcedRainByLowPlayers = false;
```

**验收：**

- 对局内 7 人开始下雨
- 对局内 8 人不强制下雨
- 大厅阶段不触发
- 对局结束后不持续强制下雨

---

## 4. Simple Voice Chat 大厅群组修复

### 4.1 改造

`SREGameModeBase.java` 新增：

```java
public static void queueLobbyGroupJoin(MinecraftServer server, UUID playerUUID)
private static boolean tryAddPlayerToLobbyGroup(MinecraftServer server, UUID playerUUID)
public static boolean isAnySreGameRunning(MinecraftServer server)
```

`tryAddPlayerToLobbyGroup(...)` 返回 boolean：

- voicechat 缺失 → false
- `SERVER_API == null` → false
- connection 未建立 → false
- 成功 `setGroup` → true

### 4.2 JOIN 事件修改

`HabiTrainCore.registerLifecycleEvents()` 中 JOIN 逻辑：

- 如果当前没有 SRE 对局运行 → 调用 `queueLobbyGroupJoin(server, player.getUUID())`
- 如果有对局运行 → 不入队，避免把游戏中的玩家拉进大厅群组

### 4.3 队列处理修复

修复 `processPendingVoiceJoins(...)`：

- 玩家离线 → 移除
- 重试次数耗尽 → 移除并记录日志
- `tryAddPlayerToLobbyGroup(...) == true` → 移除
- 否则保留并递减重试次数

`MAX_VOICE_JOIN_RETRIES = 400`（约 20 秒）

### 4.4 对局结束处理

`processGameEndGroupJoin(...)` 改为惰性入队模式：

- 不直接调用 `addPlayerToLobbyGroup`
- 将当前所有在线玩家入队 `pendingVoiceJoins`（如果 `pendingVoiceJoins` 当前为空）
- 由 pending 队列重试，避免对局结束瞬间 voicechat connection 尚未恢复

---

## 5. 文件清单

### 新增文件（13 个）

| 文件 | 模块 |
|------|------|
| `game/blackout/BlackoutPoliceHireService.java` | 1 |
| `game/blackout/BlackoutPhoneHandler.java` | 1 |
| `game/blackout/BlackoutHornVoteHandler.java` | 2 |
| `game/blackout/BlackoutExileVoteManager.java` | 2 |
| `game/blackout/BlackoutOverlayTypes.java` | 1 |
| `game/sre/SREWeatherController.java` | 3 |
| `network/BlackoutPhoneOpenPayload.java` | 1 |
| `network/BlackoutHirePolicePayload.java` | 1 |
| `network/BlackoutVotePayload.java` | 2 |
| `network/BlackoutVoteCastPayload.java` | 2 |
| `client/gui/BlackoutPhoneHireScreen.java` | 1 |
| `client/gui/BlackoutVoteScreen.java` | 2 |
| `client/gui/BlackoutVoteState.java` | 2 |

### 修改文件（12 个）

| 文件 | 模块 | 改动 |
|------|------|------|
| `HabiTrainCore.java` | 1,2,4 | 注册新 payload、handler、JOIN 语音入队逻辑 |
| `ModTickHandler.java` | 3 | tick 天气控制器 |
| `BlackoutMode.java` | 1,2 | preStart/reset/cleanup 新服务 |
| `BlackoutTickCoordinator.java` | 1 | 移除自动警长投票 tick，tick 新服务 |
| `BlackoutSheriffVoteManager.java` | 1 | 停用自动 60s 开票 |
| `BlackoutRoleManager.java` | 1 | 新增获取非警察好人候选人的 helper |
| `game/sre/mixin/MapScannerMixin.java` | 1 | 扫描电话常量方块 |
| `client/mixin/CustomTaskBlockRendererMixin.java` | 1 | 渲染常量透视方块 |
| `client/HabiTrainCoreClient.java` | 1,2 | 注册 S2C 接收器，自动打开放逐投票 GUI |
| `client/BlackoutKeyHandler.java` | 2 | V 键打开当前 active vote |
| `client/network/PayloadSenders.java` | 1,2 | 新增 C2S 发送方法 |
| `game/sre/SREGameModeBase.java` | 4 | 改造语音群组 retry 队列 |
| `assets/habitrain_core/lang/zh_cn.json` | 2 | 死亡原因、GUI 文本 |
| `assets/habitrain_core/lang/en_us.json` | 2 | 同上 |

---

## 6. 实施顺序

1. 停用自动警长投票，保证开局不会再自动刷警察
2. 抽取通用投票 UI/状态，避免后续电话和放逐同时改旧 screen
3. 实现电话雇佣警察服务和 payload
4. 接入 `yuushya:street_phone` 右键 GUI
5. 接入电话常量透视
6. 实现汽笛二次确认和扣 500 发起放逐投票
7. 实现放逐投票结算、死亡原因、SRE 回放链路
8. 添加人数不足 8 下雨控制器
9. 修复 Simple Voice Chat 大厅群组延迟重试
10. 全量构建并复制 jar 到 `D:\Backup\mc mod\临时`

---

## 7. 验收清单

### 电话雇佣警察
- [ ] 开局 0 秒没有警察阵营角色
- [ ] 开局 60 秒不会自动弹出警长投票
- [ ] 开局 120 秒前右键电话不能聘请
- [ ] 开局 120 秒后右键 `yuushya:street_phone` 打开 GUI
- [ ] GUI 中间有可交互按钮，下面显示"花费300话费拨打110"
- [ ] 玩家余额不足 300 时不能聘请
- [ ] 每名玩家每局最多成功聘请一次
- [ ] 聘请后如果警察数会大于杀手数，禁止聘请
- [ ] 场内至少可以通过电话产生第一名警察
- [ ] 成功聘请后随机存活好人变成警察职业
- [ ] 被转职玩家收到警察职业介绍
- [ ] 全图收到顶部提示
- [ ] `yuushya:street_phone` 在停电对局中作为常量透视方块显示

### 汽笛放逐投票
- [ ] 右键 `trainmurdermystery:horn` 第一次只显示 MC 原生标题"再次拉动发动投票"
- [ ] 有效时间内再次右键 horn 且余额 ≥ 500 时发起放逐投票并扣钱
- [ ] 放逐投票 GUI 列表为当前对局内存活玩家
- [ ] 无人投票时不放逐
- [ ] 最高票唯一时放逐最高票玩家
- [ ] 平票时从最高票并列玩家中随机放逐 1 人
- [ ] 被放逐玩家走 `GameUtils.killPlayer()`，回放中能看到死亡
- [ ] 放逐后胜负条件能立即检查

### 下雨机制
- [ ] 对局人数 7 人时开始下雨
- [ ] 对局人数 8 人不强制下雨
- [ ] 大厅阶段不触发
- [ ] 对局结束后不持续强制下雨

### Voice Chat 修复
- [ ] 新玩家在对局外加入世界后，等待 voicechat connection 建立后进入 LobbyChat
- [ ] 新玩家在对局进行中加入世界时，不会被拉进大厅语音群组
- [ ] 对局结束后所有在线玩家通过重试队列进入大厅语音群组
