package com.habitrain.core.client.gui;

import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 对局中的背包：上游快捷栏底图换成一条车票票条，右下角的「菜单」按钮换成一本邮票册——点开后菜单项化作
 * 横版邮票从册中依次抽出、向上排开；悬停时邮票向外抽出，点击盖邮戳后执行上游原动作。
 *
 * <p>只换外观不换布局：快捷栏仍在原位、菜单仍在右下角，职业控件与商店的坐标不受影响。
 * 菜单项来自上游按钮列表，最多两列；超出时分页，始终保留滚轮、按键与可点击的翻页入口。</p>
 */
public final class TicketMenuTray implements GuiEventListener, NarratableEntry {
    private static final float STRIP_W = 176.0f;
    private static final float STRIP_H = 32.0f;
    private static final float STAMP_W = 84.0f;
    private static final float STAMP_H = 26.0f;
    private static final float STAMP_GAP = 3.0f;
    private static final float BOOK_W = 76.0f;
    private static final float BOOK_H = 22.0f;
    private static final float MARGIN = 6.0f;
    private static final long OPEN_MS = 300L;
    private static final long STAGGER_MS = 38L;
    private static final long CLOSE_MS = 150L;
    private static final long ACTION_DELAY_MS = 200L;

    private final Minecraft mc = Minecraft.getInstance();
    private final TicketPerformanceButton modeButton;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();
    private final TicketTextCache texts = new TicketTextCache();
    private final TicketClockText clockText = new TicketClockText();
    private static final StampArt.Ink[] BOOK_INKS = {StampArt.SAPPHIRE, StampArt.EMERALD, StampArt.OCHRE};
    private boolean lightweight;
    private Consumer<Boolean> toggle;
    private BooleanSupplier openState;
    private boolean active;
    private boolean focused;
    private int width;
    private int height;
    private float k = 1.0f;
    private boolean open;
    private long changedAt = -1L;
    private long lastFrameAt = -1L;
    private long lastSoundAt;
    private int hovered = -1;
    private boolean bookHovered;
    private float bookHover;
    private Entry pending;
    private long pendingAt;
    private int page;
    private int pages = 1;
    private int perColumn = 1;
    private int perPage = 1;
    /** -2: pointer mode; -1: album cover; >= 0: menu entry. */
    private int keyboardIndex = -2;
    private int previousMouseX = Integer.MIN_VALUE;
    private int previousMouseY = Integer.MIN_VALUE;
    private float pagerY;
    private float pagerLeft;
    private float pagerRight;

    public TicketMenuTray(TicketPerformanceButton modeButton) {
        this.modeButton = modeButton;
    }

    /** 屏幕 init 时调用：按上游对局菜单按钮重建邮票；toggle/openState 对应上游的菜单开关。 */
    public void rebuild(List<? extends Button> buttons, Consumer<Boolean> toggle, BooleanSupplier openState) {
        releaseResources();
        this.toggle = toggle;
        this.openState = openState;
        entries.clear();
        int number = 0;
        for (Button button : buttons) {
            if (button != null) {
                entries.add(new Entry(button, ++number));
            }
        }
        entries.add(new Entry(modeButton, ++number));
        pending = null;
        hovered = -1;
        keyboardIndex = -2;
        open = openState != null && openState.getAsBoolean();
        changedAt = Util.getMillis();
    }

    public void sync(List<? extends Button> buttons) {
        boolean changed = buttons.size() + 1 != entries.size();
        for (int i = 0; !changed && i < buttons.size(); i++) {
            Button source = buttons.get(i);
            changed = source != entries.get(i).source || !source.getMessage().equals(entries.get(i).label);
        }
        if (changed) {
            rebuild(buttons, toggle, openState);
            layout(width, height);
        }
        Entry mode = entries.getLast();
        if (!mode.label.equals(modeButton.getMessage())) {
            mode.label = modeButton.getMessage();
            mode.face = null;
            mode.art.close();
        }
    }

    public void layout(int width, int height) {
        releaseResources();
        this.width = width;
        this.height = height;
        this.k = TicketInventoryLayer.layoutScale(width, height, 1.4f);
        perColumn = TicketUiGeometry.trayRows(height, k);
        perPage = perColumn * TicketUiGeometry.trayColumns(width, k);
        pages = TicketUiGeometry.pages(entries.size(), perPage);
        page = Mth.clamp(page, 0, pages - 1);
        for (Entry entry : entries) {
            entry.face = null;
        }
    }

    /** 本帧是否处于对局界面；否则不接收任何鼠标事件。 */
    public void setActive(boolean active) {
        this.active = active;
        if (openState != null && !openState.getAsBoolean()) pending = null;
        if (!active) {
            hovered = -1;
            bookHovered = false;
            pending = null;
            keyboardIndex = -2;
            focused = false;
        }
    }

    public void tick() {
        if (active && open && pending != null && Util.getMillis() >= pendingAt) {
            Entry entry = pending;
            pending = null;
            if (!TicketInventoryLayer.isLockedKey(entry.key)) entry.source.onPress();
        }
    }

    public void setLightweight(boolean lightweight) {
        if (this.lightweight == lightweight) return;
        this.lightweight = lightweight;
        pending = null;
        releaseResources();
    }

    public void releaseResources() {
        for (Entry entry : entries) entry.art.close();
    }

    // ==================== 快捷栏票条 ====================

    /** 替换上游 176×32 的快捷栏底图；物品仍由上游画在 (x+8+18i, y+8)。 */
    public void renderStrip(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        TicketArt.Palette p = TicketArt.SAPPHIRE;
        LocalPlayer player = mc.player;
        int selected = player == null ? -1 : player.getInventory().selected;
        long now = Util.getMillis();
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0f);
        GuiGeo geo = GuiGeo.begin(g);
        geo.convexV(RailArt.chamfer(1.5f, 2.5f, STRIP_W, STRIP_H, 4.0f), 0, STRIP_H, RailArt.a(0xFF000000, 0.3f),
                RailArt.a(0xFF000000, 0.42f));
        geo.convexV(RailArt.chamfer(0, 0, STRIP_W, STRIP_H, 4.0f), 0, STRIP_H, p.paperTop(), p.paperBottom());
        geo.rectV(0, 0, STRIP_W, 5.0f, RailArt.a(p.band(), 0.75f), RailArt.a(p.band(), 0));
        // 两端检票缺口
        geo.arc(0, STRIP_H / 2.0f, 0, 3.6f, 1.0f, -Mth.HALF_PI, Mth.HALF_PI, RailArt.a(RailArt.NIGHT_0, 0.95f),
                RailArt.a(RailArt.NIGHT_0, 0.95f));
        geo.arc(STRIP_W, STRIP_H / 2.0f, 0, 3.6f, 1.0f, Mth.HALF_PI, Mth.PI + Mth.HALF_PI,
                RailArt.a(RailArt.NIGHT_0, 0.95f), RailArt.a(RailArt.NIGHT_0, 0.95f));
        geo.outline(RailArt.chamfer(2.5f, 2.5f, STRIP_W - 5.0f, STRIP_H - 5.0f, 3.0f), 0.7f, p.trim());
        for (int i = 0; i < 9; i++) {
            float fx = 7.0f + i * 18.0f;
            float fy = 7.0f;
            boolean sel = i == selected;
            boolean hover = mouseX >= x + fx && mouseX < x + fx + 18.0f && mouseY >= y + fy && mouseY < y + fy + 18.0f;
            geo.rectV(fx, fy, fx + 18.0f, fy + 18.0f, RailArt.a(RailArt.NIGHT_0, 0.62f), RailArt.a(RailArt.NIGHT_1, 0.5f));
            geo.rect(fx, fy, fx + 18.0f, fy + 1.5f, RailArt.a(0xFF000000, 0.35f));
            int edge = sel ? RailArt.BRASS_LIGHT : RailArt.a(hover ? RailArt.BRASS : RailArt.BRASS_DIM, hover ? 1.0f : 0.75f);
            geo.rectOutline(fx, fy, fx + 18, fy + 18, sel ? 0.9f : 0.5f, edge);
            if (sel) {
                geo.tri(fx + 7.0f, fy + 20.6f, edge, fx + 11.0f, fy + 20.6f, edge, fx + 9.0f, fy + 18.8f, edge);
            }
        }
        geo.end();
        Font font = mc.font;
        float tiny = TicketArt.crispAscii(0.5f);
        for (int i = 0; i < 9; i++) {
            TicketArt.drawScaled(g, font, TicketTextCache.HOTBAR_NUMBERS[i], 7.0f + i * 18.0f + 1.5f, 8.5f, tiny,
                    RailArt.a(RailArt.TEXT_FAINT, 0.9f));
        }
        if (!lightweight && selected >= 0 && selected < 9) {
            GuiGeo glow = GuiGeo.glow(g);
            float breathe = 0.75f + 0.25f * Mth.sin(now / 420.0f);
            glow.softGlow(16.0f + selected * 18.0f, 16.0f, 15.0f, 15.0f, RailArt.BRASS_LIGHT, Math.round(44 * breathe));
            glow.end();
        }
        g.pose().popPose();
    }

    // ==================== 邮票册 ====================

    public void renderTray(GuiGraphics g, int mouseX, int mouseY) {
        long now = Util.getMillis();
        clockText.update(System.currentTimeMillis());
        if (texts.refreshLanguage()) {
            for (Entry entry : entries) {
                entry.face = null;
                entry.art.close();
            }
        }
        float dt = lastFrameAt < 0L ? 0.0f : Math.min(0.1f, (now - lastFrameAt) / 1000.0f);
        lastFrameAt = now;
        if (mouseX != previousMouseX || mouseY != previousMouseY) keyboardIndex = -2;
        previousMouseX = mouseX;
        previousMouseY = mouseY;
        boolean nowOpen = openState != null && openState.getAsBoolean();
        if (nowOpen != open) {
            open = nowOpen;
            changedAt = now;
            pending = null;
            if (!open) keyboardIndex = -1;
            TicketSounds.slide(open ? 1.3f : 0.95f);
        }
        Font font = mc.font;
        float right = width - MARGIN * k;
        float bottom = height - MARGIN * k;
        float bookX = right - BOOK_W * k;
        float bookY = bottom - BOOK_H * k;
        int start = page * perPage;
        int end = Math.min(entries.size(), start + perPage);

        // 位置与动画量
        boolean settledClosed = !open && (lightweight || now - changedAt >= CLOSE_MS);
        float motion = lightweight ? 0 : mc.options.screenEffectScale().get().floatValue();
        for (int i = start; !settledClosed && i < end; i++) {
            Entry e = entries.get(i);
            int order = i - start;
            float p;
            if (lightweight) {
                p = open ? 1 : 0;
            } else if (open) {
                p = easeOutBack(TransitionFx.clamp01((now - changedAt - order * STAGGER_MS) / (float) OPEN_MS));
            } else {
                p = changedAt < 0L ? 0.0f : 1.0f - TransitionFx.clamp01((now - changedAt) / (float) CLOSE_MS);
            }
            e.progress = p;
            float target = i == (keyboardIndex >= 0 ? keyboardIndex : hovered) ? 1.0f : 0.0f;
            e.hover += (target - e.hover) * (dt <= 0.0f ? 1.0f : 1.0f - (float) Math.exp(-dt * 15.0f));
            e.yaw += dt * 3.0f * e.hover * motion;
            int column = order / perColumn;
            int row = order % perColumn;
            float restY = bookY - (STAMP_GAP + (row + 1) * STAMP_H + row * STAMP_GAP) * k;
            float startY = bookY - STAMP_H * k * 0.15f;
            float shake = 0.0f;
            if (e.deniedAt >= 0L) {
                float d = (now - e.deniedAt) / 360.0f;
                if (d < 1.0f) {
                    shake = Mth.sin(d * 34.0f) * 2.4f * (1.0f - d);
                }
            }
            e.gx = right - STAMP_W * k - column * (STAMP_W + 5.0f) * k - (lightweight ? 0 : e.hover * 7.0f - shake) * k;
            e.gy = Mth.lerp(p, startY, restY);
        }
        int previous = hovered;
        hovered = active && open ? entryAt(mouseX, mouseY) : -1;
        if (hovered >= 0 && hovered != previous && now - lastSoundAt > 70L) {
            lastSoundAt = now;
            TicketSounds.slide(1.5f + TicketArt.hash(hovered * 7) * 0.3f);
        }
        bookHovered = active && (keyboardIndex == -1 || bookAt(mouseX, mouseY));
        bookHover += ((bookHovered ? 1.0f : 0.0f) - bookHover) * (dt <= 0.0f ? 1.0f : 1.0f - (float) Math.exp(-dt * 14.0f));

        for (int i = end - 1; !settledClosed && i >= start; i--) {
            Entry e = entries.get(i);
            if (e.progress > 0.02f) {
                drawEntry(g, font, e, now);
            }
        }
        drawBook(g, font, bookX, bookY, now);
        drawPager(g, font, right, bookY);
    }

    private void drawPager(GuiGraphics g, Font font, float right, float bookY) {
        pagerY = bookY - (perColumn * (STAMP_H + STAMP_GAP) + 16.0f) * k;
        pagerRight = right;
        pagerLeft = right - STAMP_W * k;
        if (!open || pages <= 1) return;
        GuiGeo geo = GuiGeo.begin(g);
        geo.rect(pagerLeft, pagerY, pagerRight, pagerY + 14.0f * k, 0xDD0A1330);
        int left = page > 0 ? RailArt.BRASS_LIGHT : RailArt.TEXT_FAINT;
        int next = page < pages - 1 ? RailArt.BRASS_LIGHT : RailArt.TEXT_FAINT;
        geo.tri(pagerLeft + 4 * k, pagerY + 7 * k, left,
                pagerLeft + 9 * k, pagerY + 3 * k, left, pagerLeft + 9 * k, pagerY + 11 * k, left);
        geo.tri(pagerRight - 4 * k, pagerY + 7 * k, next,
                pagerRight - 9 * k, pagerY + 11 * k, next, pagerRight - 9 * k, pagerY + 3 * k, next);
        geo.end();
        TicketArt.drawCentered(g, font, Component.literal((page + 1) + " / " + pages),
                (pagerLeft + pagerRight) / 2.0f, pagerY + 3.0f * k,
                StampArt.crispDu("0", 1.0f, k) * k, RailArt.CHAMPAGNE);
    }

    private void changePage(int delta) {
        int next = Mth.clamp(page + delta, 0, pages - 1);
        if (next == page) return;
        releaseResources();
        page = next;
        changedAt = Util.getMillis();
        hovered = -1;
        keyboardIndex = -2;
        pending = null;
        for (Entry entry : entries) {
            entry.progress = 0.0f;
            entry.hover = 0.0f;
        }
        TicketSounds.slide(1.1f);
    }

    private boolean pagerAt(double mouseX, double mouseY) {
        return open && pages > 1 && mouseY >= pagerY && mouseY < pagerY + 14.0f * k
                && mouseX >= pagerLeft && mouseX < pagerRight;
    }

    private void drawEntry(GuiGraphics g, Font font, Entry e, long now) {
        if (e.face == null) {
            e.face = buildFace(font, e);
        }
        boolean locked = TicketInventoryLayer.isLockedKey(e.key);
        if (locked != e.face.locked()) e.face = e.face.withLocked(locked);
        StampArt.Face face = e.face;
        float alpha = TransitionFx.clamp01(e.progress * 3.0f);
        float tilt = lightweight ? 0 : ((e.number & 1) == 0 ? 1.2f : -1.2f) * (1.0f - e.hover);
        float scale = lightweight ? 1 : 1.0f + 0.04f * e.hover;
        g.pose().pushPose();
        g.pose().translate(e.gx + STAMP_W * k / 2.0f, e.gy + STAMP_H * k / 2.0f, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(tilt));
        g.pose().scale(k * scale, k * scale, 1.0f);
        g.pose().translate(-STAMP_W / 2.0f, -STAMP_H / 2.0f, 0.0f);
        StampArt.drawLandscape(g, font, face, STAMP_W, STAMP_H, e.yaw, e.hover, alpha, e.number * 41, k * scale,
                e.art, lightweight);
        if (e.clickedAt >= 0L || visited.contains(e.key)) {
            float slam = lightweight || e.clickedAt < 0L ? 1.0f : TransitionFx.clamp01((now - e.clickedAt) / 150.0f);
            StampArt.postmark(g, font, STAMP_W * 0.62f, STAMP_H / 2.0f, 9.0f, clockText.stamp(), -12.0f,
                    alpha * (e.clickedAt >= 0L && now - e.clickedAt < 1200L ? 1.0f : 0.65f), slam, e.number, k * scale);
            if (e.clickedAt >= 0L && slam < 1.0f) {
                TicketArt.inkSplash(g, STAMP_W * 0.62f, STAMP_H / 2.0f, 13.0f, StampArt.POSTMARK, slam, e.number * 3);
            }
        }
        g.pose().popPose();
    }

    /** 邮票册：深红皮面、黄铜包角、缝线，册口露出几枚邮票的齿边。 */
    private void drawBook(GuiGraphics g, Font font, float x, float y, long now) {
        float lift = lightweight ? 0 : bookHover * 1.5f;
        float time = lightweight ? 0 : now / 1000.0f;
        g.pose().pushPose();
        g.pose().translate(x, y - lift * k, 0.0f);
        g.pose().scale(k, k, 1.0f);
        GuiGeo geo = GuiGeo.begin(g);
        if (!open) {
            for (int t = 0; t < 3; t++) {
                float tx = 14.0f + t * 18.0f;
                float rise = 3.5f + (t == 1 ? 1.2f : 0.0f) + bookHover * (1.5f + t * 0.6f) + 0.4f * Mth.sin(time * 2.0f + t);
                geo.rect(tx - 1.0f, -rise - 1.0f, tx + 15.0f, 3.0f, StampArt.PAPER_TOP);
                for (float px = tx; !lightweight && px <= tx + 14.0f; px += 2.4f) {
                    geo.disc(px, -rise - 1.0f, 0.8f, StampArt.PAPER_TOP);
                }
                geo.rectV(tx + 1.0f, -rise + 0.5f, tx + 13.0f, 3.0f, BOOK_INKS[t].top(), BOOK_INKS[t].bottom());
            }
        }
        geo.convexV(RailArt.chamfer(1.2f, 2.0f, BOOK_W, BOOK_H, 3.0f), 0, BOOK_H, RailArt.a(0xFF000000, 0.32f),
                RailArt.a(0xFF000000, 0.45f));
        geo.convexV(RailArt.chamfer(0, 0, BOOK_W, BOOK_H, 3.0f), 0, BOOK_H, 0xFF74283C, 0xFF3C111E);
        geo.rect(0.6f, 0.6f, 6.0f, BOOK_H - 0.6f, RailArt.a(0xFF000000, 0.28f));
        geo.rectV(0, 0, BOOK_W, 3.0f, RailArt.a(0xFFFFFFFF, 0.1f + 0.08f * bookHover), RailArt.a(0xFFFFFFFF, 0));
        int stitch = RailArt.a(RailArt.BRASS_LIGHT, 0.45f);
        for (float sx = 8.0f; sx < BOOK_W - 3.0f; sx += 3.0f) {
            geo.rect(sx, 2.4f, sx + 1.6f, 2.9f, stitch);
            geo.rect(sx, BOOK_H - 2.9f, sx + 1.6f, BOOK_H - 2.4f, stitch);
        }
        int brass = RailArt.a(RailArt.BRASS, 0.95f);
        float c = 4.0f;
        geo.tri(BOOK_W, 0, brass, BOOK_W - c, 0, brass, BOOK_W, c, brass);
        geo.tri(BOOK_W, BOOK_H, brass, BOOK_W, BOOK_H - c, brass, BOOK_W - c, BOOK_H, brass);
        // 开合箭头
        float ax = BOOK_W - 8.0f;
        float ay = BOOK_H / 2.0f;
        int arrow = RailArt.a(RailArt.BRASS_LIGHT, 0.8f + 0.2f * bookHover);
        if (open) {
            geo.tri(ax - 2.6f, ay - 1.3f, arrow, ax + 2.6f, ay - 1.3f, arrow, ax, ay + 1.6f, arrow);
        } else {
            geo.tri(ax - 2.6f, ay + 1.3f, arrow, ax, ay - 1.6f, arrow, ax + 2.6f, ay + 1.3f, arrow);
        }
        geo.end();
        if (!lightweight && bookHover > 0.01f) {
            GuiGeo glow = GuiGeo.glow(g);
            glow.softGlow(BOOK_W / 2.0f, BOOK_H / 2.0f, BOOK_W * 0.6f, BOOK_H * 1.1f, RailArt.BRASS_LIGHT,
                    Math.round(34 * bookHover));
            glow.end();
        }
        Component title = texts.label("inventory.habitrain_core.ticket.menu");
        String sub = texts.subtitle("inventory.habitrain_core.ticket.menu");
        float s = StampArt.crispDu(title.getString(), 1.0f, k);
        float subScale = StampArt.crispDu(sub, 0.5f, k);
        float subW = sub.isBlank() ? 0.0f : TicketArt.trackedWidth(mc.font, sub, subScale) + 3.0f;
        float total = font.width(title) * s + subW;
        float left = 8.0f + (BOOK_W - 18.0f - total) / 2.0f;
        float ty = (BOOK_H - 8.0f * s) / 2.0f;
        TicketArt.drawScaled(g, font, title, left, ty, s, RailArt.CHAMPAGNE);
        if (!sub.isBlank()) {
            TicketArt.drawTrackedLeft(g, font, sub, left + font.width(title) * s + 3.0f, ty + 8.0f * s - 8.0f * subScale,
                    subScale, RailArt.a(RailArt.BRASS_LIGHT, 0.8f));
        }
        g.pose().popPose();
    }

    private StampArt.Face buildFace(Font font, Entry e) {
        float avail = StampArt.landscapeLabelWidth(STAMP_W, STAMP_H);
        String plain = e.label.getString();
        Component display = Component.literal(plain);
        float[][] tries = {{1.0f, 1}, {0.75f, 1}, {1.0f, 2}, {0.75f, 2}, {0.5f, 1}, {0.5f, 2}};
        for (float[] attempt : tries) {
            float scale = StampArt.crispDu(plain, attempt[0], k);
            List<FormattedCharSequence> split = font.split(display, Math.max(1, Math.round(avail / scale)));
            if (split.size() <= attempt[1] && (attempt[1] == 1 || (8.0f * scale + 1.0f) * split.size() <= STAMP_H - 8.0f)) {
                e.truncated = false;
                return new StampArt.Face(split, scale, e.icon, e.ink, e.number, false, e.restricted);
            }
        }
        float scale = StampArt.crispDu(plain, 0.5f, k);
        List<FormattedCharSequence> split = font.split(display, Math.max(1, Math.round(avail / scale)));
        e.truncated = split.size() > 2;
        return new StampArt.Face(new ArrayList<>(split.subList(0, Math.min(2, split.size()))), scale, e.icon, e.ink,
                e.number, false, e.restricted);
    }

    /** 最上层：截断的全名与无权限提示。 */
    public void renderOverlay(GuiGraphics g, int mouseX, int mouseY) {
        int selected = keyboardIndex >= 0 ? keyboardIndex : hovered;
        if (selected < 0 || selected >= entries.size()) {
            return;
        }
        Entry e = entries.get(selected);
        if (e.source == modeButton) {
            int tipX = keyboardIndex >= 0 ? Math.round(e.gx + STAMP_W * k / 2.0f) : mouseX;
            int tipY = keyboardIndex >= 0 ? Math.round(e.gy) : mouseY;
            g.renderTooltip(mc.font, mc.font.split(modeButton.description(), 200), tipX, tipY);
            return;
        }
        List<Component> lines = new ArrayList<>();
        if (e.truncated) {
            lines.add(e.label);
        }
        if (TicketInventoryLayer.isLockedKey(e.key)) {
            lines.add(texts.lockedHint());
        }
        if (!lines.isEmpty()) {
            g.renderComponentTooltip(mc.font, lines, mouseX, mouseY);
        }
    }

    // ==================== 输入 ====================

    private int entryAt(double mx, double my) {
        if (bookAt(mx, my)) return -1;
        for (int i = page * perPage; i < Math.min(entries.size(), (page + 1) * perPage); i++) {
            Entry e = entries.get(i);
            float tilt = lightweight ? 0 : ((e.number & 1) == 0 ? 1.2f : -1.2f) * (1.0f - e.hover);
            if (e.progress > 0.8f && TicketUiGeometry.contains(mx, my, e.gx, e.gy,
                    STAMP_W * k, STAMP_H * k, lightweight ? 1 : 1.0f + 0.04f * e.hover, tilt)) {
                return i;
            }
        }
        return -1;
    }

    private boolean bookAt(double mx, double my) {
        float right = width - MARGIN * k;
        float bottom = height - MARGIN * k;
        float lift = lightweight ? 0 : bookHover * 1.5f * k;
        return mx >= right - BOOK_W * k && mx < right && my >= bottom - BOOK_H * k - lift && my < bottom;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || button != 0) {
            return false;
        }
        long now = Util.getMillis();
        keyboardIndex = -2;
        if (bookAt(mouseX, mouseY)) {
            if (toggle != null) {
                pending = null;
                toggle.accept(!open);
            }
            return true;
        }
        if (pagerAt(mouseX, mouseY)) {
            if (mouseX < pagerLeft + 16.0f * k) changePage(-1);
            else if (mouseX >= pagerRight - 16.0f * k) changePage(1);
            return true;
        }
        int index = open ? entryAt(mouseX, mouseY) : -1;
        if (index < 0) {
            return false;
        }
        Entry e = entries.get(index);
        return clickEntry(e, now);
    }

    private boolean clickEntry(Entry e, long now) {
        if (pending != null) {
            return true;
        }
        if (TicketInventoryLayer.isLockedKey(e.key)) {
            e.deniedAt = now;
            TicketSounds.denied();
            MenuPermissions.showDeniedMessage();
            return true;
        }
        e.clickedAt = now;
        visited.add(e.key);
        TicketSounds.postmark();
        pending = e;
        pendingAt = now + ACTION_DELAY_MS;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!active || !open || pages <= 1 || scrollY == 0.0 || !isMouseOver(mouseX, mouseY)) return false;
        changePage(scrollY < 0 ? 1 : -1);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (!active) return false;
        if (open && (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN)) {
            changePage(key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1);
            keyboardIndex = entries.isEmpty() ? -1 : page * perPage;
            focused = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB || focused && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN
                || key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT)) {
            int direction = (key == GLFW.GLFW_KEY_TAB && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0)
                    || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_LEFT ? -1 : 1;
            int count = open ? entries.size() + 1 : 1;
            int next = keyboardIndex < -1 ? -1 : Math.floorMod(keyboardIndex + 1 + direction, count) - 1;
            if (next >= 0 && next / perPage != page) changePage(next / perPage - page);
            keyboardIndex = next;
            focused = true;
            return true;
        }
        if (keyboardIndex >= -1 && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER
                || key == GLFW.GLFW_KEY_SPACE)) {
            if (keyboardIndex == -1 && toggle != null) {
                pending = null;
                toggle.accept(!open);
            } else if (open && keyboardIndex < entries.size()) {
                clickEntry(entries.get(keyboardIndex), Util.getMillis());
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return active && (bookAt(mouseX, mouseY) || pagerAt(mouseX, mouseY) || open && entryAt(mouseX, mouseY) >= 0);
    }

    @Override
    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    public NarrationPriority narrationPriority() {
        if (!active) return NarrationPriority.NONE;
        return keyboardIndex >= -1 ? NarrationPriority.FOCUSED
                : hovered >= 0 || bookHovered ? NarrationPriority.HOVERED : NarrationPriority.NONE;
    }

    @Override
    public void updateNarration(NarrationElementOutput output) {
        int index = keyboardIndex >= 0 ? keyboardIndex : hovered;
        output.add(NarratedElementType.TITLE, index >= 0 && index < entries.size() ? entries.get(index).label
                : texts.label("inventory.habitrain_core.ticket.menu"));
        output.add(NarratedElementType.USAGE, texts.label(index >= 0 && index < entries.size()
                && entries.get(index).source == modeButton ? "inventory.habitrain_core.ticket.mode.hint"
                : "inventory.habitrain_core.ticket.controls_pages"));
    }

    private static float easeOutBack(float x) {
        float c1 = 1.5f;
        float c3 = c1 + 1.0f;
        float t = TransitionFx.clamp01(x) - 1.0f;
        return 1.0f + c3 * t * t * t + c1 * t * t;
    }

    /** 一枚对局菜单邮票。 */
    private static final class Entry {
        final Button source;
        Component label;
        final String key;
        final WireModels.Icon icon;
        final StampArt.Ink ink;
        final int number;
        final boolean restricted;
        StampArt.Face face;
        final StampArt.Cache art = new StampArt.Cache();
        boolean truncated;
        float hover;
        float yaw;
        float progress;
        long clickedAt = -1L;
        long deniedAt = -1L;
        float gx;
        float gy;

        Entry(Button source, int number) {
            this.source = source;
            this.label = source.getMessage();
            this.key = source instanceof TicketPerformanceButton ? TicketPerformanceButton.KEY
                    : TicketInventoryLayer.keyOf(label);
            this.icon = TicketInventoryLayer.iconFor(key);
            this.ink = TicketInventoryLayer.inkFor(key, number);
            this.number = number;
            this.restricted = TicketInventoryLayer.isRestricted(label);
            this.yaw = TicketArt.hash(key.hashCode() * 3) * Mth.TWO_PI;
        }
    }
}
