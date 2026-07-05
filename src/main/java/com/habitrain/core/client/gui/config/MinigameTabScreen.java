package com.habitrain.core.client.gui.config;

import com.habitrain.core.client.gui.LiveConfigAccess;
import com.habitrain.core.client.gui.SharedGuiConstants;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MinigameConfigEntry;
import io.wifi.starrailexpress.content.minigame.QuestMinigame;
import io.wifi.starrailexpress.content.minigame.QuestMinigames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "小游戏" Tab — SRE QuestMinigames 列表 + 独立配置。
 * 每个小游戏卡片显示色条/名称/id/开关/编辑齿轮。
 */
public class MinigameTabScreen {

    private static final int CARD_W = 240;
    private static final int CARD_H = 56;
    private static final int CARD_GAP = 6;
    private static final int HEADER_H = 28;
    private static final int COLUMNS = 2;

    private final ConfigRootScreen root;
    private final Font font;
    private final boolean editable;

    private List<QuestMinigame> minigames = new ArrayList<>();
    private String searchText = "";
    private EditBox searchBox;

    private double contentScroll = 0;
    private boolean draggingContent = false;
    private double dragStartY = 0;
    private double dragStartScroll = 0;

    private final List<CardHit> cardHits = new ArrayList<>();

    private record CardHit(QuestMinigame minigame, int x, int y, int w, int h, int toggleX, int toggleW, int editX, int editW) {}

    public MinigameTabScreen(ConfigRootScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        loadMinigames();
    }

    private void loadMinigames() {
        minigames.clear();
        try {
            minigames.addAll(QuestMinigames.getAll());
        } catch (Throwable t) {
            // SRE 未安装
        }
    }

    private boolean sreAvailable() { return !minigames.isEmpty(); }

    // ==================== 渲染 ====================

    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        if (!sreAvailable()) {
            g.drawString(font, Component.literal("§c未检测到 SRE（星穹列车）模组，小游戏功能不可用"),
                    x + 8, y + 10, 0xFF5555, false);
            return;
        }

        // 搜索框 + 统计
        if (searchBox == null) {
            searchBox = new EditBox(font, x + 6, y + 6, w - 12, 14, Component.literal(""));
            searchBox.setMaxLength(64);
            searchBox.setHint(Component.literal("搜索小游戏..."));
            searchBox.setResponder(t -> { searchText = t == null ? "" : t.trim().toLowerCase(Locale.ROOT); contentScroll = 0; });
        }
        searchBox.setX(x + 6);
        searchBox.setY(y + 6);
        searchBox.setWidth(w - 12);
        searchBox.render(g, mx, my, delta);

        int listY = y + HEADER_H;
        int listH = h - HEADER_H;
        cardHits.clear();
        g.enableScissor(x, listY, x + w, listY + listH);

        int cy = listY - (int) contentScroll;
        int colW = (w - CARD_GAP) / COLUMNS;
        int filtered = 0;
        int enabled = 0;
        int idx = 0;
        for (QuestMinigame mg : minigames) {
            String displayName = mg.displayName() != null ? mg.displayName().getString() : mg.id();
            if (!matchesSearch(displayName, mg.id())) continue;
            filtered++;
            MinigameConfigEntry cfg = ConfigManager.getInstance().getMinigameConfig(mg.id());
            boolean isOn = cfg == null || cfg.enabled;
            if (isOn) enabled++;
            int col = idx % COLUMNS;
            int row = idx / COLUMNS;
            int cardX = x + col * (colW + CARD_GAP);
            int cardY = cy + row * (CARD_H + CARD_GAP);
            drawCard(g, mg, displayName, cfg, cardX, cardY, colW, mx, my);
            idx++;
        }

        // 滚动条
        int totalRows = (idx + COLUMNS - 1) / COLUMNS;
        int contentH = totalRows * (CARD_H + CARD_GAP);
        int maxScroll = Math.max(0, contentH - listH);
        SharedGuiKit.drawScrollbar(g, x + w - 4, listY, listH, contentScroll, maxScroll, 3);
        g.disableScissor();

        // 统计（搜索框右侧）
        String stats = "§7" + enabled + "/" + filtered + " 已启用 §8| §7总计 " + minigames.size();
        g.drawString(font, stats, x + w - font.width(stats) - 8, y + 10, 0xFF888888, false);
    }

    private void drawCard(GuiGraphics g, QuestMinigame mg, String displayName,
                          MinigameConfigEntry cfg, int x, int y, int w, int mx, int my) {
        boolean enabled = cfg == null || cfg.enabled;
        int color = cfg != null ? cfg.instinctColor : SharedGuiKit.accentFor(mg.id());
        boolean hover = SharedGuiKit.inBounds(mx, my, x, y, w, CARD_H);

        // 卡片背景
        g.fill(x, y, x + w, y + CARD_H, hover ? SharedGuiKit.BG_ROW_HOVER : SharedGuiKit.BG_ROW);
        // 色条
        SharedGuiKit.drawAccentStripe(g, x, y, CARD_H, color);
        // 名称
        String name = displayName.length() > 22 ? displayName.substring(0, 20) + "…" : displayName;
        g.drawString(font, name, x + 8, y + 6, SharedGuiKit.TEXT_PRIMARY, false);
        // id
        g.drawString(font, "§7" + mg.id(), x + 8, y + 20, SharedGuiKit.TEXT_SECONDARY, false);
        // 奖励预览
        StringBuilder reward = new StringBuilder();
        if (cfg != null) {
            if (cfg.goldReward >= 0) reward.append("§6金").append(cfg.goldReward).append(" ");
            if (cfg.emotionReward >= 0f) reward.append("§b情").append(String.format("%.1f", cfg.emotionReward));
        }
        if (reward.length() > 0) {
            g.drawString(font, reward.toString(), x + 8, y + 34, 0xFFAAAAAA, false);
        }

        // 开关
        int toggleX = x + w - 60;
        int toggleW = 40;
        g.fill(toggleX, y + 6, toggleX + toggleW, y + 20, enabled ? 0xFF1B3A2A : 0xFF3A1B1B);
        g.drawString(font, enabled ? "§a启用" : "§c停用", toggleX + 4, y + 8, 0xFFFFFFFF, false);
        // 编辑齿轮
        int editX = x + w - 60;
        int editW = 40;
        g.fill(editX, y + 28, editX + editW, y + 42, 0xFF222B36);
        g.drawString(font, "§e编辑", editX + (editW - font.width("编辑")) / 2, y + 30, 0xFFFFFFFF, false);

        cardHits.add(new CardHit(mg, x, y, w, CARD_H, toggleX, toggleW, editX, editW));
    }

    private boolean matchesSearch(String displayName, String id) {
        if (searchText.isEmpty()) return true;
        return (displayName + " " + id).toLowerCase(Locale.ROOT).contains(searchText);
    }

    // ==================== 交互 ====================

    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (!sreAvailable()) return false;
        if (searchBox != null && searchBox.mouseClicked(mx, my, btn)) return true;
        for (CardHit hit : cardHits) {
            if (my < hit.y() || my >= hit.y() + hit.h()) continue;
            if (mx >= hit.toggleX() && mx < hit.toggleX() + hit.toggleW()) {
                toggleMinigame(hit.minigame());
                return true;
            }
            if (mx >= hit.editX() && mx < hit.editX() + hit.editW()) {
                openMinigameEditor(hit.minigame());
                return true;
            }
        }
        // 内容滚动拖拽
        int listY = y + HEADER_H;
        if (my >= listY) {
            draggingContent = true;
            dragStartY = my;
            dragStartScroll = contentScroll;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        if (draggingContent) {
            contentScroll = Mth.clamp(dragStartScroll + (dragStartY - my), 0, 10000);
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        contentScroll = Mth.clamp(contentScroll - sy * 18, 0, 10000);
        return true;
    }

    public boolean keyPressed(int key, int scan, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.keyPressed(key, scan, mod)) return true;
        return false;
    }

    public boolean charTyped(char ch, int mod) {
        if (searchBox != null && searchBox.isFocused() && searchBox.charTyped(ch, mod)) return true;
        return false;
    }

    private void toggleMinigame(QuestMinigame mg) {
        if (!editable) { LiveConfigAccess.showDeniedMessage(); return; }
        MinigameConfigEntry cfg = ConfigManager.getInstance().getMinigameConfig(mg.id());
        if (cfg == null) cfg = MinigameConfigEntry.createDefault();
        cfg.enabled = !cfg.enabled;
        ConfigManager.getInstance().setMinigameConfig(mg.id(), cfg);
        // 强制更新 SRE
        ConfigManager.getInstance().applyMinigameEnforcement(Minecraft.getInstance().getSingleplayerServer());
    }

    private void openMinigameEditor(QuestMinigame mg) {
        if (!editable) { LiveConfigAccess.showDeniedMessage(); return; }
        MinigameConfigEntry cfg = ConfigManager.getInstance().getMinigameConfig(mg.id());
        if (cfg == null) cfg = MinigameConfigEntry.createDefault();
        ConfigManager.getInstance().putMinigameConfig(mg.id(), cfg);
        Minecraft.getInstance().setScreen(new MinigameEditScreen(root, mg, cfg));
    }
}