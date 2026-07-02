package com.habitrain.core.client.gui;

import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 任务列表面板 — 渲染和点击处理（非 Screen，由 MainConfigScreen 调用）
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
     */
    public static void render(GuiGraphics g, Font font, List<TaskDefinition> tasks,
                              String searchText, double scroll,
                              int areaX, int areaY, int areaW, int areaH,
                              int headerH, int mx, int my,
                              Consumer<TaskDefinition> onOpen) {
        int contentX = areaX + 4;
        int contentW = areaW - areaX - 8 - SCROLLBAR_W;
        int listY = areaY + headerH + 24;

        List<TaskDefinition> filtered = filterTasks(tasks, searchText);

        // 顶部标题
        String title = "§l任务列表";
        String stats = String.format("§7%d 个任务", filtered.size());
        g.drawString(font, Component.literal(title), contentX, areaY + 4, 0xFFFFFF, false);
        g.drawString(font, Component.literal(stats),
                contentX + font.width(title) + 12, areaY + 4, 0x888888, false);

        // 分隔线
        g.fill(areaX + 4, areaY + headerH, areaW - 4, areaY + headerH + 1, 0x44446666);

        // 可见区域
        int totalH = filtered.size() * (ROW_H + ROW_GAP);
        int visibleH = areaH - headerH - 36;
        int maxScroll = Math.max(0, totalH - visibleH);
        double safeScroll = Math.min(scroll, maxScroll);

        // 渲染行
        int startIdx = (int) (safeScroll / (ROW_H + ROW_GAP));
        int y = listY - (int) (safeScroll % (ROW_H + ROW_GAP));
        int endY = areaY + areaH - 36;

        for (int i = startIdx; i < filtered.size() && y < endY; i++) {
            TaskDefinition def = filtered.get(i);
            TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(def.getFullId());
            boolean enabled = cfg == null || cfg.enabled;
            Color color = cfg != null ? new Color(cfg.getColor(), true)
                    : new Color(def.getInstinctColorRGB(), true);
            boolean hover = mx >= contentX && mx < contentX + contentW
                    && my >= y && my < y + ROW_H;

            if (hover) g.fill(contentX, y, contentX + contentW, y + ROW_H, 0x22FFFFFF);

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

        // 无结果
        if (filtered.isEmpty()) {
            String msg = searchText.isEmpty() ? "§7该模式下没有任务" : "§7没有匹配 \"§f" + searchText + "§7\" 的任务";
            g.drawString(font, Component.literal(msg),
                    contentX + contentW / 2 - font.width(msg) / 2, listY + 30, 0, false);
        }

        // 滚动条
        if (maxScroll > 0) {
            int sbH = Math.max(20, (int) ((float) visibleH / totalH * visibleH));
            int sbY = listY + (int) ((safeScroll / maxScroll) * (visibleH - sbH));
            g.fill(areaW - SCROLLBAR_W - 2, sbY, areaW - 2, sbY + sbH, 0x88AAAAAA);
        }
    }

    /**
     * 处理鼠标点击
     */
    public static void mouseClicked(List<TaskDefinition> tasks, String searchText, double scroll,
                                    int areaX, int areaY, int areaW, int areaH, int headerH,
                                    int mx, int my, int btn,
                                    Consumer<TaskDefinition> onOpen,
                                    Consumer<TaskDefinition> onToggle) {
        List<TaskDefinition> filtered = filterTasks(tasks, searchText);
        int contentX = areaX + 4;
        int contentW = areaW - areaX - 8 - SCROLLBAR_W;
        int listY = areaY + headerH + 24;
        int visibleH = areaH - headerH - 36;
        int totalH = filtered.size() * (ROW_H + ROW_GAP);
        int maxScroll = Math.max(0, totalH - visibleH);
        double safeScroll = Math.min(scroll, maxScroll);

        int y = listY - (int) (safeScroll % (ROW_H + ROW_GAP));
        int startIdx = (int) (safeScroll / (ROW_H + ROW_GAP));

        for (int i = startIdx; i < filtered.size(); i++) {
            if (y + ROW_H > areaY + areaH - 36) break;

            if (mx >= contentX && mx < contentX + contentW && my >= y && my < y + ROW_H) {
                TaskDefinition def = filtered.get(i);
                int toggleX = contentX + contentW - TOGGLE_W;
                if (mx >= toggleX && onToggle != null) {
                    onToggle.accept(def);
                } else if (onOpen != null) {
                    onOpen.accept(def);
                }
                return;
            }
            y += ROW_H + ROW_GAP;
        }
    }

    private static List<TaskDefinition> filterTasks(List<TaskDefinition> tasks, String searchText) {
        if (searchText == null || searchText.isEmpty()) return tasks;
        return tasks.stream()
                .filter(t -> t.getDisplayName().toLowerCase().contains(searchText)
                        || t.getFullId().toLowerCase().contains(searchText)
                        || t.getModId().toLowerCase().contains(searchText))
                .collect(Collectors.toList());
    }

    private static String getModTag(String modId) {
        return switch (modId) {
            case "habitrain_core" -> "SRE";
            case "habitrain_more_tasks" -> "more";
            default -> modId.length() > 8 ? modId.substring(0, 8) + "…" : modId;
        };
    }
}
