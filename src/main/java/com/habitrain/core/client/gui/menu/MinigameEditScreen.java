package com.habitrain.core.client.gui.menu;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MinigameConfigEntry;
import io.wifi.starrailexpress.content.minigame.QuestMinigame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 小游戏详情编辑 — 仿 TaskEditScreen 布局。
 * 可编辑：启用/颜色/轮廓/金币奖励/情绪奖励/刷新权重/地图过滤。
 */
public class MinigameEditScreen extends Screen {

    private static final int PAD = 10;
    private static final int HEADER_H = 36;
    private static final int ROW_H = 22;
    private static final int LABEL_W = 76;
    private static final int ALPHA = 0xB4;

    private final ConfigMenuScreen parent;
    private final QuestMinigame minigame;
    private final MinigameConfigEntry cfg;
    private final boolean remoteEditable;
    private final String displayName;

    private double scrollOffset = 0;
    private boolean draggingScroll = false;
    private double dragStartY = 0, dragStartOff = 0;
    private int contentHeight = 0;

    private Button enableBtn, colorBtn, outlineMinusBtn, outlinePlusBtn;
    private EditBox goldField, emotionField, weightField;
    private Button mapFilterBtn;
    private EditBox mapField;
    private Button saveBtn, resetBtn, topBackBtn;
    private int colorIndex = 0;

    public MinigameEditScreen(ConfigMenuScreen parent, QuestMinigame minigame, MinigameConfigEntry cfg) {
        super(Component.literal("§l" + (minigame.displayName() != null ? minigame.displayName().getString() : minigame.id()) + " - 小游戏配置"));
        this.parent = parent;
        this.minigame = minigame;
        this.cfg = cfg;
        this.remoteEditable = MenuPermissions.canEditRemoteConfigs();
        this.displayName = minigame.displayName() != null ? minigame.displayName().getString() : minigame.id();
    }

    @Override
    protected void init() {
        super.init();
        // 从当前颜色找到 index
        colorIndex = 0;
        for (int i = 0; i < MenuTheme.getColorCount(); i++) {
            if (MenuTheme.getColor(i, ALPHA) == cfg.instinctColor) { colorIndex = i; break; }
        }

        enableBtn = Button.builder(makeEnableText(), b -> {
            if (!remoteEditable) { MenuPermissions.showDeniedMessage(); return; }
            cfg.enabled = !cfg.enabled;
            enableBtn.setMessage(makeEnableText());
            saveCurrent();
        }).bounds(-10000, -10000, 80, 18).build();
        addRenderableWidget(enableBtn);

        colorBtn = Button.builder(colorLabel(), b -> {
            if (!remoteEditable) { MenuPermissions.showDeniedMessage(); return; }
            colorIndex = (colorIndex + 1) % MenuTheme.getColorCount();
            cfg.instinctColor = MenuTheme.getColor(colorIndex, ALPHA);
            colorBtn.setMessage(colorLabel());
            saveCurrent();
        }).bounds(-10000, -10000, 100, 18).build();
        addRenderableWidget(colorBtn);

        outlineMinusBtn = Button.builder(Component.literal("§c◀"), b -> {
            if (!remoteEditable) { MenuPermissions.showDeniedMessage(); return; }
            cfg.outlineWidth = (float) Math.max(1.0, cfg.outlineWidth - 0.5);
            saveCurrent();
        }).bounds(-10000, -10000, 22, 18).build();
        addRenderableWidget(outlineMinusBtn);

        outlinePlusBtn = Button.builder(Component.literal("▶"), b -> {
            if (!remoteEditable) { MenuPermissions.showDeniedMessage(); return; }
            cfg.outlineWidth = (float) Math.min(10.0, cfg.outlineWidth + 0.5);
            saveCurrent();
        }).bounds(-10000, -10000, 22, 18).build();
        addRenderableWidget(outlinePlusBtn);

        goldField = new EditBox(font, -10000, -10000, 60, 14, Component.literal(""));
        goldField.setMaxLength(8);
        goldField.setValue(cfg.hasGoldReward ? String.valueOf(cfg.goldReward) : "");
        goldField.setHint(Component.literal("默认"));
        goldField.setFilter(s -> s.isEmpty() || s.matches("-?\\d*"));
        goldField.setEditable(remoteEditable);
        addRenderableWidget(goldField);

        emotionField = new EditBox(font, -10000, -10000, 60, 14, Component.literal(""));
        emotionField.setMaxLength(8);
        emotionField.setValue(cfg.hasEmotionReward ? String.format("%.2f", cfg.emotionReward) : "");
        emotionField.setHint(Component.literal("默认"));
        emotionField.setFilter(s -> s.isEmpty() || s.matches("-?\\d*\\.?\\d*"));
        emotionField.setEditable(remoteEditable);
        addRenderableWidget(emotionField);

        weightField = new EditBox(font, -10000, -10000, 60, 14, Component.literal(""));
        weightField.setMaxLength(8);
        weightField.setValue(cfg.hasRefreshWeight ? String.format("%.2f", cfg.refreshWeight) : "");
        weightField.setHint(Component.literal("默认"));
        weightField.setFilter(s -> s.isEmpty() || s.matches("\\d*\\.?\\d*"));
        weightField.setEditable(remoteEditable);
        addRenderableWidget(weightField);

        mapFilterBtn = Button.builder(mapFilterLabel(), b -> {
            if (!remoteEditable) { MenuPermissions.showDeniedMessage(); return; }
            cfg.mapFilterMode = (cfg.mapFilterMode + 1) % 3;
            mapFilterBtn.setMessage(mapFilterLabel());
            saveCurrent();
        }).bounds(-10000, -10000, 80, 18).build();
        addRenderableWidget(mapFilterBtn);

        mapField = new EditBox(font, -10000, -10000, 160, 14, Component.literal(""));
        mapField.setMaxLength(256);
        mapField.setValue(String.join(",", cfg.enabledMaps));
        mapField.setHint(Component.literal("用逗号分隔地图名"));
        mapField.setEditable(remoteEditable);
        addRenderableWidget(mapField);

        saveBtn = Button.builder(Component.literal("§a保存并返回"), b -> {
            commitFields();
            ConfigManager.getInstance().setMinigameConfig(minigame.id(), cfg);
            Minecraft.getInstance().setScreen(parent);
        }).bounds(-10000, -10000, 100, 20).build();
        addRenderableWidget(saveBtn);

        resetBtn = Button.builder(Component.literal("§7重置"), b -> {
            cfg.enabled = true;
            cfg.instinctColor = 0xB4C8C8C8;
            cfg.outlineWidth = 4.0f;
            cfg.hasGoldReward = false;
            cfg.goldReward = 0;
            cfg.hasEmotionReward = false;
            cfg.emotionReward = 0f;
            cfg.hasRefreshWeight = false;
            cfg.refreshWeight = 0f;
            cfg.mapFilterMode = 0;
            cfg.enabledMaps.clear();
            goldField.setValue("");
            emotionField.setValue("");
            weightField.setValue("");
            mapField.setValue("");
            ConfigManager.getInstance().setMinigameConfig(minigame.id(), cfg);
        }).bounds(-10000, -10000, 60, 20).build();
        addRenderableWidget(resetBtn);

        topBackBtn = Button.builder(Component.literal("§7← 返回"), b -> {
            Minecraft.getInstance().setScreen(parent);
        }).bounds(PAD, 4, 70, 18).build();
        addRenderableWidget(topBackBtn);

        if (!remoteEditable) {
            enableBtn.active = false;
            colorBtn.active = false;
            outlineMinusBtn.active = false;
            outlinePlusBtn.active = false;
            mapFilterBtn.active = false;
            saveBtn.active = false;
            resetBtn.active = false;
        }
    }

    private Component makeEnableText() {
        return Component.literal(cfg.enabled ? "§a已启用" : "§c已停用");
    }

    private Component colorLabel() {
        return Component.literal("§l● " + MenuTheme.COLOR_NAMES[colorIndex]);
    }

    private Component mapFilterLabel() {
        return switch (cfg.mapFilterMode) {
            case 1 -> Component.literal("§e白名单");
            case 2 -> Component.literal("§c黑名单");
            default -> Component.literal("§a全部地图");
        };
    }

    private void saveCurrent() {
        ConfigManager.getInstance().putMinigameConfig(minigame.id(), cfg);
    }

    private void commitFields() {
        String v;
        v = goldField.getValue().trim();
        try {
            cfg.hasGoldReward = !v.isEmpty();
            cfg.goldReward = v.isEmpty() ? 0 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            cfg.hasGoldReward = false;
            cfg.goldReward = 0;
        }
        v = emotionField.getValue().trim();
        try {
            cfg.hasEmotionReward = !v.isEmpty();
            cfg.emotionReward = v.isEmpty() ? 0f : Float.parseFloat(v);
        } catch (NumberFormatException e) {
            cfg.hasEmotionReward = false;
            cfg.emotionReward = 0f;
        }
        v = weightField.getValue().trim();
        try {
            cfg.hasRefreshWeight = !v.isEmpty();
            cfg.refreshWeight = v.isEmpty() ? 0f : Math.max(0f, Float.parseFloat(v));
        } catch (NumberFormatException e) {
            cfg.hasRefreshWeight = false;
            cfg.refreshWeight = 0f;
        }
        v = mapField.getValue().trim();
        cfg.enabledMaps.clear();
        if (!v.isEmpty()) {
            for (String s : v.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) cfg.enabledMaps.add(t);
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);

        // 标题
        g.drawString(font, Component.literal("§l" + displayName), PAD + 80, 8, 0xFFFFFFFF, false);
        g.drawString(font, Component.literal("§7" + minigame.id()), PAD + 80, 22, 0xFF888888, false);

        int contentTop = HEADER_H;
        int contentBot = height - 30;
        int contentH = contentBot - contentTop;
        int scrollW = width - PAD * 2;

        g.enableScissor(PAD, contentTop, PAD + scrollW, contentBot);
        int y = contentTop - (int) scrollOffset;

        // ===== 基础设置 =====
        g.drawString(font, Component.literal("§e§l基础设置"), PAD, y + 2, MenuTheme.ACCENT_CYAN, false);
        y += ROW_H + 4;

        // 启用/禁用
        g.drawString(font, "启用状态", PAD, y + 3, 0xFFCCCCCC, false);
        enableBtn.setX(PAD + LABEL_W); enableBtn.setY(y); enableBtn.setWidth(80);
        y += ROW_H;

        // 颜色
        g.drawString(font, "透视颜色", PAD, y + 3, 0xFFCCCCCC, false);
        colorBtn.setX(PAD + LABEL_W); colorBtn.setY(y); colorBtn.setWidth(100);
        y += ROW_H;

        // 轮廓
        g.drawString(font, "轮廓宽度", PAD, y + 3, 0xFFCCCCCC, false);
        String wText = String.format("%.1f", cfg.outlineWidth);
        int wTextW = font.width(wText);
        int minusX = PAD + LABEL_W;
        int plusX = minusX + 24;
        int textX = plusX + 24;
        outlineMinusBtn.setX(minusX); outlineMinusBtn.setY(y); outlineMinusBtn.setWidth(22);
        outlinePlusBtn.setX(plusX); outlinePlusBtn.setY(y); outlinePlusBtn.setWidth(22);
        g.drawString(font, wText, textX + 4, y + 3, 0xFFFFFFFF, false);
        y += ROW_H + 6;

        // ===== 奖励设置 =====
        g.drawString(font, Component.literal("§e§l奖励设置"), PAD, y + 2, MenuTheme.ACCENT_CYAN, false);
        y += ROW_H + 4;

        g.drawString(font, "金币奖励", PAD, y + 3, 0xFFCCCCCC, false);
        goldField.setX(PAD + LABEL_W); goldField.setY(y + 1); goldField.setWidth(60);
        y += ROW_H;

        g.drawString(font, "情绪奖励", PAD, y + 3, 0xFFCCCCCC, false);
        emotionField.setX(PAD + LABEL_W); emotionField.setY(y + 1); emotionField.setWidth(60);
        y += ROW_H;

        g.drawString(font, "刷新权重", PAD, y + 3, 0xFFCCCCCC, false);
        weightField.setX(PAD + LABEL_W); weightField.setY(y + 1); weightField.setWidth(60);
        y += ROW_H + 6;

        // ===== 地图设置 =====
        g.drawString(font, Component.literal("§e§l地图设置"), PAD, y + 2, MenuTheme.ACCENT_CYAN, false);
        y += ROW_H + 4;

        g.drawString(font, "过滤模式", PAD, y + 3, 0xFFCCCCCC, false);
        mapFilterBtn.setX(PAD + LABEL_W); mapFilterBtn.setY(y); mapFilterBtn.setWidth(80);
        y += ROW_H;

        g.drawString(font, "地图列表", PAD, y + 3, 0xFFCCCCCC, false);
        mapField.setX(PAD + LABEL_W); mapField.setY(y + 1); mapField.setWidth(160);
        y += ROW_H + 6;

        // ===== 基本信息 =====
        g.drawString(font, Component.literal("§e§l基本信息（只读）"), PAD, y + 2, MenuTheme.ACCENT_CYAN, false);
        y += ROW_H + 4;
        g.drawString(font, "§7小游戏 ID: §f" + minigame.id(), PAD, y + 2, 0xFFAAAAAA, false);
        y += ROW_H;
        g.drawString(font, "§7显示名称: §f" + displayName, PAD, y + 2, 0xFFAAAAAA, false);
        y += ROW_H;
        g.drawString(font, "§7归属: §fsre:base（基础任务池）", PAD, y + 2, 0xFFAAAAAA, false);
        y += 30;

        contentHeight = y - contentTop + (int) scrollOffset;

        // 底部按钮（在 super.render 前更新位置，确保渲染在裁剪区内外正确）
        saveBtn.setX(width / 2 - 110); saveBtn.setY(height - 26); saveBtn.setWidth(100);
        resetBtn.setX(width / 2 + 10); resetBtn.setY(height - 26); resetBtn.setWidth(60);

        // 渲染 widgets（在裁剪区内）
        super.render(g, mx, my, delta);
        g.disableScissor();

        // 滚动条
        int maxScroll = Math.max(0, contentHeight - contentH);
        MenuTheme.drawScrollbar(g, PAD + scrollW - 4, contentTop, contentH, scrollOffset, maxScroll, 3);

        if (!remoteEditable) {
            g.drawString(font, Component.literal("§c只读：联机服务器中仅 OP 可修改"),
                    PAD, height - 10, 0xFF5555, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (super.mouseClicked(mx, my, btn)) return true;
        int contentTop = HEADER_H;
        int contentBot = height - 30;
        if (my >= contentTop && my < contentBot) {
            int maxScroll = Math.max(0, contentHeight - (contentBot - contentTop));
            // 滚动条拖拽检测
            int scrollW = width - PAD * 2;
            int sbX = PAD + scrollW - 4;
            if (mx >= sbX - 2 && mx <= sbX + 6) {
                draggingScroll = true;
                dragStartY = my;
                dragStartOff = scrollOffset;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (draggingScroll) {
            int contentTop = HEADER_H;
            int contentBot = height - 30;
            int contentH = contentBot - contentTop;
            int maxScroll = Math.max(0, contentHeight - contentH);
            double delta = dragStartY - my;
            scrollOffset = Mth.clamp(dragStartOff + delta, 0, maxScroll);
            return true;
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        draggingScroll = false;
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int contentTop = HEADER_H;
        int contentBot = height - 30;
        int contentH = contentBot - contentTop;
        int maxScroll = Math.max(0, contentHeight - contentH);
        scrollOffset = Mth.clamp(scrollOffset - sy * 18, 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (goldField.isFocused() && goldField.keyPressed(key, scan, mod)) return true;
        if (emotionField.isFocused() && emotionField.keyPressed(key, scan, mod)) return true;
        if (weightField.isFocused() && weightField.keyPressed(key, scan, mod)) return true;
        if (mapField.isFocused() && mapField.keyPressed(key, scan, mod)) return true;
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, scan, mod);
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
    public void onClose() {
        ConfigManager.getInstance().save();
        Minecraft.getInstance().setScreen(parent);
    }
}
