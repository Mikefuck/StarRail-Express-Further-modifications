# Slice S5 — network 层审计发现

审计日期：2026-07-09
切片：S5 network 层
范围：`src/main/java/com/habitrain/core/network/*.java`（15 个 payload）+ 交叉核对 `client/network/PayloadSenders.java`
独立性声明：本报告仅基于源码事实从零审查，未参考仓库内任何既有审计/计划/报告。

## 文件覆盖确认表

| 文件 | 已读 | 说明 |
|------|------|------|
| network/ShaderConfigPayload.java | ✓ | S2C 光影白名单 |
| network/ShaderInfoPayload.java | ✓ | C2S 光影包名 |
| network/ConfigUpdatePayload.java | ✓ | C2S 配置更新 |
| network/BlackoutSheriffVotePayload.java | ✓ | S2C 警长投票状态 |
| network/BlackoutSheriffVoteCastPayload.java | ✓ | C2S 警长投票 |
| network/ActiveTaskPayload.java | ✓ | S2C 活跃任务 |
| network/TaskConfigPayload.java | ✓ | S2C 任务配置 |
| network/CustomTaskBlockPayload.java | ✓ | S2C 自定义任务方块 |
| network/BlackoutAnnouncePayload.java | ✓ | S2C 开局报幕 |
| network/BlackoutTimerPayload.java | ✓ | S2C 停电计时 |
| network/FullConfigSyncPayload.java | ✓ | S2C 全量配置 |
| network/BlackoutPhoneOpenPayload.java | ✓ | S2C 电话状态 |
| network/BlackoutHirePolicePayload.java | ✓ | C2S 聘请警察 |
| network/BlackoutVotePayload.java | ✓ | S2C 放逐投票 |
| network/BlackoutVoteCastPayload.java | ✓ | C2S 通用投票 |
| client/network/PayloadSenders.java | ✓ | 客户端发送辅助 |

交叉核对项：
- 所有 15 个 payload 的 `register()` 均在 `HabiTrainCore.java:94-108` 统一注册（payload 注册集中，无散落）。
- 服务端接收器统一在 `HabiTrainCore.java:267-361`；客户端接收器统一在 `HabiTrainCoreClient.java`（handler 注册集中，无散落）。
- 逐个 payload 核发送方：15 个 payload 全部存在发送方（无“只注册不发送”的 payload）。证据：
  - BlackoutAnnouncePayload：`BlackoutPoliceHireService.java:211`、`BlackoutSheriffResolver.java:69` 发送。
  - BlackoutPhoneOpenPayload：`HabiTrainCore.java:338`、`BlackoutPhoneHandler.java:70` 发送。
  - BlackoutHirePolicePayload：`PayloadSenders.sendHirePolice`（C2S）。
  - 其余均有 sendToPlayer/broadcastToAll 或 PayloadSenders 调用。
- codec 字段全部被读写（逐个核对 encode/decode 顺序一致，无遗漏字段）。唯一注意：TaskConfigPayload.decode 第83行 `mapFilterMode = Math.max(0, Math.min(2, buf.readInt()))`，但 mapFilterMode 在 JSON/UI 中始终被夹取到 0..2（`% 3`），故 encode 端写入值已在范围内，无数据丢失。
- 命名空间：所有 payload 均用 `habitrain_core` 命名空间（`HabiTrainCore.MOD_ID`/`HabiTrainCore.id()` 或硬编码 `"habitrain_core"`），payload 层无 `habitrain_taskapi` 字样。

## 发现列表

### S5-001 BlackoutVotePayload 每秒无变化门控全量广播（与 SheriffVoteBroadcaster 不一致）
- 文件：network/BlackoutVotePayload.java（经 game/blackout/BlackoutExileVoteManager.java 调用）
- 行：BlackoutExileVoteManager.java:115-124（tickSecond → broadcastState）、230-246（broadcastState）、248-271（buildEntryList）
- 维度：性能
- 严重度：S2
- 证据：`BlackoutExileVoteManager.tickSecond`（每 20 tick/秒调用一次，见 BlackoutTickCoordinator.java:68）在 `state.active` 期间每秒调用 `broadcastState`；`broadcastState` 每次都新建 `HashMap counts`、`HashMap nameCache`（遍历 `level.players()` 取名）、`List<Entry> entries` 并 `BlackoutVotePayload.broadcastToAll` 发给全体在线玩家。对照 `SheriffVoteBroadcaster`（同切片 S2C 投票）用 `computeHash` + `lastPayloadHash` 做内容去重门控（SheriffVoteBroadcaster.java:19-24），放逐投票路径完全没有等价门控。
- 影响：停电模式放逐投票进行期间（VOTE_DURATION_SECONDS），服务端每秒分配 3 个临时集合 + 构造 payload + 向所有在线玩家广播，即使票数/候选名单未变化也照发。玩家数多时每秒产生 O(玩家数) 的名字查询与 N 个玩家×M 候选的串行写包，属可量化但有限的每秒劣化（非每 tick）。
- 方向：为放逐投票路径引入与 SheriffVoteBroadcaster 等价的内容快照/哈希门控，状态未变化时跳过广播与集合重建。

### S5-002 ShaderConfigPayload 解码缺少 count 上限与 len 负值保护
- 文件：network/ShaderConfigPayload.java
- 行：52-63（decode）
- 维度：死逻辑/健壮性
- 严重度：S2
- 证据：`decode` 第54行 `int count = buf.readInt();` 无上限校验；第57行 `int len = buf.readInt(); len = Math.min(len, MAX_STRING_LENGTH);` 未先判 `len < 0`，`Math.min(负数, 65536)` 仍为负数，第59行 `new byte[len]` 触发 NegativeArraySizeException。对照同目录 TaskConfigPayload 对 count/len 均做 `<0 || >MAX` 范围校验并抛 DecoderException（TaskConfigPayload.java:51-58、66-68）。
- 影响：S2C 包，正常情况下服务端可信；但损坏/异常包会让客户端 decode 时以 NPE/NegativeArray 路径崩溃而非受控丢弃。count 无上限意味着恶意/异常服务端可发送超大 count 触发 OOM。
- 方向：对 count 与每个 len 增加范围校验（参考 TaskConfigPayload 的 MAX_* 常量 + DecoderException 模式）。

### S5-003 CustomTaskBlockPayload 解码缺少 entryCount/setCount 上限
- 文件：network/CustomTaskBlockPayload.java
- 行：36-52（decode）
- 维度：死逻辑/健壮性
- 严重度：S2
- 证据：`decode` 第37行 `int entryCount = buf.readInt(); if (entryCount < 0) entryCount = 0;` 只挡负数无上限；第45行 `int setCount = buf.readInt(); if (setCount < 0) setCount = 0;` 同样只挡负数。每个 entry 还读取 3 个 int 坐标 + setCount 个 int typeId。对照 TaskConfigPayload 对同类 size 做了 MAX_ENTRIES/MAX_MAPS_PER_ENTRY 上限校验。
- 影响：S2C 包，异常/恶意服务端发送超大 entryCount 或 setCount 时，客户端按 size 循环 new HashMap/HashSet + readInt，可触发内存放大与 OOM。`MapScannerMixin.java:173` 的 broadcastToAll 会把 CustomTaskBlockCache.snapshot() 全量发给所有玩家，若缓存规模失控则放大风险。
- 方向：为 entryCount 与 setCount 增加 MAX 上限校验，超限抛 DecoderException。

### S5-004 BlackoutSheriffVotePayload / BlackoutVotePayload 解码候选列表 size 无上限
- 文件：network/BlackoutSheriffVotePayload.java、network/BlackoutVotePayload.java
- 行：BlackoutSheriffVotePayload.java:39-46（readPlayers）、BlackoutVotePayload.java:40-47（readCandidates）
- 维度：死逻辑/健壮性
- 严重度：S2
- 证据：两处 `int size = buf.readVarInt();` 后直接 `new java.util.ArrayList<>(size)` 并按 size 循环读 Entry。size 无上限校验。readVarInt 最大可达约 2^31，`new ArrayList<>(2_000_000_000)` 会触发 OutOfMemoryError（或至少预分配失败）。
- 影响：S2C 包，异常/恶意服务端发送超大 size 时客户端 OOM。同切片其他 S2C 包（BlackoutTimerPayload/BlackoutPhoneOpenPayload）为定长字段无此问题。
- 方向：对候选列表 size 增加合理上限校验，超限抛 DecoderException 或截断。

### S5-005 habitrain_taskapi 资源目录孤立 + 命名空间混用
- 文件：src/main/resources/assets/habitrain_taskapi/** （icon.png、lang/zh_cn.json、lang/en_us.json）
- 维度：标识
- 严重度：S2
- 证据：fabric.mod.json:3 `"id": "habitrain_core"`，fabric.mod.json:10 icon 引用 `assets/habitrain_core/icon.png`。所有 payload 用 `habitrain_core` 命名空间。但资源树存在 `assets/habitrain_taskapi/`（icon.png + lang/zh_cn.json + lang/en_us.json），且其 lang 内容是 `assets/habitrain_core/lang/*` 的子集（task.* 键重复）。`habitrain_taskapi` 不是任何已注册 mod id，该资源目录不会被 Minecraft 自动加载。
- 影响：`habitrain_taskapi` 命名空间在源码中作为标识出现但与实际 mod id（`habitrain_core`）不一致，造成命名空间混用；该资源树是死资源（永不加载），lang 重复维护。下游 `client/gui/TaskEditScreen.java:192` 用 `"habitrain_taskapi".equals(def.getModId())` 判定“内置任务”，但内置任务实际用 `HabiTrainCore.MOD_ID`（`habitrain_core`）注册（BuiltinTaskRegistrar.java:47 等），故该判定恒为 false，所有内置任务被错标为“[外部/DLC任务]”——此为命名空间混用直接导致的功能性标识错误（虽 TaskEditScreen 超出本切片文件范围，但根因是 habitrain_taskapi 残留命名空间）。
- 方向：统一命名空间为 `habitrain_core`，删除孤立的 `assets/habitrain_taskapi/` 目录，并将 TaskEditScreen 内置判定改为 `HabiTrainCore.MOD_ID`（或常量）。

### S5-006 BlackoutAnnouncePayload 显示串上限魔法数字 32767
- 文件：network/BlackoutAnnouncePayload.java
- 行：20-30（readUtf(32767) / writeUtf(…, 32767)）
- 维度：标识
- 严重度：S3
- 证据：roleName/subtitle/goal 三段显示文本统一硬编码上限 `32767`（约 32KB/串，三段合计可近 96KB 单包）。32767 是 MC 旧式 chat 串上限魔法数字，与该包实际只承载短显示文本的语义不符，且未以命名常量表达。
- 影响：单包理论体积偏大；魔法数字散落且无语义命名，影响可维护性。无功能错误。
- 方向：将 32767 替换为按实际显示文本语义设定的命名常量（如 ROLE_NAME_MAX 等），收紧到合理上限。

### S5-007 BlackoutVotePayload.purpose 与 BlackoutVoteCastPayload.purpose 上限不一致
- 文件：network/BlackoutVotePayload.java、network/BlackoutVoteCastPayload.java
- 行：BlackoutVotePayload.java:36-37（purpose readUtf/writeUtf 上限 32）、BlackoutVoteCastPayload.java:24、34（purpose readUtf/writeUtf 上限 32）
- 维度：标识
- 严重度：S3
- 证据：两包 purpose 字段都用 32 字符上限。服务端 `BlackoutVoteCastPayload` receiver 用 `"EXILE".equals(payload.purpose())`（HabiTrainCore.java:357）做路由；`BlackoutVotePayload`（S2C 回显）同样承载 purpose 但客户端只判 `"EXILE".equals`（HabiTrainCoreClient.java:276）。purpose 作为枚举语义值（"EXILE" / 计划中的 "SHERIFF"）却以裸 String + 字面量比较在收发两端各写一次，无单一枚举/常量定义，易随新增 purpose 漏改一端。
- 影响：新增投票类型时需在收发两端多处同步字面量，漏改会导致路由缺失/静默丢弃。当前仅 EXILE，无即时错误。
- 方向：将 purpose 提取为枚举或常量集合，收发两端共用，避免字面量散落。