package com.habitrain.core.client.gui.menu;

import com.habitrain.core.client.gui.menu.page.InGameBalancePage;
import com.habitrain.core.client.gui.menu.page.InGameEnvPage;
import com.habitrain.core.client.gui.menu.page.InGameMinigamesPage;
import com.habitrain.core.client.gui.menu.page.ModeRolesPage;
import com.habitrain.core.client.gui.menu.page.ModeTasksPage;
import com.habitrain.core.client.gui.menu.page.OtherPage;
import com.habitrain.core.client.gui.menu.page.OutGameLobbyEnvPage;
import com.habitrain.core.client.gui.menu.page.OutGameMvpAnimationsPage;
import com.habitrain.core.client.gui.menu.page.OutGameShaderPage;
import com.habitrain.core.client.gui.menu.page.OutGameVotePage;
import com.habitrain.core.client.gui.menu.ui.SaveBar;
import com.habitrain.core.client.gui.menu.ui.SubTabBar;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.network.ConfigUpdateScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 哈比列车控制台根屏。
 *
 * <p>一级分类采用永久可见的左侧导航，二级分类和页面工具区位于右侧。页面切换、
 * 保存、只读权限和桥接契约保持不变；这个类只负责新的信息架构与统一视觉外壳。
 */
public class ConfigMenuScreen extends Screen {

    public static final int TOP_IN_GAME = 0;
    public static final int TOP_OUT_GAME = 1;
    public static final int TOP_MODE = 2;
    public static final int TOP_OTHER = 3;

    private static final String[] TOP_LABELS = {"游戏内", "游戏外", "游戏模式", "其他"};
    private static final String[] TOP_HINTS = {"对局体验", "大厅与投票", "任务与角色", "扩展设置"};
    private static final int[] TOP_ACCENTS = {
        MenuTheme.ACCENT_MINT, MenuTheme.ACCENT_BLUE,
        MenuTheme.ACCENT_AMBER, MenuTheme.TEXT_SECONDARY
    };
    private static final String[][] SUB_LABELS = {
        {"小游戏", "数值平衡", "环境"},
        {"投票", "大厅环境", "光影白名单", "MVP 动画"},
        {"任务配置", "角色覆盖", "角色扩展"},
        {}
    };
    private static final String[][] PAGE_HINTS = {
        {"管理小游戏任务池、奖励与出现条件", "调整核心数值与全局对局开关", "控制对局、结算和动态天气"},
        {"配置模式和地图投票流程", "设置大厅时间、天气与雾效", "限制服务器允许使用的光影包", "配置结算页玩家动作与随机规则"},
        {"按模式管理任务并编辑奖励与地图", "管理角色替换、调整和冲突状态", "管理 v2 角色扩展 provider/entry 与冲突裁决"},
        {"兼容、耐久与服务端自动行为"}
    };

    public enum AccessMode {
        FULL,
        TASK_SETTINGS_ONLY,
        MAP_VOTE_ONLY
    }

    private static final int HEADER_H = 50;
    private static final int PAD = 10;
    private static final int NAV_TOP = 56;
    private static final int NAV_ITEM_H = 38;
    private static final int NAV_GAP = 4;

    private final Screen parent;
    private final AccessMode accessMode;
    private final boolean remoteEditable;
    private int topTab = TOP_IN_GAME;
    private int subTab;

    private ConfigPage[][] pages;
    private final SubTabBar[] subBars = new SubTabBar[4];
    private SaveBar saveBar;
    private int subHitThisFrame = -1;

    public ConfigMenuScreen(Screen parent) {
        this(parent, AccessMode.FULL);
    }

    private ConfigMenuScreen(Screen parent, boolean taskSettingsOnly) {
        this(parent, taskSettingsOnly ? AccessMode.TASK_SETTINGS_ONLY : AccessMode.FULL);
    }

    private ConfigMenuScreen(Screen parent, AccessMode accessMode) {
        super(Component.literal("哈比列车核心 — 控制台"));
        this.parent = parent;
        this.accessMode = accessMode;
        ConfigUpdateContext.setCurrentScope(configUpdateScope());
        this.remoteEditable = MenuPermissions.canEditRemoteConfigs(configUpdateScope());
        this.saveBar = new SaveBar(remoteEditable);
        for (int i = 0; i < SUB_LABELS.length; i++) {
            if (SUB_LABELS[i].length > 0) subBars[i] = new SubTabBar(SUB_LABELS[i], TOP_ACCENTS[i]);
        }
    }

    /** 抽奖桥接：直接打开投票页。 */
    public static ConfigMenuScreen openVote(Screen parent) {
        ConfigMenuScreen screen = new ConfigMenuScreen(parent);
        screen.topTab = TOP_OUT_GAME;
        screen.subTab = 0;
        return screen;
    }

    /** SRE 背包入口：只允许访问游戏模式/任务配置页。 */
    public static ConfigMenuScreen openTaskSettings(Screen parent) {
        ConfigMenuScreen screen = new ConfigMenuScreen(parent, AccessMode.TASK_SETTINGS_ONLY);
        screen.topTab = TOP_MODE;
        screen.subTab = 0;
        return screen;
    }

    /** SRE 背包入口：地图设置，只允许访问地图轮换与投票。 */
    public static ConfigMenuScreen openMapSettings(Screen parent) {
        ConfigMenuScreen screen = new ConfigMenuScreen(parent, AccessMode.MAP_VOTE_ONLY);
        screen.topTab = TOP_OUT_GAME;
        screen.subTab = 0;
        return screen;
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
            new OutGameShaderPage(this, font, remoteEditable),
            new OutGameMvpAnimationsPage(this, font, remoteEditable)
        };
        pages[TOP_MODE] = new ConfigPage[]{
            new ModeTasksPage(this, font, remoteEditable),
            new ModeRolesPage(this, font, remoteEditable),
            new com.habitrain.core.client.gui.menu.page.RoleExtensionsPage(this, font, remoteEditable)
        };
        pages[TOP_OTHER] = new ConfigPage[]{new OtherPage(this, font, remoteEditable)};
        return pages;
    }

    private ConfigPage currentPage() {
        ConfigPage[][] all = ensurePages();
        if (accessMode == AccessMode.TASK_SETTINGS_ONLY) return all[TOP_MODE][0];
        if (accessMode == AccessMode.MAP_VOTE_ONLY) return all[TOP_OUT_GAME][0];
        return topTab == TOP_OTHER ? all[TOP_OTHER][0] : all[topTab][subTab];
    }

    public Screen getParent() {
        return parent;
    }

    public boolean canEdit() {
        return remoteEditable;
    }

    public void saveConfigNow() {
        ConfigManager.getInstance().save();
    }

    /** 角色覆盖配置同步后刷新列表。 */
    public void refreshRoleOverrideTab() {
        ConfigPage[][] all = ensurePages();
        if (all[TOP_MODE][1] instanceof ModeRolesPage roles) roles.rebuildRows();
    }

    /** Dedicated map profile editor changed values also shown by the inline vote page. */
    public void refreshVoteMapEntry(String mapId) {
        ConfigPage[][] all = ensurePages();
        if (all[TOP_OUT_GAME][0] instanceof OutGameVotePage votePage) {
            votePage.refreshMapEntry(mapId);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        if (!isScreenAllowed()) {
            MenuTheme.drawBackdrop(g, width, height, MenuTheme.DANGER);
            drawLockedOverlay(g);
            return;
        }
        int accent = activeAccent();
        MenuTheme.drawBackdrop(g, width, height, accent);

        int navW = navWidth();
        drawSidebar(g, mx, my, navW);
        drawWorkspaceHeader(g, navW, accent);

        ConfigPage page = currentPage();
        int contentX = navW + PAD;
        int contentY = HEADER_H;
        int contentW = Math.max(1, width - contentX - PAD);

        if (topTab != TOP_OTHER) {
            subHitThisFrame = accessMode == AccessMode.TASK_SETTINGS_ONLY
                    ? renderTaskSettingsOnlySubBar(g, contentX, contentW, mx, my)
                    : accessMode == AccessMode.MAP_VOTE_ONLY
                            ? renderMapVoteOnlySubBar(g, contentX, contentW, mx, my)
                            : subBars[topTab].render(g, font, contentX, HEADER_H, contentW, subTab, mx, my);
            contentY += SubTabBar.H + 7;
        } else {
            subHitThisFrame = -1;
            contentY += 5;
        }

        boolean showSaveBar = page.canSave();
        int contentH = Math.max(1, height - contentY - PAD - (showSaveBar ? SaveBar.HEIGHT : 0));
        MenuTheme.panel(g, contentX, contentY, contentW, contentH);

        g.enableScissor(contentX + 1, contentY + 1, contentX + contentW - 1, contentY + contentH - 1);
        page.render(g, mx, my, delta, contentX + 1, contentY + 1, contentW - 2, contentH - 2);
        g.disableScissor();

        if (showSaveBar) {
            saveBar.render(g, font, contentX, contentW, height, accent, mx, my);
        }
    }

    private void drawSidebar(GuiGraphics g, int mx, int my, int navW) {
        g.fill(0, 0, navW, height, MenuTheme.BG_SIDEBAR);
        g.fill(navW - 1, 0, navW, height, MenuTheme.BORDER);

        g.drawString(font, "HABITRAIN", 12, 13, MenuTheme.TEXT_PRIMARY, false);
        g.drawString(font, "CORE / CONTROL", 12, 28, MenuTheme.TEXT_DIM, false);
        g.fill(12, 43, navW - 12, 44, MenuTheme.BORDER);

        for (int i = 0; i < TOP_LABELS.length; i++) {
            int y = NAV_TOP + i * (NAV_ITEM_H + NAV_GAP);
            boolean enabled;
            if (accessMode == AccessMode.TASK_SETTINGS_ONLY) {
                enabled = (i == TOP_MODE);
            } else if (accessMode == AccessMode.MAP_VOTE_ONLY) {
                enabled = (i == TOP_OUT_GAME);
            } else {
                enabled = true;
            }
            boolean selected = enabled && i == topTab;
            boolean hover = enabled && MenuTheme.inBounds(mx, my, 7, y, navW - 14, NAV_ITEM_H);
            if (selected || hover) {
                g.fill(7, y, navW - 7, y + NAV_ITEM_H,
                        selected ? MenuTheme.BG_ROW_SELECTED : MenuTheme.BG_ROW_HOVER);
                MenuTheme.outline(g, 7, y, navW - 14, NAV_ITEM_H,
                        selected ? MenuTheme.withAlpha(TOP_ACCENTS[i], 0x75) : MenuTheme.BORDER_SOFT);
            } else if (!enabled) {
                g.fill(7, y, navW - 7, y + NAV_ITEM_H,
                        MenuTheme.withAlpha(MenuTheme.BG_ROW_HOVER, 0x55));
                MenuTheme.outline(g, 7, y, navW - 14, NAV_ITEM_H, MenuTheme.BORDER_SOFT);
            }
            if (selected) g.fill(7, y, 10, y + NAV_ITEM_H, TOP_ACCENTS[i]);

            String index = "0" + (i + 1);
            g.drawString(font, index, 15, y + 8, selected ? TOP_ACCENTS[i] : MenuTheme.TEXT_DIM, false);
            g.drawString(font, TOP_LABELS[i], 38, y + 7,
                    selected ? MenuTheme.TEXT_PRIMARY : MenuTheme.TEXT_SECONDARY, false);
            g.drawString(font, TOP_HINTS[i], 38, y + 21, MenuTheme.TEXT_DIM, false);
        }

        String state = remoteEditable ? "● 可编辑" : "● 只读";
        int stateColor = remoteEditable ? MenuTheme.ACCENT_MINT : MenuTheme.DANGER;
        g.drawString(font, state, 12, height - 28, stateColor, false);
        g.drawString(font, "ESC  返回", 12, height - 14, MenuTheme.TEXT_DIM, false);
    }

    private int renderTaskSettingsOnlySubBar(GuiGraphics g, int x, int w, int mx, int my) {
        int tabW = Math.max(60, Math.min(132, w));
        boolean hover = MenuTheme.inBounds(mx, my, x, HEADER_H, tabW, SubTabBar.H);
        MenuTheme.chip(g, font, SUB_LABELS[TOP_MODE][0], x, HEADER_H,
                tabW, SubTabBar.H, TOP_ACCENTS[TOP_MODE], true);
        if (hover) MenuTheme.outline(g, x, HEADER_H, tabW, SubTabBar.H, TOP_ACCENTS[TOP_MODE]);
        return -1;
    }

    private int renderMapVoteOnlySubBar(GuiGraphics g, int x, int w, int mx, int my) {
        int tabW = Math.max(60, Math.min(132, w));
        boolean hover = MenuTheme.inBounds(mx, my, x, HEADER_H, tabW, SubTabBar.H);
        MenuTheme.chip(g, font, SUB_LABELS[TOP_OUT_GAME][0], x, HEADER_H,
                tabW, SubTabBar.H, TOP_ACCENTS[TOP_OUT_GAME], true);
        if (hover) MenuTheme.outline(g, x, HEADER_H, tabW, SubTabBar.H, TOP_ACCENTS[TOP_OUT_GAME]);
        return -1;
    }

    /** 门控未授权时覆盖整个场景的「当前为未授权的访问」提示。 */
    private void drawLockedOverlay(GuiGraphics g) {
        g.fill(0, 0, width, height, 0xC0000000);
        int boxW = Math.min(400, width - 40);
        int boxH = 100;
        int boxX = (width - boxW) / 2;
        int boxY = (height - boxH) / 2;
        MenuTheme.panel(g, boxX, boxY, boxW, boxH);
        String title = "当前为未授权的访问";
        String sub = "服务器管理员未授予你 Mod 菜单编辑权限";
        String hint = "请联系管理员，或由后台控制台执行 /habi_api menugate add <你的名字>";
        g.drawString(font, title, boxX + (boxW - font.width(title)) / 2, boxY + 28, MenuTheme.DANGER, false);
        g.drawString(font, sub, boxX + (boxW - font.width(sub)) / 2, boxY + 52, MenuTheme.TEXT_SECONDARY, false);
        g.drawString(font, hint, boxX + (boxW - font.width(hint)) / 2, boxY + 68, MenuTheme.TEXT_DIM, false);
        String esc = "ESC  返回";
        g.drawString(font, esc, width / 2 - font.width(esc) / 2, height - 18, MenuTheme.TEXT_DIM, false);
    }

    private void drawWorkspaceHeader(GuiGraphics g, int navW, int accent) {
        int x = navW + PAD;
        String label = activePageLabel();
        g.drawString(font, label, x, 11, MenuTheme.TEXT_PRIMARY, false);
        g.fill(x, 26, x + 20, 28, accent);
        g.drawString(font, activePageHint(), x + 28, 22, MenuTheme.TEXT_SECONDARY, false);

        String breadcrumb = TOP_LABELS[topTab] + (topTab == TOP_OTHER ? "" : "  /  " + label);
        g.drawString(font, breadcrumb, width - PAD - font.width(breadcrumb), 11,
                MenuTheme.TEXT_DIM, false);
    }

    private String activePageLabel() {
        if (accessMode == AccessMode.TASK_SETTINGS_ONLY) return "任务点设置";
        if (topTab == TOP_OTHER) return "其他设置";
        return SUB_LABELS[topTab][subTab];
    }

    private String activePageHint() {
        if (accessMode == AccessMode.TASK_SETTINGS_ONLY) return "仅开放任务点设置，其他设置已锁定";
        if (accessMode == AccessMode.MAP_VOTE_ONLY) return PAGE_HINTS[TOP_OUT_GAME][0];
        if (topTab == TOP_OTHER) return PAGE_HINTS[TOP_OTHER][0];
        return PAGE_HINTS[topTab][subTab];
    }

    private int activeAccent() {
        return TOP_ACCENTS[topTab];
    }

    private int navWidth() {
        return Math.max(108, Math.min(138, width / 5));
    }

    private int contentX() {
        return navWidth() + PAD;
    }

    private int contentY() {
        return HEADER_H + (topTab == TOP_OTHER ? 5 : SubTabBar.H + 7);
    }

    private int contentW() {
        return Math.max(1, width - contentX() - PAD);
    }

    private int contentH() {
        return Math.max(1, height - contentY() - PAD - (currentPage().canSave() ? SaveBar.HEIGHT : 0));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (!isScreenAllowed()) return false;
        int navW = navWidth();
        for (int i = 0; i < TOP_LABELS.length; i++) {
            int y = NAV_TOP + i * (NAV_ITEM_H + NAV_GAP);
            if (MenuTheme.inBounds(mx, my, 7, y, navW - 14, NAV_ITEM_H)) {
                if (accessMode == AccessMode.TASK_SETTINGS_ONLY && i != TOP_MODE) return true;
                if (accessMode == AccessMode.MAP_VOTE_ONLY && i != TOP_OUT_GAME) return true;
                currentPage().flushPending();
                boolean switched = i != topTab;
                topTab = i;
                if (switched) subTab = 0;
                MenuSounds.playClick();
                return true;
            }
        }

        if (topTab != TOP_OTHER && subHitThisFrame >= 0
                && my >= HEADER_H && my < HEADER_H + SubTabBar.H) {
            if (accessMode == AccessMode.TASK_SETTINGS_ONLY && subHitThisFrame != 0) return true;
            if (subHitThisFrame != subTab) currentPage().flushPending();
            subTab = subHitThisFrame;
            MenuSounds.playClick();
            return true;
        }

        if (currentPage().canSave() && saveBar.mouseClicked(mx, my, contentX(), contentW(), height)) {
            MenuSounds.playClick();
            if (!remoteEditable || !MenuPermissions.canEditRemoteConfigs()) {
                MenuPermissions.showDeniedMessage();
                return true;
            }
            ConfigPage page = currentPage();
            page.flushPending();
            page.save();
            ConfigManager.getInstance().save();
            var player = Minecraft.getInstance().player;
            if (player != null) player.displayClientMessage(Component.literal("§a配置已保存"), true);
            return true;
        }

        if (my >= contentY() && my < contentY() + contentH()) {
            return currentPage().mouseClicked(mx, my, btn,
                    contentX() + 1, contentY() + 1, contentW() - 2, contentH() - 2);
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (!isScreenAllowed()) return false;
        return currentPage().mouseDragged(mx, my, btn, dx, dy,
                contentX() + 1, contentY() + 1, contentW() - 2, contentH() - 2);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (!isScreenAllowed()) return false;
        return currentPage().mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!isScreenAllowed()) return false;
        if (MenuTheme.inBounds(mx, my, contentX(), contentY(), contentW(), contentH())) {
            return currentPage().mouseScrolled(mx, my, sx, sy,
                    contentX() + 1, contentY() + 1, contentW() - 2, contentH() - 2);
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (!isScreenAllowed()) {
            if (key == 256) {
                // 未授权时 ESC 只关闭菜单，不能触发 flushPending/saveConfigNow
                Minecraft.getInstance().setScreen(parent);
                return true;
            }
            return false;
        }
        if (currentPage().keyPressed(key, scan, mod)) return true;
        if (key == 256) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (!isScreenAllowed()) return false;
        if (currentPage().charTyped(ch, mod)) return true;
        return super.charTyped(ch, mod);
    }

    @Override
    public void onClose() {
        currentPage().flushPending();
        saveConfigNow();
        ConfigUpdateContext.setCurrentScope(ConfigUpdateScope.FULL_MOD_MENU);
        Minecraft.getInstance().setScreen(parent);
    }

    private ConfigUpdateScope configUpdateScope() {
        return switch (accessMode) {
            case FULL -> ConfigUpdateScope.FULL_MOD_MENU;
            case TASK_SETTINGS_ONLY -> ConfigUpdateScope.BACKPACK_TASKS;
            case MAP_VOTE_ONLY -> ConfigUpdateScope.BACKPACK_MAP_VOTE;
        };
    }

    private boolean isScreenAllowed() {
        return accessMode != AccessMode.FULL || MenuPermissions.canEditRemoteConfigs(ConfigUpdateScope.FULL_MOD_MENU);
    }
}
