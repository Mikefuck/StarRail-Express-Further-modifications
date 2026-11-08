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

import java.util.Locale;

/**
 * 小游戏详情编辑 — 仿 TaskEditScreen 布局。
 * 可编辑：启用/颜色/轮廓/金币奖励/情绪奖励/刷新权重/地图过滤。
 */
public class MinigameEditScreen extends Screen {

    private static final int PAD = 10;
    private static final int HEADER_H = 44;
    private static final int FOOTER_H = 38;
    private static final int ROW_H = 22;
    private static final int LABEL_W = 76;
    private static final int ALPHA = 0xB4;

    private final ConfigMenuScreen parent;
    private final QuestMinigame minigame;
    private final MinigameConfigEntry cfg;
    private final boolean remoteEditable;
    private final String displayName;

    private double scrollOffset = 0;
    private final com.habitrain.core.client.gui.menu.ui.ScrollbarDrag scrollbar = new com.habitrain.core.client.gui.menu.ui.ScrollbarDrag();
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
        String[] draft = goldField == null ? null : new String[] {goldField.getValue(), emotionField.getValue(),
                weightField.getValue(), mapField.getValue()};
        super.init();
        ConfigManager.getInstance().beginClientEditing();
        // 从当前颜色找到 index
        colorIndex = 0;
        for (int i = 0; i < MenuTheme.getColorCount(); i++) {
            if (MenuTheme.getColor(i, ALPHA) == cfg.instinctColor) { colorIndex = i; break; }
        }

        // 优先注册顶栏与底栏按钮，避免滚动到顶/底部的表单控件抢夺点击
        topBackBtn = Button.builder(Component.literal("§7← 返回"), b -> {
            onClose();
        }).bounds(PAD, 4, 70, 18).build();
        addRenderableWidget(topBackBtn);

        saveBtn = Button.builder(Component.literal("§a保存并返回"), b -> {
            saveAndReturn();
        }).bounds(-10000, -10000, 100, 20).build();
        addRenderableWidget(saveBtn);

        resetBtn = Button.builder(Component.literal("§7重置"), b -> {
            if (!remoteEditable) { MenuPermissions.showDeniedMessage(); return; }
            cfg.enabled = true;
            cfg.hasInstinctColor = false;
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
            colorIndex = 0;
            for (int i = 0; i < MenuTheme.getColorCount(); i++) {
                if (MenuTheme.getColor(i, ALPHA) == cfg.instinctColor) { colorIndex = i; break; }
            }
            enableBtn.setMessage(makeEnableText());
            colorBtn.setMessage(colorLabel());
            mapFilterBtn.setMessage(mapFilterLabel());
            goldField.setValue("");
            emotionField.setValue("");
            weightField.setValue("");
            mapField.setValue("");
            ConfigManager.getInstance().setMinigameConfig(minigame.id(), cfg);
        }).bounds(-10000, -10000, 60, 20).build();
        addRenderableWidget(resetBtn);

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
            cfg.hasInstinctColor = true;
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
        emotionField.setValue(cfg.hasEmotionReward ? String.format(Locale.ROOT, "%.2f", cfg.emotionReward) : "");
        emotionField.setHint(Component.literal("默认"));
        emotionField.setFilter(s -> s.isEmpty() || s.matches("-?\\d*\\.?\\d*"));
        emotionField.setEditable(remoteEditable);
        addRenderableWidget(emotionField);

        weightField = new EditBox(font, -10000, -10000, 60, 14, Component.literal(""));
        weightField.setMaxLength(8);
        weightField.setValue(cfg.hasRefreshWeight ? String.format(Locale.ROOT, "%.2f", cfg.refreshWeight) : "");
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
        if (draft != null) {
            goldField.setValue(draft[0]);
            emotionField.setValue(draft[1]);
            weightField.setValue(draft[2]);
            mapField.setValue(draft[3]);
        }

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

    @Override
    public void removed() {
        ConfigManager.getInstance().endClientEditing();
        super.removed();
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
        if (!remoteEditable || !MenuPermissions.canEditRemoteConfigs()) {
            MenuPermissions.showDeniedMessage();
            return;
        }
        ConfigManager.getInstance().putMinigameConfig(minigame.id(), cfg);
    }

    private boolean commitFields() {
        try {
            var numbers = com.habitrain.core.config.TaskNumericDraft.parse(goldField.getValue(), emotionField.getValue(), weightField.getValue());
            var maps = TaskMapFilterEditor.parseMapList(mapField.getValue());
            numbers.apply(cfg);
            cfg.enabledMaps = maps;
            return true;
        } catch (IllegalArgumentException error) {
            TaskSaveController.showMessage("§c" + error.getMessage() + "，未保存");
            return false;
        }
    }

    private void saveAndReturn() {
        if (remoteEditable && MenuPermissions.canEditRemoteConfigs()) {
            if (!commitFields()) return;
            ConfigManager.getInstance().setMinigameConfig(minigame.id(), cfg);
            var result = ConfigManager.getInstance().save();
            com.habitrain.core.client.config.ClientConfigSubmission.show(result);
            if (!result.successful() && result.status() != com.habitrain.core.config.ConfigSaveResult.Status.SUBMITTING) return;
        }
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        MenuTheme.drawBackdrop(g, width, height, MenuTheme.ACCENT_MINT);
        MenuTheme.editorHeader(g, font, width, displayName, minigame.id(), MenuTheme.ACCENT_MINT);
        MenuTheme.editorFooter(g, width, height, FOOTER_H);

        int contentTop = HEADER_H;
        int contentBot = height - FOOTER_H;
        int contentH = Math.max(1, contentBot - contentTop);
        int scrollW = width - PAD * 2;

        // 底部按钮（更新位置，确保渲染与点击一致）
        saveBtn.setX(width / 2 - 110); saveBtn.setY(height - 28); saveBtn.setWidth(100);
        resetBtn.setX(width / 2 + 10); resetBtn.setY(height - 28); resetBtn.setWidth(60);

        g.enableScissor(PAD, contentTop, PAD + scrollW, contentBot);
        try {
            int y = contentTop - (int) scrollOffset;

            // ===== 基础设置 =====
            g.drawString(font, "基础设置", PAD, y + 2, MenuTheme.TEXT_PRIMARY, false);
            g.fill(PAD, y + 14, PAD + scrollW - 8, y + 15, MenuTheme.BORDER);
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
            String wText = String.format(Locale.ROOT, "%.1f", cfg.outlineWidth);
            int minusX = PAD + LABEL_W;
            int plusX = minusX + 24;
            int textX = plusX + 24;
            outlineMinusBtn.setX(minusX); outlineMinusBtn.setY(y); outlineMinusBtn.setWidth(22);
            outlinePlusBtn.setX(plusX); outlinePlusBtn.setY(y); outlinePlusBtn.setWidth(22);
            g.drawString(font, wText, textX + 4, y + 3, 0xFFFFFFFF, false);
            y += ROW_H + 6;

            // ===== 奖励设置 =====
            g.drawString(font, "奖励设置", PAD, y + 2, MenuTheme.TEXT_PRIMARY, false);
            g.fill(PAD, y + 14, PAD + scrollW - 8, y + 15, MenuTheme.BORDER);
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
            g.drawString(font, "地图设置", PAD, y + 2, MenuTheme.TEXT_PRIMARY, false);
            g.fill(PAD, y + 14, PAD + scrollW - 8, y + 15, MenuTheme.BORDER);
            y += ROW_H + 4;

            g.drawString(font, "过滤模式", PAD, y + 3, 0xFFCCCCCC, false);
            mapFilterBtn.setX(PAD + LABEL_W); mapFilterBtn.setY(y); mapFilterBtn.setWidth(80);
            y += ROW_H;

            g.drawString(font, "地图列表", PAD, y + 3, 0xFFCCCCCC, false);
            mapField.setX(PAD + LABEL_W); mapField.setY(y + 1); mapField.setWidth(160);
            y += ROW_H + 6;

            // ===== 基本信息 =====
            g.drawString(font, "基本信息 / 只读", PAD, y + 2, MenuTheme.TEXT_PRIMARY, false);
            g.fill(PAD, y + 14, PAD + scrollW - 8, y + 15, MenuTheme.BORDER);
            y += ROW_H + 4;
            g.drawString(font, "§7小游戏 ID: §f" + minigame.id(), PAD, y + 2, 0xFFAAAAAA, false);
            y += ROW_H;
            g.drawString(font, "§7显示名称: §f" + displayName, PAD, y + 2, 0xFFAAAAAA, false);
            y += ROW_H;
            g.drawString(font, "§7归属: §fsre:base（基础任务池）", PAD, y + 2, 0xFFAAAAAA, false);
            y += 30;

            contentHeight = y - contentTop + (int) scrollOffset;

            for (var widget : java.util.List.of(enableBtn, colorBtn, outlineMinusBtn, outlinePlusBtn,
                    goldField, emotionField, weightField, mapFilterBtn, mapField)) {
                widget.visible = widget.getY() >= contentTop && widget.getY() + widget.getHeight() <= contentBot;
                if (!widget.visible) {
                    widget.setFocused(false);
                    if (getFocused() == widget) setFocused(null);
                }
            }

            // 内容控件必须留在裁剪区，避免滚动后覆盖标题和底栏。
            enableBtn.render(g, mx, my, delta);
            colorBtn.render(g, mx, my, delta);
            outlineMinusBtn.render(g, mx, my, delta);
            outlinePlusBtn.render(g, mx, my, delta);
            goldField.render(g, mx, my, delta);
            emotionField.render(g, mx, my, delta);
            weightField.render(g, mx, my, delta);
            mapFilterBtn.render(g, mx, my, delta);
            mapField.render(g, mx, my, delta);
        } finally {
            g.disableScissor();
        }

        topBackBtn.render(g, mx, my, delta);
        saveBtn.render(g, mx, my, delta);
        resetBtn.render(g, mx, my, delta);

        // 滚动条
        int maxScroll = Math.max(0, contentHeight - contentH);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
        MenuTheme.drawScrollbar(g, PAD + scrollW - 4, contentTop, contentH, scrollOffset, maxScroll, 3);

        if (!remoteEditable) {
            g.drawString(font, Component.literal("§c只读：联机服务器中仅 OP 可修改"),
                    PAD, height - 12, MenuTheme.DANGER, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int contentTop = HEADER_H;
        int contentBot = height - FOOTER_H;
        if (my < contentTop || my >= contentBot) {
            if (topBackBtn != null && topBackBtn.mouseClicked(mx, my, btn)) {
                setFocused(topBackBtn);
                return true;
            }
            if (saveBtn != null && saveBtn.mouseClicked(mx, my, btn)) {
                setFocused(saveBtn);
                return true;
            }
            if (resetBtn != null && resetBtn.mouseClicked(mx, my, btn)) {
                setFocused(resetBtn);
                return true;
            }
            return false;
        }
        // 滚动条拖拽优先检测
        int scrollW = width - PAD * 2;
        int sbX = PAD + scrollW - 4;
        if (scrollbar.begin(mx, my, btn, sbX - 2, contentTop, 9,
                Math.max(1, contentBot - contentTop), contentHeight, scrollOffset)) return true;
        setFocused(null);
        if (goldField != null) goldField.setFocused(false);
        if (emotionField != null) emotionField.setFocused(false);
        if (weightField != null) weightField.setFocused(false);
        if (mapField != null) mapField.setFocused(false);
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (scrollbar.isDragging()) {
            scrollOffset = scrollbar.offset(my, Math.max(1, height - FOOTER_H - HEADER_H), contentHeight);
            return true;
        }

        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        scrollbar.end();
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int contentTop = HEADER_H;
        int contentBot = height - FOOTER_H;
        int contentH = Math.max(1, contentBot - contentTop);
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
        if (remoteEditable && MenuPermissions.canEditRemoteConfigs()) {
            if (commitFields()) {
                ConfigManager.getInstance().setMinigameConfig(minigame.id(), cfg);
            }
        }
        Minecraft.getInstance().setScreen(parent);
    }
}
