# 哈比列车核心 — 任务系统 Bug 修复设计规格书

> **日期**: 2026-06-28
> **项目**: 哈比列车核心 (HabiTrain Core)
> **状态**: 设计

---

## 1. 概述

修复在游戏测试中发现的三个任务系统 Bug：HUD 任务显示消失、ModMenu 配置界面输入框不工作、配置界面布局重叠。

---

## 2. Bug 1：DLC 任务 HUD 显示错误 + 快速消失

### 2.1 根因

**`TaskInstance.toNbt()` 缺少 `type` 字段**，导致 SRE 的客户端-服务端任务同步机制失效。

SRE 使用 Cardinal Components API 进行客户端-服务端同步，流程如下：

```
服务端 writeToSyncNbt():
  for (TrainTask task : tasks.values())
      tagList.add(task.toNbt())       ← 调用我们的 TaskInstance.toNbt()

客户端 readFromSyncNbt():
  for (Tag element : tagList)
      int typeId = tag.getInt("type", 99)
      Task taskType = Task.values()[typeId]
      TrainTask task = taskType.setFunction.apply(tag)  ← 反序列化
      tasks.put(taskType, task)
```

`TaskInstance.toNbt()` 写入了 `customId`、`customName`、`fulfilled`、`failed`、`progress`、`maxProgress`、`elapsedTicks`，但**没有写入 `type` 字段**。客户端收到 sync 后调用 `readFromSyncNbt()`，`type` 默认为 99 → 越界 → CUSTOM 任务数据被丢弃。

后果：
- 客户端的 `tasks` 映射中没有 CUSTOM 条目
- `HudMoodRenderer`（左上角 HUD）检测不到 CUSTOM 任务 → HUD 不显示
- 中心弹窗"新任务已派发"在服务端生成时不依赖客户端同步，所以能看到一瞬间，但显示的名字因 SRE 内部格式化带有 `task_` 前缀

### 2.2 修复方案

#### 2.2.1 `TaskInstance.toNbt()` 加入 `type` 字段

```java
// 在 toNbt() 返回前加入
var customType = TaskEnumHelper.getCustom();
if (customType != null) {
    nbt.putInt("type", customType.ordinal());
}
```

同时 `fromNbt()` 也要能处理客户端反序列化的 `CustomTask` 格式（SRE 自己的 `CustomTask` 类创建的 NBT）。

#### 2.2.2 `TaskInstance.fromNbt()` 兼容 SRE 的 CustomTask NBT 格式

SRE 的 `CustomTask.toNbt()` 写入 `type`、`customName`、`customId`。`fromNbt()` 应当能够在服务端从这种格式反序列化（虽然目前主要由 `TaskManager` 创建，但保持兼容）。

#### 2.2.3 修正中心弹窗的 `task_` 前缀

通过 mixin 注入 `HudMoodRenderer$TaskRenderer.tick()` 或 SRE 的通知生成代码，确保 CUSTOM 类型任务不显示 `task_` 前缀。

实际上，`TaskRenderer.tick()` 已经对 CUSTOM 类型做了特殊处理（直接 `Component.literal(task.getName())`，不加 `task_`）。**所以修复同步后，HUD 上的名字就是正确的。**

对于中心弹窗，可通过 mixin 注入 SRE 生成通知消息的代码，或用下面更简单的方式：

在 `GenerateTaskMixin.createAndTrackDlcTask()` 中，任务分配时主动发送正确名称的消息给玩家：

```java
// 在 createAndTrackDlcTask() 末尾加入
if (player instanceof ServerPlayer sp) {
    sp.sendSystemMessage(
        Component.literal("§a✦ 新任务已派发: §f" + def.getDisplayName()),
        false
    );
}
```

#### 2.2.4 覆盖文件

| 文件 | 改动 |
|------|------|
| `api/TaskInstance.java` | toNbt() 加入 type 字段；fromNbt() 兼容 |
| `game/sre/mixin/GenerateTaskMixin.java` | createAndTrackDlcTask 发送正确名称 |

---

## 3. Bug 2：TaskDetailPanel 输入字段不工作

### 3.1 根因

`TaskDetailPanel.java` 是一个纯静态渲染类，所有可编辑字段（金币奖励、情绪奖励、刷新权重、地图列表）都只使用 `GuiGraphics.drawString()` 渲染文本，**没有创建任何 `EditBox` 控件**。用户在输入区域点击/键盘输入完全无响应。

`keyPressed()` 方法直接返回 `false`。

### 3.2 修复方案

将 `TaskDetailPanel` 从纯静态类重构为有状态面板，在 `MainConfigScreen` 中实例化并管理它的 `EditBox` 控件。

#### 3.2.1 新的类结构

```
TaskDetailPanel (非静态，由 MainConfigScreen 持有)
├── EditBox 金币奖励 (整数, 空=默认)
├── EditBox 情绪奖励 (浮点数, 空=默认)
├── EditBox 刷新权重 (浮点数, 空=默认)
├── EditBox 地图列表 (逗号分隔文本)
├── 颜色切换按钮 (保留现有逻辑, 修正Y偏移)
├── 描边 [-] [+] 按钮 (保留)
├── 地图模式循环 (保留)
├── [保存] [重置] [返回] 按钮
```

#### 3.2.2 渲染布局修正

修正 Y 坐标偏移：

| 行 | 内容 | Y 偏移 (相对面板顶) | 高度 |
|----|------|---------------------|------|
| 标题 | ← 返回 + 任务名 | 8 | — |
| 1 | 启用/禁用 | 36 | 22 |
| 2 | 颜色选择 | 58 | 22 |
| 3 | 描边 [+]/[-] | 80 | 22 |
| 4 | 金币奖励 EditBox | 102 | 22 |
| 5 | 情绪奖励 EditBox | 124 | 22 |
| 6 | 刷新权重 EditBox | 146 | 22 |
| 7 | 地图过滤模式 | 168 | 22 |
| 8 | 地图列表 EditBox | 190 | 22 |
| 底部 | 保存/重置/返回 | areaH - 50 | — |

#### 3.2.3 交互逻辑

- `EditBox` 由 `super.render()` / `super.mouseClicked()` / `super.keyPressed()` 自动处理
- 颜色点击区域：使用明确的矩形命中检测 `Rect2i` 代替当前的手动坐标计算
- 保存：从各 `EditBox` 读取值，写入 `ConfigManager`，调用 `save()`
- 重置：清空所有 `EditBox` 为默认值
- 返回：关闭详情面板

#### 3.2.4 覆盖文件

| 文件 | 改动 |
|------|------|
| `client/gui/TaskDetailPanel.java` | 重写为非静态类，添加 EditBox，修正布局 |
| `client/gui/MainConfigScreen.java` | 适配非静态 TaskDetailPanel，转发键盘事件 |

---

## 4. Bug 3：搜索框/返回按钮/任务列表重叠

### 4.1 根因

`MainConfigScreen.init()` 中：
- 搜索框位置：`searchY = HEADER_H + 4 = 34`
- `TaskListPanel.render()` 中：`listY = areaY + headerH + 2 = 32`
- 两者仅差 2px，**内容完全重叠**

底部：
- 关闭按钮在 `height - 22`
- 任务列表滚动到底部时最后一行也画在此处 → **底部重叠**

### 4.2 修复方案

#### 4.2.1 搜索框区域

```
MainConfigScreen HEADER_H = 30
搜索框: y = HEADER_H + 2 = 32, height = 14
搜索框底部: 32 + 14 + 4 = 50 (含 4px 间距)

TaskListPanel 可见区域:
  listStartY = areaY + headerH + 24 (跳过搜索框区域)
  listEndY = areaY + areaH - 30 (跳过底部按钮)
```

#### 4.2.2 详细修正

| 项 | 当前值 | 修正值 |
|----|--------|--------|
| 搜索框 Y | `HEADER_H + 4` | `HEADER_H + 2` |
| 任务列表起始 Y | `areaY + headerH + 2` | `areaY + headerH + 24` |
| 任务列表下边距 | 无 | 底部留 30px |
| 关闭按钮 Y | `height - 22` | `height - 26` |

`TaskListPanel.render()` 改为接受 `bottomPadding` 参数或自动从 `areaH` 中预留底部空间。

#### 4.2.3 覆盖文件

| 文件 | 改动 |
|------|------|
| `client/gui/TaskListPanel.java` | 列表起始/结束 Y 修正 |
| `client/gui/MainConfigScreen.java` | 搜索框位置、关闭按钮位置微调 |

---

## 5. 实施顺序

| # | 阶段 | 内容 |
|---|------|------|
| 1 | TaskInstance.toNbt 修复 | 加入 type 字段 + fromNbt 兼容 |
| 2 | GenerateTaskMixin 通知修复 | 分配 DLC 任务时发送正确名称 |
| 3 | 构建验证 | ./gradlew clean build 确认编译通过 |
| 4 | TaskDetailPanel 重写 | EditBox 控件 + 布局修正 |
| 5 | 布局重叠修复 | 搜索框/列表/按钮 Y 坐标修正 |
| 6 | 整体构建 | 构建两个模组，游戏内验证 |

---

## 6. 不包含的范围

- ❌ 不添加新的 HUD 元素
- ❌ 不添加新的配置项
- ❌ 不修改 SRE 本体代码
- ❌ 不修改 GameMode API 或任务注册 API
