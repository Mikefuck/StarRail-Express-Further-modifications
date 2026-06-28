package com.habitrain.core.client.gui;

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
 * =========================================================
 *  哈比列车任务系统 - 任务列表界面 (原版风格)
 * =========================================================
 *
 * 导航层级:
 *   ConfigScreen (模式选择) →
 *   TaskListScreen (任务列表) ← 当前
 *   → TaskEditScreen (任务详情)
 *
 * 功能:
 *   - 显示指定模式下的所有任务
 *   - 每行一个任务: 颜色指示、名称、来源标签、启用开关
 *   - 点击任务行 → 进入详细编辑
 *   - 搜索过滤
 *   - 启用/禁用快捷切换
 *   - 滚动列表
 */
public class TaskListScreen extends Screen {

    // ====== 布局常量 ======
    private static final int PAD = 8;
    private static final int HEADER_H = 30;
    private static final int SEARCH_H = 20;
    private static final int FOOTER_H = 26;
    private static final int ROW_H = 28;
    private static final int ROW_GAP = 2;
    private static final int SCROLLBAR_W = 4;

    // ====== 颜色预设 ======
    private static final int[] COLORS = {
            0xB4FF0000, 0xB4FF7F00, 0xB4FFFF00, 0xB400FF00,
            0xB40000FF, 0xB48B00FF, 0xB4FF00FF, 0xB400FFFF,
            0xB4FFC0CB, 0xB4FFA500, 0xB4C0C0C0, 0xB4FFFFFF,
            0xB4FF6B6B, 0xB4FFD700, 0xB47CFC00, 0xB400FA9A,
            0xB46020F0, 0xB4FF1493, 0xB400CED1, 0xB4FF8C00,
    };
    private static final String[] COLOR_NAMES = {
            "红","橙","黄","绿","蓝","紫","品红","青",
            "粉","琥珀","银","白","珊瑚","金","草绿","碧绿",
            "紫罗兰","深粉","深蓝","亮橙"
    };

    // ====== 状态 ======
    private final Screen parent;
    private final TaskCategory category;
    private final String modeDisplayName;
    private final int modeAccentColor;

    private final List<TaskRowInfo> allTaskInfos = new ArrayList<>();
    private final List<TaskRowWidget> visibleRows = new ArrayList<>();
    private double scrollOffset = 0;
    private int contentHeight = 0;

    // 控件
    private Button backBtn;
    private EditBox searchBox;
    private String searchText = "";

    // 拖动滚动
    private boolean draggingScroll = false;
    private double dragStartY = 0;
    private double dragStartOff = 0;

    // ====== 构造 ======
    public TaskListScreen(Screen parent, TaskCategory category,
                              String modeDisplayName, int modeAccentColor) {
        super(Component.literal("§l" + modeDisplayName + " - 任务列表"));
        this.parent = parent;
        this.category = category;
        this.modeDisplayName = modeDisplayName;
        this.modeAccentColor = modeAccentColor;
    }

    // =========================================================
    //  数据类
    // =========================================================

    /** 单行任务数据（不可变信息，从注册表构建） */
    private record TaskRowInfo(
            TaskDefinition def,
            TaskConfigEntry cfg,
            boolean isBuiltin,
            int colorRgb,
            String colorName
    ) {}

    /** 单行任务的交互控件 */
    private static class TaskRowWidgets {
        final Button toggleBtn;
        final Button editBtn;

        TaskRowWidgets(Button toggleBtn, Button editBtn) {
            this.toggleBtn = toggleBtn;
            this.editBtn = editBtn;
        }
    }

    // =========================================================
    //  初始化
    // =========================================================

    @Override
    protected void init() {
        super.init();

        // ---- 返回按钮 ----
        backBtn = addRenderableWidget(Button.builder(
                Component.literal("§7← 返回"), b ->
                        Minecraft.getInstance().setScreen(parent)
        ).bounds(PAD, 4, 80, 18).build());

        // ---- 搜索框（放在标题文字右侧，动态计算位置）----
        String titleStr = "§l" + modeDisplayName + " §7- 任务列表";
        int titleWidth = font.width(Component.literal(titleStr));
        int searchStartX = PAD + 84 + titleWidth + 8; // 标题右侧 + 8px间距
        int searchWidth = Math.max(80, Math.min(180, width - searchStartX - PAD));
        searchBox = new EditBox(font, searchStartX, 5, searchWidth, 14, Component.literal(""));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("🔍 搜索任务..."));
        searchBox.setResponder(t -> {
            searchText = t.trim().toLowerCase();
            rebuildRows();
        });
        addRenderableWidget(searchBox);

        // ---- 构建任务列表 ----
        rebuildTaskInfos();
        rebuildRows();
    }

    /** 从注册表构建任务信息列表 */
    private void rebuildTaskInfos() {
        allTaskInfos.clear();

        List<TaskDefinition> tasks = new ArrayList<>();
        for (var def : TaskRegistry.getAll()) {
            TaskCategory cat = def.getCategory();
            if (cat == category || cat == TaskCategory.ALL) {
                if (category == TaskCategory.ALL && cat != TaskCategory.ALL) continue;
                if (category == TaskCategory.CUSTOM && cat != TaskCategory.CUSTOM) continue;
                tasks.add(def);
            }
        }

        // 排序: 内置优先, 再按ID排序
        tasks.sort(Comparator.comparingInt((TaskDefinition d) ->
                "habitrain_taskapi".equals(d.getModId()) ? 0 : 1)
                .thenComparing(TaskDefinition::getFullId));

        for (var def : tasks) {
            TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
            if (cfg == null) {
                cfg = new TaskConfigEntry(true);
                ConfigManager.getInstance().setTaskConfig(def.getFullId(), cfg);
            }

            boolean isBuiltin = "habitrain_taskapi".equals(def.getModId());

            // 计算颜色名称
            int colorIdx = -1;
            int cur = cfg.instinctColor & 0x00FFFFFF;
            for (int i = 0; i < COLORS.length; i++) {
                if ((COLORS[i] & 0x00FFFFFF) == cur) { colorIdx = i; break; }
            }
            String colorName = colorIdx >= 0 ? COLOR_NAMES[colorIdx] : "自定义";

            allTaskInfos.add(new TaskRowInfo(def, cfg, isBuiltin, cfg.instinctColor, colorName));
        }
    }

    /** 根据搜索条件重建可见行控件 */
    private void rebuildRows() {
        // 旧控件引用丢弃即可（未注册到widget系统，无须removeWidget）
        visibleRows.clear();

        // 过滤
        List<TaskRowInfo> filtered = allTaskInfos;
        if (!searchText.isEmpty()) {
            filtered = allTaskInfos.stream()
                    .filter(r -> r.def.getDisplayName().toLowerCase().contains(searchText)
                            || r.def.getFullId().toLowerCase().contains(searchText)
                            || r.def.getModId().toLowerCase().contains(searchText))
                    .collect(Collectors.toList());
        }

        // 创建行
        for (var info : filtered) {
            TaskRowWidgets widgets = createRowWidgets(info);
            visibleRows.add(new TaskRowWidget(info, widgets));
        }

        recalcContentHeight();
    }

    /** 为一行的信息创建交互控件（不注册到widget系统，由renderRow在裁剪区域内手动渲染） */
    private TaskRowWidgets createRowWidgets(TaskRowInfo info) {
        // 启用/禁用开关（初始位置在屏幕外，renderRow中会重新定位并渲染）
        Button toggleBtn = Button.builder(
                Component.literal(info.cfg.enabled ? "§a✔" : "§c✘"), b -> {
                    info.cfg.enabled = !info.cfg.enabled;
                    b.setMessage(Component.literal(info.cfg.enabled ? "§a✔" : "§c✘"));
                    ConfigManager.getInstance().setTaskConfig(info.def.getFullId(), info.cfg);
                }
        ).bounds(-10000, -10000, 36, 18).build();

        // 编辑按钮（初始位置在屏幕外，renderRow中会重新定位并渲染）
        Button editBtn = Button.builder(
                Component.literal("§7编辑"), b -> {
                    Minecraft.getInstance().setScreen(
                            new TaskEditScreen(this, info.def, info.cfg,
                                    category, modeDisplayName, modeAccentColor));
                }
        ).bounds(-10000, -10000, 44, 18).build();

        // 注意：不调用 addRenderableWidget — 按钮在 renderRow() 中手动渲染以受裁剪区域控制
        return new TaskRowWidgets(toggleBtn, editBtn);
    }

    /** 计算内容总高度 */
    private void recalcContentHeight() {
        contentHeight = visibleRows.size() * (ROW_H + ROW_GAP);
        clampScroll();
    }

    /** 获取列表可视区域 */
    private int getListTop() {
        return HEADER_H + SEARCH_H + 4;
    }

    private int getListBottom() {
        return height - FOOTER_H - 4;
    }

    private int getListHeight() {
        return getListBottom() - getListTop();
    }

    private void clampScroll() {
        int listH = getListHeight();
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, contentHeight - listH)));
    }

    // =========================================================
    //  渲染
    // =========================================================

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        super.render(g, mx, my, delta);

        Font f = font;
        int listTop = getListTop();
        int listBot = getListBottom();

        // ---- 顶部导航 ----
        // 模式标题（在返回按钮右侧）
        String titleStr = "§l" + modeDisplayName + " §7- 任务列表";
        g.drawString(f, Component.literal(titleStr), PAD + 84, 6, modeAccentColor | 0x00FFFFFF, false);

        // 统计信息（放在搜索框下方，避免重叠）
        long enabledCount = visibleRows.stream().filter(r -> r.info.cfg.enabled).count();
        String info = String.format("§7共 §e%d §7个任务  |  已启用 §a%d§7/§e%d",
                visibleRows.size(), enabledCount, visibleRows.size());
        g.drawString(f, Component.literal(info), PAD + 84, 22, 0x888888, false);

        // 搜索框和列表之间的分割线
        g.fill(PAD, HEADER_H + SEARCH_H, width - PAD, HEADER_H + SEARCH_H + 1, 0x30FFFFFF);

        // ---- 可滚动列表区域 ----
        int scrollAreaX = PAD;
        int scrollAreaW = width - PAD * 2 - SCROLLBAR_W;
        int contentY = listTop - (int) scrollOffset;

        // 裁剪区域
        g.enableScissor(scrollAreaX, listTop, width - PAD, listBot);

        // 渲染每一行
        for (var row : visibleRows) {
            int rowY = contentY;
            contentY += ROW_H + ROW_GAP;

            // 跳过不可见的行
            if (rowY + ROW_H < listTop || rowY > listBot) continue;

            renderRow(g, f, row, scrollAreaX, scrollAreaW, rowY, mx, my, delta);
        }

        g.disableScissor();

        // ---- 空状态 ----
        if (visibleRows.isEmpty()) {
            String emptyMsg = searchText.isEmpty()
                    ? "§7该模式下暂无任务"
                    : "§7没有找到匹配 \"§f" + searchText + "§7\" 的任务";
            g.drawString(f, Component.literal(emptyMsg),
                    width / 2 - f.width(emptyMsg) / 2, listTop + 20, 0, false);
        }

        // ---- 滚动条 ----
        int listH = getListHeight();
        if (contentHeight > listH) {
            int thumbH = Math.max(20, (int) ((float) listH / contentHeight * listH));
            int thumbY = listTop + (int) ((float) scrollOffset / (contentHeight - listH) * (listH - thumbH));
            int sx = width - PAD - SCROLLBAR_W;

            // 轨道
            g.fill(sx, listTop, sx + SCROLLBAR_W, listBot, 0x20FFFFFF);
            // 滑块
            g.fill(sx, thumbY, sx + SCROLLBAR_W, thumbY + thumbH, 0x90AAAAAA);
        }

        // ---- 底部 ----
        g.fill(PAD, listBot, width - PAD, listBot + 1, 0x30FFFFFF);
        String tip = "§7点击任务行编辑详细属性  |  左侧开关快速启用/禁用";
        g.drawString(f, Component.literal(tip), PAD + 2, listBot + 5, 0x777777, false);
    }

    /** 渲染单行任务 */
    private void renderRow(GuiGraphics g, Font f, TaskRowWidget row,
                           int x, int w, int y, int mx, int my, float delta) {
        var info = row.info;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + ROW_H;

        // ---- 背景 ----
        int bg = hover ? 0x18FFFFFF : 0x08FFFFFF;
        g.fill(x, y, x + w, y + ROW_H, bg);

        // ---- 左侧颜色条 (2px) ----
        g.fill(x, y + 2, x + 2, y + ROW_H - 2, info.colorRgb);

        // ---- 颜色方块 (8x8) ----
        int dotX = x + 8;
        int dotY = y + 8;
        g.fill(dotX, dotY, dotX + 10, dotY + 10, info.colorRgb);
        // 浅色边框
        g.fill(dotX, dotY, dotX + 1, dotY + 10, 0x66FFFFFF);
        g.fill(dotX + 9, dotY, dotX + 10, dotY + 10, 0x66FFFFFF);

        // ---- 任务名称 ----
        int textX = dotX + 16;
        String name = info.def.getDisplayName();
        g.drawString(f, Component.literal(name), textX, y + 7, 0xFFFFFF, false);

        // ---- 来源标签 ----
        int nameEndX = textX + f.width(name) + 4;
        String sourceLabel = info.isBuiltin ? "§8[内置]" : "§e[外部]";
        g.drawString(f, Component.literal(sourceLabel), nameEndX, y + 7, 0, false);

        // ---- 颜色名称（悬停时显示） ----
        if (hover && !info.colorName.equals("自定义")) {
            String colorLabel = "§7颜色: §" + (info.isBuiltin ? "8" : "e") + info.colorName;
            g.drawString(f, Component.literal(colorLabel),
                    nameEndX + f.width(sourceLabel) + 6, y + 7, 0x777777, false);
        }

        // ---- 右侧控件位置更新（在裁剪区域内渲染，确保滚动时被边框遮住）----
        int rEdge = x + w - 4;

        row.widgets.editBtn.setX(rEdge - 48);
        row.widgets.editBtn.setY(y + 5);
        row.widgets.toggleBtn.setX(rEdge - 88);
        row.widgets.toggleBtn.setY(y + 5);

        // 在裁剪区域内手动渲染按钮（非addRenderableWidget，不受super.render()影响）
        row.widgets.toggleBtn.render(g, mx, my, delta);
        row.widgets.editBtn.render(g, mx, my, delta);

        // ---- 任务ID（悬停时在底部显示） ----
        if (hover) {
            g.drawString(f, Component.literal("§7" + info.def.getFullId()),
                    textX, y + 19, 0x555555, false);
        }

        // ---- 奖励预览（非悬停时显示） ----
        if (!hover) {
            String rewardPreview;
            if (info.cfg.goldReward >= 0) {
                rewardPreview = "§6金币:" + info.cfg.goldReward;
            } else {
                rewardPreview = "§8默认奖励";
            }
            g.drawString(f, Component.literal(rewardPreview),
                    textX, y + 19, 0, false);
        }
    }

    // =========================================================
    //  鼠标事件
    // =========================================================

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int listTop = getListTop();
        int listBot = getListBottom();
        if (my >= listTop && my < listBot) {
            scrollOffset -= dy * 16;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;

        int listTop = getListTop();
        int listBot = getListBottom();

        // 滚动条拖动
        int sx = width - PAD - SCROLLBAR_W;
        if (mx >= sx && mx < width - PAD && my >= listTop && my < listBot) {
            draggingScroll = true;
            dragStartY = my;
            dragStartOff = scrollOffset;
            return true;
        }

        // ---- 行内按钮点击检测（按钮未在widget系统，需手动处理）----
        int contentY = listTop - (int) scrollOffset;
        int scrollAreaW = width - PAD * 2 - SCROLLBAR_W;
        for (var row : visibleRows) {
            int rowY = contentY;
            contentY += ROW_H + ROW_GAP;

            if (rowY + ROW_H < listTop || rowY > listBot) continue;

            int rEdge = PAD + scrollAreaW - 4;

            // 编辑按钮
            int editX = rEdge - 48;
            int editY = rowY + 5;
            if (mx >= editX && mx < editX + 44 && my >= editY && my < editY + 18) {
                Minecraft.getInstance().setScreen(
                        new TaskEditScreen(this, row.info.def, row.info.cfg,
                                category, modeDisplayName, modeAccentColor));
                return true;
            }

            // 启用/禁用开关
            int toggleX = rEdge - 88;
            int toggleY = rowY + 5;
            if (mx >= toggleX && mx < toggleX + 36 && my >= toggleY && my < toggleY + 18) {
                row.info.cfg.enabled = !row.info.cfg.enabled;
                row.widgets.toggleBtn.setMessage(Component.literal(row.info.cfg.enabled ? "§a✔" : "§c✘"));
                ConfigManager.getInstance().setTaskConfig(row.info.def.getFullId(), row.info.cfg);
                return true;
            }
        }

        // ---- 点击任务行 → 进入编辑页 ----
        contentY = listTop - (int) scrollOffset;
        for (var row : visibleRows) {
            int rowY = contentY;
            contentY += ROW_H + ROW_GAP;

            if (rowY + ROW_H < listTop || rowY > listBot) continue;

            if (my >= rowY && my < rowY + ROW_H) {
                Minecraft.getInstance().setScreen(
                        new TaskEditScreen(this, row.info.def, row.info.cfg,
                                category, modeDisplayName, modeAccentColor));
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingScroll = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingScroll) {
            int listH = getListHeight();
            double scale = (double) (contentHeight - listH) / Math.max(1, listH - 20);
            scrollOffset = dragStartOff + (my - dragStartY) * scale;
            clampScroll();
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    // =========================================================
    //  键盘事件
    // =========================================================

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.keyPressed(key, sc, mod)) {
            return true;
        }
        if (key == 256) { // ESC
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        return super.keyPressed(key, sc, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.charTyped(ch, mod)) {
            return true;
        }
        return super.charTyped(ch, mod);
    }

    // =========================================================
    //  内部记录类
    // =========================================================

    private record TaskRowWidget(TaskRowInfo info, TaskRowWidgets widgets) {}
}
