# Slice S11 — client.gui Blackout 侧审计发现

审查范围（7 文件，全读过）：
- BlackoutHudOverlay.java
- BlackoutWelcomeRenderer.java
- BlackoutVoteScreen.java
- BlackoutVoteState.java
- BlackoutSheriffVoteScreen.java
- BlackoutSheriffVoteState.java
- BlackoutPhoneHireScreen.java

独立性声明：本切片仅基于源码事实判断，未参考仓库内任何既有审计/计划/报告。

## 文件覆盖确认表

| 文件 | 是否已通读 | 行数 |
|---|---|---|
| BlackoutHudOverlay.java | 是 | 1-134 |
| BlackoutWelcomeRenderer.java | 是 | 1-100 |
| BlackoutVoteScreen.java | 是 | 1-174 |
| BlackoutVoteState.java | 是 | 1-67 |
| BlackoutSheriffVoteScreen.java | 是 | 1-174 |
| BlackoutSheriffVoteState.java | 是 | 1-83 |
| BlackoutPhoneHireScreen.java | 是 | 1-155 |

## 发现列表

### S11-001 BlackoutHudOverlay 每帧多次调用 getLocalCountdown 重复访问 level
- 文件: BlackoutHudOverlay.java:86-87,106
- 维度: 性能
- 严重度: S2
- 证据: render 每帧执行；第86行 `getLocalCountdown() > 0` 调一次，第87行 markerX 计算又调一次 `getLocalCountdown()`，第106行 `getLocalCountdown() > 0` 第三次，第108行 `formatTime(getLocalCountdown())` 第四次。getLocalCountdown() 每次都 `Minecraft.getInstance().level` + `level.getGameTime()`（第42-45行）。
- 影响: 每帧 4 次取 level/游戏时间，HUD overlay 是热路径（每帧渲染）。虽单次开销小，但属于可避免的重复查找。
- 方向: 在 render 入口或条件块内取一次本地值缓存为局部变量复用。

### S11-002 BlackoutHudOverlay.totalDuration 仅单调增长，reset 重置但跨轮次不收敛
- 文件: BlackoutHudOverlay.java:20,26-28,48-56
- 维度: 死逻辑
- 严重度: S2
- 证据: updateTime 第26-28行 `if (total > totalDuration) totalDuration = total;` 仅在更大时更新，永不下调；reset 把 totalDuration 设回 300。每次新对局开始若总时长不同（例如从 600 回到 300），totalDuration 仍保留 600，导致进度条 elapsed/totalDuration 比例失真。
- 影响: 跨对局 totalDuration 不收敛，进度条 filledW/markerX/warningX 全部基于 totalDuration 计算（第74、87、91行），比例错误显示。
- 方向: 每次 updateTime 直接以服务端 total 重置 totalDuration，而非仅取大值。

### S11-003 BlackoutHudOverlay.cachedEndTimeTick 默认 0 时 markerX 计算可能除零/异常
- 文件: BlackoutHudOverlay.java:22,86-89
- 维度: 死逻辑/标识
- 严重度: S2
- 证据: cachedEndTimeTick 是 long 默认 0。第87行 markerX = barX + (totalDuration - getLocalCountdown()) / totalDuration * barW，getLocalCountdown 在 cachedEndTimeTick=0 时返回 `Math.max(0, 0 - now)` = 0，故 markerX = barX + totalDuration/totalDuration*barW = barX+barW（右端），靠 `getLocalCountdown() > 0` 守卫（第86行）保证未设时不画，但语义不清：用“剩余倒计时>0”作为“已设置 endTime”的代理，二者不等价。
- 影响: 0 值 sentinel 与“未设置”语义混用；若 cachedEndTimeTick 被设成过去时刻会画错标记。
- 方向: 用单独的 boolean 或 long sentinel（如 -1）显式表示“未设置”，与倒计时计算解耦。

### S11-004 BlackoutVoteState.maxSelections 字段写入但无读取
- 文件: BlackoutVoteState.java:14,28
- 维度: 死逻辑
- 严重度: S2
- 证据: 第14行声明 `maxSelections`，第28行 `maxSelections = payload.maxSelections();` 写入；全仓 grep 无 getMaxSelections，无任何读取点。toggleSelection（第60-66行）只支持单选（selectedTargetId 单 UUID），完全忽略 maxSelections。
- 影响: 服务端下发的多选能力被静默丢弃；UI 只能单选，maxSelections>1 的投票场景功能缺失或与设计不符。
- 方向: 若设计就是单选，移除字段与 payload 字段；若需多选，toggleSelection 需按 maxSelections 限制。

### S11-005 BlackoutVoteState.totalSeconds 与 SheriffVoteState 的 getTotalSeconds/getTimerText 为死方法
- 文件: BlackoutVoteState.java:52, BlackoutSheriffVoteState.java:49,81
- 维度: 死逻辑
- 严重度: S3
- 证据: grep `getTotalSeconds`/`getTimerText` 全仓无任何调用点（仅定义处）。totalSeconds 字段仍写入（VoteState 第27行、SheriffVoteState 第22行）。
- 影响: 死方法/字段，维护噪音。
- 方向: 移除未被调用的 getter 与对应字段，或在 UI 中实际使用。

### S11-006 BlackoutWelcomeRenderer.startWelcome 参数 killers/targets 未使用
- 文件: BlackoutWelcomeRenderer.java:24
- 维度: 死逻辑
- 严重度: S2
- 证据: `startWelcome(String name, String sub, String g, int killers, int targets)` 第24行，方法体内仅赋值 roleName/subtitle/goal/welcomeTime（第25-28行），killers 与 targets 完全未引用。HabiTrainCoreClient.java:263-265 调用时仍传入 payload.killerCount()/targetCount()。
- 影响: 调用方传递的杀手/目标数信息被丢弃；若设计要求显示人数（如“本局 N 杀手 M 目标”），功能缺失。
- 方向: 要么使用参数渲染人数，要么删除参数与调用端多余传参。

### S11-007 BlackoutWelcomeRenderer.getRoleName 死方法
- 文件: BlackoutWelcomeRenderer.java:33-35
- 维度: 死逻辑
- 严重度: S3
- 证据: getRoleName 定义第33行，grep 全仓仅命中定义处，无调用。
- 影响: 死方法维护噪音。
- 方向: 移除或在别处实际使用。

### S11-008 BlackoutHudOverlay.setVisible 死方法
- 文件: BlackoutHudOverlay.java:39
- 维度: 死逻辑
- 严重度: S3
- 证据: setVisible 定义第39行，grep `setVisible(` 仅命中定义，无任何调用点；showHud 实际由 updateTime/reset 控制（第33、52行）。
- 影响: 死方法；外部若以为可通过它控制可见性，但实际无人调用，可能误导。
- 方向: 移除 setVisible 或改由实际触发点调用。

### S11-009 BlackoutHudOverlay 大量坐标/颜色/时长魔法数字
- 文件: BlackoutHudOverlay.java:15,17,20,21,65-68,70-71,77,82,88,91-92,100,104,107
- 维度: 标识
- 严重度: S3
- 证据: 硬编码 300（第15、20、49、54行）、60（TIME_WARNING_SECONDS）、220/11/2（barW/barY/barH 第65-68行）、颜色 0x332A3642/0x88262E38/0xFF596573/0xFFFFD84B/0xFF4AC06A/0xFFFF6A6A/0xFFFFFF00 等散落各处、phase==2 判断“剩余供电时间”语义（第107行魔法 phase 数字）。
- 影响: 可维护性差，phase 数字与中英混用（“对局剩余时间”/“停电中”/“剩余供电时间”）散落代码。
- 方向: 提取颜色/尺寸常量，phase 用枚举或命名常量。

### S11-010 BlackoutWelcomeRenderer 静态可变状态网，跨实例共享且无隔离
- 文件: BlackoutWelcomeRenderer.java:17-21
- 维度: 耦合
- 严重度: S2
- 证据: roleName/subtitle/goal/welcomeTime 全 static mutable（第18-21行），由 startWelcome 写、tick/render 读、reset 清。多“实例”无意义（类全静态）。与 BlackoutHudOverlay/BlackoutVoteState 同样为静态可变单例网。
- 影响: 全局静态状态，无法测试隔离，无法多对局并行；与客户端 mixin（BlackoutTimeRendererMixin 等）共享静态 flag 形成隐式耦合网。
- 方向: 长期可考虑实例化状态对象经客户端上下文传递；短期至少集中到单一 client state holder。

### S11-011 BlackoutVoteScreen/BlackoutSheriffVoteScreen tick 仅靠服务端推送更新，无本地倒计时递减
- 文件: BlackoutVoteScreen.java:40-44, BlackoutSheriffVoteScreen.java:41-45
- 维度: 性能/死逻辑
- 严重度: S2
- 证据: 两个 Screen 的 tick 仅在 !isActive 时关闭（第41-43/42-44行），不本地递减 remainingSeconds。UI 显示的“剩余时间 Ns”完全依赖服务端 payload 周期推送（HabiTrainCoreClient.java:270-273/228-235）。若服务端推送频率低，UI 倒计时会卡住/跳变。
- 影响: 倒计时显示精度依赖网络推送频率；推送稀疏时用户看到的剩余时间不连续。
- 方向: 客户端本地基于 tick 递减 remainingSeconds，服务端推送做校准。

### S11-012 BlackoutVoteScreen 与 BlackoutSheriffVoteScreen render 每帧 new Component.literal 多次
- 文件: BlackoutVoteScreen.java:63-70,102-111, BlackoutSheriffVoteScreen.java:65-72,106-114
- 维度: 性能
- 严重度: S2
- 证据: render 每帧调用 Component.literal(title/description/timer/“票数:”+votes/“✓”/selectedSlot 等)（第64、70、104、109、113行等），每个候选行每帧新建多个 Component 对象。候选行数 N，每帧 O(N) 对象分配，Screen 在投票期间持续渲染（热路径）。
- 影响: 每帧 N 行 × 多个 Component 分配，增加 GC 压力；尤其候选多时。
- 方向: 对静态文本（标题/描述/表头）缓存为字段，行内动态文本按需缓存。

### S11-013 BlackoutSheriffVoteState.update 每次 payload 用 stream removeIf 清理选中
- 文件: BlackoutSheriffVoteState.java:29
- 维度: 性能
- 严重度: S3
- 证据: 第29行 `selectedTargetIds.removeIf(id -> candidates.stream().noneMatch(entry -> entry.playerId().equals(id)))`，对 selectedTargetIds 每个 id 遍历整个 candidates 流。selectedTargetIds 上限 sheriffCount（通常小），candidates 可能多，每次 payload 推送执行。
- 影响: 每次 S2C 推送 O(sel × cand) 比较；量级小但每推送都跑。
- 方向: 先把 candidates 的 playerId 收集成 Set 再 removeIf 查询，O(sel+cand)。

### S11-014 BlackoutSheriffVoteState.toggleSelection 已选满时静默替换第一票
- 文件: BlackoutSheriffVoteState.java:73-78
- 维度: 标识/死逻辑
- 严重度: S2
- 证据: 第73-78行：size < sheriffCount 时 add，否则 `selectedTargetIds.set(0, targetId)` 替换第一票，注释“已选满，替换第一个”。但 mouseClicked（SheriffVoteScreen.java:137-144）toggle 后用 `indexOf(targetId)>=0` 判断是否选中来决定发 sendSheriffVoteCast(targetId, slotIndex)：被替换掉的第一票此刻不在列表中，不会发送撤回，仅对新选目标发 cast。
- 影响: 选满后改选，被替换掉的旧目标票在客户端被静默移除但未向服务端发撤回（slotIndex=-1），导致客户端与服务端投票状态不一致。
- 方向: 替换场景需对被替换的旧目标也发送撤回 payload，或服务端按整批覆盖。

### S11-015 BlackoutPhoneHireScreen.lockCountdownTicks 本地倒计时与服务端不同步可能误启用
- 文件: BlackoutPhoneHireScreen.java:23,29,48-55,100-109
- 维度: 死逻辑/标识
- 严重度: S2
- 证据: 构造时 lockCountdownTicks = unlocked?0:remainingLockSeconds*20（第29行）；tick 每次递减到 0 即视为解锁（第102-108行）；canHire（第48-55行）用 `!state.unlocked() && lockCountdownTicks > 0` 作为锁定判据。注释（第22行）承认“实际聘请仍由服务端二次校验”。但若本地倒数到 0 而服务端实际未解锁（如服务端倒计时更长/时钟漂移），按钮会被本地启用，玩家点击后服务端拒绝——statusText 仅显示“正在请求...”后无反馈。
- 影响: 本地与服务端时钟不一致时按钮可点但请求必失败，且失败无 UI 反馈（statusText 永远停在“正在请求...”）。
- 方向: 失败需有服务端回执驱动 statusText 更新；本地倒数只作乐观估计，禁用态以服务端为准。

### S11-016 BlackoutPhoneHireScreen statusText 无失败/成功回写路径
- 文件: BlackoutPhoneHireScreen.java:20,85,141-143
- 维度: 死逻辑
- 严重度: S2
- 证据: 点击后 statusText = “正在请求...”（第85行），btn.active=false 防重复点；updateState 第35行清 statusText。但没有任何接收“聘请成功/失败”结果的回写点：grep 无对 statusText 的成功/失败赋值。render 第141行 `if (!statusText.getString().isEmpty())` 显示。
- 影响: 玩家点击后只看到“正在请求...”，成功/失败均无明确反馈；若服务端不推送新 state（如余额不足拒绝），statusText 不会被清，停在“正在请求...”。
- 方向: 增加服务端聘请结果 payload 回执，据结果设置成功/失败 statusText。

### S11-017 GUI 直接读 client state 单例而非经注入，跨包静态耦合
- 文件: BlackoutVoteScreen.java:41,72,80, BlackoutSheriffVoteScreen.java:42,74,82
- 维度: 耦合
- 严重度: S3
- 证据: Screen 直接静态调用 BlackoutVoteState.isActive()/getCandidates()/isSelected()/getPurpose（VoteScreen 第41,72,80,134行）与 BlackoutSheriffVoteState 同理。Screen 与 State 之间无接口隔离，强耦合具体静态类。
- 影响: Screen 不可复用/测试隔离；State 改签名直接影响 Screen。
- 方向: 长期可经构造注入 state 视图接口；短期可接受。

### S11-018 GUI 未直读服务端 manager（边界正确），但 BlackoutHudOverlay 与 client mixin 共享静态 flag
- 文件: BlackoutHudOverlay.java:18,36-37
- 维度: 耦合
- 严重度: S3
- 证据: blackoutModeActive 静态字段由 BlackoutHudOverlay.setBlackoutModeActive 写（HabiTrainCoreClient.java:221），由 BlackoutTimeRendererMixin.java:32、CustomTaskBlockRendererMixin.java:349 读取。HUD 类成为 mixin 间共享全局状态载体。
- 影响: HUD overlay 类承担了“客户端全局 blackout 状态”职责，职责蔓延到 mixin，单一职责边界模糊。
- 方向: 把 blackoutModeActive 移到专门 client state holder，HUD 只负责读+渲染。

### S11-019 BlackoutVoteScreen.mouseClicked 取消投票时发 null UUID，语义依赖服务端隐式约定
- 文件: BlackoutVoteScreen.java:133-138
- 维度: 标识
- 严重度: S3
- 证据: 第137行 `PayloadSenders.sendVoteCast(getPurpose(), null)` 以 null UUID 表示弃票，注释“发送 null UUID 表示弃票”。该语义在客户端靠注释承载，与服务端隐式约定。
- 影响: 弃票协议语义散在客户端注释，易与服务端实现漂移。
- 方向: 用显式方法名 sendVoteRevoke 或专用 payload 表达弃票意图。