# 哈比列车核心 — ModMenu 配置界面重建设计规格书

> **日期**: 2026-06-28
> **项目**: 哈比列车核心 (HabiTrain Core) — ModMenu 配置界面
> **状态**: 设计已批准

---

## 1. 概述

将现存的 4 卡片硬编码布局的 `ConfigScreen` 重写为**左侧导航栏 + 右侧面板**的布局，支持从 `GameModeRegistry` 动态读取已注册的游戏模式。DLC 模组注册新的 GameMode 后会自动出现在导航栏中，无需修改本模组代码。

### 目标

- **动态模式列表** — 不再硬编码 4 个分类，改为遍历 `GameModeRegistry.getAll()`
- **三级导航** — 侧栏选择模式 → 任务列表面板 → 滑入详情面板
- **保留所有现有设置项** — 启用/禁用、透视颜色、描边粗细、奖励、地图过滤
- **全局设置** — DLC 目标占比、光影白名单

---

## 2. 导航结构

```
Screens:

MainConfigScreen (ModMenu入口)
├── 左侧导航栏
│   ├── ⚙ 全局设置          → GlobalSettingsScreen (保留现有)
│   ├── ──────────────  (分隔线)
│   ├── 🔪 谋杀模式  [SRE]  → TaskListPanel (右侧)
│   ├── 🔧 修机模式  [SRE]
│   ├── ⭐ 通用任务  [core]
│   ├── 📦 自定义任务 [core]
│   ├── 🦴 槟榔模式  [DLC]  (如果注册了)
│   └── ✖ 关闭
│
├── 右侧任务面板 (TaskListPanel)
│   ├── 搜索框
│   ├── 统计 "X/Y 已启用"
│   └── 任务列表 (可滚动)
│       └── 每行: 颜色指示器 | 任务名 | 来源模组标签 | 启用开关
│           └── 点击 → 滑入 TaskDetailPanel
│
└── 详情滑入面板 (TaskDetailPanel)
    ├── 启用/禁用开关
    ├── 透视颜色 (20色循环)
    ├── 描边粗细 (±0.5步进, 1.0~10.0)
    ├── 金币奖励 (EditBox)
    ├── 情绪奖励 (EditBox)
    ├── 刷新权重 (EditBox)
    ├── 地图过滤模式 (循环切换: 全部/白名单/黑名单)
    ├── 地图列表 (EditBox, 逗号分隔)
    ├── [保存] [重置默认] [返回]
    └── ← 返回按钮 (顶部)
```

---

## 3. Screen 设计

### 3.1 MainConfigScreen — 主配置界面

**类**: `MainConfigScreen extends Screen`

**布局**:
- 全屏，左侧固定宽度导航栏 (约 180px)，右侧占剩余宽度
- 顶部标题栏显示 "哈比列车核心 — 任务配置"

**左侧导航栏**:
- 固定不可滚动
- 顶部: "⚙ 全局设置" 按钮（选中态高亮）
- 分隔线
- 遍历 `GameModeRegistry.getAll()` 生成模式列表
  - 每个模式显示: 图标(可选) + displayName + modeId
  - 使用 `GameMode.getDisplayName()` 作为显示名
  - 当前选中的模式高亮
- 底部: "✖ 关闭" 按钮

**右侧面板**:
- 根据左侧选中项切换内容
- 选中全局设置 → 打开 `GlobalSettingsScreen`（独立 Screen，非内嵌，保持现有实现）
- 选中某个 GameMode → 显示 `TaskListPanel`

**输入处理**:
- 鼠标点击侧栏项切换右侧内容
- ESC 关闭配置界面

### 3.2 TaskListPanel — 任务列表面板

**类**: `TaskListPanel` (作为 Screen 的一部分进行渲染，而非独立 Screen)

**布局**:
- 顶部: 模式名称 + 任务统计 ("14 个任务, 10/14 已启用")
- 搜索框 (按任务名/ID/模组名过滤)
- 滚动任务列表

**任务行渲染**:
| 元素 | 说明 |
|------|------|
| 颜色点 | `TaskConfigEntry.getColor()` 的小方块 (.getRGB()) |
| 任务名 | `TaskDefinition.getDisplayName()` |
| 来源标签 | 模组 ID 简短标签 (如 "SRE", "more_tasks", "DLC") |
| 启用开关 | 快速切换, 实时修改 `TaskConfigEntry.enabled` |

**交互**:
- 点击**颜色点/任务名** → 滑入 `TaskDetailPanel`
- 点击**启用开关** → 立即保存
- 搜索框 → 实时过滤

### 3.3 TaskDetailPanel — 任务详情滑入面板

**类**: `TaskDetailPanel` (渲染在 TaskListPanel 之上，右侧滑入)

**布局**:
- 半透明遮罩层覆盖在 TaskListPanel 上
- 右侧弹出面板 (约 320px 宽)
- 顶部: ← 返回按钮 + 任务名 + 模式标签
- 中部: 设置项表单（可滚动）
- 底部: [保存] [重置] [返回]

**设置项**:

| 设置项 | 控件类型 | 默认值 | 说明 |
|--------|---------|--------|------|
| 启用/禁用 | 开关按钮 | true | |
| 透视颜色 | 循环颜色按钮 | 任务定义值 | 20色循环: 红橙黄绿蓝紫品青粉琥珀银白珊瑚金草绿碧绿紫罗兰深粉深蓝亮橙 |
| 描边粗细 | [-] 数字 [+] 按钮 | 4.0 | 步进 0.5, 范围 1.0~10.0 |
| 金币奖励 | EditBox (整数) | 空=系统默认 | |
| 情绪奖励 | EditBox (浮点数) | 空=系统默认 | |
| 刷新权重 | EditBox (浮点数) | 空=任务定义值 | |
| 地图过滤模式 | 循环按钮 | 全部地图 | 循环: 全部地图 → 仅以下地图 → 排除以下地图 |
| 地图列表 | EditBox | 空 | 逗号分隔地图名 |

**交互**:
- "保存": 更新 `ConfigManager` 并持久化
- "重置": 重置该任务为默认配置
- "← 返回": 关闭滑入面板

---

## 4. 数据流

```
User操作
  │
  ▼
MainConfigScreen
  │
  ├─ 切换模式 → TaskListPanel 更新
  │
  ├─ 搜索过滤 → TaskListPanel 列表过滤
  │
  ├─ 启用开关 → ConfigManager.getInstance().getTaskConfig(fullId).enabled = value
  │             → ConfigManager.getInstance().save()
  │
  └─ 点击任务 → TaskDetailPanel 滑入
                  │
                  ├─ 修改设置 → 控件本地状态
                  │
                  └─ 保存 → ConfigManager.getInstance().getTaskConfig(fullId).updateField(...)
                            → ConfigManager.getInstance().save()
```

---

## 5. 文件变更清单

### 新文件

| 文件 | 说明 |
|------|------|
| `client/gui/MainConfigScreen.java` | 主配置界面（侧栏 + 面板容器） |
| `client/gui/TaskListPanel.java` | 任务列表面板（渲染 + 交互逻辑） |
| `client/gui/TaskDetailPanel.java` | 任务详情滑入面板 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `client/gui/ModMenuIntegration.java` | `ConfigScreen::new` → `MainConfigScreen::new` |
| `client/gui/GlobalSettingsScreen.java` | 检查是否需要适配内嵌模式 |

### 删除文件

无（现有 ConfigScreen/TaskListScreen/TaskEditScreen 暂保留，旧入口不再引用）

---

## 6. 实施顺序

| # | 阶段 | 内容 |
|---|------|------|
| 1 | MainConfigScreen | 侧栏渲染 + GameModeRegistry 动态遍历 + 右侧面板容器 |
| 2 | TaskListPanel | 任务列表渲染 + 搜索过滤 + 启用开关 |
| 3 | TaskDetailPanel | 设置项表单 + 颜色循环 + 地图模式 + 保存/重置 |
| 4 | 集成与清理 | ModMenuIntegration 入口替换 + 构建验证 |

---

## 7. 不包含的范围

- ❌ 不修改现有 `ConfigManager` / `TaskConfigEntry` / `GameModeRegistry`
- ❌ 不修改网络同步逻辑
- ❌ 不添加新设置项（仅保留现有项）
- ❌ 不影响 `习惯列车更多修改` 或槟榔模组
