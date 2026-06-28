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
    private TaskDefinition editingTask = null;
    private Color currentColor;
    private float currentOutlineWidth;
    private int currentMapFilter;
    private String mapsText = "";

    // ====== 滚动 ======
    private double scrollOffset = 0;
    private boolean draggingScroll = false;
    private double dragStartY = 0, dragStartOff = 0;

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

        // 搜索框
        int searchX = SIDEBAR_W + PAD;
        int searchY = HEADER_H + 4;
        searchBox = new EditBox(font, searchX, searchY, Math.min(200, width - SIDEBAR_W - PAD * 4), 14, Component.literal(""));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("🔍 搜索任务..."));
        searchBox.setResponder(t -> searchText = t.trim().toLowerCase());
        addRenderableWidget(searchBox);

        // 关闭按钮
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

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        super.render(g, mx, my, delta);

        // 侧栏背景
        g.fill(0, 0, SIDEBAR_W, height, 0xFF1A1A2E);
        g.fill(SIDEBAR_W, 0, SIDEBAR_W + 1, height, 0xFF333355);

        // 侧栏条目
        renderSidebar(g, mx, my);

        // 内容区背景
        g.fill(SIDEBAR_W + 1, 0, width, height, 0xFF2D2D3F);

        // 内容
        if (selectedSidebarIndex == 0) {
            String msg = "§e⚙ 点击左侧「全局设置」打开全局设置页面";
            g.drawString(font, Component.literal(msg),
                    SIDEBAR_W + (width - SIDEBAR_W) / 2 - font.width(msg) / 2,
                    height / 2 - 10, 0, false);
        } else if (selectedSidebarIndex < sidebarEntries.size()) {
            SidebarEntry entry = sidebarEntries.get(selectedSidebarIndex);
            if (entry.isGameMode() && entry.tag() instanceof GameMode gm) {
                String modeId = gm.getId();
                List<TaskDefinition> tasks = getTasksForGameMode(modeId);

                if (editingTask != null) {
                    TaskListPanel.render(g, font, tasks, searchText, scrollOffset,
                            SIDEBAR_W, 0, width, height, HEADER_H, mx, my, null);
                    TaskDetailPanel.render(g, font, editingTask,
                            currentColor, currentOutlineWidth, currentMapFilter, mapsText,
                            SIDEBAR_W, 0, width, height);
                } else {
                    TaskListPanel.render(g, font, tasks, searchText, scrollOffset,
                            SIDEBAR_W, 0, width, height, HEADER_H, mx, my, this::openTaskDetail);
                }
            }
        }
    }

    private void renderSidebar(GuiGraphics g, int mx, int my) {
        int y = 10;
        for (int i = 0; i < sidebarEntries.size(); i++) {
            SidebarEntry entry = sidebarEntries.get(i);
            boolean hover = mx >= 4 && mx < SIDEBAR_W - 4 && my >= y && my < y + SIDEBAR_ENTRY_H;
            boolean selected = i == selectedSidebarIndex;

            if (entry.label().isEmpty()) {
                // 分隔线
                g.fill(10, y + SIDEBAR_ENTRY_H / 2, SIDEBAR_W - 10, y + SIDEBAR_ENTRY_H / 2 + 1, 0xFF444466);
                y += SIDEBAR_ENTRY_H;
                continue;
            }

            if (selected) g.fill(4, y, SIDEBAR_W - 4, y + SIDEBAR_ENTRY_H, 0xFF333388);
            else if (hover) g.fill(4, y, SIDEBAR_W - 4, y + SIDEBAR_ENTRY_H, 0xFF2A2A55);

            if (selected) g.fill(2, y, 4, y + SIDEBAR_ENTRY_H, 0xFF8888FF);

            String text = entry.icon() + " " + entry.label();
            int textColor = selected ? 0xFFFFFF : (hover ? 0xDDDDDD : 0x999999);
            g.drawString(font, Component.literal(text), 12, y + 7, textColor, false);

            y += SIDEBAR_ENTRY_H;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (super.mouseClicked(mx, my, btn)) return true;

        // 详情面板开启时，优先处理
        if (editingTask != null && mx > width - DETAIL_PANEL_W) {
            if (TaskDetailPanel.mouseClicked(this, (int) mx, (int) my, btn, editingTask,
                    currentColor, currentOutlineWidth, currentMapFilter, mapsText,
                    SIDEBAR_W, 0, width, height, this::closeTaskDetail)) {
                return true;
            }
        }

        // 侧栏点击
        int y = 10;
        for (int i = 0; i < sidebarEntries.size(); i++) {
            SidebarEntry entry = sidebarEntries.get(i);
            if (entry.label().isEmpty()) { y += SIDEBAR_ENTRY_H; continue; }
            boolean hit = mx >= 4 && mx < SIDEBAR_W - 4 && my >= y && my < y + SIDEBAR_ENTRY_H;
            if (hit) {
                selectedSidebarIndex = i;
                editingTask = null;
                scrollOffset = 0;

                if (i == 0) {
                    Minecraft.getInstance().setScreen(new GlobalSettingsScreen(this));
                } else {
                    searchBox.setValue("");
                    searchText = "";
                }
                return true;
            }
            y += SIDEBAR_ENTRY_H;
        }

        // 任务列表点击
        if (editingTask == null && selectedSidebarIndex > 0 && selectedSidebarIndex < sidebarEntries.size()) {
            SidebarEntry entry = sidebarEntries.get(selectedSidebarIndex);
            if (entry.isGameMode() && entry.tag() instanceof GameMode gm) {
                List<TaskDefinition> tasks = getTasksForGameMode(gm.getId());
                TaskListPanel.mouseClicked(tasks, searchText, scrollOffset,
                        SIDEBAR_W, 0, width, height, HEADER_H, (int) mx, (int) my, btn,
                        this::openTaskDetail, this::toggleTask);
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
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, sc, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.charTyped(ch, mod)) return true;
        return super.charTyped(ch, mod);
    }

    // ========== 任务交互 ==========

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
        } else {
            TaskConfigEntry newCfg = new TaskConfigEntry(false);
            ConfigManager.getInstance().setTaskConfig(def.getFullId(), newCfg);
        }
    }

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
