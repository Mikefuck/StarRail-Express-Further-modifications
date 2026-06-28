package com.habitrain.core.client.gui;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 任务详情滑入面板 — 带 EditBox 控件的非静态面板。
 * 由 MainConfigScreen 在打开任务详情时创建并管理生命周期。
 */
public class TaskDetailPanel {

    private static final int PANEL_W = 320;
    private static final int PAD = 10;
    private static final int ROW_H = 22;
    private static final int LABEL_W = 72;
    private static final int CONTENT_W = PANEL_W - PAD * 2 - LABEL_W - 6;

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

    // ---- 面板状态 ----
    private final TaskDefinition def;
    private final Runnable onClose;

    // ---- 渲染/交互用的缓存值 ----
    private Color currentColor;
    private float outlineWidth;
    private int mapFilterMode;
    private boolean enabled;

    // ---- EditBox 控件 ----
    private final EditBox goldBox;
    private final EditBox emotionBox;
    private final EditBox weightBox;
    private final EditBox mapsBox;
    private final List<EditBox> allBoxes = new ArrayList<>();

    // ---- 按钮矩形区域（用于点击检测） ----
    private int panelX, panelY;
    private int colorRectX, colorRectY;
    private int outlineDecX, outlineIncX;
    private int mapModeX;
    private int saveBtnX, resetBtnX, backBtnX;
    private int btnY;

    public TaskDetailPanel(MainConfigScreen screen, TaskDefinition def, Runnable onClose) {
        this.def = def;
        this.onClose = onClose;
        Font font = Minecraft.getInstance().font;

        // 加载当前配置
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        this.enabled = cfg == null || cfg.enabled;
        this.currentColor = cfg != null ? cfg.getColor()
            : (def.getInstinctColor() != null ? def.getInstinctColor() : new Color(200, 200, 200, 180));
        this.outlineWidth = cfg != null ? cfg.outlineWidth : 4.0f;
        this.mapFilterMode = cfg != null ? cfg.mapFilterMode : 0;
        String mapsStr = (cfg != null && cfg.enabledMaps != null) ? String.join(",", cfg.enabledMaps) : "";

        // 创建 EditBox
        int boxH = 14;
        int panelX = screen.width - PANEL_W;

        goldBox = new EditBox(font, panelX + PAD + LABEL_W, 102, CONTENT_W, boxH, Component.literal(""));
        goldBox.setMaxLength(8);
        goldBox.setValue(cfg != null && cfg.goldReward >= 0 ? String.valueOf(cfg.goldReward) : "");
        goldBox.setFilter(s -> s.isEmpty() || s.matches("-?\\d*"));
        allBoxes.add(goldBox);

        emotionBox = new EditBox(font, panelX + PAD + LABEL_W, 124, CONTENT_W, boxH, Component.literal(""));
        emotionBox.setMaxLength(8);
        emotionBox.setValue(cfg != null && cfg.emotionReward >= 0f ? String.format("%.1f", cfg.emotionReward) : "");
        emotionBox.setFilter(s -> s.isEmpty() || s.matches("-?\\d*\\.?\\d*"));
        allBoxes.add(emotionBox);

        weightBox = new EditBox(font, panelX + PAD + LABEL_W, 146, CONTENT_W, boxH, Component.literal(""));
        weightBox.setMaxLength(8);
        weightBox.setValue(cfg != null && cfg.refreshWeight >= 0f ? String.format("%.1f", cfg.refreshWeight) : "");
        weightBox.setFilter(s -> s.isEmpty() || s.matches("-?\\d*\\.?\\d*"));
        allBoxes.add(weightBox);

        mapsBox = new EditBox(font, panelX + PAD + LABEL_W, 190, CONTENT_W, boxH, Component.literal(""));
        mapsBox.setMaxLength(256);
        mapsBox.setValue(mapsStr);
        mapsBox.setHint(Component.literal("逗号分隔地图名"));
        allBoxes.add(mapsBox);

        // 注册到 screen（使键盘/鼠标事件能路由到 EditBox）
        screen.registerDetailWidgets(allBoxes);
    }

    /** 注销 EditBox 控件 */
    public void dispose(MainConfigScreen screen) {
        screen.unregisterDetailWidgets(allBoxes);
    }

    public void render(GuiGraphics g, Font font, int areaX, int areaY, int areaW, int areaH) {
        panelX = areaW - PANEL_W;
        panelY = areaY;

        // 半透明遮罩
        g.fill(areaX, areaY, panelX, areaH, 0x88000000);
        // 面板背景
        g.fill(panelX, panelY, areaW, areaH, 0xFF2D2D3F);
        g.fill(panelX, panelY, panelX + 1, areaH, 0xFF555577);

        // 标题
        g.drawString(font, Component.literal("§l← 返回"), panelX + PAD, panelY + 8, 0xFFFFFF, false);
        String title = def.getDisplayName() + " §7(" + def.getFullId() + ")";
        g.drawString(font, Component.literal(title),
                panelX + PAD + font.width("← 返回") + 14, panelY + 8, 0xDDDDDD, false);

        int y = panelY + 36;

        // 1. 启用/禁用
        g.drawString(font, Component.literal("§7状态:"), panelX + PAD, y, 0, false);
        String status = enabled ? "§a✔ 已启用" : "§c✖ 已禁用";
        g.drawString(font, Component.literal(status), panelX + PAD + LABEL_W, y, 0, false);
        y += ROW_H;

        // 2. 颜色选择
        g.drawString(font, Component.literal("§7颜色:"), panelX + PAD, y, 0, false);
        colorRectX = panelX + PAD + LABEL_W;
        colorRectY = y + 3;
        g.fill(colorRectX, colorRectY, colorRectX + 18, colorRectY + 18, currentColor.getRGB());
        String colorName = getColorName(currentColor);
        g.drawString(font, Component.literal("§f" + colorName + " §7[点击切换]"),
                colorRectX + 22, y, 0, false);
        y += ROW_H;

        // 3. 描边粗细
        g.drawString(font, Component.literal("§7描边:"), panelX + PAD, y, 0, false);
        outlineDecX = panelX + PAD + LABEL_W;
        String owStr = String.format("%.1f", outlineWidth);
        outlineIncX = outlineDecX + font.width("[-] " + owStr + " ");
        g.drawString(font, Component.literal("§f[-] §e" + owStr + " §f[+]"),
                outlineDecX, y, 0, false);
        y += ROW_H;

        // 4. 金币奖励
        g.drawString(font, Component.literal("§7金币:"), panelX + PAD, y, 0, false);
        goldBox.setY(y);
        goldBox.render(g, 0, 0, 0);
        y += ROW_H;

        // 5. 情绪奖励
        g.drawString(font, Component.literal("§7情绪:"), panelX + PAD, y, 0, false);
        emotionBox.setY(y);
        emotionBox.render(g, 0, 0, 0);
        y += ROW_H;

        // 6. 刷新权重
        g.drawString(font, Component.literal("§7权重:"), panelX + PAD, y, 0, false);
        weightBox.setY(y);
        weightBox.render(g, 0, 0, 0);
        y += ROW_H;

        // 7. 地图过滤模式
        g.drawString(font, Component.literal("§7地图:"), panelX + PAD, y, 0, false);
        mapModeX = panelX + PAD + LABEL_W;
        g.drawString(font, Component.literal("§f[ " + MAP_MODES[mapFilterMode] + " ] §7[点击切换]"),
                mapModeX, y, 0, false);
        y += ROW_H;

        // 8. 地图列表
        g.drawString(font, Component.literal("§7地图列:"), panelX + PAD, y, 0, false);
        mapsBox.setY(y);
        mapsBox.render(g, 0, 0, 0);
        y += ROW_H + 8;

        // 9. 按钮
        btnY = Math.max(y, panelY + areaH - 50);
        saveBtnX = panelX + PAD + 10;
        resetBtnX = saveBtnX + font.width("§a[保存]  ");
        backBtnX = resetBtnX + font.width("§7[重置]  ");
        g.drawString(font, Component.literal("§a[保存]  §7[重置]  §c[返回]"),
                panelX + PAD + 10, btnY, 0, false);
    }

    /** 处理鼠标点击 */
    public boolean mouseClicked(int mx, int my, int btn) {
        Font font = Minecraft.getInstance().font;

        // 先给 EditBox 处理
        for (EditBox box : allBoxes) {
            if (box.mouseClicked(mx, my, btn)) return true;
        }

        // ← 返回
        if (mx >= panelX + PAD && mx <= panelX + PAD + font.width("← 返回") + 10
                && my >= panelY + 8 && my <= panelY + 30) {
            onClose.run();
            return true;
        }

        // 颜色切换
        if (mx >= colorRectX && mx <= colorRectX + 120
                && my >= colorRectY && my <= colorRectY + ROW_H) {
            int nextIdx = (findColorIndex(currentColor) + 1) % COLOR_PRESETS.length;
            currentColor = new Color(COLOR_PRESETS[nextIdx]);
            saveColor();
            return true;
        }

        // 描边 [-]
        if (mx >= outlineDecX && mx <= outlineDecX + 24
                && my >= panelY + 80 && my <= panelY + 102) {
            outlineWidth = Math.max(1.0f, outlineWidth - 0.5f);
            saveOutlineWidth();
            return true;
        }

        // 描边 [+]
        if (mx >= outlineIncX && mx <= outlineIncX + 24
                && my >= panelY + 80 && my <= panelY + 102) {
            outlineWidth = Math.min(10.0f, outlineWidth + 0.5f);
            saveOutlineWidth();
            return true;
        }

        // 地图模式切换
        if (mx >= mapModeX && mx <= mapModeX + font.width("[ 全部地图 ]") + 40
                && my >= panelY + 168 && my <= panelY + 190) {
            mapFilterMode = (mapFilterMode + 1) % 3;
            saveMapFilter();
            return true;
        }

        // 保存
        if (mx >= saveBtnX && mx <= saveBtnX + font.width("[保存]") + 10
                && my >= btnY && my <= btnY + 16) {
            saveAll();
            return true;
        }

        // 重置
        if (mx >= resetBtnX && mx <= resetBtnX + font.width("[重置]") + 10
                && my >= btnY && my <= btnY + 16) {
            resetAll();
            return true;
        }

        return false;
    }

    /** 处理键盘按键 */
    public boolean keyPressed(int key, int sc, int mod) {
        for (EditBox box : allBoxes) {
            if (box.isFocused() && box.keyPressed(key, sc, mod)) return true;
        }
        return false;
    }

    /** 处理字符输入 */
    public boolean charTyped(char ch, int mod) {
        for (EditBox box : allBoxes) {
            if (box.isFocused() && box.charTyped(ch, mod)) return true;
        }
        return false;
    }

    public void setEnabled(boolean v) { this.enabled = v; }
    public boolean isEnabled() { return enabled; }

    // ========== 持久化 ==========

    private void saveColor() {
        TaskConfigEntry cfg = getOrCreateConfig();
        cfg.instinctColor = currentColor.getRGB();
        ConfigManager.getInstance().save();
    }

    private void saveOutlineWidth() {
        TaskConfigEntry cfg = getOrCreateConfig();
        cfg.outlineWidth = outlineWidth;
        ConfigManager.getInstance().save();
    }

    private void saveMapFilter() {
        TaskConfigEntry cfg = getOrCreateConfig();
        cfg.mapFilterMode = mapFilterMode;
        ConfigManager.getInstance().save();
    }

    private void saveAll() {
        TaskConfigEntry cfg = getOrCreateConfig();
        cfg.enabled = enabled;
        cfg.instinctColor = currentColor.getRGB();
        cfg.outlineWidth = outlineWidth;
        cfg.mapFilterMode = mapFilterMode;
        cfg.goldReward = parseOptionalInt(goldBox.getValue());
        cfg.emotionReward = parseOptionalFloat(emotionBox.getValue());
        cfg.refreshWeight = parseOptionalFloat(weightBox.getValue());
        String raw = mapsBox.getValue().trim();
        cfg.enabledMaps = raw.isEmpty() ? List.of() : List.of(raw.split("\\s*,\\s*"));
        ConfigManager.getInstance().save();
    }

    private void resetAll() {
        ConfigManager.getInstance().setTaskConfig(def.getFullId(), new TaskConfigEntry(true));
        // 重载 EditBox
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        goldBox.setValue("");
        emotionBox.setValue("");
        weightBox.setValue("");
        mapsBox.setValue("");
        currentColor = def.getInstinctColor() != null ? def.getInstinctColor() : new Color(200, 200, 200, 180);
        outlineWidth = 4.0f;
        mapFilterMode = 0;
        enabled = true;
    }

    private TaskConfigEntry getOrCreateConfig() {
        TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
        if (cfg == null) {
            cfg = new TaskConfigEntry();
            ConfigManager.getInstance().setTaskConfig(def.getFullId(), cfg);
        }
        return cfg;
    }

    // ========== 工具 ==========

    private static int parseOptionalInt(String s) {
        if (s == null || s.trim().isEmpty()) return -1;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private static float parseOptionalFloat(String s) {
        if (s == null || s.trim().isEmpty()) return -1f;
        try { return Float.parseFloat(s.trim()); } catch (NumberFormatException e) { return -1f; }
    }

    private static int findColorIndex(Color c) {
        int rgb = c.getRGB() & 0x00FFFFFF;
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
