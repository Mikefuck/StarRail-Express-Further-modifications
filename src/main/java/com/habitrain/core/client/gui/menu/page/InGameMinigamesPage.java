package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.*;
import com.habitrain.core.client.gui.menu.ui.*;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MinigameConfigEntry;
import io.wifi.starrailexpress.content.minigame.QuestMinigame;
import io.wifi.starrailexpress.content.minigame.QuestMinigames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 游戏内·小游戏：SRE QuestMinigames 卡片网格 + 搜索 + 编辑入口。 */
public class InGameMinigamesPage implements ConfigPage {

    private static final int CARD_H = 56;
    private static final int CARD_GAP = 6;
    private static final int HEADER_H = 28;
    private static final int COLUMNS = 2;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private final List<QuestMinigame> minigames = new ArrayList<>();
    private final EditBox searchBox;
    private String searchText = "";
    private ScrollArea area;
    private ConfigManager snapshot;

    private final List<CardHit> cardHits = new ArrayList<>();
    private record CardHit(QuestMinigame mg, int x, int y, int w, int h,
                           int toggleX, int toggleY, int toggleW, int toggleH,
                           int editX, int editY, int editW, int editH) {}

    public InGameMinigamesPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        this.searchBox = new EditBox(font, 0, 0, 10, 14, Component.literal(""));
        this.searchBox.setMaxLength(64);
        this.searchBox.setHint(Component.literal("搜索小游戏..."));
        this.searchBox.setResponder(t -> { searchText = t == null ? "" : t.trim().toLowerCase(Locale.ROOT); });
        this.area = new ScrollArea(0, 0, 0, 0);
        try { minigames.addAll(QuestMinigames.getAll()); } catch (Throwable ignored) {}
    }

    private boolean sreAvailable() { return !minigames.isEmpty(); }

    @Override public boolean canSave() { return true; }
    @Override public void save() {}
    @Override public void flushPending() {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        snapshot = ConfigManager.getInstance();
        if (!sreAvailable()) {
            g.drawString(font, Component.literal("§c未检测到 SRE（星穹列车）模组，小游戏功能不可用"),
                    x + 8, y + 10, 0xFF5555, false);
            return;
        }
        searchBox.setX(x + 6); searchBox.setY(y + 6); searchBox.setWidth(w - 12);
        searchBox.render(g, mx, my, delta);

        int listY = y + HEADER_H;
        int listH = h - HEADER_H;
        area = new ScrollArea(x, listY, w, listH);
        cardHits.clear();
        g.enableScissor(x, listY, x + w, listY + listH);

        int cy = area.getContentY();
        int colW = (w - CARD_GAP) / COLUMNS;
        int filtered = 0, enabled = 0, idx = 0;
        for (QuestMinigame mg : minigames) {
            String dn = mg.displayName() != null ? mg.displayName().getString() : mg.id();
            if (!matches(dn, mg.id())) continue;
            filtered++;
            MinigameConfigEntry cfg = snapshot.getMinigameConfig(mg.id());
            boolean on = cfg == null || cfg.enabled;
            if (on) enabled++;
            int col = idx % COLUMNS, row = idx / COLUMNS;
            int cx = x + col * (colW + CARD_GAP);
            int cardY = cy + row * (CARD_H + CARD_GAP);
            drawCard(g, mg, dn, cfg, cx, cardY, colW, mx, my);
            idx++;
        }
        int totalRows = (idx + COLUMNS - 1) / COLUMNS;
        area.setContentHeight(totalRows * (CARD_H + CARD_GAP));
        area.render(g);
        g.disableScissor();

        String stats = "§7" + enabled + "/" + filtered + " 已启用 §8| §7总计 " + minigames.size();
        g.drawString(font, stats, x + w - font.width(stats) - 8, y + 10, 0xFF888888, false);
    }

    private void drawCard(GuiGraphics g, QuestMinigame mg, String dn, MinigameConfigEntry cfg,
                          int x, int y, int w, int mx, int my) {
        boolean on = cfg == null || cfg.enabled;
        int color = cfg != null ? cfg.instinctColor : MenuTheme.accentFor(mg.id());
        boolean hover = MenuTheme.inBounds(mx, my, x, y, w, CARD_H);
        g.fill(x, y, x + w, y + CARD_H, hover ? MenuTheme.BG_ROW_HOVER : MenuTheme.BG_ROW);
        MenuTheme.drawAccentStripe(g, x, y, CARD_H, color);
        String name = dn.length() > 22 ? dn.substring(0, 20) + "…" : dn;
        g.drawString(font, name, x + 8, y + 6, MenuTheme.TEXT_PRIMARY, false);
        g.drawString(font, "§7" + mg.id(), x + 8, y + 20, MenuTheme.TEXT_SECONDARY, false);
        StringBuilder reward = new StringBuilder();
        if (cfg != null) {
            if (cfg.hasGoldReward) reward.append("§6金").append(cfg.goldReward).append(" ");
            if (cfg.hasEmotionReward) reward.append("§b情").append(String.format("%.1f", cfg.emotionReward));
        }
        if (reward.length() > 0) g.drawString(font, reward.toString(), x + 8, y + 34, 0xFFAAAAAA, false);

        int toggleX = x + w - 60, toggleW = 40;
        PillToggle.render(g, font, toggleX, y + 6, toggleW, 14, on, "§a启用", "§c停用");
        int editX = x + w - 60, editW = 40;
        g.fill(editX, y + 28, editX + editW, y + 42, MenuTheme.BG_EDIT);
        g.drawString(font, "§e编辑", editX + (editW - font.width("编辑")) / 2, y + 30, 0xFFFFFFFF, false);
        cardHits.add(new CardHit(mg, x, y, w, CARD_H, toggleX, y + 6, toggleW, 14, editX, y + 28, editW, 14));
    }

    private boolean matches(String dn, String id) {
        if (searchText.isEmpty()) return true;
        return (dn + " " + id).toLowerCase(Locale.ROOT).contains(searchText);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (!sreAvailable()) return false;
        if (searchBox.mouseClicked(mx, my, btn)) return true;
        for (CardHit hit : cardHits) {
            if (my < hit.y() || my >= hit.y() + hit.h()) continue;
            if (PillToggle.hit(mx, my, hit.toggleX(), hit.toggleY(), hit.toggleW(), hit.toggleH())) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                MinigameConfigEntry cfg = ConfigManager.getInstance().getMinigameConfig(hit.mg().id());
                if (cfg == null) cfg = MinigameConfigEntry.createDefault();
                cfg.enabled = !cfg.enabled;
                ConfigManager.getInstance().setMinigameConfig(hit.mg().id(), cfg);
                ConfigManager.getInstance().applyMinigameEnforcement(Minecraft.getInstance().getSingleplayerServer());
                return true;
            }
            if (PillToggle.hit(mx, my, hit.editX(), hit.editY(), hit.editW(), hit.editH())) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                MinigameConfigEntry cfg = ConfigManager.getInstance().getMinigameConfig(hit.mg().id());
                if (cfg == null) cfg = MinigameConfigEntry.createDefault();
                ConfigManager.getInstance().putMinigameConfig(hit.mg().id(), cfg);
                Minecraft.getInstance().setScreen(new MinigameEditScreen(root, hit.mg(), cfg));
                return true;
            }
        }
        return area.mouseClicked(mx, my, btn);
    }

    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) { return area.mouseDragged(my); }
    @Override public boolean mouseReleased(double mx, double my, int btn) { return area.mouseReleased(); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) { return area.mouseScrolled(sy); }
    @Override public boolean keyPressed(int key, int scan, int mod) { return searchBox.isFocused() && searchBox.keyPressed(key, scan, mod); }
    @Override public boolean charTyped(char ch, int mod) { return searchBox.isFocused() && searchBox.charTyped(ch, mod); }
}
