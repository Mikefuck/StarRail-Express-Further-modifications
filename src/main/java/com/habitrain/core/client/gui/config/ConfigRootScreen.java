package com.habitrain.core.client.gui.config;

import com.habitrain.core.client.gui.LiveConfigAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 配置中心主入口 — 顶部 Tab 导航 + 内容区域切换。
 * ModMenu 打开此界面，由它分发到任务配置 / 小游戏 / 全局设置 / 投票设置 / 环境设置子 Tab。
 */
public class ConfigRootScreen extends Screen {

    public static final int TAB_TASKS = 0;
    public static final int TAB_MINIGAMES = 1;
    public static final int TAB_GLOBAL = 2;
    public static final int TAB_VOTE = 3;
    public static final int TAB_ENV = 4;
    public static final int TAB_ROLE_OVERRIDES = 5;

    private static final String[] TAB_LABELS = {"任务配置", "小游戏", "全局设置", "投票设置", "环境设置", "角色覆盖"};
    private static final int[] TAB_ACCENTS = {
            0xFF57C6D6, 0xFFD4A55A, 0xFF8B6B47, 0xFF7C9CFF, 0xFF55C28A, 0xFFD45A5A
    };

    private static final int TAB_H = 28;
    private static final int PAD = 8;

    private final Screen parent;
    private final boolean remoteEditable;

    private int selectedTab = TAB_TASKS;

    private TaskTabScreen taskTab;
    private MinigameTabScreen minigameTab;
    private GlobalTabScreen globalTab;
    private VoteTabScreen voteTab;
    private EnvironmentTabScreen envTab;
    private RoleOverrideTabScreen roleOverrideTab;

    private int[] tabX;
    private int[] tabW;

    public ConfigRootScreen(Screen parent) {
        super(Component.literal("哈比列车核心 — 配置中心"));
        this.parent = parent;
        this.remoteEditable = LiveConfigAccess.canEditRemoteConfigs();
    }

    /** Open config center directly on the vote/map-pool tab (used by lottery hub bridge). */
    public static ConfigRootScreen openVote(Screen parent) {
        ConfigRootScreen screen = new ConfigRootScreen(parent);
        screen.selectedTab = TAB_VOTE;
        return screen;
    }

    @Override
    protected void init() {
        super.init();
        // 懒初始化子 Tab (S10-018)：只在首次 init 时创建，缓存实例避免重建
        if (taskTab == null) {
            taskTab = new TaskTabScreen(this, font, remoteEditable);
        }
        if (minigameTab == null) {
            minigameTab = new MinigameTabScreen(this, font, remoteEditable);
        }
        if (globalTab == null) {
            globalTab = new GlobalTabScreen(this, font, remoteEditable);
        }
        if (voteTab == null) {
            voteTab = new VoteTabScreen(this, font, remoteEditable);
        }
        if (envTab == null) {
            envTab = new EnvironmentTabScreen(this, font, remoteEditable);
        }
        if (roleOverrideTab == null) {
            roleOverrideTab = new RoleOverrideTabScreen(this, font, remoteEditable);
        }

        // 计算 Tab 宽度（等分）
        int totalW = width - PAD * 2;
        int perTab = totalW / TAB_LABELS.length;
        tabX = new int[TAB_LABELS.length];
        tabW = new int[TAB_LABELS.length];
        for (int i = 0; i < TAB_LABELS.length; i++) {
            tabX[i] = PAD + i * perTab;
            tabW[i] = (i == TAB_LABELS.length - 1) ? (totalW - i * perTab) : perTab;
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        SharedGuiKit.drawBackdrop(g, width, height, 0xFF57C6D6);

        // 顶部 Tab 栏
        drawTabs(g, mx, my);

        // 内容区域
        int contentY = TAB_H + PAD;
        int contentH = height - contentY - PAD;
        g.enableScissor(0, contentY, width, contentY + contentH);
        switch (selectedTab) {
            case TAB_TASKS -> taskTab.render(g, mx, my, delta, PAD, contentY, width - PAD, contentH);
            case TAB_MINIGAMES -> minigameTab.render(g, mx, my, delta, PAD, contentY, width - PAD, contentH);
            case TAB_GLOBAL -> globalTab.render(g, mx, my, delta, PAD, contentY, width - PAD, contentH);
            case TAB_VOTE -> voteTab.render(g, mx, my, delta, PAD, contentY, width - PAD, contentH);
            case TAB_ENV -> envTab.render(g, mx, my, delta, PAD, contentY, width - PAD, contentH);
            case TAB_ROLE_OVERRIDES -> roleOverrideTab.render(g, mx, my, delta, PAD, contentY, width - PAD, contentH);
        }
        g.disableScissor();

        // 权限提示
        if (!remoteEditable) {
            g.drawString(font, Component.literal("§c只读模式：联机服务器中仅 OP 可修改配置"),
                    PAD, height - 12, 0xFF5555, false);
        }
    }

    private void drawTabs(GuiGraphics g, int mx, int my) {
        for (int i = 0; i < TAB_LABELS.length; i++) {
            boolean selected = i == selectedTab;
            boolean hover = SharedGuiKit.inBounds(mx, my, tabX[i], 0, tabW[i], TAB_H);
            int bg = selected ? 0xFF1B222B : (hover ? 0xFF1A1F26 : 0xFF141820);
            g.fill(tabX[i], 0, tabX[i] + tabW[i], TAB_H, bg);
            // 底部高亮条
            g.fill(tabX[i], TAB_H - 2, tabX[i] + tabW[i], TAB_H,
                    selected ? TAB_ACCENTS[i] : 0x20FFFFFF);
            // 文字
            int textColor = selected ? 0xFFFFFFFF : 0xFF8A92A0;
            int textW = font.width(TAB_LABELS[i]);
            int textX = tabX[i] + (tabW[i] - textW) / 2;
            g.drawString(font, TAB_LABELS[i], textX, (TAB_H - font.lineHeight) / 2, textColor, false);
        }
        // Tab 栏底部分隔线
        g.fill(0, TAB_H, width, TAB_H + 1, 0x30FFFFFF);
    }

    /** Flush vote-tab text fields when leaving the tab (toggles already persist immediately). */
    private void flushVoteTabIfLeaving(int newTab) {
        if (selectedTab == TAB_VOTE && newTab != TAB_VOTE && voteTab != null) {
            voteTab.flushPendingFields();
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // Tab 切换
        if (my < TAB_H) {
            for (int i = 0; i < TAB_LABELS.length; i++) {
                if (SharedGuiKit.inBounds(mx, my, tabX[i], 0, tabW[i], TAB_H)) {
                    flushVoteTabIfLeaving(i);
                    selectedTab = i;
                    return true;
                }
            }
        }
        // 分发给子 Tab
        int contentY = TAB_H + PAD;
        int contentH = height - contentY - PAD;
        if (my >= contentY && my < contentY + contentH) {
            switch (selectedTab) {
                case TAB_TASKS -> { return taskTab.mouseClicked(mx, my, btn, PAD, contentY, width - PAD, contentH); }
                case TAB_MINIGAMES -> { return minigameTab.mouseClicked(mx, my, btn, PAD, contentY, width - PAD, contentH); }
                case TAB_GLOBAL -> { return globalTab.mouseClicked(mx, my, btn, PAD, contentY, width - PAD, contentH); }
                case TAB_VOTE -> { return voteTab.mouseClicked(mx, my, btn, PAD, contentY, width - PAD, contentH); }
                case TAB_ENV -> { return envTab.mouseClicked(mx, my, btn, PAD, contentY, width - PAD, contentH); }
                case TAB_ROLE_OVERRIDES -> { return roleOverrideTab.mouseClicked(mx, my, btn, PAD, contentY, width - PAD, contentH); }
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        int contentY = TAB_H + PAD;
        int contentH = height - contentY - PAD;
        switch (selectedTab) {
            case TAB_TASKS -> { return taskTab.mouseDragged(mx, my, btn, dx, dy, PAD, contentY, width - PAD, contentH); }
            case TAB_MINIGAMES -> { return minigameTab.mouseDragged(mx, my, btn, dx, dy, PAD, contentY, width - PAD, contentH); }
            case TAB_GLOBAL -> { return globalTab.mouseDragged(mx, my, btn, dx, dy, PAD, contentY, width - PAD, contentH); }
            case TAB_VOTE -> { return voteTab.mouseDragged(mx, my, btn, dx, dy, PAD, contentY, width - PAD, contentH); }
            case TAB_ENV -> { return envTab.mouseDragged(mx, my, btn, dx, dy, PAD, contentY, width - PAD, contentH); }
        }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (selectedTab == TAB_GLOBAL && globalTab != null && globalTab.mouseReleased(mx, my, btn)) {
            return true;
        }
        if (selectedTab == TAB_VOTE && voteTab != null && voteTab.mouseReleased(mx, my, btn)) {
            return true;
        }
        if (selectedTab == TAB_ENV && envTab != null && envTab.mouseReleased(mx, my, btn)) {
            return true;
        }
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int contentY = TAB_H + PAD;
        int contentH = height - contentY - PAD;
        switch (selectedTab) {
            case TAB_TASKS -> { return taskTab.mouseScrolled(mx, my, scrollX, scrollY, PAD, contentY, width - PAD, contentH); }
            case TAB_MINIGAMES -> { return minigameTab.mouseScrolled(mx, my, scrollX, scrollY, PAD, contentY, width - PAD, contentH); }
            case TAB_GLOBAL -> { return globalTab.mouseScrolled(mx, my, scrollX, scrollY, PAD, contentY, width - PAD, contentH); }
            case TAB_VOTE -> { return voteTab.mouseScrolled(mx, my, scrollX, scrollY, PAD, contentY, width - PAD, contentH); }
            case TAB_ENV -> { return envTab.mouseScrolled(mx, my, scrollX, scrollY, PAD, contentY, width - PAD, contentH); }
            case TAB_ROLE_OVERRIDES -> { return roleOverrideTab.mouseScrolled(mx, my, scrollX, scrollY, PAD, contentY, width - PAD, contentH); }
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        switch (selectedTab) {
            case TAB_TASKS -> { if (taskTab.keyPressed(key, scan, mod)) return true; }
            case TAB_MINIGAMES -> { if (minigameTab.keyPressed(key, scan, mod)) return true; }
            case TAB_GLOBAL -> { if (globalTab.keyPressed(key, scan, mod)) return true; }
            case TAB_VOTE -> { if (voteTab.keyPressed(key, scan, mod)) return true; }
            case TAB_ENV -> { if (envTab.keyPressed(key, scan, mod)) return true; }
            case TAB_ROLE_OVERRIDES -> {}
        }
        if (key == 256) { // ESC
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        switch (selectedTab) {
            case TAB_TASKS -> { if (taskTab.charTyped(ch, mod)) return true; }
            case TAB_MINIGAMES -> { if (minigameTab.charTyped(ch, mod)) return true; }
            case TAB_GLOBAL -> { if (globalTab.charTyped(ch, mod)) return true; }
            case TAB_VOTE -> { if (voteTab.charTyped(ch, mod)) return true; }
            case TAB_ENV -> { if (envTab.charTyped(ch, mod)) return true; }
            case TAB_ROLE_OVERRIDES -> {}
        }
        return super.charTyped(ch, mod);
    }

    public Screen getParent() { return parent; }

    @Override
    public void onClose() {
        // Match GlobalTab: toggles already dirty; flush vote text fields before root save
        if (voteTab != null) {
            voteTab.flushPendingFields();
        }
        ConfigManager_save();
        Minecraft.getInstance().setScreen(parent);
    }

    private void ConfigManager_save() {
        com.habitrain.core.config.ConfigManager.getInstance().save();
    }
}