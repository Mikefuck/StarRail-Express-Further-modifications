# ModMenu 配置界面重写 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将硬编码 4 卡片布局的 ConfigScreen 重写为左侧导航栏 + 右侧面板的三级导航 ModMenu 配置界面

**Architecture:** MainConfigScreen 作为唯一的 Screen，管理左侧侧栏和右侧内容区。TaskListPanel 和 TaskDetailPanel 是非 Screen 的渲染辅助类，MainConfigScreen 根据状态切换并调用它们的渲染方法。动态模式列表来自 GameModeRegistry.getAll()。

**Tech Stack:** Fabric 1.21.1, Java 21, Minecraft GUI (Screen, GuiGraphics)

## 全局约束

- 不修改现有 `ConfigManager` / `TaskConfigEntry` / `GameModeRegistry`
- 不修改网络同步逻辑
- 不添加新设置项（仅保留现有项）
- 侧栏固定 180px 宽
- 详情面板固定 320px 宽
- GameMode 列表从 `GameModeRegistry.getAll()` 动态读取

---

## 文件清单

### 新建（3 个文件）

```
src/main/java/com/habitrain/core/client/gui/
├── MainConfigScreen.java      — 主配置界面：侧栏 + 内容容器 + 状态管理
├── TaskListPanel.java         — 任务列表面板：渲染 + 搜索 + 启用开关
└── TaskDetailPanel.java       — 任务详情滑入面板：设置项表单
```

### 修改（1 个文件）

```
src/main/java/com/habitrain/core/client/gui/ModMenuIntegration.java
  → ConfigScreen::new → MainConfigScreen::new
```

---

### Task 1：MainConfigScreen — 侧栏 + 内容容器

**文件：**
- 创建：`src/main/java/com/habitrain/core/client/gui/MainConfigScreen.java`

**接口：**
- 消耗：`GameModeRegistry.getAll()`, `GameMode.getDisplayName()`, `TaskCategory` 常量
- 产出：MainConfigScreen 类，包含侧栏渲染和右侧内容区域管理

- [ ] **Step 1: 创建 MainConfigScreen.java**

```java
package com.habitrain.core.client.gui;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.GameModeRegistry;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 哈比列车核心 — ModMenu 配置主界面
 *
 * 导航层级:
 *   MainConfigScreen (侧栏 + 内容区)
 *     ├─ 侧栏: 全局设置 | 各 GameMode
 *     ├─ 内容: TaskListPanel (任务列表)
 *     └─ 叠加: TaskDetailPanel (任务详情滑入面板)
 *
 * GameMode 列表从 GameModeRegistry 动态读取，DLC 模组注册后自动出现。
 */
public class MainConfigScreen extends Screen {

    // ====== 布局常量 ======
    private static final int SIDEBAR_W = 180;
    private static final int DETAIL_PANEL_W = 320;
    private static final int PAD = 6;
    private static final int SIDEBAR_ENTRY_H = 28;
    private static final int HEADER_H = 30;

    // ====== 侧栏条目 ======
    private record SidebarEntry(String label, String icon, Object tag, boolean isGameMode) {}

    private final List<SidebarEntry> sidebarEntries = new ArrayList<>();
    private int selectedSidebarIndex = 0;

    // ====== 右侧面板状态 ======
    private final Screen parent;
    private String searchText = "";
    private EditBox searchBox;
    private TaskCategory currentCategory;       // 当前选中的模式
    private TaskDefinition editingTask = null;   // 非 null = 显示详情面板
    private Color currentColor;                  // 编辑中的颜色
    private float currentOutlineWidth;           // 编辑中的描边
    private int currentMapFilter;                // 编辑中的地图模式
    private String mapsText = "";                // 编辑中的地图列表

    // ====== 滚动 ======
    private double scrollOffset = 0;
    private boolean draggingScroll = false;
    private double dragStartY = 0, dragStartOff = 0;

    // ====== 颜色预设（20色） ======
    private static final int[] COLOR_PRESETS = {
        0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00, 0xFF00FF00,
        0xFF0000FF, 0xFF8B00FF, 0xFFFF00FF, 0xFF00FFFF,
        0xFFFFC0CB, 0xFFFFA500, 0xFFC0C0C0, 0xFFFFFFFF,
        0xFFFF6B6B, 0xFFFFD700, 0xFF7CFC00, 0xFF00FA9A,
        0xFF6020F0, 0xFFFF1493, 0xFF00CED1, 0xFFFF8C00
    };

    private static final String[] COLOR_NAMES = {
        "红色","橙色","黄色","绿色","蓝色","紫色","品红色","青色",
        "粉色","琥珀色","银色","白色","珊瑚色","金色","草绿色","碧绿色",
        "紫罗兰","深粉色","深蓝色","亮橙色"
    };

    public MainConfigScreen(Screen parent) {
        super(Component.literal("§l哈比列车核心 — 任务配置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        rebuildSidebar();
        currentColor = new Color(200, 200, 200, 180);
        currentOutlineWidth = 4.0f;
        currentMapFilter = 0;
        mapsText = "";

        // 搜索框 (在右侧内容区顶部)
        int searchX = SIDEBAR_W + PAD;
        int searchY = HEADER_H + 4;
        searchBox = new EditBox(font, searchX, searchY, Math.min(200, width - SIDEBAR_W - PAD * 4), 14, Component.literal(""));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("🔍 搜索任务..."));
        searchBox.setResponder(t -> searchText = t.trim().toLowerCase());
        addRenderableWidget(searchBox);

        // 底部关闭按钮
        addRenderableWidget(Button.builder(
                Component.literal("§c✖ 关闭"), b -> onClose()
        ).bounds(width - 70, height - 22, 60, 16).build());
    }

    /** 构建侧栏条目列表 */
    private void rebuildSidebar() {
        sidebarEntries.clear();
        sidebarEntries.add(new SidebarEntry("全局设置", "⚙", null, false));
        sidebarEntries.add(new SidebarEntry("", "─", null, false)); // 分隔线
        for (var gameMode : GameModeRegistry.getAll()) {
            sidebarEntries.add(new SidebarEntry(
                    gameMode.getDisplayName(), "▸", gameMode, true));
        }
    }

    // =========================================================
    //  渲染
    // =========================================================

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);

        // 侧栏背景
        g.fill(0, 0, SIDEBAR_W, height, 0xFF1A1A2E);
        g.fill(SIDEBAR_W, 0, SIDEBAR_W + 1, height, 0xFF333355);

        // 渲染侧栏条目
        renderSidebar(g, mx, my);

        // 内容区背景
        g.fill(SIDEBAR_W + 1, 0, width, height, 0xFF2D2D3F);

        // 根据当前状态渲染右侧内容
        if (selectedSidebarIndex == 0) {
            // 全局设置 — 提示文字（点击打开独立 Screen）
            String msg = "§e⚙ 点击左侧「全局设置」打开全局设置页面";
            g.drawString(font, Component.literal(msg),
                    SIDEBAR_W + (width - SIDEBAR_W) / 2 - font.width(msg) / 2,
                    height / 2 - 10, 0, false);
        } else {
            SidebarEntry entry = sidebarEntries.get(selectedSidebarIndex);
            if (entry.isGameMode() && entry.tag() instanceof GameMode gm) {
                // 获取此模式下的所有任务
                String modeId = gm.getId();
                List<TaskDefinition> tasks = getTasksForGameMode(modeId);

                if (editingTask != null) {
                    // 渲染任务列表（半透明遮罩）+ 详情面板
                    TaskListPanel.render(g, font, tasks, searchText, scrollOffset, SIDEBAR_W, 0,
                            width, height, HEADER_H, mx, my, this::openTaskDetail);
                    TaskDetailPanel.render(g, font, editingTask,
                            currentColor, currentOutlineWidth, currentMapFilter, mapsText,
                            SIDEBAR_W, 0, width, height);
                } else {
                    TaskListPanel.render(g, font, tasks, searchText, scrollOffset, SIDEBAR_W, 0,
                            width, height, HEADER_H, mx, my, this::openTaskDetail);
                }
            }
        }

        super.render(g, mx, my, delta);
    }

    /** 渲染侧栏条目 */
    private void renderSidebar(GuiGraphics g, int mx, int my) {
        int y = 10;
        for (int i = 0; i < sidebarEntries.size(); i++) {
            SidebarEntry entry = sidebarEntries.get(i);
            boolean hover = mx >= 4 && mx < SIDEBAR_W - 4 && my >= y && my < y + SIDEBAR_ENTRY_H;
            boolean selected = i == selectedSidebarIndex;

            if (entry.label().equals("─")) {
                // 分隔线
                g.fill(10, y + SIDEBAR_ENTRY_H / 2, SIDEBAR_W - 10, y + SIDEBAR_ENTRY_H / 2 + 1, 0xFF444466);
                y += SIDEBAR_ENTRY_H;
                continue;
            }

            // 选中/悬停背景
            if (selected) g.fill(4, y, SIDEBAR_W - 4, y + SIDEBAR_ENTRY_H, 0xFF333388);
            else if (hover) g.fill(4, y, SIDEBAR_W - 4, y + SIDEBAR_ENTRY_H, 0xFF2A2A55);

            // 左侧选中指示条
            if (selected) g.fill(2, y, 4, y + SIDEBAR_ENTRY_H, 0xFF8888FF);

            // 图标 + 标签
            String text = entry.icon() + " " + entry.label();
            int textColor = selected ? 0xFFFFFF : (hover ? 0xDDDDDD : 0x999999);
            g.drawString(font, Component.literal(text), 12, y + 7, textColor, false);

            y += SIDEBAR_ENTRY_H;
        }
    }

    // =========================================================
    //  鼠标事件
    // =========================================================

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (super.mouseClicked(mx, my, btn)) return true;

        // 详情面板开启时，优先处理详情面板点击
        if (editingTask != null && mx > width - DETAIL_PANEL_W) {
            if (TaskDetailPanel.mouseClicked(this, (int) mx, (int) my, btn, editingTask,
                    currentColor, currentOutlineWidth, currentMapFilter, mapsText,
                    SIDEBAR_W, 0, width, height, this::closeTaskDetail)) {
                return true;
            }
        }

        // 侧栏点击检测
        int y = 10;
        for (int i = 0; i < sidebarEntries.size(); i++) {
            SidebarEntry entry = sidebarEntries.get(i);
            boolean hit = mx >= 4 && mx < SIDEBAR_W - 4 && my >= y && my < y + SIDEBAR_ENTRY_H;
            if (hit && !entry.label().equals("─")) {
                selectedSidebarIndex = i;
                editingTask = null; // 关闭详情面板
                scrollOffset = 0;

                if (i == 0) {
                    // 全局设置 — 打开独立 Screen
                    Minecraft.getInstance().setScreen(new GlobalSettingsScreen(this));
                } else if (entry.isGameMode()) {
                    // 切换模式 — 更新搜索框 hint
                    searchBox.setValue("");
                    searchText = "";
                }
                return true;
            }
            y += entry.label().equals("─") ? SIDEBAR_ENTRY_H : SIDEBAR_ENTRY_H;
        }

        // 任务列表点击（没开启详情面板时）
        if (editingTask == null && selectedSidebarIndex > 0) {
            SidebarEntry entry = sidebarEntries.get(selectedSidebarIndex);
            if (entry.isGameMode() && entry.tag() instanceof GameMode) {
                List<TaskDefinition> tasks = getTasksForGameMode(((GameMode) entry.tag()).getId());
                int clicked = TaskListPanel.mouseClicked(tasks, searchText, scrollOffset,
                        SIDEBAR_W, 0, width, height, HEADER_H, (int) mx, (int) my, btn,
                        this::openTaskDetail, this::toggleTask);
                if (clicked >= 0) {
                    // 滚动条拖拽开始
                    if (clicked == -2) { draggingScroll = true; dragStartY = my; dragStartOff = scrollOffset; }
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        draggingScroll = false;
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (editingTask != null) {
            // 详情面板滚动
            return TaskDetailPanel.mouseScrolled(vertical);
        }
        if (mx > SIDEBAR_W) {
            scrollOffset = Math.max(0, scrollOffset - vertical * 28);
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.keyPressed(key, sc, mod)) return true;
        if (editingTask != null && TaskDetailPanel.keyPressed(key, sc, mod,
                editingTask, currentOutlineWidth, this)) return true;
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, sc, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.charTyped(ch, mod)) return true;
        return super.charTyped(ch, mod);
    }

    // =========================================================
    //  任务交互
    // =========================================================

    private void openTaskDetail(TaskDefinition def) {
        editingTask = def;
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (cfg != null) {
            currentColor = cfg.getColor();
            currentOutlineWidth = cfg.outlineWidth;
            currentMapFilter = cfg.mapFilterMode;
            mapsText = cfg.enabledMaps != null ? String.join(",", cfg.enabledMaps) : "";
        } else {
            currentColor = def.getInstinctColor() != null ? def.getInstinctColor() : new Color(200, 200, 200, 180);
            currentOutlineWidth = 4.0f;
            currentMapFilter = 0;
            mapsText = "";
        }
    }

    private void closeTaskDetail() {
        editingTask = null;
    }

    private void toggleTask(TaskDefinition def) {
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (cfg != null) {
            cfg.enabled = !cfg.enabled;
            ConfigManager.getInstance().save();
        }
    }

    /** 获取属于某个 GameMode 的所有任务 */
    private List<TaskDefinition> getTasksForGameMode(String gameModeId) {
        return TaskRegistry.getAll().stream()
                .filter(def -> gameModeId.equals(def.getGameModeId()))
                .sorted(Comparator.comparing(TaskDefinition::getFullId))
                .collect(Collectors.toList());
    }

    @Override
    public void onClose() {
        ConfigManager.getInstance().save();
        Minecraft.getInstance().setScreen(parent);
    }
}
```

*Note: TaskListPanel and TaskDetailPanel are referenced as static helper classes. They will be created in Tasks 2 and 3.*

- [ ] **Step 2: 验证无语法错误**

编译检查无错误即可（TaskListPanel 和 TaskDetailPanel 的引用暂时标红属于正常）。

- [ ] **Step 3: 暂不构建（等后续 Task）**

---

### Task 2：TaskListPanel — 任务列表面板

**文件：**
- 创建：`src/main/java/com/habitrain/core/client/gui/TaskListPanel.java`

**接口：**
- 消耗：`TaskDefinition`, `TaskConfigEntry`, `ConfigManager`
- 产出：静态 `render()` 和 `mouseClicked()` 方法，供 MainConfigScreen 调用

- [ ] **Step 1: 创建 TaskListPanel.java**

```java
package com.habitrain.core.client.gui;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 任务列表面板 — 渲染和点击处理（非 Screen，由 MainConfigScreen 调用）
 *
 * 布局:
 *   ┌─ 模式名称 + 统计 ──────────────────────────┐
 *   │  谋杀模式                      14/14 已启用  │
 *   ├─ 任务列表 (可滚动) ─────────────────────────┤
 *   │  █ 睡觉                           [SRE] [✓] │
 *   │  █ 进食                           [SRE] [✓] │
 *   │  █ test_grass              [more_tasks] [✓] │
 *   │  █ 摸猫猫                      [more_tasks] [✓] │
 *   └─────────────────────────────────────────────┘
 */
public class TaskListPanel {

    private static final int ROW_H = 26;
    private static final int ROW_GAP = 2;
    private static final int SCROLLBAR_W = 4;
    private static final int COLOR_DOT_W = 10;
    private static final int TOGGLE_W = 28;

    private TaskListPanel() {}

    /**
     * 渲染任务列表
     *
     * @param g          GuiGraphics
     * @param font       Minecraft Font
     * @param tasks      当前模式的所有任务
     * @param searchText 搜索过滤文字
     * @param scroll     滚动偏移量
     * @param areaX      内容区左上 X
     * @param areaY      内容区左上 Y
     * @param areaW      内容区宽度
     * @param areaH      内容区高度
     * @param headerH    顶部标题高度
     * @param mx         鼠标 X
     * @param my         鼠标 Y
     * @param onOpen     (TaskDefinition) -> void，点击任务行时调用
     */
    public static void render(GuiGraphics g, Font font, List<TaskDefinition> tasks,
                              String searchText, double scroll,
                              int areaX, int areaY, int areaW, int areaH,
                              int headerH, int mx, int my,
                              Consumer<TaskDefinition> onOpen) {
        int contentX = areaX + 4;
        int contentW = areaW - areaX - 8 - SCROLLBAR_W;
        int listY = areaY + headerH + 2;

        // 过滤
        List<TaskDefinition> filtered = filterTasks(tasks, searchText);

        // 顶部标题
        String title = getModeName(tasks);
        String stats = String.format("§7%d 个任务", filtered.size());
        g.drawString(font, Component.literal(title), contentX, areaY + 4, 0xFFFFFF, false);
        g.drawString(font, Component.literal(stats),
                contentX + font.width(title) + 12, areaY + 4, 0x888888, false);

        // 分隔线
        g.fill(areaX + 4, areaY + headerH, areaW - 4, areaY + headerH + 1, 0x44446666);

        // 计算可见区域
        int totalH = filtered.size() * (ROW_H + ROW_GAP);
        int visibleH = areaH - headerH - 6;
        int maxScroll = Math.max(0, totalH - visibleH);
        double safeScroll = Math.min(scroll, maxScroll);

        // 渲染每行
        int startIdx = (int) (safeScroll / (ROW_H + ROW_GAP));
        int y = listY - (int) (safeScroll % (ROW_H + ROW_GAP));
        int endY = areaY + areaH - 6;

        for (int i = startIdx; i < filtered.size() && y < endY; i++) {
            TaskDefinition def = filtered.get(i);
            TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
            boolean enabled = cfg == null || cfg.enabled;
            Color color = cfg != null ? cfg.getColor()
                    : (def.getInstinctColor() != null ? def.getInstinctColor() : new Color(200, 200, 200, 180));
            boolean hover = mx >= contentX && mx < contentX + contentW
                    && my >= y && my < y + ROW_H;

            // 行背景
            if (hover) {
                g.fill(contentX, y, contentX + contentW, y + ROW_H, 0x22FFFFFF);
            }

            // 颜色点
            g.fill(contentX + 2, y + 8, contentX + 2 + COLOR_DOT_W, y + 8 + COLOR_DOT_W, color.getRGB());

            // 任务名
            String name = def.getDisplayName();
            int nameColor = enabled ? 0xDDDDDD : 0x666666;
            g.drawString(font, Component.literal(name), contentX + COLOR_DOT_W + 8, y + 7, nameColor, false);

            // 来源标签
            String modTag = getModTag(def.getModId());
            g.drawString(font, Component.literal("§7[" + modTag + "]"),
                    contentX + COLOR_DOT_W + 8 + font.width(name) + 8, y + 7, 0, false);

            // 启用开关
            String toggle = enabled ? "§a✔" : "§c✖";
            int toggleX = contentX + contentW - TOGGLE_W;
            g.drawString(font, Component.literal(toggle), toggleX, y + 7, 0, false);

            y += ROW_H + ROW_GAP;
        }

        // 搜索无结果
        if (filtered.isEmpty()) {
            String msg = searchText.isEmpty() ? "§7该模式下没有任务" : "§7没有匹配 \"§f" + searchText + "§7\" 的任务";
            g.drawString(font, Component.literal(msg),
                    contentX + contentW / 2 - font.width(msg) / 2, listY + 30, 0, false);
        }

        // 滚动条
        if (maxScroll > 0) {
            int scrollBarH = Math.max(20, (int) ((float) visibleH / totalH * visibleH));
            int scrollBarY = listY + (int) ((safeScroll / maxScroll) * (visibleH - scrollBarH));
            g.fill(areaW - SCROLLBAR_W - 2, scrollBarY, areaW - 2, scrollBarY + scrollBarH, 0x88AAAAAA);
        }
    }

    /**
     * 处理鼠标点击
     * @return 0=点击任务行, -1=未处理, -2=滚动条
     */
    public static int mouseClicked(List<TaskDefinition> tasks, String searchText, double scroll,
                                   int areaX, int areaY, int areaW, int areaH, int headerH,
                                   int mx, int my, int btn,
                                   Consumer<TaskDefinition> onOpen,
                                   Consumer<TaskDefinition> onToggle) {
        List<TaskDefinition> filtered = filterTasks(tasks, searchText);
        int contentX = areaX + 4;
        int contentW = areaW - areaX - 8 - SCROLLBAR_W;
        int listY = areaY + headerH + 2;
        int visibleH = areaH - headerH - 6;
        int totalH = filtered.size() * (ROW_H + ROW_GAP);
        int maxScroll = Math.max(0, totalH - visibleH);
        double safeScroll = Math.min(scroll, maxScroll);

        int y = listY - (int) (safeScroll % (ROW_H + ROW_GAP));
        int startIdx = (int) (safeScroll / (ROW_H + ROW_GAP));

        for (int i = startIdx; i < filtered.size(); i++) {
            if (y + ROW_H > areaY + areaH - 6) break;

            if (mx >= contentX && mx < contentX + contentW && my >= y && my < y + ROW_H) {
                TaskDefinition def = filtered.get(i);
                // 检查是否点到了启用开关区域
                int toggleX = contentX + contentW - 28;
                if (mx >= toggleX) {
                    if (onToggle != null) onToggle.accept(def);
                    return 0;
                }
                // 点击任务行 → 打开详情
                if (onOpen != null) onOpen.accept(def);
                return 0;
            }
            y += ROW_H + ROW_GAP;
        }

        return -1;
    }

    private static List<TaskDefinition> filterTasks(List<TaskDefinition> tasks, String searchText) {
        if (searchText == null || searchText.isEmpty()) return tasks;
        return tasks.stream()
                .filter(t -> t.getDisplayName().toLowerCase().contains(searchText)
                        || t.getFullId().toLowerCase().contains(searchText)
                        || t.getModId().toLowerCase().contains(searchText))
                .collect(Collectors.toList());
    }

    private static String getModeName(List<TaskDefinition> tasks) {
        if (tasks.isEmpty()) return "§l未知模式";
        String gmId = tasks.get(0).getGameModeId();
        return "§l" + gmId;
    }

    private static String getModTag(String modId) {
        return switch (modId) {
            case "habitrain_core" -> "SRE";
            case "habitrain_more_tasks" -> "more";
            default -> modId.length() > 8 ? modId.substring(0, 8) + "…" : modId;
        };
    }
}
```

- [ ] **Step 2: 验证语法**

---

### Task 3：TaskDetailPanel — 任务详情滑入面板

**文件：**
- 创建：`src/main/java/com/habitrain/core/client/gui/TaskDetailPanel.java`

**接口：**
- 消耗：`TaskDefinition`, `TaskConfigEntry`, `ConfigManager`
- 产出：静态 `render()`, `mouseClicked()`, `keyPressed()`, `mouseScrolled()` 方法

- [ ] **Step 1: 创建 TaskDetailPanel.java**

```java
package com.habitrain.core.client.gui;

import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.awt.Color;

/**
 * 任务详情滑入面板 — 渲染和交互处理（非 Screen，由 MainConfigScreen 调用）
 *
 * 从右侧滑入，覆盖在 TaskListPanel 之上，带半透明遮罩。
 * 约 320px 宽，包含所有设置项。
 */
public class TaskDetailPanel {

    private static final int PANEL_W = 320;
    private static final int PAD = 10;
    private static final int ROW_H = 22;
    private static final int LABEL_W = 72;
    private static final int[] COLOR_PRESETS = {
        0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00, 0xFF00FF00,
        0xFF0000FF, 0xFF8B00FF, 0xFFFF00FF, 0xFF00FFFF,
        0xFFFFC0CB, 0xFFFFA500, 0xFFC0C0C0, 0xFFFFFFFF,
        0xFFFF6B6B, 0xFFFFD700, 0xFF7CFC00, 0xFF00FA9A,
        0xFF6020F0, 0xFFFF1493, 0xFF00CED1, 0xFFFF8C00
    };
    private static final String[] COLOR_NAMES = {
        "红色","橙色","黄色","绿色","蓝色","紫色","品红色","青色",
        "粉色","琥珀色","银色","白色","珊瑚色","金色","草绿色","碧绿色",
        "紫罗兰","深粉色","深蓝色","亮橙色"
    };
    private static final String[] MAP_MODES = {"全部地图", "仅以下地图", "排除以下地图"};

    private static double detailScroll = 0;

    private TaskDetailPanel() {}

    /**
     * 渲染详情面板（覆盖在任务列表之上）
     */
    public static void render(GuiGraphics g, Font font, TaskDefinition def,
                              Color currentColor, float outlineWidth,
                              int mapFilterMode, String mapsText,
                              int areaX, int areaY, int areaW, int areaH) {
        int panelX = areaW - PANEL_W;
        int panelY = areaY;

        // 半透明遮罩
        g.fill(areaX, areaY, panelX, areaH, 0x88000000);

        // 面板背景
        g.fill(panelX, panelY, areaW, areaH, 0xFF2D2D3F);
        g.fill(panelX, panelY, panelX + 1, areaH, 0xFF555577);

        // 顶部: ← 返回 + 标题
        g.drawString(font, Component.literal("§l← 返回"), panelX + PAD, panelY + 8, 0xFFFFFF, false);
        String title = def.getDisplayName() + " §7(" + def.getFullId() + ")";
        g.drawString(font, Component.literal(title),
                panelX + PAD + font.width("← 返回") + 14, panelY + 8, 0xDDDDDD, false);

        int y = panelY + 36;

        // 内容（可滚动）
        int contentStart = y;
        int contentEnd = panelY + areaH - 50;

        // 1. 启用/禁用
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        boolean enabled = cfg == null || cfg.enabled;
        g.drawString(font, Component.literal("§7状态:"), panelX + PAD, y, 0, false);
        String status = enabled ? "§a✔ 已启用" : "§c✖ 已禁用";
        g.drawString(font, Component.literal(status), panelX + PAD + LABEL_W, y, 0, false);
        y += ROW_H;

        // 2. 透视颜色
        g.drawString(font, Component.literal("§7颜色:"), panelX + PAD, y, 0, false);
        int colorX = panelX + PAD + LABEL_W;
        g.fill(colorX, y + 4, colorX + 16, y + 20, currentColor.getRGB());
        String colorName = getColorName(currentColor);
        g.drawString(font, Component.literal("§f" + colorName + " §7[点击切换]"),
                colorX + 20, y, 0, false);
        y += ROW_H;

        // 3. 描边粗细
        g.drawString(font, Component.literal("§7描边:"), panelX + PAD, y, 0, false);
        g.drawString(font, Component.literal("§f[-] §e" + String.format("%.1f", outlineWidth) + " §f[+]"),
                panelX + PAD + LABEL_W, y, 0, false);
        y += ROW_H;

        // 4. 金币奖励
        g.drawString(font, Component.literal("§7金币:"), panelX + PAD, y, 0, false);
        String gold = cfg != null && cfg.goldReward >= 0 ? String.valueOf(cfg.goldReward) : "§7(默认)";
        g.drawString(font, Component.literal("§f" + gold),
                panelX + PAD + LABEL_W, y, 0, false);
        y += ROW_H;

        // 5. 情绪奖励
        g.drawString(font, Component.literal("§7情绪:"), panelX + PAD, y, 0, false);
        String emotion = cfg != null && cfg.emotionReward >= 0f ? String.format("%.1f", cfg.emotionReward) : "§7(默认)";
        g.drawString(font, Component.literal("§f" + emotion),
                panelX + PAD + LABEL_W, y, 0, false);
        y += ROW_H;

        // 6. 刷新权重
        g.drawString(font, Component.literal("§7权重:"), panelX + PAD, y, 0, false);
        String weight = cfg != null && cfg.refreshWeight >= 0f ? String.format("%.1f", cfg.refreshWeight) : "§7(默认)";
        g.drawString(font, Component.literal("§f" + weight),
                panelX + PAD + LABEL_W, y, 0, false);
        y += ROW_H;

        // 7. 地图过滤模式
        g.drawString(font, Component.literal("§7地图:"), panelX + PAD, y, 0, false);
        String mapMode = MAP_MODES[Math.min(mapFilterMode, MAP_MODES.length - 1)];
        g.drawString(font, Component.literal("§f[ " + mapMode + " ] §7[点击切换]"),
                panelX + PAD + LABEL_W, y, 0, false);
        y += ROW_H;

        // 8. 地图列表
        g.drawString(font, Component.literal("§7地图列:"), panelX + PAD, y, 0, false);
        String displayMaps = mapsText.isEmpty() ? "§7(空=全部)" : mapsText;
        if (font.width(displayMaps) > PANEL_W - PAD * 2 - LABEL_W) {
            displayMaps = font.plainSubstrByWidth(displayMaps, PANEL_W - PAD * 2 - LABEL_W - 6) + "…";
        }
        g.drawString(font, Component.literal("§f" + displayMaps),
                panelX + PAD + LABEL_W, y, 0, false);
        y += ROW_H + 8;

        // 9. 按钮
        int btnY = Math.max(y, panelY + areaH - 50);
        g.drawString(font, Component.literal("§a[保存]  §7[重置]  §c[返回]"),
                panelX + PAD + 10, btnY, 0, false);
    }

    /**
     * 处理详情面板鼠标点击
     */
    public static boolean mouseClicked(MainConfigScreen screen, int mx, int my, int btn,
                                        TaskDefinition def, Color currentColor, float outlineWidth,
                                        int mapFilterMode, String mapsText,
                                        int areaX, int areaY, int areaW, int areaH,
                                        Runnable onClose) {
        Font font = Minecraft.getInstance().font;
        int panelX = areaW - PANEL_W;

        // ← 返回区域
        if (mx >= panelX + PAD && mx <= panelX + PAD + font.width("← 返回") + 10
                && my >= areaY + 8 && my <= areaY + 30) {
            onClose.run();
            return true;
        }

        // 颜色切换
        int colorLabelX = panelX + PAD + LABEL_W + 20 + font.width(getColorName(currentColor)) + 20;
        if (mx >= panelX + PAD + LABEL_W && mx <= panelX + PAD + LABEL_W + 100
                && my >= areaY + 36 + ROW_H && my <= areaY + 36 + ROW_H * 2) {
            // 颜色循环
            int nextIdx = (findColorIndex(currentColor) + 1) % COLOR_PRESETS.length;
            int rgb = COLOR_PRESETS[nextIdx];
            saveColor(def, new Color(rgb));
            return true;
        }

        // 描边粗细 [-]
        if (mx >= panelX + PAD + LABEL_W && mx <= panelX + PAD + LABEL_W + 24
                && my >= areaY + 36 + ROW_H * 2 && my <= areaY + 36 + ROW_H * 3) {
            saveOutlineWidth(def, Math.max(1.0f, outlineWidth - 0.5f));
            return true;
        }

        // 描边粗细 [+]
        if (mx >= panelX + PAD + LABEL_W + 24 + font.width(String.format("%.1f", outlineWidth)) + 8
                && mx <= panelX + PAD + LABEL_W + 24 + font.width(String.format("%.1f", outlineWidth)) + 8 + 24
                && my >= areaY + 36 + ROW_H * 2 && my <= areaY + 36 + ROW_H * 3) {
            saveOutlineWidth(def, Math.min(10.0f, outlineWidth + 0.5f));
            return true;
        }

        // 地图过滤模式切换
        if (mx >= panelX + PAD + LABEL_W + 24 && mx <= panelX + PAD + LABEL_W + 24 + font.width("[ 全部地图 ]") + 40
                && my >= areaY + 36 + ROW_H * 6 && my <= areaY + 36 + ROW_H * 7) {
            int nextMode = (mapFilterMode + 1) % 3;
            saveMapFilter(def, nextMode);
            return true;
        }

        // 保存按钮
        if (mx >= panelX + PAD + 10 && mx <= panelX + PAD + 10 + font.width("[保存]") + 10
                && my >= areaY + areaH - 50 && my <= areaY + areaH - 50 + 16) {
            ConfigManager.getInstance().save();
            return true;
        }

        // 重置按钮
        int resetX = panelX + PAD + 10 + font.width("[保存]  ");
        if (mx >= resetX && mx <= resetX + font.width("[重置]") + 10
                && my >= areaY + areaH - 50 && my <= areaY + areaH - 50 + 16) {
            resetTask(def);
            return true;
        }

        return false;
    }

    public static boolean mouseScrolled(double vertical) {
        detailScroll = Math.max(0, detailScroll - vertical * 14);
        return true;
    }

    public static boolean keyPressed(int key, int sc, int mod,
                                      TaskDefinition def, float outlineWidth,
                                      MainConfigScreen screen) {
        // 未实现 EditBox 键盘输入 — 详情面板中的数字编辑将在后续迭代中保留给 EditBox
        return false;
    }

    // ========== 持久化操作 ==========

    private static void saveColor(TaskDefinition def, Color color) {
        TaskConfigEntry cfg = getOrCreateConfig(def);
        cfg.instinctColor = color.getRGB();
        ConfigManager.getInstance().save();
    }

    private static void saveOutlineWidth(TaskDefinition def, float width) {
        TaskConfigEntry cfg = getOrCreateConfig(def);
        cfg.outlineWidth = width;
        ConfigManager.getInstance().save();
    }

    private static void saveMapFilter(TaskDefinition def, int mode) {
        TaskConfigEntry cfg = getOrCreateConfig(def);
        cfg.mapFilterMode = mode;
        ConfigManager.getInstance().save();
    }

    private static TaskConfigEntry getOrCreateConfig(TaskDefinition def) {
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (cfg == null) {
            cfg = new TaskConfigEntry();
            ConfigManager.getInstance().setTaskConfig(def.getFullId(), cfg);
        }
        return cfg;
    }

    private static void resetTask(TaskDefinition def) {
        ConfigManager.getInstance().setTaskConfig(def.getFullId(), new TaskConfigEntry(true));
        ConfigManager.getInstance().save();
    }

    // ========== 工具方法 ==========

    private static int findColorIndex(Color c) {
        int rgb = c.getRGB() & 0x00FFFFFF; // 忽略 alpha
        for (int i = 0; i < COLOR_PRESETS.length; i++) {
            if ((COLOR_PRESETS[i] & 0x00FFFFFF) == rgb) return i;
        }
        return 0;
    }

    private static String getColorName(Color c) {
        int idx = findColorIndex(c);
        return idx < COLOR_NAMES.length ? COLOR_NAMES[idx] : "自定义";
    }
}
```

- [ ] **Step 2: 验证语法**

---

### Task 4：集成 + 编译验证

**文件：**
- 修改：`src/main/java/com/habitrain/core/client/gui/ModMenuIntegration.java`

- [ ] **Step 1: 更新 ModMenuIntegration**

```java
package com.habitrain.core.client.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu 集成 — 提供任务配置界面
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MainConfigScreen::new;
    }
}
```

- [ ] **Step 2: 编译**

```bash
cd "D:/Backup/mc mod/哈比列车api"
./gradlew clean build 2>&1
```

预期：BUILD SUCCESSFUL

- [ ] **Step 3: 验证 JAR**

```bash
ls build/libs/habitrain_core-*.jar
jar tf build/libs/habitrain_core-*.jar | grep "MainConfigScreen"
# 应显示 MainConfigScreen.class 存在
```

- [ ] **Step 4: 复制到临时目录**

```bash
cp build/libs/habitrain_core-*.jar "D:/Backup/mc mod/临时/"
```
