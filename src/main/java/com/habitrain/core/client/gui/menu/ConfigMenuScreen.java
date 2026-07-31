package com.habitrain.core.client.gui.menu;

import com.habitrain.core.client.gui.menu.page.InGameBalancePage;
import com.habitrain.core.client.gui.menu.page.InGameEnvPage;
import com.habitrain.core.client.gui.menu.page.InGameMinigamesPage;
import com.habitrain.core.client.gui.menu.page.ModeRolesPage;
import com.habitrain.core.client.gui.menu.page.ModeTasksPage;
import com.habitrain.core.client.gui.menu.page.OtherPage;
import com.habitrain.core.client.gui.menu.page.OutGameLobbyEnvPage;
import com.habitrain.core.client.gui.menu.page.OutGameShaderPage;
import com.habitrain.core.client.gui.menu.page.OutGameVotePage;
import com.habitrain.core.client.gui.menu.ui.SaveBar;
import com.habitrain.core.client.gui.menu.ui.SubTabBar;
import com.habitrain.core.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 配置中心根屏：4 大分类 Tab → 二级子 Tab → 页面内容 + 固定底部保存栏。 */
public class ConfigMenuScreen extends Screen {

    public static final int TOP_IN_GAME = 0;
    public static final int TOP_OUT_GAME = 1;
    public static final int TOP_MODE = 2;
    public static final int TOP_OTHER = 3;

    private static final String[] TOP_LABELS = {"游戏内修改", "游戏外修改", "游戏模式修改", "其他修改"};
    private static final int[] TOP_ACCENTS = {0xFF55C28A, 0xFF7C9CFF, 0xFFD4A55A, 0xFF8A92A0};
    private static final String[][] SUB_LABELS = {
        {"小游戏", "数值平衡", "环境"},
        {"投票", "大厅环境", "光影白名单"},
        {"任务配置", "角色覆盖"},
        {}
    };
    private static final int TOP_TAB_H = 28;
    private static final int PAD = 8;

    private final Screen parent;
    private final boolean remoteEditable;

    private int topTab = TOP_IN_GAME;
    private int subTab = 0;

    private ConfigPage[][] pages;          // 懒初始化缓存
    private final SubTabBar[] subBars = new SubTabBar[4];
    private SaveBar saveBar;
    private int subHitThisFrame = -1;

    public ConfigMenuScreen(Screen parent) {
        super(Component.literal("哈比列车核心 — 配置中心"));
        this.parent = parent;
        this.remoteEditable = MenuPermissions.canEditRemoteConfigs();
        for (int i = 0; i < 4; i++) {
            if (SUB_LABELS[i].length > 0) subBars[i] = new SubTabBar(SUB_LABELS[i], TOP_ACCENTS);
        }
    }

    /** 抽奖桥接：直接打开投票页。 */
    public static ConfigMenuScreen openVote(Screen parent) {
        ConfigMenuScreen s = new ConfigMenuScreen(parent);
        s.topTab = TOP_OUT_GAME;
        s.subTab = 0; // 投票
        return s;
    }

    private ConfigPage[][] ensurePages() {
        if (pages != null) return pages;
        pages = new ConfigPage[4][];
        pages[TOP_IN_GAME] = new ConfigPage[]{
            new InGameMinigamesPage(this, font, remoteEditable),
            new InGameBalancePage(this, font, remoteEditable),
            new InGameEnvPage(this, font, remoteEditable)
        };
        pages[TOP_OUT_GAME] = new ConfigPage[]{
            new OutGameVotePage(this, font, remoteEditable),
            new OutGameLobbyEnvPage(this, font, remoteEditable),
            new OutGameShaderPage(this, font, remoteEditable)
        };
        pages[TOP_MODE] = new ConfigPage[]{
            new ModeTasksPage(this, font, remoteEditable),
            new ModeRolesPage(this, font, remoteEditable)
        };
        pages[TOP_OTHER] = new ConfigPage[]{ new OtherPage(this, font, remoteEditable) };
        return pages;
    }

    private ConfigPage currentPage() {
        ConfigPage[][] p = ensurePages();
        if (topTab == TOP_OTHER) return p[TOP_OTHER][0];
        return p[topTab][subTab];
    }

    public Screen getParent() { return parent; }
    public boolean canEdit() { return remoteEditable; }
    public void saveConfigNow() { ConfigManager.getInstance().save(); }

    /** 角色覆盖配置同步后刷新列表。 */
    public void refreshRoleOverrideTab() {
        ConfigPage[][] p = ensurePages();
        if (p[TOP_MODE][1] instanceof ModeRolesPage roles) roles.rebuildRows();
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        MenuTheme.drawBackdrop(g, width, height, TOP_ACCENTS[topTab]);

        drawTopTabs(g, mx, my);

        ConfigPage page = currentPage();
        int contentY = TOP_TAB_H;
        int contentH = height - TOP_TAB_H;
        boolean showSaveBar = page.canSave();

        if (topTab != TOP_OTHER) {
            int subHit = subBars[topTab].render(g, font, PAD, TOP_TAB_H + 4, width - PAD * 2, subTab, mx, my);
            subHitThisFrame = subHit; // 存字段供 mouseClicked 使用（渲染时已算好命中）
            contentY += SubTabBar.H + 4;
            contentH -= SubTabBar.H + 4;
        }
        if (showSaveBar) contentH -= SaveBar.HEIGHT;

        g.enableScissor(PAD, contentY, width - PAD, contentY + contentH);
        page.render(g, mx, my, delta, PAD, contentY, width - PAD * 2, contentH);
        g.disableScissor();

        if (showSaveBar) {
            saveBar = new SaveBar(remoteEditable);
            saveBar.render(g, font, width, height, mx, my);
        } else {
            saveBar = null;
        }

        if (!remoteEditable && !page.canSave()) {
            g.drawString(font, Component.literal("§c只读模式：联机服务器中仅 OP 可修改"),
                    PAD, height - 12, 0xFF5555, false);
        }
    }

    private void drawTopTabs(GuiGraphics g, int mx, int my) {
        int totalW = width - PAD * 2;
        int perTab = totalW / TOP_LABELS.length;
        for (int i = 0; i < TOP_LABELS.length; i++) {
            int x = PAD + i * perTab;
            int w = (i == TOP_LABELS.length - 1) ? (totalW - i * perTab) : perTab;
            boolean sel = i == topTab;
            boolean hover = MenuTheme.inBounds(mx, my, x, 0, w, TOP_TAB_H);
            g.fill(x, 0, x + w, TOP_TAB_H, sel ? 0xFF1B222B : (hover ? 0xFF1A1F26 : 0xFF141820));
            g.fill(x, TOP_TAB_H - 2, x + w, TOP_TAB_H, sel ? TOP_ACCENTS[i] : 0x20FFFFFF);
            int textW = font.width(TOP_LABELS[i]);
            g.drawString(font, TOP_LABELS[i], x + (w - textW) / 2, (TOP_TAB_H - font.lineHeight) / 2,
                    sel ? 0xFFFFFFFF : 0xFF8A92A0, false);
        }
        g.fill(0, TOP_TAB_H, width, TOP_TAB_H + 1, 0x30FFFFFF);
    }

    // ---------------- 输入 ----------------

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (my < TOP_TAB_H) {
            int totalW = width - PAD * 2;
            int perTab = totalW / TOP_LABELS.length;
            for (int i = 0; i < TOP_LABELS.length; i++) {
                int x = PAD + i * perTab;
                int w = (i == TOP_LABELS.length - 1) ? (totalW - i * perTab) : perTab;
                if (MenuTheme.inBounds(mx, my, x, 0, w, TOP_TAB_H)) {
                    if (i != topTab) { currentPage().flushPending(); }
                    topTab = i;
                    subTab = 0;
                    return true;
                }
            }
            return true;
        }
        if (topTab != TOP_OTHER && subHitThisFrame >= 0) {
            int y = TOP_TAB_H + 4;
            if (my >= y && my < y + SubTabBar.H) {
                if (subHitThisFrame != subTab) { currentPage().flushPending(); }
                subTab = subHitThisFrame;
                return true;
            }
        }
        if (saveBar != null && saveBar.mouseClicked(mx, my, width, height)) {
            if (!remoteEditable) { MenuPermissions.showDeniedMessage(); return true; }
            ConfigPage page = currentPage();
            page.flushPending();
            page.save();
            ConfigManager.getInstance().save();
            var p = Minecraft.getInstance().player;
            if (p != null) p.displayClientMessage(Component.literal("§a已保存"), true);
            return true;
        }
        int contentY = TOP_TAB_H + (topTab != TOP_OTHER ? SubTabBar.H + 4 : 0);
        int contentH = height - contentY - (currentPage().canSave() ? SaveBar.HEIGHT : 0);
        if (my >= contentY && my < contentY + contentH) {
            return currentPage().mouseClicked(mx, my, btn, PAD, contentY, width - PAD * 2, contentH);
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        int contentY = TOP_TAB_H + (topTab != TOP_OTHER ? SubTabBar.H + 4 : 0);
        int contentH = height - contentY - (currentPage().canSave() ? SaveBar.HEIGHT : 0);
        return currentPage().mouseDragged(mx, my, btn, dx, dy, PAD, contentY, width - PAD * 2, contentH);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        int contentY = TOP_TAB_H + (topTab != TOP_OTHER ? SubTabBar.H + 4 : 0);
        int contentH = height - contentY - (currentPage().canSave() ? SaveBar.HEIGHT : 0);
        return currentPage().mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int contentY = TOP_TAB_H + (topTab != TOP_OTHER ? SubTabBar.H + 4 : 0);
        int contentH = height - contentY - (currentPage().canSave() ? SaveBar.HEIGHT : 0);
        return currentPage().mouseScrolled(mx, my, sx, sy, PAD, contentY, width - PAD * 2, contentH);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (currentPage().keyPressed(key, scan, mod)) return true;
        if (key == 256) { onClose(); return true; }
        return super.keyPressed(key, scan, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (currentPage().charTyped(ch, mod)) return true;
        return super.charTyped(ch, mod);
    }

    @Override
    public void onClose() {
        currentPage().flushPending();
        saveConfigNow();
        Minecraft.getInstance().setScreen(parent);
    }
}
