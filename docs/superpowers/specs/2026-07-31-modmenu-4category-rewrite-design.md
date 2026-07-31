# 配置中心（Mod Menu）四分类重写 — 设计文档

- 日期：2026-07-31
- 项目：`habitrain_core`（哈比列车 API，Fabric / MC 1.21.1 / Java 21）
- 状态：已与 Mike 逐节确认（第 1–5 节全部通过）
- 旧相关文件：已暂存删除的 `docs/superpowers/specs/2026-07-31-modmenu-rewrite-design.md` 与 `plans/2026-07-31-modmenu-rewrite.md` 属上一轮 WIP，本设计不沿用其内容。

## 1. 目标

把旧配置中心（`ConfigRootScreen` + 6 Tab + 若干子编辑页）**从 0 重写**到全新包 `com.habitrain.core.client.gui.menu`，满足：

1. **保留原有设计**：深色主题、顶部彩色 Tab、色条、绿/红药丸开关、自定义滚动条等视觉风格完全一致。
2. **功能与旧版相同**：所有可配置项、行为、权限语义与旧版一致（见第 4 节行为保真清单）。
3. **分类完整、符合直觉**：顶层 4 大类（游戏内 / 游戏外 / 游戏模式 / 其他），每类下二级子 Tab 分功能组。
4. **每个可修改选项的页面底部都有「保存」按钮**：固定底部 SaveBar，点击提交未确认文本 + 写盘 + 提示。
5. **旧文件完全移出 api**：旧配置 GUI 文件整体 move 到 `D:\Backup\mc mod\临时\旧配置GUI备份_2026-07-31\`，api 内不保留旧文件。

## 2. 确认的关键决策

| 项 | 结论 |
|---|---|
| 范围 | 全部配置界面重写（根屏 + 所有页面 + 5 个子编辑页） |
| 顶层 Tab | 游戏内修改 / 游戏外修改 / 游戏模式修改 / 其他修改 |
| 二级导航 | 每类下用子 Tab 分功能组；投票/环境再内层子 Tab |
| 保存语义 | 即时生效（不改旧行为）+ 每页固定底部「保存」写盘并提示 |
| 保存按钮位置 | 固定底部栏（始终可见，内容区上移） |
| 实现方案 | 方案 C：全新包 + 新类；旧文件整体 move 到临时备份；api 不保留旧文件 |
| 视觉 | 保留现有深色主题/色条/药丸/滚动条风格 |

## 3. 目标分类结构

```
游戏内修改   [小游戏] [数值平衡] [环境]
  环境:      [对局环境] [局后时间] [动态雨]
游戏外修改   [投票] [大厅环境] [光影白名单]
  投票:      [主设置] [地图池轮换] [可投票模式] [可投票地图]
游戏模式修改 [任务配置] [角色覆盖]
其他修改     （空态：暂无其他设置）
```

功能归属：

- **游戏内修改**
  - 小游戏 → 旧 `MinigameTabScreen`（卡片网格 + 搜索 + 编辑）
  - 数值平衡 → 旧 `GlobalTabScreen` 拆分：DLC 任务目标占比滑块、警长数量除数、临时电源价格、杀手刀耐久、小游戏任务总开关
  - 环境（对局/局后/雨）→ 旧 `EnvironmentTabScreen` 减去「大厅」
- **游戏外修改**
  - 投票（主设置/池轮换/可投票模式/可投票地图）→ 旧 `VoteTabScreen` 拆分 + 子编辑 `MapPoolEditorScreen` / `ModeAllowedMapsScreen`
  - 大厅环境 → 旧 `EnvironmentTabScreen` 的「大厅」子页
  - 光影白名单 → 旧 `ShaderWhitelistScreen` 内容内联为页面（不再经入口按钮跳子屏）
- **游戏模式修改**
  - 任务配置 → 旧 `TaskTabScreen`（模式侧栏 + 任务网格）+ 子编辑 `TaskEditScreen`
  - 角色覆盖 → 旧 `RoleOverrideTabScreen`
- **其他修改** → 空态页

## 4. 包结构与文件布局

### 新包 `com.habitrain.core.client.gui.menu`

根屏与共享组件（`menu/`）：
- `ConfigMenuScreen` — 根：4 大 Tab → 子 Tab → 内容 + 固定 SaveBar。保留 `refreshRoleOverrideTab()` 与静态 `openVote(Screen)` 契约（供外部桥接）。
- `MenuTheme` — 颜色/绘制工具，替代 `SharedGuiKit` + `SharedGuiConstants`（含 20 色调色板与中文色名）。
- `MenuPermissions` — 替代 `LiveConfigAccess`：`canEditRemoteConfigs()` / `showDeniedMessage()`。
- 可复用组件：`ScrollArea`（滚动+滚动条+拖拽）、`SubTabBar`、`PillToggle`、`SliderRow`、`EditRow`、`SectionHeader`、`SaveBar`。

页面（`menu/page/`，各实现统一 `ConfigPage` 接口）：
- `InGameMinigamesPage`、`InGameBalancePage`、`InGameEnvPage`（对局/局后/雨）
- `OutGameVotePage`（主设置/池轮换/模式/地图）、`OutGameLobbyEnvPage`、`OutGameShaderPage`
- `ModeTasksPage`、`ModeRolesPage`、`OtherPage`

子编辑页（`menu/`，独立 Screen，用同一套组件重写）：
- `TaskEditScreen`、`MinigameEditScreen`、`MapPoolEditorScreen`、`ModeAllowedMapsScreen`、`ShaderWhitelistScreen`
- 辅助：`TaskColorPicker`、`TaskMapFilterEditor`、`TaskSaveController`

### 旧文件移动备份

19 个旧文件整体 `move`（剪切，非复制）到 `D:\Backup\mc mod\临时\旧配置GUI备份_2026-07-31\`，保留包相对路径：

`client/gui/config/`（11 个）：`ConfigRootScreen`、`TaskTabScreen`、`MinigameTabScreen`、`GlobalTabScreen`、`VoteTabScreen`、`EnvironmentTabScreen`、`RoleOverrideTabScreen`、`MapPoolEditorScreen`、`MinigameEditScreen`、`ModeAllowedMapsScreen`、`SharedGuiKit`

`client/gui/`（8 个）：`ModMenuIntegration`、`LiveConfigAccess`、`TaskEditScreen`、`TaskSaveController`、`TaskColorPicker`、`TaskMapFilterEditor`、`ShaderWhitelistScreen`、`SharedGuiConstants`

> 说明：`client/gui/` 下非菜单类（Blackout HUD/投票/电话/商店、GreedTrade、OptionVote、TaskColorPicker 等之外的游戏内界面）**保留不动**。移动时若旧文件带有工作树既有改动（如 `ConfigRootScreen`/`GlobalTabScreen`/`RoleOverrideTabScreen` 当前未提交改动），移动副本原样包含这些改动，不丢失、不归因。

### 外部引用同步（同一批提交内完成，保证编译）

- api 内：
  - `ModMenuIntegration` → 指向新 `ConfigMenuScreen`（`getModConfigScreenFactory()` 返回 `ConfigMenuScreen::new`）
  - `RoleOverrideRefreshDispatcher`（`RoleOverrideRefreshDispatcher.java:35-37`）→ `screen instanceof com.habitrain.core.client.gui.menu.ConfigMenuScreen`，调用 `refreshRoleOverrideTab()`
  - `ClientLifecycleHandler`（`:9,74`）→ `import ...menu.MenuPermissions`，调用 `MenuPermissions.canEditRemoteConfigs()`
- 抽奖补齐 mod：
  - `HabiCoreMenuBridge.java:26` 反射 FQCN 更新为 `com.habitrain.core.client.gui.menu.ConfigMenuScreen`（新根屏保留静态 `openVote(Screen)` 返回 Screen，反射 fallback 的构造 + `selectedTab` 字段路径亦兼容）
  - 其余跨仓扫描（更多职业/更多职业移植/原版职业修改/槟榔）无 core GUI 类引用

## 5. 固定保存栏模型（SaveBar）

- 位置：`ConfigMenuScreen` 底部固定一条约 40px 的 SaveBar，内容区上移，不随滚动消失。
- 覆盖：每个可修改选项的页面都有；「其他」空态页无修改项不显示。
- 行为（维持即时生效）：
  1. 开关/滑块/列表改动照旧立即写内存配置。
  2. 点「保存」→ 先提交该页未确认文本/数值框 → `ConfigManager.getInstance().save()` 写盘 → 顶部/聊天弹 `§a已保存`。
  3. 关闭菜单（ESC/返回）仍执行旧版收尾保存。
- 只读模式（联机非 OP）：保存按钮禁用置灰 + 保留「§c只读模式：联机服务器中仅 OP 可修改」提示 + 编辑控件禁用并弹权限拒绝消息。

各页 save 动作：

| 页面 | 即时生效项 | 保存按钮额外动作 |
|---|---|---|
| 小游戏 | 开关+编辑 | 写盘+提示 |
| 数值平衡 | 滑块/开关 | 提交警长/电源价文本框 + 写盘 + 提示 |
| 环境（对局/局后/雨） | 开关/天气等 | 提交聚焦的 tick/雾/人数框 + 写盘 + 提示 |
| 投票 | 开关/上下移 | 提交时长/显示名框 + persist + 写盘 + 提示 |
| 大厅环境 | 同环境页 | 同环境页 |
| 光影白名单 | 增删/开关即存 | 写盘+提示 |
| 任务配置 | 开关即存 | 写盘+提示 |
| 角色覆盖 | 开关即存 | 写盘+提示 |

子编辑页保持各自独立底部按钮语义（保存并返回 / 保存 / 重置），不并入 SaveBar。

## 6. 行为保真清单（逐项保持旧版）

### 通用
- 只读模式：控件禁用 + 权限拒绝弹消息 + 底部只读提示
- 非法数字输入：解析失败静默忽略，保留旧值，不崩溃
- 滚动：滚轮 + 拖拽 + 自定义滚动条；每页滚动位置独立
- 文本框焦点：点击空白失焦；聚焦时 charTyped/keyPressed 正确路由

### 任务配置
- 侧栏按模式分组；停电模式按阵营拆「好人任务/坏人任务」（`__good`/`__bad` key）
- 组标题（谋杀/修机/通用/自定义/好人池/坏人池）、搜索过滤、启用计数
- 行内：色条、名称、完整 ID、阵营药丸（仅停电）、状态药丸、编辑按钮
- 开关/编辑先 `getTaskConfig` → 缺省 `createDefault()` → `putTaskConfig`；编辑页模式名/色条取自 section

### 小游戏
- SRE 未安装显示「未检测到 SRE」提示
- 2 列卡片网格、搜索、统计（启用/总数）
- 开关/编辑 `createDefault()`；开关后 `applyMinigameEnforcement(singleplayerServer)`

### 数值平衡
- DLC 滑块：0.10–0.80、步进 0.05、拖动即时写内存、四段渐变填充、刻度、百分值
- 警长/电源价：数字框，由保存按钮提交（写内存语义不变）
- 刀耐久/小游戏总开关：即时 toggle + 立即 `applyMinigameEnforcement`

### 环境
- 内层子 Tab 切换滚动归零
- 对局环境：左地图列表（默认+覆盖）+ 右侧编辑器；选图懒创建覆盖并标脏；「删除地图覆盖」
- 时间模式 PRESET/TICK 循环、预设循环、天气/雪/沙尘/雾/雾距离/日夜/天气循环
- 聚焦框不被每帧模型回写打断（`isFocused()` 保护）；保存前 flush 聚焦框
- 局后时间好人/杀手两段各自 toggle + 时间编辑

### 投票
- 模式/地图 id：持久化顺序优先 + 追加注册表 id；名称框「仅当用户改动才创建条目」
- 时长 clamp 5–120；保存/离开 flush 全部文本
- 池轮换：开关/自动重分/直接抽图↔限制投票循环、摘要行、「跳过当前」发 C2S、编辑池子
- 模式行：开关/显示名/↑↓（写盘 LinkedHashMap 顺序）/可选地图；地图行：开关/显示名
- 上移下移后立即 `ConfigManager.save()`

### 角色覆盖
- 总开关、冲突横幅、逐条目切换；启用时同 target 其它条目自动关闭
- 状态文本（条目开·ACTIVE/DISABLED/CONFLICT/INVALID）、来源/接入标注/角色ID/说明/状态详情
- 每次改动 `rebuildRows()` + `saveConfigNow()`

### 光影白名单
- 启用开关 + 增删列表（忽略大小写去重）+ 白名单外被踢说明；即时保存

### 子编辑页
- 任务编辑：基础/奖励/地图/只读信息四区、颜色选择器、地图过滤、商店价格（仅停电任务）、保存/保存并返回/重置/返回列表
- 小游戏编辑：基础（启用/颜色/轮廓 +/-）/奖励/地图/只读信息、保存并返回/重置
- 地图池编辑：池列表（增删/命名/启用/当前徽标/数量）、多选地图、均摊、清空、保存；池数 clamp 1–MAX
- 可选地图：空选=不限制；清空/保存

## 7. 错误处理与验证

### 错误处理
- 所有数字解析沿用「失败静默忽略 + 保留旧值」
- SRE 未安装：小游戏页提示并禁用；环境/任务读取上游 Map/Vote 用 `try/catch(Throwable)` 兜底
- 所有写操作统一走 `MenuPermissions`，非 OP 点击编辑控件弹权限拒绝并中断
- 子编辑页保存前统一 flush 未确认文本
- 移动旧文件后全仓 Grep 复查：不得残留 `client.gui.config` / 旧类名引用（含抽奖 mod）

### 构建与交付（按项目协议强制）
1. `cd 哈比列车api && ./gradlew clean build` — 必须 BUILD SUCCESSFUL
2. 确认 `build/libs/habitrain_core-2.0.1.jar` 为本轮唯一主产物
3. 复制到 `D:\Backup\mc mod\临时\`，比对文件名、字节长度、SHA-256
4. 抽奖补齐 mod（改了 `HabiCoreMenuBridge`）一并 `./gradlew clean build` 并交付其 JAR 到 `临时\`
5. `maintenance-log.md` 追加唯一 maintenance-entry；`mod-architecture.md` 第 11 节（配置持久化与客户端 UI）同步更新

### 游戏内验证（构建之外）
- ModMenu 进入配置中心，检查 4 大 Tab + 子 Tab 导航
- 各页保存按钮：改动后点击保存，确认提示且落盘
- 只读（非 OP）提示与禁用
- 任务页停电阵营分组、投票池子编辑、环境按图覆盖等旧行为逐项确认
- 抽奖中心跳转投票设置，确认反射桥接仍工作

## 8. 明确的非目标（YAGNI）

- 不新增任何旧版没有的功能项（分类与保存按钮除外）。
- 不改配置数据模型 / ConfigManager 契约 / 网络 Payload。
- 不改非菜单类游戏内 GUI（Blackout、GreedTrade、OptionVote 等）。
- 不做「延迟保存（点保存才生效）」：保持即时生效语义。
