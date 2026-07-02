package com.habitrain.core.client.gui;

import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskDefinition;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * =========================================================
 *  哈比列车任务系统 - 任务详细编辑界面 (原版风格)
 * =========================================================
 *
 * 导航: ConfigScreen → TaskListScreen → TaskEditScreen
 *
 * 可编辑属性:
 *   ✓ 启用/禁用
 *   ✓ 任务方块透视颜色 (20色循环)
 *   ✓ 透视描边粗细 (±0.5 步进, 范围 1.0~10.0)
 *   ✓ 金币/情绪/权重奖励
 *   ✓ 地图模式 + 列表
 *
 * 底部按钮:
 *   - 保存修改   [保存当前任务配置]
 *   - 保存并返回 [保存后返回任务列表]
 *   - 重置默认   [恢复默认值]
 *   - 返回列表   [不保存返回]
 */
public class TaskEditScreen extends Screen {

    // ====== 布局常量 ======
    private static final int PAD = 10;
    private static final int HEADER_H = 48;
    private static final int FOOTER_H = 32;
    private static final int SECTION_GAP = 8;
    private static final int ROW_H = 22;
    private static final int LABEL_W = 76;
    private static final int SCROLLBAR_W = 4;

    // ====== 20色预设 ======
    private static final int[] COLORS = {
            0xB4FF0000, 0xB4FF7F00, 0xB4FFFF00, 0xB400FF00,
            0xB40000FF, 0xB48B00FF, 0xB4FF00FF, 0xB400FFFF,
            0xB4FFC0CB, 0xB4FFA500, 0xB4C0C0C0, 0xB4FFFFFF,
            0xB4FF6B6B, 0xB4FFD700, 0xB47CFC00, 0xB400FA9A,
            0xB46020F0, 0xB4FF1493, 0xB400CED1, 0xB4FF8C00,
    };
    private static final String[] COLOR_NAMES = {
            "红色","橙色","黄色","绿色",
            "蓝色","紫色","品红色","青色",
            "粉色","琥珀色","银色","白色",
            "珊瑚色","金色","草绿色","碧绿色",
            "紫罗兰","深粉色","深蓝色","亮橙色"
    };

    // ====== 状态 ======
    private final Screen parent;
    private final TaskDefinition def;
    private final TaskConfigEntry cfg;
    private final TaskCategory category;
    private final String modeDisplayName;
    private final int modeAccentColor;

    // 滚动
    private double scrollOffset = 0;
    private boolean draggingScroll = false;
    private double dragStartY = 0, dragStartOff = 0;
    private int contentHeight = 0;

    // ====== 控件 ======
    // 基础设置
    private Button enableBtn;
    private Button colorBtn;
    private Button outlineMinusBtn, outlinePlusBtn;

    // 奖励设置
    private EditBox goldField, emotionField, weightField;

    // 地图设置
    private Button mapFilterBtn;
    private EditBox mapField;

    // 底部按钮
    private Button saveBtn, saveReturnBtn, resetBtn;

    // 顶部返回
    private Button topBackBtn;

    // ====== 构造 ======
    public TaskEditScreen(Screen parent, TaskDefinition def, TaskConfigEntry cfg,
                              TaskCategory category, String modeDisplayName, int modeAccentColor) {
        super(Component.literal("§l" + def.getDisplayName() + " - 详细配置"));
        this.parent = parent;
        this.def = def;
        this.cfg = cfg;
        this.category = category;
        this.modeDisplayName = modeDisplayName;
        this.modeAccentColor = modeAccentColor;
    }

    // =========================================================
    //  初始化 — 创建所有控件
    // =========================================================

    @Override
    protected void init() {
        super.init();
        Font f = font;

        // ===== 基础设置区（不注册到widget系统，在裁剪区域内手动渲染）=====
        enableBtn = Button.builder(makeEnableText(), b -> {
            cfg.enabled = !cfg.enabled;
            enableBtn.setMessage(makeEnableText());
            saveCurrent();
        }).bounds(-10000, -10000, 88, 20).build();

        colorBtn = Button.builder(Component.literal("点击切换"), b -> cycleColor())
                .bounds(-10000, -10000, 88, 20).build();

        outlineMinusBtn = Button.builder(Component.literal("§c−"), b -> {
            cfg.outlineWidth = Math.max(1.0f, cfg.outlineWidth - 0.5f);
            saveCurrent();
        }).bounds(-10000, -10000, 20, 20).build();

        outlinePlusBtn = Button.builder(Component.literal("§a+"), b -> {
            cfg.outlineWidth = Math.min(10.0f, cfg.outlineWidth + 0.5f);
            saveCurrent();
        }).bounds(-10000, -10000, 20, 20).build();

        // ===== 奖励设置区（不注册到widget系统）=====
        goldField = new EditBox(f, -10000, -10000, 60, 14, Component.literal(""));
        goldField.setMaxLength(8);
        if (cfg.goldReward >= 0) goldField.setValue(String.valueOf(cfg.goldReward));
        goldField.setHint(Component.literal("默认"));

        emotionField = new EditBox(f, -10000, -10000, 60, 14, Component.literal(""));
        emotionField.setMaxLength(8);
        if (cfg.emotionReward >= 0f) emotionField.setValue(String.format("%.2f", cfg.emotionReward));
        emotionField.setHint(Component.literal("默认"));

        weightField = new EditBox(f, -10000, -10000, 60, 14, Component.literal(""));
        weightField.setMaxLength(8);
        if (cfg.refreshWeight >= 0f) weightField.setValue(String.format("%.1f", cfg.refreshWeight));
        weightField.setHint(Component.literal("默认"));

        // ===== 地图设置区（不注册到widget系统）=====
        mapFilterBtn = Button.builder(
                Component.literal(getFilterModeLabel()), b -> cycleFilterMode()
        ).bounds(-10000, -10000, 90, 20).build();

        mapField = new EditBox(f, -10000, -10000, 180, 14, Component.literal(""));
        mapField.setMaxLength(512);
        String initMap = String.join(",", cfg.enabledMaps);
        if (!initMap.isEmpty()) mapField.setValue(initMap);

        // ===== 底部按钮 =====
        int centerX = width / 2;
        int btnY = height - FOOTER_H + 6;

        saveBtn = Button.builder(Component.literal("§a✔ 保存修改"), b -> {
            syncFields();
            saveCurrent();
            showMessage("§a✔ 任务「" + def.getDisplayName() + "」已保存！");
        }).bounds(centerX - 155, btnY, 90, 20).build();
        addRenderableWidget(saveBtn);

        saveReturnBtn = Button.builder(Component.literal("§b✔ 保存并返回"), b -> {
            syncFields();
            saveCurrent();
            goBack();
        }).bounds(centerX - 58, btnY, 100, 20).build();
        addRenderableWidget(saveReturnBtn);

        resetBtn = Button.builder(Component.literal("§7↺ 重置默认"), b -> {
            resetDefault();
            // 重建界面
            clearWidgets();
            init();
        }).bounds(centerX + 50, btnY, 80, 20).build();
        addRenderableWidget(resetBtn);

        // 顶部返回按钮
        topBackBtn = Button.builder(Component.literal("§7← 返回列表"), b -> goBack())
                .bounds(width - 90, 4, 80, 16).build();
        addRenderableWidget(topBackBtn);

        // 计算内容高度
        recalcContentHeight();
    }

    // =========================================================
    //  布局计算
    // =========================================================

    /** 计算各区域高度 */
    private int calcSectionCount() {
        // 基础设置: 3行 (enable, color, outline)
        // 奖励设置: 3行 (gold, emotion, weight)
        // 地图设置: 2行 (filter mode button + map field + help text)
        // 基本信息: 8行 (只读信息)
        return 3 + 3 + 2 + 8;
    }

    private int calcTotalContentHeight() {
        int rows = calcSectionCount();
        // 4个section header (每个24px + 8px gap) + rows * ROW_H + padding
        return PAD + 4 * 24 + rows * ROW_H + 3 * SECTION_GAP + PAD;
    }

    private void recalcContentHeight() {
        contentHeight = calcTotalContentHeight();
        clampScroll();
    }

    private int getBodyTop() {
        return HEADER_H + 4;
    }

    private int getBodyBottom() {
        return height - FOOTER_H - 4;
    }

    private int getBodyHeight() {
        return getBodyBottom() - getBodyTop();
    }

    private void clampScroll() {
        int bodyH = getBodyHeight();
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, contentHeight - bodyH)));
    }

    // =========================================================
    //  渲染
    // =========================================================

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        super.render(g, mx, my, delta);

        Font f = font;
        int bodyTop = getBodyTop();
        int bodyBot = getBodyBottom();
        int scrollW = width - PAD * 2;

        // ---- 顶部区域 ----
        // 面包屑导航
        String breadcrumb = "§7" + modeDisplayName + " §f> §r§l" + def.getDisplayName();
        g.drawString(f, Component.literal(breadcrumb), PAD, 4, 0xFFFFFF, false);

        // 完整ID
        g.drawString(f, Component.literal("§8" + def.getFullId()), PAD, 17, 0x555555, false);

        // 来源标记
        boolean builtin = "habitrain_taskapi".equals(def.getModId());
        g.drawString(f, Component.literal(builtin ? "§8[内置任务]" : "§e[外部/DLC任务]"),
                PAD, 30, 0, false);

        // 顶部彩色装饰线
        g.fill(PAD, HEADER_H - 1, width - PAD, HEADER_H, modeAccentColor);

        // ---- 可滚动的表单区域 ----
        g.enableScissor(PAD, bodyTop, width - PAD, bodyBot);
        int curY = bodyTop - (int) scrollOffset;

        curY = renderSectionBasic(g, f, scrollW, curY, mx, my, delta);
        curY += SECTION_GAP;
        curY = renderSectionReward(g, f, scrollW, curY, mx, my, delta);
        curY += SECTION_GAP;
        curY = renderSectionMap(g, f, scrollW, curY, mx, my, delta);
        curY += SECTION_GAP;
        curY = renderSectionInfo(g, f, scrollW, curY);

        g.disableScissor();

        // ---- 滚动条 ----
        int bodyH = getBodyHeight();
        if (contentHeight > bodyH) {
            int thumbH = Math.max(20, bodyH * bodyH / contentHeight);
            int thumbY = bodyTop + (int) ((float) scrollOffset / (contentHeight - bodyH) * (bodyH - thumbH));
            int sx = width - PAD - SCROLLBAR_W;
            g.fill(sx, bodyTop, sx + SCROLLBAR_W, bodyBot, 0x20FFFFFF);
            g.fill(sx, thumbY, sx + SCROLLBAR_W, thumbY + thumbH, 0x90AAAAAA);
        }

        // ---- 底部装饰线 ----
        g.fill(PAD, bodyBot, width - PAD, bodyBot + 1, 0x30FFFFFF);

        // ---- 底部提示 ----
        String tip = "§7提示: 修改后记得点击「保存修改」或「保存并返回」";
        g.drawString(f, Component.literal(tip), PAD, bodyBot + 6, 0x777777, false);
    }

    // =========================================================
    //  区域渲染
    // =========================================================

    /** 渲染带标题栏的分区背景和标签 */
    private int renderSectionFrame(GuiGraphics g, Font f, int w, int y, String title, int titleColor) {
        // 分区标题栏
        int barH = 22;
        g.fill(PAD, y, PAD + w, y + barH, titleColor & 0x00FFFFFF | 0x44000000);
        g.fill(PAD, y, PAD + w, y + 1, titleColor | 0xFF000000);
        g.drawString(f, Component.literal("§l§f" + title), PAD + 6, y + 7, 0xFFFFFF, false);
        return y + barH;
    }

    /** 渲染带标签的行背景（确保控件不会重叠到标签区） */
    private int renderRowFrame(GuiGraphics g, Font f, int w, int y, String label, boolean isLast) {
        g.drawString(f, Component.literal("§7" + label + ":"), PAD + 8, y + 4, 0xCCCCCC, false);
        return y + ROW_H;
    }

    /** 渲染颜色选择器的色块预览 */
    private void renderColorSwatch(GuiGraphics g, Font f, int x, int y, Color color, String colorName) {
        int fill = (color.getAlpha() << 24) | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
        g.fill(x, y, x + 12, y + 12, fill);
        // 边框
        g.fill(x, y, x + 1, y + 12, 0x88FFFFFF);
        g.fill(x + 11, y, x + 12, y + 12, 0x88FFFFFF);
        g.fill(x, y, x + 12, y + 1, 0x88FFFFFF);
        g.fill(x, y + 11, x + 12, y + 12, 0x88FFFFFF);
        // 颜色名称
        g.drawString(f, Component.literal("§7" + colorName), x + 16, y + 2, 0x888888, false);
    }

    // -------------------- 基础设置 --------------------

    private int renderSectionBasic(GuiGraphics g, Font f, int w, int y, int mx, int my, float delta) {
        // mx/my 取自渲染上下文中的鼠标位置，通过 Screen.render 传入
        int secY = renderSectionFrame(g, f, w, y, "⚙ 基础设置", 0x44FFFFFF);
        int rowX = PAD + 8 + LABEL_W;

        // 第1行: 启用/禁用
        int r1 = secY;
        g.drawString(f, Component.literal("§7任务状态:"), PAD + 8, r1 + 4, 0xCCCCCC, false);
        enableBtn.setX(rowX);
        enableBtn.setY(r1);
        enableBtn.render(g, mx, my, delta);

        // 第2行: 透视颜色
        int r2 = r1 + ROW_H;
        g.drawString(f, Component.literal("§7透视颜色:"), PAD + 8, r2 + 4, 0xCCCCCC, false);
        colorBtn.setX(rowX);
        colorBtn.setY(r2);
        colorBtn.render(g, mx, my, delta);
        // 色块预览
        int swatchX = colorBtn.getX() + colorBtn.getWidth() + 4;
        Color col = new Color(cfg.getColor(), true);
        int idx = getColorIndex();
        String cName = idx >= 0 ? COLOR_NAMES[idx] : "自定义";
        renderColorSwatch(g, f, swatchX, r2 + 3, col, cName);

        // 第3行: 描边粗细
        int r3 = r2 + ROW_H;
        g.drawString(f, Component.literal("§7描边粗细:"), PAD + 8, r3 + 4, 0xCCCCCC, false);
        outlineMinusBtn.setX(rowX);
        outlineMinusBtn.setY(r3);
        outlinePlusBtn.setX(rowX + 24);
        outlinePlusBtn.setY(r3);
        outlineMinusBtn.render(g, mx, my, delta);
        outlinePlusBtn.render(g, mx, my, delta);
        // 当前值显示
        String valStr = String.format("§b%.1f", cfg.outlineWidth);
        g.drawString(f, Component.literal(valStr), rowX + 50, r3 + 4, 0xFFFFFF, false);
        g.drawString(f, Component.literal("§7(1.0 ~ 10.0)"), rowX + 50 + f.width(valStr) + 4, r3 + 4, 0x555555, false);

        return r3 + ROW_H;
    }

    // -------------------- 奖励设置 --------------------

    private int renderSectionReward(GuiGraphics g, Font f, int w, int y, int mx, int my, float delta) {
        int secY = renderSectionFrame(g, f, w, y, "💰 奖励设置", 0x44FFD700);
        int rowX = PAD + 8 + LABEL_W;

        // 第1行: 金币奖励
        int r1 = secY;
        g.drawString(f, Component.literal("§6金币奖励:"), PAD + 8, r1 + 4, 0xCCCCCC, false);
        goldField.setX(rowX);
        goldField.setY(r1 + 4);
        goldField.setWidth(60);
        goldField.render(g, mx, my, delta);
        g.drawString(f, Component.literal("§7(留空 = 系统默认值)"), rowX + 66, r1 + 4, 0x777777, false);

        // 第2行: 情绪奖励
        int r2 = r1 + ROW_H;
        g.drawString(f, Component.literal("§d情绪奖励:"), PAD + 8, r2 + 4, 0xCCCCCC, false);
        emotionField.setX(rowX);
        emotionField.setY(r2 + 4);
        emotionField.setWidth(60);
        emotionField.render(g, mx, my, delta);
        g.drawString(f, Component.literal("§7(留空 = 系统默认值)"), rowX + 66, r2 + 4, 0x777777, false);

        // 第3行: 刷新权重
        int r3 = r2 + ROW_H;
        g.drawString(f, Component.literal("§e刷新权重:"), PAD + 8, r3 + 4, 0xCCCCCC, false);
        weightField.setX(rowX);
        weightField.setY(r3 + 4);
        weightField.setWidth(60);
        weightField.render(g, mx, my, delta);
        float effW = cfg.refreshWeight >= 0f ? cfg.refreshWeight : def.getWeight();
        String effWStr = String.format("§7生效: §e%.1f", effW);
        g.drawString(f, Component.literal(effWStr), rowX + 66, r3 + 4, 0x777777, false);

        return r3 + ROW_H;
    }

    // -------------------- 地图设置 --------------------

    private int renderSectionMap(GuiGraphics g, Font f, int w, int y, int mx, int my, float delta) {
        int secY = renderSectionFrame(g, f, w, y, "🗺 地图设置", 0x4455AAFF);
        int rowX = PAD + 8 + LABEL_W;
        boolean disabled = !cfg.enabled;

        // 第1行: 过滤模式按钮
        int r1 = secY;
        g.drawString(f, Component.literal("§7过滤模式:"), PAD + 8, r1 + 4, 0xCCCCCC, false);

        mapFilterBtn.setX(rowX);
        mapFilterBtn.setY(r1);
        mapFilterBtn.active = !disabled; // 禁用时按钮不可点击
        int btnColor = disabled ? 0xFF555555 : 0xFFFFFF;
        if (!disabled) {
            mapFilterBtn.render(g, mx, my, delta);
        } else {
            // 禁用时显示灰色版本
            g.fill(rowX, r1, rowX + 90, r1 + 20, 0x33FFFFFF);
            g.drawString(f, Component.literal(getFilterModeLabel()).withStyle(style -> style.withColor(0x555555)),
                    rowX + 5, r1 + 6, 0, false);
        }

        // 模式说明
        String modeHint;
        if (disabled) {
            modeHint = "§7任务已禁用，地图设置不可用";
        } else if (cfg.mapFilterMode == 0) {
            modeHint = "§a✔ 所有地图都出现此任务";
        } else if (cfg.mapFilterMode == 1) {
            modeHint = "§e⚡ 仅以下列表中的地图出现此任务";
        } else {
            modeHint = "§c⛔ 以下列表中的地图§l不会§r出现此任务";
        }
        g.drawString(f, Component.literal(modeHint), rowX + 96, r1 + 4, 0, false);

        // 第2行: 地图列表
        int r2 = r1 + ROW_H;
        g.drawString(f, Component.literal("§7地图列表:"), PAD + 8, r2 + 4, 0xCCCCCC, false);
        mapField.setX(rowX);
        mapField.setY(r2 + 4);
        mapField.setWidth(Math.min(200, w - LABEL_W - 40));
        mapField.setEditable(!disabled);
        mapField.render(g, mx, my, delta);

        // 禁用遮罩
        if (disabled) {
            g.fill(mapField.getX(), mapField.getY(), mapField.getX() + mapField.getWidth(), mapField.getY() + 14, 0x22FFFFFF);
        }

        // 说明文字
        if (!disabled) {
            String hint;
            if (cfg.mapFilterMode == 0) {
                hint = "§7列表已忽略（当前为全部地图模式）";
            } else {
                hint = "§7逗号分隔多个地图名，如: map1,map2";
            }
            g.drawString(f, Component.literal(hint), rowX, r2 + 20, 0x777777, false);
        }

        return r2 + ROW_H + 6;
    }

    // -------------------- 基本信息 --------------------

    private int renderSectionInfo(GuiGraphics g, Font f, int w, int y) {
        int secY = renderSectionFrame(g, f, w, y, "ℹ 基本信息 (只读)", 0x44555555);
        int rowX = PAD + 8 + LABEL_W;

        String[][] infos = {
                {"任务名称", def.getDisplayName()},
                {"完整ID", def.getFullId()},
                {"模组来源", def.getModId()},
                {"任务分类", getCategoryName(def.getCategory())},
                {"默认权重", String.format("%.1f", def.getWeight())},
                {"方块类型ID", def.getBlockTypeId() >= 0 ? String.valueOf(def.getBlockTypeId()) : "无"},
                {"可直接获胜", def.canDirectlyWin() ? "§a是" : "§c否"},
                {"扫描方块数", String.valueOf(def.getScanBlocks().size())},
        };

        int curY = secY;
        for (String[] info : infos) {
            g.drawString(f, Component.literal("§7" + info[0] + ":"), PAD + 12, curY + 2, 0x888888, false);
            g.drawString(f, Component.literal("§f" + info[1]), rowX, curY + 2, 0xFFFFFF, false);
            curY += ROW_H - 2;
        }
        return curY;
    }

    // =========================================================
    //  辅助方法
    // =========================================================

    private Component makeEnableText() {
        return Component.literal(cfg.enabled ? "§a✔ 已启用" : "§c✘ 已禁用");
    }

    private int getColorIndex() {
        int cur = cfg.instinctColor & 0x00FFFFFF;
        for (int i = 0; i < COLORS.length; i++) {
            if ((COLORS[i] & 0x00FFFFFF) == cur) return i;
        }
        return -1;
    }

    private void cycleColor() {
        int cur = cfg.instinctColor & 0x00FFFFFF;
        for (int i = 0; i < COLORS.length; i++) {
            if ((COLORS[i] & 0x00FFFFFF) == cur) {
                cfg.instinctColor = COLORS[(i + 1) % COLORS.length];
                saveCurrent();
                return;
            }
        }
        cfg.instinctColor = COLORS[0];
        saveCurrent();
    }

    private void syncFields() {
        // 同步地图列表 → enabledMaps
        String raw = mapField != null ? mapField.getValue() : "";
        cfg.enabledMaps = parseMapList(raw);
        parseNumFields();
    }

    private void parseNumFields() {
        try {
            String v = goldField.getValue().trim();
            cfg.goldReward = v.isEmpty() ? -1 : Math.max(-1, Integer.parseInt(v));
        } catch (NumberFormatException ignored) {}
        try {
            String v = emotionField.getValue().trim();
            cfg.emotionReward = v.isEmpty() ? -1f : Math.max(-1f, Float.parseFloat(v));
        } catch (NumberFormatException ignored) {}
        try {
            String v = weightField.getValue().trim();
            cfg.refreshWeight = v.isEmpty() ? -1f : Math.max(-1f, Float.parseFloat(v));
        } catch (NumberFormatException ignored) {}
    }

    private void resetDefault() {
        cfg.enabled = true;
        cfg.enabledMaps.clear();
        cfg.mapFilterMode = 0;
        cfg.instinctColor = new Color(200, 200, 200, 180).getRGB();
        cfg.outlineWidth = 4.0f;
        cfg.goldReward = -1;
        cfg.emotionReward = -1f;
        cfg.refreshWeight = -1f;
        saveCurrent();
    }

    private void saveCurrent() {
        ConfigManager.getInstance().setTaskConfig(def.getFullId(), cfg);
    }

    private void goBack() {
        Minecraft.getInstance().setScreen(parent);
    }

    private void showMessage(String msg) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(msg), true);
    }

    private String getCategoryName(TaskCategory cat) {
        if (cat == TaskCategory.MURDER) return "谋杀模式";
        if (cat == TaskCategory.REPAIR) return "修机模式";
        if (cat == TaskCategory.ALL) return "通用任务";
        if (cat == TaskCategory.CUSTOM) return "自定义任务";
        return cat.getDisplayName();
    }

    // ---- 地图过滤模式 ----

    private String getFilterModeLabel() {
        return switch (cfg.mapFilterMode) {
            case 0 -> "§a全部地图";
            case 1 -> "§e白名单";
            case 2 -> "§c黑名单";
            default -> "§7未知";
        };
    }

    private void cycleFilterMode() {
        cfg.mapFilterMode = (cfg.mapFilterMode + 1) % 3;
        mapFilterBtn.setMessage(Component.literal(getFilterModeLabel()));
        saveCurrent();
    }

    // ---- 工具 ----

    private static List<String> parseMapList(String v) {
        if (v == null || v.trim().isEmpty()) return new ArrayList<>();
        return Arrays.stream(v.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    // =========================================================
    //  鼠标事件
    // =========================================================

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int bodyTop = getBodyTop();
        int bodyBot = getBodyBottom();
        if (my >= bodyTop && my < bodyBot) {
            scrollOffset -= dy * 16;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 先处理底部/顶部固定按钮（saveBtn/saveReturnBtn/resetBtn/topBackBtn仍在widget系统中）
        if (super.mouseClicked(mx, my, button)) return true;

        int bodyTop = getBodyTop();
        int bodyBot = getBodyBottom();

        // 滚动条
        int sx = width - PAD - SCROLLBAR_W;
        if (mx >= sx && mx < width - PAD && my >= bodyTop && my < bodyBot) {
            draggingScroll = true;
            dragStartY = my;
            dragStartOff = scrollOffset;
            return true;
        }

        // 只处理正文区域内的点击
        if (my < bodyTop || my >= bodyBot) return false;

        // 清除所有编辑框焦点（未注册到widget系统，需手动管理）
        goldField.setFocused(false);
        emotionField.setFocused(false);
        weightField.setFocused(false);
        mapField.setFocused(false);

        // 按钮点击——使用上次render设置的当前位置
        if (mx >= enableBtn.getX() && mx < enableBtn.getX() + 88
                && my >= enableBtn.getY() && my < enableBtn.getY() + 20) {
            enableBtn.mouseClicked(mx, my, button);
            return true;
        }
        if (mx >= colorBtn.getX() && mx < colorBtn.getX() + 88
                && my >= colorBtn.getY() && my < colorBtn.getY() + 20) {
            colorBtn.mouseClicked(mx, my, button);
            return true;
        }
        if (mx >= outlineMinusBtn.getX() && mx < outlineMinusBtn.getX() + 20
                && my >= outlineMinusBtn.getY() && my < outlineMinusBtn.getY() + 20) {
            outlineMinusBtn.mouseClicked(mx, my, button);
            return true;
        }
        if (mx >= outlinePlusBtn.getX() && mx < outlinePlusBtn.getX() + 20
                && my >= outlinePlusBtn.getY() && my < outlinePlusBtn.getY() + 20) {
            outlinePlusBtn.mouseClicked(mx, my, button);
            return true;
        }
        // 地图过滤模式按钮（仅在启用时可点击）
        if (cfg.enabled && mx >= mapFilterBtn.getX() && mx < mapFilterBtn.getX() + 90
                && my >= mapFilterBtn.getY() && my < mapFilterBtn.getY() + 20) {
            mapFilterBtn.mouseClicked(mx, my, button);
            return true;
        }

        // 编辑框点击——设置焦点
        if (mx >= goldField.getX() && mx < goldField.getX() + 60
                && my >= goldField.getY() && my < goldField.getY() + 14) {
            goldField.setFocused(true);
            return true;
        }
        if (mx >= emotionField.getX() && mx < emotionField.getX() + 60
                && my >= emotionField.getY() && my < emotionField.getY() + 14) {
            emotionField.setFocused(true);
            return true;
        }
        if (mx >= weightField.getX() && mx < weightField.getX() + 60
                && my >= weightField.getY() && my < weightField.getY() + 14) {
            weightField.setFocused(true);
            return true;
        }
        if (cfg.enabled && mx >= mapField.getX() && mx < mapField.getX() + mapField.getWidth()
                && my >= mapField.getY() && my < mapField.getY() + 14) {
            mapField.setFocused(true);
            return true;
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
            int bodyH = getBodyHeight();
            double scale = (double) (contentHeight - bodyH) / Math.max(1, bodyH - 20);
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
        // 让所有 EditBox 有机会处理键盘事件
        if (goldField.isFocused() && goldField.keyPressed(key, sc, mod)) return true;
        if (emotionField.isFocused() && emotionField.keyPressed(key, sc, mod)) return true;
        if (weightField.isFocused() && weightField.keyPressed(key, sc, mod)) return true;
        if (mapField.isFocused() && mapField.keyPressed(key, sc, mod)) return true;

        if (key == 256) { // ESC
            goBack();
            return true;
        }
        return super.keyPressed(key, sc, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (goldField.isFocused() && goldField.charTyped(ch, mod)) return true;
        if (emotionField.isFocused() && emotionField.charTyped(ch, mod)) return true;
        if (weightField.isFocused() && weightField.charTyped(ch, mod)) return true;
        if (mapField.isFocused() && mapField.charTyped(ch, mod)) return true;
        return super.charTyped(ch, mod);
    }

    @Override
    public boolean keyReleased(int key, int sc, int mod) {
        if (goldField.isFocused() && goldField.keyReleased(key, sc, mod)) return true;
        if (emotionField.isFocused() && emotionField.keyReleased(key, sc, mod)) return true;
        if (weightField.isFocused() && weightField.keyReleased(key, sc, mod)) return true;
        if (mapField.isFocused() && mapField.keyReleased(key, sc, mod)) return true;
        return super.keyReleased(key, sc, mod);
    }
}
