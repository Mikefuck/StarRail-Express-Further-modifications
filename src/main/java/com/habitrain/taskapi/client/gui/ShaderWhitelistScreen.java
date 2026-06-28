package com.habitrain.taskapi.client.gui;

import com.habitrain.taskapi.HabiTrainTaskAPI;
import com.habitrain.taskapi.impl.config.HabiConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * =========================================================
 *  Iris 光影白名单设置界面
 * =========================================================
 *
 * 功能：
 *   - 启用/禁用光影白名单
 *   - 添加/删除允许的光影包
 *   - 仅 OP 玩家可修改（修改会通过C2S包发送到服务端）
 */
public class ShaderWhitelistScreen extends Screen {

    // ====== 布局常量 ======
    private static final int PAD = 12;
    private static final int HEADER_H = 50;
    private static final int ROW_H = 26;
    private static final int ROW_GAP = 2;
    private static final int FOOTER_H = 30;
    private static final int SCROLLBAR_W = 4;

    // ====== 状态 ======
    private final Screen parent;
    private boolean whitelistEnabled;
    private final List<String> whitelist = new ArrayList<>();

    // 控件
    private Button toggleBtn;
    private Button addBtn;
    private Button backBtn;
    private EditBox addBox;
    private String addText = "";

    // 滚动状态
    private double scrollOffset = 0;
    private int contentHeight = 0;
    private boolean draggingScroll = false;
    private double dragStartY = 0;
    private double dragStartOff = 0;

    public ShaderWhitelistScreen(Screen parent) {
        super(Component.literal("§l🎨 Iris 光影白名单"));
        this.parent = parent;

        // 从配置管理器加载当前状态
        HabiConfigManager cfg = HabiConfigManager.getInstance();
        this.whitelistEnabled = cfg.isShaderWhitelistEnabled();
        this.whitelist.clear();
        this.whitelist.addAll(cfg.getShaderWhitelist());
    }

    @Override
    protected void init() {
        super.init();
        int centerX = width / 2;

        // ---- 启用/禁用开关 ----
        toggleBtn = addRenderableWidget(Button.builder(
                getToggleMessage(), b -> {
                    whitelistEnabled = !whitelistEnabled;
                    b.setMessage(getToggleMessage());
                    saveToServer();
                }
        ).bounds(centerX - 80, HEADER_H - 4, 160, 20).build());

        // ---- 添加区域 ----
        addBox = new EditBox(font, centerX - 80, HEADER_H + 24, 130, 16, Component.literal(""));
        addBox.setMaxLength(128);
        addBox.setHint(Component.literal("输入光影包名称..."));
        addBox.setResponder(t -> addText = t.trim());
        addRenderableWidget(addBox);

        addBtn = addRenderableWidget(Button.builder(
                Component.literal("§a+ 添加"), b -> addCurrentText()
        ).bounds(centerX + 56, HEADER_H + 22, 50, 18).build());

        // ---- 底部按钮 ----
        backBtn = addRenderableWidget(Button.builder(
                Component.literal("§7← 返回"), b ->
                        Minecraft.getInstance().setScreen(parent)
        ).bounds(PAD, height - FOOTER_H + 4, 80, 20).build());
    }

    private Component getToggleMessage() {
        return whitelistEnabled
                ? Component.literal("§a✔ 光影白名单已启用")
                : Component.literal("§c✘ 光影白名单已禁用");
    }

    /** 添加输入框中的文本到白名单 */
    private void addCurrentText() {
        if (addText.isEmpty()) return;
        // 检查是否已存在（忽略大小写）
        boolean exists = whitelist.stream().anyMatch(
                n -> n.equalsIgnoreCase(addText));
        if (exists) {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("§e该光影包已在白名单中"), true);
            }
            return;
        }
        whitelist.add(addText);
        addBox.setValue("");
        addText = "";
        saveToServer();
    }

    /** 从白名单中移除指定条目 */
    private void removeEntry(int index) {
        if (index >= 0 && index < whitelist.size()) {
            whitelist.remove(index);
            saveToServer();
        }
    }

    /** 保存到服务端（通过已有的 save 回调 → C2S 配置更新通道） */
    private void saveToServer() {
        // 使用批量更新方法（仅调用一次 save，触发 save 回调自动发送到服务端）
        HabiConfigManager cfg = HabiConfigManager.getInstance();
        cfg.setShaderWhitelistConfig(whitelistEnabled, whitelist);
    }

    // =========================================================
    //  渲染
    // =========================================================

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        super.render(g, mx, my, delta);

        Font f = font;
        int centerX = width / 2;

        // ---- 标题 ----
        g.drawString(f, Component.literal("§l🎨 Iris 光影白名单"), PAD, 4, 0xFFFFFF, false);
        g.drawString(f, Component.literal("§7设置服务器允许使用的 Iris 光影包，仅 OP 可修改"),
                PAD, 18, 0x888888, false);

        // 分割线
        g.fill(PAD, HEADER_H + 2, width - PAD, HEADER_H + 3, 0x30FFFFFF);

        // ---- 列表区域 ----
        int listTop = HEADER_H + 48;
        int listBot = height - FOOTER_H - 4;
        int listH = listBot - listTop;

        // 调整内容高度
        contentHeight = whitelist.size() * (ROW_H + ROW_GAP);
        if (whitelist.isEmpty()) contentHeight = 40;
        clampScroll(listH);

        int scrollAreaX = PAD;
        int scrollAreaW = width - PAD * 2 - SCROLLBAR_W;

        // 裁剪区域
        g.enableScissor(scrollAreaX, listTop, width - PAD, listBot);

        int contentY = listTop - (int) scrollOffset;

        if (whitelist.isEmpty()) {
            // 空状态
            String emptyMsg = whitelistEnabled
                    ? "§7暂无允许的光影包 — 将阻止所有光影包"
                    : "§7暂无添加的光影包，点击上方添加";
            g.drawString(f, Component.literal(emptyMsg),
                    centerX - f.width(emptyMsg) / 2, listTop + 14, 0x555555, false);
        } else {
            // 渲染每一行
            for (int i = 0; i < whitelist.size(); i++) {
                int rowY = contentY;
                contentY += ROW_H + ROW_GAP;

                if (rowY + ROW_H < listTop || rowY > listBot) continue;

                boolean hover = mx >= scrollAreaX && mx < scrollAreaX + scrollAreaW
                        && my >= rowY && my < rowY + ROW_H;
                renderRow(g, f, i, whitelist.get(i), scrollAreaX, scrollAreaW, rowY, hover, mx, my);
            }
        }

        g.disableScissor();

        // ---- 滚动条 ----
        if (contentHeight > listH) {
            int thumbH = Math.max(20, (int) ((float) listH / contentHeight * listH));
            int thumbY = listTop + (int) ((float) scrollOffset / (contentHeight - listH) * (listH - thumbH));
            int sx = width - PAD - SCROLLBAR_W;

            g.fill(sx, listTop, sx + SCROLLBAR_W, listBot, 0x20FFFFFF);
            g.fill(sx, thumbY, sx + SCROLLBAR_W, thumbY + thumbH, 0x90AAAAAA);
        }

        // ---- 信息区 ----
        int infoY = height - FOOTER_H - 14;
        g.drawString(f, Component.literal("§7💡 已允许 §e" + whitelist.size() + " §7个光影包  |  白名单状态: "
                        + (whitelistEnabled ? "§a启用" : "§c禁用")),
                PAD + 4, infoY, 0, false);
        g.drawString(f, Component.literal("§7⚡ 白名单外光影的玩家将被踢出服务器"),
                PAD + 4, infoY + 10, 0x555555, false);
    }

    /** 渲染单个白名单条目 */
    private void renderRow(GuiGraphics g, Font f, int index, String packName,
                           int x, int w, int y, boolean hover, int mx, int my) {
        // 背景
        int bg = hover ? 0x18FFFFFF : 0x08FFFFFF;
        g.fill(x, y, x + w, y + ROW_H, bg);

        // 序号
        String numStr = "§7" + (index + 1) + ".";
        g.drawString(f, Component.literal(numStr), x + 4, y + 6, 0x888888, false);

        // 光影包名称
        int nameX = x + 24;
        g.drawString(f, Component.literal("§f" + packName), nameX, y + 6, 0xFFFFFF, false);

        // 光影图标
        g.drawString(f, Component.literal("§e📦"), x + 4, y + 6, 0, false);

        // 删除按钮（悬停时显示）
        int delX = x + w - 22;
        int delY = y + 4;
        boolean delHover = hover && mx >= delX && mx < delX + 18 && my >= delY && my < delY + 18;

        if (delHover) {
            g.fill(delX, delY, delX + 18, delY + 18, 0x44FF0000);
        }
        g.drawString(f, Component.literal(delHover ? "§c✕" : "§8✕"), delX + 5, delY + 4, 0, false);
    }

    // =========================================================
    //  鼠标事件
    // =========================================================

    private int getListTop() { return HEADER_H + 48; }
    private int getListBot() { return height - FOOTER_H - 4; }

    private void clampScroll(int listH) {
        if (contentHeight <= listH) scrollOffset = 0;
        else scrollOffset = Math.max(0, Math.min(scrollOffset, contentHeight - listH));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int listTop = getListTop();
        int listBot = getListBot();
        if (my >= listTop && my < listBot) {
            scrollOffset -= dy * 16;
            clampScroll(listBot - listTop);
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;

        int listTop = getListTop();
        int listBot = getListBot();
        int listH = listBot - listTop;

        // 滚动条拖动
        int sx = width - PAD - SCROLLBAR_W;
        if (mx >= sx && mx < width - PAD && my >= listTop && my < listBot) {
            draggingScroll = true;
            dragStartY = my;
            dragStartOff = scrollOffset;
            return true;
        }

        // 检查列表中的删除按钮点击
        int scrollAreaW = width - PAD * 2 - SCROLLBAR_W;
        int contentY = listTop - (int) scrollOffset;

        for (int i = 0; i < whitelist.size(); i++) {
            int rowY = contentY;
            contentY += ROW_H + ROW_GAP;

            if (rowY + ROW_H < listTop || rowY > listBot) continue;

            // 删除按钮区域
            int delX = PAD + scrollAreaW - 22;
            int delY = rowY + 4;
            if (mx >= delX && mx < delX + 18 && my >= delY && my < delY + 18) {
                removeEntry(i);
                return true;
            }
        }

        // 点击添加按钮的键盘快捷键（回车）
        if (addBox.isFocused() && button == 0) {
            // 如果点击了添加按钮外部，但不处理
        }

        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingScroll = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingScroll) {
            int listH = getListBot() - getListTop();
            double scale = (double) (contentHeight - listH) / Math.max(1, listH - 20);
            scrollOffset = dragStartOff + (my - dragStartY) * scale;
            clampScroll(listH);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    // =========================================================
    //  键盘事件
    // =========================================================

    @Override
    public boolean keyPressed(int key, int sc, int mod) {
        if (addBox != null && addBox.isFocused()) {
            if (key == 257 || key == 335) { // Enter
                addCurrentText();
                return true;
            }
            if (addBox.keyPressed(key, sc, mod)) {
                return true;
            }
        }
        if (key == 256) { // ESC
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        return super.keyPressed(key, sc, mod);
    }

    @Override
    public boolean charTyped(char ch, int mod) {
        if (addBox != null && addBox.isFocused() && addBox.charTyped(ch, mod)) {
            return true;
        }
        return super.charTyped(ch, mod);
    }
}
