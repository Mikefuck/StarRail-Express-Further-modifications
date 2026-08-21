package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.habitrain.core.client.gui.menu.MenuSounds;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.PillToggle;
import com.habitrain.core.client.gui.menu.ui.ScrollArea;
import com.habitrain.core.client.gui.menu.ui.SliderRow;
import com.habitrain.core.client.mvp.MvpAnimationController;
import com.habitrain.core.client.mvp.MvpAnimationDefinition;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MvpAnimationSettings;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏外·MVP 动画配置页。
 * 提供新版 MVP 动画总开关、随机轮换、同队排重、武器显示、速度滑块、30 个内置动作的独立开关以及资源诊断。
 */
public class OutGameMvpAnimationsPage implements ConfigPage {

    private static final int PAD = 14;
    private static final int GAP = 6;
    private static final int ACTION_MASTER_ENABLED = -1;
    private static final int ACTION_RANDOM = -2;
    private static final int ACTION_AVOID_DUP = -3;
    private static final int ACTION_SHOW_ITEMS = -4;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private final MvpAnimationSettings settings;
    private final SliderRow speedSlider = new SliderRow(0.5f, 1.5f, 0.05f);
    private final ScrollArea area = new ScrollArea(0, 0, 1, 1);

    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int action, String animationId, int x, int y, int w, int h) {}

    public OutGameMvpAnimationsPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        this.settings = ConfigManager.getInstance().getMvpAnimationSettings().copy();
    }

    @Override
    public boolean canSave() {
        return true;
    }

    @Override
    public void save() {
        ConfigManager.getInstance().setMvpAnimationSettings(this.settings);
        ConfigManager.getInstance().save();
    }

    @Override
    public void flushPending() {
    }

    private void persistChange() {
        ConfigManager.getInstance().setMvpAnimationSettings(this.settings);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        area.setBounds(x, y, w, h);
        hits.clear();
        g.enableScissor(x, y, x + w, y + h);

        int cy = area.getContentY() + PAD;
        int cardX = x + PAD;
        int cardW = Math.max(1, w - PAD * 2 - 5);

        // ===== 顶部标题 =====
        drawClipped(g, Component.translatable("screen.habitrain_core.mvp_anim.title"), cardX, cy,
                cardW, MenuTheme.TEXT_PRIMARY);
        cy += 14;
        drawClipped(g, Component.translatable("screen.habitrain_core.mvp_anim.subtitle"), cardX, cy,
                cardW, MenuTheme.TEXT_SECONDARY);
        cy += 18;

        // ===== 第一组：总控设置卡片 =====
        cy = renderMasterControls(g, mx, my, cardX, cy, cardW);
        cy += GAP + 4;

        // ===== 第二组：动画池 =====
        cy = renderAnimationPool(g, mx, my, cardX, cy, cardW);
        cy += GAP + 4;

        // ===== 第三组：引擎与资源诊断 =====
        cy = renderDiagnostics(g, cardX, cy, cardW);
        cy += PAD;

        int totalHeight = cy - area.getContentY();
        area.setContentHeight(totalHeight);
        area.render(g);
        g.disableScissor();
    }

    private int renderMasterControls(GuiGraphics g, int mx, int my, int cardX, int cy, int cardW) {
        int startY = cy;
        int rowH = 24;

        g.fill(cardX, cy, cardX + cardW, cy + 1, MenuTheme.BORDER);
        cy += 6;
        g.drawString(font, "全局选项", cardX + 4, cy, MenuTheme.ACCENT_MINT, false);
        cy += 14;

        // 1. 总开关
        int toggleW = Math.max(1, Math.min(220, cardW - 8));
        PillToggle.render(g, font, cardX + 4, cy, toggleW, 18, settings.enabled,
                "新版 MVP 动画 · 已启用", "新版 MVP 动画 · 已禁用");
        hits.add(new Hit(ACTION_MASTER_ENABLED, null, cardX + 4, cy, toggleW, 18));
        cy += rowH;

        // 2. 随机选择
        PillToggle.render(g, font, cardX + 4, cy, toggleW, 18, settings.randomSelection,
                "随机选择动作 · 开启", "随机选择动作 · 关闭");
        hits.add(new Hit(ACTION_RANDOM, null, cardX + 4, cy, toggleW, 18));
        cy += rowH;

        // 3. 同队排重
        PillToggle.render(g, font, cardX + 4, cy, toggleW, 18, settings.avoidDuplicates,
                "同队动作尽量不重复 · 开启", "同队动作尽量不重复 · 关闭");
        hits.add(new Hit(ACTION_AVOID_DUP, null, cardX + 4, cy, toggleW, 18));
        cy += rowH;

        // 4. 展示武器
        PillToggle.render(g, font, cardX + 4, cy, toggleW, 18, settings.showRoleItems,
                "展示角色武器道具 · 开启", "展示角色武器道具 · 关闭");
        hits.add(new Hit(ACTION_SHOW_ITEMS, null, cardX + 4, cy, toggleW, 18));
        cy += rowH;

        // 5. 动画速度滑块
        drawClipped(g, String.format("动画播放速度: §6%.2fx", settings.speed), cardX + 6, cy,
                Math.max(1, cardW - 12), MenuTheme.TEXT_PRIMARY);
        cy += 12;
        int sliderW = Math.max(1, Math.min(220, cardW - 44));
        if (editable) {
            if (speedSlider.mouseDragged()) {
                float nv = speedSlider.valueFromMouse(mx);
                if (Math.abs(nv - settings.speed) > 0.01f) {
                    settings.setSpeed(nv);
                    persistChange();
                }
            }
            speedSlider.render(g, font, cardX + 6, cy, sliderW, settings.speed);
        } else {
            g.drawString(font, String.format("§7%.2fx", settings.speed), cardX + 6, cy, MenuTheme.TEXT_DIM, false);
        }
        cy += 20;

        return cy;
    }

    private int renderAnimationPool(GuiGraphics g, int mx, int my, int cardX, int cy, int cardW) {
        g.fill(cardX, cy, cardX + cardW, cy + 1, MenuTheme.BORDER);
        cy += 6;

        int enabledCount = settings.getEnabledCount();
        int totalCount = MvpAnimationDefinition.BUILT_INS.size();
        drawClipped(g, String.format("动画动作池 (已启用 %d / %d)", enabledCount, totalCount),
                cardX + 4, cy, Math.max(1, cardW - 8), MenuTheme.ACCENT_BLUE);
        cy += 14;

        if (enabledCount == 0) {
            drawClipped(g, "§c没有可用动作，结算时将自动使用旧版走入场/蹲姿/举刀动画",
                    cardX + 6, cy, Math.max(1, cardW - 12), 0xFFFF5555);
            cy += 14;
        }

        int itemH = 34;
        for (int i = 0; i < MvpAnimationDefinition.BUILT_INS.size(); i++) {
            MvpAnimationDefinition def = MvpAnimationDefinition.BUILT_INS.get(i);
            boolean isEnabled = settings.isAnimationEnabled(def.id());
            boolean hover = MenuTheme.inBounds(mx, my, cardX, cy, cardW, itemH);

            MenuTheme.row(g, cardX, cy, cardW, itemH, hover, false);

            // 序号
            String indexStr = String.format("%02d", i + 1);
            g.drawString(font, indexStr, cardX + 6, cy + 6, MenuTheme.TEXT_DIM, false);

            // 右侧开关；窄窗口时同步缩小，避免越过卡片边界。
            int toggleW = Math.max(1, Math.min(68, cardW / 3));
            int toggleH = 16;
            int toggleX = cardX + cardW - toggleW - 6;
            int toggleY = cy + 9;

            // 动作名称与标签
            int textX = cardX + 26;
            Component nameComp = Component.translatable(def.nameKey());
            String badges = def.squadSafe() ? " §b[小队]" : " §e[单人]";
            if (def.prefersHiddenItem()) badges += " §7[藏武器]";
            int textW = Math.max(1, toggleX - textX - 4);
            drawClipped(g, nameComp.getString() + badges, textX, cy + 4, textW,
                    isEnabled ? MenuTheme.TEXT_PRIMARY : MenuTheme.TEXT_DIM);

            // 动作简述
            Component descComp = Component.translatable(def.descKey());
            drawClipped(g, descComp, textX, cy + 18, textW, MenuTheme.TEXT_SECONDARY);

            PillToggle.render(g, font, toggleX, toggleY, toggleW, toggleH, isEnabled, "启用", "禁用");
            hits.add(new Hit(i, def.id(), toggleX, toggleY, toggleW, toggleH));

            cy += itemH + 2;
        }

        return cy;
    }

    private int renderDiagnostics(GuiGraphics g, int cardX, int cy, int cardW) {
        g.fill(cardX, cy, cardX + cardW, cy + 1, MenuTheme.BORDER);
        cy += 6;

        drawClipped(g, "动画引擎与资源诊断", cardX + 4, cy,
                Math.max(1, cardW - 8), MenuTheme.TEXT_SECONDARY);
        cy += 14;

        boolean libLoaded = MvpAnimationController.isPlayerAnimatorLoaded();
        drawClipped(g, "Player Animator: " + (libLoaded ? "§a已正常加载" : "§c未加载"),
                cardX + 6, cy, Math.max(1, cardW - 12), MenuTheme.TEXT_SECONDARY);
        cy += 12;

        int regCount = MvpAnimationController.getRegisteredBuiltinCount();
        int totalBuiltins = MvpAnimationDefinition.BUILT_INS.size();
        drawClipped(g, String.format("已注册动画资源: §f%d / %d", regCount, totalBuiltins),
                cardX + 6, cy, Math.max(1, cardW - 12), MenuTheme.TEXT_SECONDARY);
        cy += 12;

        List<String> missing = MvpAnimationController.getMissingBuiltinIds();
        if (!missing.isEmpty()) {
            drawClipped(g, "§c缺失资源: " + String.join(", ", missing),
                    cardX + 6, cy, Math.max(1, cardW - 12), 0xFFFF5555);
            cy += 12;
        }

        return cy;
    }

    private void drawClipped(GuiGraphics g, Component text, int x, int y, int maxWidth, int color) {
        drawClipped(g, text.getString(), x, y, maxWidth, color);
    }

    private void drawClipped(GuiGraphics g, String text, int x, int y, int maxWidth, int color) {
        g.drawString(font, font.plainSubstrByWidth(text, Math.max(1, maxWidth)), x, y, color, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (!editable && !hits.isEmpty()) {
            for (Hit hit : hits) {
                if (PillToggle.hit(mx, my, hit.x, hit.y, hit.w, hit.h)) {
                    MenuPermissions.showDeniedMessage();
                    return true;
                }
            }
        }

        for (Hit hit : hits) {
            if (PillToggle.hit(mx, my, hit.x, hit.y, hit.w, hit.h)) {
                MenuSounds.playClick();
                if (!editable) {
                    MenuPermissions.showDeniedMessage();
                    return true;
                }
                switch (hit.action) {
                    case ACTION_MASTER_ENABLED -> {
                        settings.enabled = !settings.enabled;
                        persistChange();
                        return true;
                    }
                    case ACTION_RANDOM -> {
                        settings.randomSelection = !settings.randomSelection;
                        persistChange();
                        return true;
                    }
                    case ACTION_AVOID_DUP -> {
                        settings.avoidDuplicates = !settings.avoidDuplicates;
                        persistChange();
                        return true;
                    }
                    case ACTION_SHOW_ITEMS -> {
                        settings.showRoleItems = !settings.showRoleItems;
                        persistChange();
                        return true;
                    }
                    default -> {
                        if (hit.animationId != null) {
                            boolean cur = settings.isAnimationEnabled(hit.animationId);
                            settings.setAnimationEnabled(hit.animationId, !cur);
                            persistChange();
                            return true;
                        }
                    }
                }
            }
        }

        if (editable && speedSlider.mouseClicked(mx, my)) {
            MenuSounds.playClick();
            return true;
        }

        return area.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        if (speedSlider.mouseDragged()) return true;
        return area.mouseDragged(my);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (speedSlider.mouseReleased()) return true;
        return area.mouseReleased();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        return area.mouseScrolled(sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        return false;
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        return false;
    }
}
