package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.*;
import com.habitrain.core.client.gui.menu.ui.*;
import com.habitrain.core.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 游戏外·光影白名单（内联页面）：启用 + 列表增删，即时保存。 */
public class OutGameShaderPage implements ConfigPage {

    private static final int HEADER_H = 72;
    private static final int ROW_H = 26, ROW_GAP = 2;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private boolean whitelistEnabled;
    private final List<String> whitelist = new ArrayList<>();
    private final EditBox addBox;
    private String addText = "";
    private ScrollArea area;

    private final List<RowHit> rowHits = new ArrayList<>();
    private record RowHit(int index, int x, int y, int w, int h) {}

    public OutGameShaderPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        ConfigManager c = ConfigManager.getInstance();
        this.whitelistEnabled = c.isShaderWhitelistEnabled();
        this.whitelist.addAll(c.getShaderWhitelist());
        this.addBox = new EditBox(font, 0, 0, 130, 16, Component.literal(""));
        this.addBox.setMaxLength(128);
        this.addBox.setHint(Component.literal("输入光影包名称..."));
        this.addBox.setResponder(t -> addText = t == null ? "" : t.trim());
        this.addBox.setEditable(editable);
        this.area = new ScrollArea(0, 0, 0, 0);
    }

    @Override public boolean canSave() { return true; }
    @Override public void save() { ConfigManager.getInstance().save(); }
    @Override public void flushPending() {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        rowHits.clear();
        g.drawString(font, "Iris 光影白名单", x + PAD(), y + 4, MenuTheme.TEXT_PRIMARY, false);
        g.drawString(font, "设置服务器允许使用的 Iris 光影包，仅 OP 可修改", x + PAD(), y + 18, MenuTheme.TEXT_SECONDARY, false);
        // 启用开关
        int toggleW = 160;
        PillToggle.render(g, font, x + PAD(), y + 30, toggleW, 18, whitelistEnabled,
                "白名单 · 已启用", "白名单 · 已禁用");
        rowHits.add(new RowHit(-1, x + PAD(), y + 30, toggleW, 18));  // index=-1 表示总开关

        // 添加区
        addBox.setX(x + PAD()); addBox.setY(y + 52); addBox.setWidth(Math.min(260, w - PAD() * 2 - 60));
        addBox.render(g, mx, my, delta);
        int addX = addBox.getX() + addBox.getWidth() + 6;
        boolean addHover = editable && MenuTheme.inBounds(mx, my, addX, y + 50, 50, 20);
        MenuTheme.button(g, font, "+ 添加", addX, y + 50, 50, 20,
                MenuTheme.ACCENT_MINT, editable, addHover);
        rowHits.add(new RowHit(-2, addX, y + 50, 50, 20));            // index=-2 表示添加按钮

        g.fill(x + PAD(), y + HEADER_H, x + w - PAD(), y + HEADER_H + 1, MenuTheme.BORDER);

        int listY = y + HEADER_H + 4;
        int listH = h - HEADER_H - 8;
        area.setBounds(x, listY, w, listH);
        g.enableScissor(x, listY, x + w, listY + listH);
        int cy = area.getContentY();
        if (whitelist.isEmpty()) {
            String msg = whitelistEnabled ? "§7暂无允许的光影包 — 将阻止所有光影包" : "§7暂无添加的光影包，点击上方添加";
            g.drawString(font, Component.literal(msg), x + w / 2 - font.width(msg) / 2, cy + 8, 0x555555, false);
        } else {
            for (int i = 0; i < whitelist.size(); i++) {
                boolean hover = MenuTheme.inBounds(mx, my, x, cy, w, ROW_H);
                MenuTheme.row(g, x, cy, w, ROW_H, hover, false);
                String index = String.format("%02d", i + 1);
                g.drawString(font, index, x + 7, cy + 8, MenuTheme.TEXT_DIM, false);
                g.drawString(font, whitelist.get(i), x + 29, cy + 8, MenuTheme.TEXT_PRIMARY, false);
                int delX = x + w - 22;
                boolean delHover = hover && mx >= delX && mx < delX + 18 && my >= cy + 4 && my < cy + 22;
                if (delHover) g.fill(delX, cy + 4, delX + 18, cy + 22, 0x44FF0000);
                g.drawString(font, delHover ? "§c✕" : "§8✕", delX + 5, cy + 4, 0, false);
                rowHits.add(new RowHit(i, x, cy, w, ROW_H));
                cy += ROW_H + ROW_GAP;
            }
        }
        area.setContentHeight(whitelist.isEmpty() ? 40 : whitelist.size() * (ROW_H + ROW_GAP));
        area.render(g);
        g.disableScissor();

        int infoY = y + h - 24;
        g.drawString(font, "已允许 " + whitelist.size() + " 个光影包  /  状态 "
                        + (whitelistEnabled ? "启用" : "禁用"), x + PAD(), infoY,
                whitelistEnabled ? MenuTheme.ACCENT_MINT : MenuTheme.TEXT_SECONDARY, false);
        g.drawString(font, "白名单外光影的玩家将被服务器移出", x + PAD(), infoY + 10,
                MenuTheme.TEXT_DIM, false);
    }

    private static int PAD() { return 12; }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (addBox.mouseClicked(mx, my, btn)) return true;
        for (RowHit hit : rowHits) {
            if (hit.index() == -1) {
                if (PillToggle.hit(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                    MenuSounds.playClick();
                    if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                    whitelistEnabled = !whitelistEnabled;
                    saveToServer();
                    return true;
                }
                continue;
            }
            if (hit.index() == -2) {
                if (PillToggle.hit(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                    MenuSounds.playClick();
                    if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                    addCurrentText();
                    return true;
                }
                continue;
            }
            // 列表行：仅点击右侧 ✕ 删除按钮删除（与旧版一致）
            int delX = hit.x() + hit.w() - 22;
            int delY = hit.y() + 4;
            if (MenuTheme.inBounds(mx, my, delX, delY, 18, 18)) {
                MenuSounds.playClick();
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                whitelist.remove(hit.index());
                saveToServer();
                return true;
            }
        }
        return area.mouseClicked(mx, my, btn);
    }

    private void addCurrentText() {
        if (addText.isEmpty()) return;
        boolean exists = whitelist.stream().anyMatch(n -> n.equalsIgnoreCase(addText));
        if (exists) {
            var p = Minecraft.getInstance().player;
            if (p != null) p.displayClientMessage(Component.literal("§e该光影包已在白名单中"), true);
            return;
        }
        whitelist.add(addText);
        addBox.setValue("");
        addText = "";
        saveToServer();
    }

    private void saveToServer() {
        ConfigManager.getInstance().setShaderWhitelistConfig(whitelistEnabled, whitelist);
    }

    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) { return area.mouseDragged(my); }
    @Override public boolean mouseReleased(double mx, double my, int btn) { return area.mouseReleased(); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) { return area.mouseScrolled(sy); }
    @Override public boolean keyPressed(int key, int scan, int mod) {
        if (addBox.isFocused()) {
            if (key == 257 || key == 335) {
                if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                addCurrentText();
                return true;
            }
            if (addBox.keyPressed(key, scan, mod)) return true;
        }
        return false;
    }
    @Override public boolean charTyped(char ch, int mod) { return addBox.isFocused() && addBox.charTyped(ch, mod); }
}
