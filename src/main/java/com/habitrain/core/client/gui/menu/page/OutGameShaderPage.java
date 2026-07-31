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
        g.drawString(font, Component.literal("§lIris 光影白名单"), x + PAD(), y + 4, 0xFFFFFF, false);
        g.drawString(font, Component.literal("§7设置服务器允许使用的 Iris 光影包，仅 OP 可修改"), x + PAD(), y + 18, 0x888888, false);
        // 启用开关
        int toggleW = 160;
        PillToggle.render(g, font, x + PAD(), y + 30, toggleW, 18, whitelistEnabled,
                "§a✔ 光影白名单已启用", "§c✘ 光影白名单已禁用");
        rowHits.add(new RowHit(-1, x + PAD(), y + 30, toggleW, 18));  // index=-1 表示总开关

        // 添加区
        addBox.setX(x + PAD()); addBox.setY(y + 52); addBox.setWidth(Math.min(260, w - PAD() * 2 - 60));
        addBox.render(g, mx, my, delta);
        int addX = addBox.getX() + addBox.getWidth() + 6;
        g.fill(addX, y + 50, addX + 50, y + 70, editable ? MenuTheme.BG_EDIT : 0xFF222222);
        g.drawString(font, "§a+ 添加", addX + 8, y + 54, editable ? 0xFFFFFFFF : 0xFF666666, false);
        rowHits.add(new RowHit(-2, addX, y + 50, 50, 20));            // index=-2 表示添加按钮

        g.fill(x + PAD(), y + HEADER_H, x + w - PAD(), y + HEADER_H + 1, 0x30FFFFFF);

        int listY = y + HEADER_H + 4;
        int listH = h - HEADER_H - 8;
        area = new ScrollArea(x, listY, w, listH);
        g.enableScissor(x, listY, x + w, listY + listH);
        int cy = area.getContentY();
        if (whitelist.isEmpty()) {
            String msg = whitelistEnabled ? "§7暂无允许的光影包 — 将阻止所有光影包" : "§7暂无添加的光影包，点击上方添加";
            g.drawString(font, Component.literal(msg), x + w / 2 - font.width(msg) / 2, cy + 8, 0x555555, false);
        } else {
            for (int i = 0; i < whitelist.size(); i++) {
                boolean hover = MenuTheme.inBounds(mx, my, x, cy, w, ROW_H);
                g.fill(x, cy, x + w, cy + ROW_H, hover ? 0x18FFFFFF : 0x08FFFFFF);
                g.drawString(font, "§7" + (i + 1) + ".", x + 4, cy + 6, 0x888888, false);
                g.drawString(font, "§e📦", x + 4, cy + 6, 0, false);
                g.drawString(font, "§f" + whitelist.get(i), x + 24, cy + 6, 0xFFFFFF, false);
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
        g.drawString(font, Component.literal("§7💡 已允许 §e" + whitelist.size() + " §7个光影包  |  白名单状态: "
                        + (whitelistEnabled ? "§a启用" : "§c禁用")), x + PAD(), infoY, 0, false);
        g.drawString(font, Component.literal("§7⚡ 白名单外光影的玩家将被踢出服务器"), x + PAD(), infoY + 10, 0x555555, false);
    }

    private static int PAD() { return 12; }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (addBox.mouseClicked(mx, my, btn)) return true;
        for (RowHit hit : rowHits) {
            if (hit.index() == -1) {
                if (PillToggle.hit(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                    if (!editable) { MenuPermissions.showDeniedMessage(); return true; }
                    whitelistEnabled = !whitelistEnabled;
                    saveToServer();
                    return true;
                }
                continue;
            }
            if (hit.index() == -2) {
                if (PillToggle.hit(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
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
