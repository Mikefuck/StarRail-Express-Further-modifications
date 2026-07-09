# S10 切片审查发现 — client.gui 配置侧

审查日期：2026-07-09
切片：S10（client.gui 配置侧）
审查方式：从零读源码，四维审查，不参考仓库内任何既有审计/计划/报告。

## 文件覆盖确认表

| 文件（相对 src/main/java） | 已读 | 行数 | 备注 |
|---|---|---|---|
| client/gui/config/ConfigRootScreen.java | ✅ | 196 | Tab 主入口 |
| client/gui/config/MinigameTabScreen.java | ✅ | 236 | 小游戏列表 Tab |
| client/gui/config/GlobalTabScreen.java | ✅ | 235 | 全局设置 Tab |
| client/gui/config/MinigameEditScreen.java | ✅ | 388 | 小游戏详情编辑 |
| client/gui/config/SharedGuiKit.java | ✅ | 79 | 共享绘制工具 |
| client/gui/config/TaskTabScreen.java | ✅ | 407 | 任务列表 Tab |
| client/gui/ModMenuIntegration.java | ✅ | 17 | ModMenu 入口 |
| client/gui/LiveConfigAccess.java | ✅ | 45 | 权限判定 |
| client/gui/SharedGuiConstants.java | ✅ | 29 | 颜色常量 |
| client/gui/GlobalSettingsScreen.java | ✅ | 282 | 旧全局设置屏（疑似死） |
| client/gui/ShaderWhitelistScreen.java | ✅ | 390 | 光影白名单屏 |
| client/gui/TaskSaveController.java | ✅ | 65 | 任务保存控制器 |
| client/gui/TaskColorPicker.java | ✅ | 149 | 颜色选择器 |
| client/gui/TaskMapFilterEditor.java | ✅ | 140 | 地图过滤编辑器 |
| client/gui/TaskEditScreen.java | ✅ | 453 | 任务详情编辑屏 |

## 发现清单

### S10-001 — GlobalSettingsScreen 整类死代码
- 文件：client/gui/GlobalSettingsScreen.java
- 行：26-282
- 维度：死逻辑
- 严重度：S2
- 证据：全仓 grep `GlobalSettingsScreen` 仅命中该类自身定义与构造函数（行 26、46），无任何 `new GlobalSettingsScreen(...)` 调用点；功能已被 `config/GlobalTabScreen.java`（DLC 概率滑块 + 同套 MIN/MAX/STEP/DEFAULT 常量，行 24-26、92-130）取代。`ModMenuIntegration` 仅指向 `ConfigRootScreen::new`。
- 影响：282 行旧实现（含独立滑块、按钮、信息区）随构建一起打包却永不实例化，维护者误改旧屏以为生效。
- 方向：确认无外部入口后整体移除；若保留为外部 API 入口需补调用方并加注释说明与 GlobalTabScreen 的关系。

### S10-002 — MinigameEditScreen saveBtn/resetBtn 位置在渲染后才设置
- 文件：client/gui/config/MinigameEditScreen.java
- 行：296、300-301
- 维度：死逻辑/标识
- 严重度：S1
- 证据：`super.render(g, mx, my, delta)` 在行 296 调用（renderSection 之后、disableScissor 之后），但 `saveBtn.setX(width/2-110)` / `resetBtn.setX(...)` 在行 300-301 才执行（即 super.render 之后）。saveBtn/resetBtn 是通过 `addRenderableWidget` 注册（行 140、157）的，由 `super.render` 绘制，故首帧以 init 时 `-10000,-10000` 位置绘制，后续每帧绘制的是上一帧刚 setX/Y 的坐标。
- 影响：保存/重置按钮首帧画在屏外；点击命中判定用的 `mouseClicked` 走 `super.mouseClicked`（行 315）会读 super 当前 widget 状态——按钮真实命中框与肉眼可见位置错一帧；窗口尺寸变化时错位更明显。
- 方向：把 saveBtn/resetBtn 的 setX/setY/setWidth 移到 super.render 之前（与其它 widget 一样的“先定位再渲染”顺序）。

### S10-003 — MinigameEditScreen mouseClicked 内恒等式滚动 no-op
- 文件：client/gui/config/MinigameEditScreen.java
- 行：320
- 维度：死逻辑
- 严重度：S2
- 证据：`scrollOffset = Mth.clamp(scrollOffset + (my - contentTop) * 0, 0, maxScroll);` —— `(my - contentTop) * 0` 恒为 0，整个 clamp 退化为 `Mth.clamp(scrollOffset, 0, maxScroll)`，不随鼠标移动改变滚动量。
- 影响：意图上应是“点击内容区即跳转滚动到鼠标位置”，实际不产生任何滚动；属历史调试残留死逻辑，误导后续维护。
- 方向：删除该恒等式或改为按鼠标位置正确计算目标 scrollOffset。

### S10-004 — SharedGuiKit.drawStatusPill 死方法且 fontWidth 参数未用
- 文件：client/gui/config/SharedGuiKit.java
- 行：45-53
- 维度：死逻辑/标识
- 严重度：S2
- 证据：全仓 grep `drawStatusPill(` 仅命中其定义（行 45），无调用点；方法签名 `drawStatusPill(..., int fontWidth)` 中 `fontWidth` 从未被读取，方法体注释行 52 明确写“绘制文字需在外部完成（GuiGraphics 不持有 Font）”，但无任何外部调用方据此绘制文字。
- 影响：死方法 + 无意义参数，状态药丸实际由各 Tab 自行 `g.fill`+`g.drawString` 内联绘制（如 TaskTabScreen 行 280-281、MinigameTabScreen 行 153-154），重复实现未走共享工具。
- 方向：移除 drawStatusPill，或将其补全为真正复用的药丸绘制（含文字居中）并由各 Tab 改用。

### S10-005 — SharedGuiKit.drawPanel 死方法
- 文件：client/gui/config/SharedGuiKit.java
- 行：31-37
- 维度：死逻辑
- 严重度：S2
- 证据：全仓 grep `drawPanel(` 仅命中其定义（行 31），无任何调用点；配置侧各 Tab 均内联 `g.fill` 绘制面板/分隔线。
- 影响：死方法随构建保留，误导维护者以为面板绘制有统一入口。
- 方向：移除或改为各 Tab 真正复用。

### S10-006 — LiveConfigAccess.isRemoteLocked 死方法
- 文件：client/gui/LiveConfigAccess.java
- 行：30-36
- 维度：死逻辑
- 严重度：S2
- 证据：全仓 grep `isRemoteLocked` 仅命中定义（行 30），无调用点；权限判定全部走 `canEditRemoteConfigs()`（ConfigRootScreen 行 44、MinigameEditScreen 行 52 等）。
- 影响：未使用的判定分支（`mc==null||connection==null||singleplayer!=null → false`）随构建保留，与 canEditRemoteConfigs 语义重叠易致维护者误用。
- 方向：移除该死方法，统一用 canEditRemoteConfigs。

### S10-007 — TaskTabScreen 渲染热路径每帧对每任务查 ConfigManager
- 文件：client/gui/config/TaskTabScreen.java
- 行：205-206、256、305-312
- 维度：性能
- 严重度：S1
- 证据：`render` 每帧执行：侧栏每 section 调 `countEnabled(section.tasks())`（行 205），其内部对每个 task 调 `ConfigManager.getInstance().getTaskConfig(d.getFullId())`（行 308）；内容区 `drawTaskRow` 每行再调一次 `getTaskConfig(...)`（行 256）。即同一屏 N 个任务每帧触发约 2N 次 ConfigManager 查询，且 ConfigManager 非本地字段。
- 影响：任务数较多时（停电模式拆 good/bad + 多分类）每帧重复查同一份配置，GUI 打开期间持续 O(N) registry/Map 查询；ConfigManager 单例调用链未缓存。
- 方向：render 一次性把当前可见 section 的 TaskConfigEntry 快照成 Map 传入，避免逐行再查；或由 ConfigManager 提供批量/快照接口。

### S10-008 — MinigameTabScreen 渲染热路径每帧对每小游戏查 ConfigManager
- 文件：client/gui/config/MinigameTabScreen.java
- 行：102、128
- 维度：性能
- 严重度：S2
- 维度：性能
- 严重度：S2
- 证据：render 主循环每帧对每个过滤后小游戏调 `ConfigManager.getInstance().getMinigameConfig(mg.id())`（行 102），`drawCard` 内再次取色（行 128 仅在 cfg==null 时取 accentFor，但行 102 已拿到 cfg）。
- 影响：小游戏数量增长时每帧重复 Map 查询；非确定性但随列表规模线性劣化。
- 方向：同 S10-007，预取快照。

### S10-009 — ShaderWhitelistScreen mouseClicked 回车处理为空 if 死分支
- 文件：client/gui/ShaderWhitelistScreen.java
- 行：334-337
- 维度：死逻辑
- 严重度：S2
- 证据：`if (addBox.isFocused() && button == 0) { // 如果点击了添加按钮外部，但不处理 }` —— 条件成立后方法体为空注释，不产生任何动作，随后 `return false`（行 339）。
- 影响：空分支误导维护者以为有“点击外部失焦”逻辑，实际无操作；addBox 失焦需靠后续点击落到其它 widget。
- 方向：移除空分支或补全失焦逻辑。

### S10-010 — ShaderWhitelistScreen 未使用 import HabiTrainCore
- 文件：client/gui/ShaderWhitelistScreen.java
- 行：3
- 维度：死逻辑
- 严重度：S3
- 证据：行 3 `import com.habitrain.core.HabiTrainCore;`，全文件 grep `HabiTrainCore` 仅该 import 行，类体内无任何引用。
- 影响：无用 import，轻微噪声。
- 方向：移除 import。

### S10-011 — ConfigRootScreen 公开访问器 getParent/font/isEditable 中 font/isEditable 无调用
- 文件：client/gui/config/ConfigRootScreen.java
- 行：183-185
- 维度：死逻辑
- 严重度：S3
- 证据：`getParent()` 被 `ShaderWhitelistScreen`（行 61 经 root 传入 parent）/ `MinigameEditScreen` 等间接使用；但 `font()`（行 184）与 `isEditable()`（行 185）全仓 grep 仅命中定义，子 Tab 各自持 `font`/`editable` 字段（TaskTabScreen 行 40-41 等），未走 root 访问器。
- 影响：两个公开访问器无调用者，API 表面冗余。
- 方向：移除未用访问器或让子 Tab 改用其统一访问以减少状态重复。

### S10-012 — TaskTabScreen 与 MinigameTabScreen 滚动 clamp 上限硬编码 10000
- 文件：client/gui/config/TaskTabScreen.java、client/gui/config/MinigameTabScreen.java
- 行：TaskTabScreen 行 359/363/371/373；MinigameTabScreen 行 198/205
- 维度：标识/魔法数字
- 严重度：S3
- 证据：`Mth.clamp(..., 0, 10000)` 上限 10000 为魔法数字，而 render 内已计算真实 `maxSidebarScroll`/`maxContentScroll`/`maxScroll`（TaskTabScreen 行 214、250；MinigameTabScreen 行 116）但未传给交互方法，交互侧用 10000 兜底。
- 影响：滚动拖拽/滚轮上限与实际内容高度脱节，极端高内容时 clamp 偏松；魔法数字降低可维护性。
- 方向：交互方法接收或缓存真实 maxScroll，去除 10000 字面量。

### S10-013 — 多处颜色/坐标魔法数字散落且未走 SharedGuiKit 常量
- 文件：client/gui/config/GlobalTabScreen.java、MinigameTabScreen.java、TaskTabScreen.java、ShaderWhitelistScreen.java 等
- 行：GlobalTabScreen 行 110-113、121-125、129；MinigameTabScreen 行 147、153；TaskTabScreen 行 265、273-275、280；ShaderWhitelistScreen 行 249、269
- 维度：标识
- 严重度：S3
- 证据：SharedGuiKit 已定义 `BG_ROW`/`BG_ROW_HOVER`/`TEXT_PRIMARY` 等常量，但多处仍直接写 `0xFF1B3A2A`/`0xFF3A1B1B`（启用绿/停用红底，MinigameTabScreen 行 153、TaskTabScreen 行 273、280）、`0xFF222B36`（编辑钮底，行 158/285）、`0xAAFF5555` 系列滑块填充色（GlobalTabScreen 行 110-113、GlobalSettingsScreen 行 215-218 重复）。
- 影响：同一语义色（启用绿、停用红、编辑钮底）在多文件硬编码，改色需多处同步；与 SharedGuiKit 常量并存易漂移。
- 方向：把启用/停用/编辑底色等高频语义色纳入 SharedGuiKit，各处改引常量。

### S10-014 — GlobalTabScreen 按钮/输入框在 render 内懒构建且每帧 setX/Y/Width
- 文件：client/gui/config/GlobalTabScreen.java
- 行：52-85、140-143、153、166
- 维度：性能/标识
- 严重度：S3
- 证据：`sheriffField`/`shaderBtn`/`mgToggleBtn`/`sheriffApplyBtn` 在 `render` 内 `if (x==null)` 懒构建（行 52-85），随后每帧 `setX/setY/setWidth`（行 140-143、153、166）。这些控件未 `addRenderableWidget` 注册（GlobalTabScreen 非 Screen，无 widget 列表），靠每帧手动 `render` 绘制。
- 影响：每帧重复 setX/Y/Width 调用虽廉价但属冗余；控件生命周期与 Screen init 解耦，关闭界面时无统一清理；可读性差。
- 方向：把一次性构建移到 init 期（由 ConfigRootScreen 协调或在子 Tab 自建一次），render 仅定位。

### S10-015 — TaskTabScreen / MinigameTabScreen / MinigameEditScreen 直接依赖 ConfigManager 具体类
- 文件：client/gui/config/TaskTabScreen.java、MinigameTabScreen.java、MinigameEditScreen.java、GlobalTabScreen.java
- 行：TaskTabScreen 行 10、256、308、390/393/398/400；MinigameTabScreen 行 5、102、221/224；MinigameEditScreen 行 5、137/155/192；GlobalTabScreen 行 5、46-48、69-70、81
- 维度：耦合/架构边界
- 严重度：S2
- 证据：四个 Tab 直接 `import com.habitrain.core.config.ConfigManager` 并到处 `ConfigManager.getInstance().getTaskConfig/setTaskConfig/getMinigameConfig/...`，而非经 `LiveConfigAccess` 间接。`LiveConfigAccess`（client/gui）只管权限判定，未承担配置访问门面。
- 影响：GUI 直接耦合 config 包具体类与单例，未来改 ConfigManager 接口或加同步/缓存需四处改；违背“GUI 经 LiveConfigAccess 访问配置”的设计意图（检查点明确要求核对）。
- 方向：将配置读写通过 LiveConfigAccess（或新增只读快照/写入门面）集中，Tab 不再直引 ConfigManager。

### S10-016 — MinigameEditScreen 与 TaskEditScreen 表单逻辑重复未复用 TaskSaveController/TaskColorPicker/TaskMapFilterEditor
- 文件：client/gui/config/MinigameEditScreen.java
- 行：40-44、96-133、195-211
- 维度：耦合/标识
- 严重度：S2
- 证据：MinigameEditScreen 自带 `goldField/emotionField/weightField/mapField` + `commitFields()`（行 195-211，与 TaskSaveController.syncFields 行 21-40 几乎同逻辑）、自带 colorBtn + colorIndex 循环（行 73-79、179-181，与 TaskColorPicker.cycleColor 行 98-110 重复）、自带 mapFilterBtn 循环（行 120-126，与 TaskMapFilterEditor 重复）。
- 影响：两套等价表单逻辑并存，数值解析/重置默认值/颜色循环任一改动需双改；TaskSaveController 已抽离但小游戏编辑未复用，复用边界未贯彻。
- 方向：MinigameEditScreen 改用 TaskSaveController.syncFields/resetDefault、TaskColorPicker、TaskMapFilterEditor，或抽出共享 MinigameSaveController 与 Task 系对齐。

### S10-017 — MinigameEditScreen commitFields 未捕获非数字异常（与 TaskSaveController 不一致）
- 文件：client/gui/config/MinigameEditScreen.java
- 行：195-211
- 维度：死逻辑/健壮性
- 严重度：S2
- 证据：`commitFields` 直接 `Integer.parseInt(v)`/`Float.parseFloat(v)`（行 198/200/202）无 try/catch，而 setFilter 正则允许 `-?\\d*`/`-?\\d*\\.?\\d*`（行 100/108/116）仍可匹配 `-`、`-.` 等非法数（如纯 `-`、`-.`），parse 抛异常会中断保存。对照 TaskSaveController.parseNumFields（行 27-40）每个字段都有 try/catch。
- 影响：用户在小游戏金币/情绪/权重框输入 `-` 或 `-.` 后点保存，抛 NumberFormatException 中断 commit，后续字段未写、白名单未提交，配置停留在旧值。
- 方向：对齐 TaskSaveController 的逐字段 try/catch，或收紧 setFilter 正则至完整数格式。

### S10-018 — ConfigRootScreen.init 每次重建三个子 Tab（含整张任务/小游戏列表）
- 文件：client/gui/config/ConfigRootScreen.java
- 行：48-64
- 维度：性能
- 严重度：S2
- 证据：`init` 每次被调用（窗口尺寸变化、TaskEditScreen reset 后 clearWidgets+init 等）都 `new TaskTabScreen(...)`（行 51），TaskTabScreen 构造即 `rebuildSections()`（行 65）遍历 `TaskRegistry.getAll()` 全量分组排序（行 79-95），MinigameTabScreen 构造即 `loadMinigames()` 调 `QuestMinigames.getAll()`（行 53/59）。
- 影响：每次 init 重建并重新拉全量注册表，窗口缩放时重复 O(N) 分组排序；非每帧但可量化。
- 方向：子 Tab 改为按需懒建或 init 时复用已建实例仅重算布局，避免全量重建。

### S10-019 — TaskColorPicker.cycleColor 在颜色匹配不到时直接跳 color(0) 而未走 onSave 后续判断
- 文件：client/gui/TaskColorPicker.java
- 行：98-110
- 维度：标识
- 严重度：S3
- 证据：`cycleColor` 行 101-106 循环找当前色，命中则切下一色并 `onSave.run()` return；未命中走行 108 `cfg.instinctColor = color(0)` 后 `onSave.run()`。逻辑正确，但 colorBtn 初始标签由外部设置，cycleColor 不更新 colorBtn 文本（colorBtn 的 message 在 TaskEditScreen 未持有引用更新，依赖 render 内重算 `getColorIndex`+COLOR_NAMES，行 62-63）。
- 影响：行为可工作但 colorBtn 文本与状态更新路径分散在 render 而非事件回调，状态字段密度高易遗漏。
- 方向：把 colorBtn message 更新收敛到 cycleColor 内或单一 render 路径，降低状态分散。

注：TaskTabScreen / MinigameTabScreen 搜索框输入路径经 EditBox.mouseClicked 自动 setFocused，keyPressed/charTyped 守卫 `isFocused()` 可正常工作，非死逻辑，未报为问题。

## 严重度汇总
- S1：2（S10-002、S10-007）
- S2：10（S10-001、S10-003、S10-004、S10-005、S10-006、S10-008、S10-009、S10-015、S10-016、S10-017、S10-018）
- S3：5（S10-010、S10-011、S10-012、S10-013、S10-014、S10-019）
（部分条目含多维度，按主维度归档）