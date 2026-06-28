package com.habitrain.core.client.gui;

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

    private TaskDetailPanel() {}

    /**
     * 渲染详情面板
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

        // ← 返回
        if (mx >= panelX + PAD && mx <= panelX + PAD + font.width("← 返回") + 10
                && my >= areaY + 8 && my <= areaY + 30) {
            onClose.run();
            return true;
        }

        // 颜色切换
        if (mx >= panelX + PAD + LABEL_W && mx <= panelX + PAD + LABEL_W + 100
                && my >= areaY + 36 + ROW_H && my <= areaY + 36 + ROW_H * 2) {
            int nextIdx = (findColorIndex(currentColor) + 1) % COLOR_PRESETS.length;
            Color newColor = new Color(COLOR_PRESETS[nextIdx]);
            saveColor(def, newColor);
            return true;
        }

        // 描边 [-]
        if (mx >= panelX + PAD + LABEL_W && mx <= panelX + PAD + LABEL_W + 24
                && my >= areaY + 36 + ROW_H * 2 && my <= areaY + 36 + ROW_H * 3) {
            saveOutlineWidth(def, Math.max(1.0f, outlineWidth - 0.5f));
            return true;
        }

        // 描边 [+]
        String owText = String.format("%.1f", outlineWidth);
        int plusX = panelX + PAD + LABEL_W + 24 + font.width(owText) + 8;
        if (mx >= plusX && mx <= plusX + 24
                && my >= areaY + 36 + ROW_H * 2 && my <= areaY + 36 + ROW_H * 3) {
            saveOutlineWidth(def, Math.min(10.0f, outlineWidth + 0.5f));
            return true;
        }

        // 地图模式切换
        int mapTextX = panelX + PAD + LABEL_W + 24;
        if (mx >= mapTextX && mx <= mapTextX + font.width("[ 全部地图 ]") + 40
                && my >= areaY + 36 + ROW_H * 6 && my <= areaY + 36 + ROW_H * 7) {
            saveMapFilter(def, (mapFilterMode + 1) % 3);
            return true;
        }

        // 保存
        int saveX = panelX + PAD + 10;
        if (mx >= saveX && mx <= saveX + font.width("[保存]") + 10
                && my >= areaY + areaH - 50 && my <= areaY + areaH - 50 + 16) {
            ConfigManager.getInstance().save();
            return true;
        }

        // 重置
        int resetX = panelX + PAD + 10 + font.width("[保存]  ");
        if (mx >= resetX && mx <= resetX + font.width("[重置]") + 10
                && my >= areaY + areaH - 50 && my <= areaY + areaH - 50 + 16) {
            resetTask(def);
            return true;
        }

        return false;
    }

    public static boolean mouseScrolled(double vertical) {
        return true; // 占位
    }

    public static boolean keyPressed(int key, int sc, int mod,
                                      TaskDefinition def, float outlineWidth,
                                      MainConfigScreen screen) {
        return false;
    }

    // ========== 持久化 ==========

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

    // ========== 工具 ==========

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
