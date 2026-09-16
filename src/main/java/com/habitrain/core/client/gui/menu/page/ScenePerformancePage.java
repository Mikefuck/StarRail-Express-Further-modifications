package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.config.ClientVisualPreferences;
import com.habitrain.core.client.config.SceneClientPerformanceRules;
import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MenuSounds;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.PillToggle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏外 · 场景性能：本机磁盘缓存、断点续传与分片请求节奏。
 *
 * <p>这些都是**客户端本地**取值——约束的是本机磁盘、重试节奏与帧时间，服务器无从得知，
 * 也不该有权要求客户端占用多少磁盘。因此本页不走服务端保存栏，任何改动立即生效。</p>
 */
public final class ScenePerformancePage implements ConfigPage {
    private static final int PAD = 14;
    private static final int ROW_H = 24;
    private static final int ROW_GAP = 6;
    private static final int BTN = 16;
    private static final int HEADER_H = 46;

    /** 可调节的行。顺序即渲染顺序。 */
    private enum Row {
        RESUME,
        DISK_QUOTA,
        PARTIAL_QUOTA,
        PARTIAL_EXPIRY,
        TIMEOUT,
        RETRIES,
        WINDOW,
        BUILD_BUDGET,
        MEMORY_QUOTA,
        MESH_QUOTA
    }

    private static final Row[] ROWS = Row.values();
    private static final int ROW_STRIDE = ROW_H + ROW_GAP;

    private record Hit(int x, int y, int w, int h) {
        boolean test(double mx, double my) {
            return MenuTheme.inBounds(mx, my, x, y, w, h);
        }
    }

    private final Font font;
    private final List<Hit[]> rowHits = new ArrayList<>();
    private final Hit[] rowBounds = new Hit[ROWS.length];
    private int focusedRow = -1;
    private boolean saveFailed;
    /** 垂直滚动偏移。GUI scale 3 下一屏只放得下 6 行，没有它新行根本不可见。 */
    private int scroll;
    /** 一屏能显示的行数（最近一次 render 时测得）。 */
    private int visibleRows = ROWS.length;

    public ScenePerformancePage(Font font) {
        this.font = font;
    }

    /** 本机偏好立即持久化，不使用服务端保存栏。 */
    @Override public boolean canSave() { return false; }
    @Override public void save() {}
    @Override public void flushPending() {}

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        int cardX = x + PAD;
        int cardY = y + PAD;
        int cardW = Math.max(1, w - PAD * 2);
        int cardH = Math.max(160, h - PAD * 2);
        MenuTheme.panel(g, cardX, cardY, cardW, cardH);
        MenuTheme.drawAccentStripe(g, cardX, cardY, cardH, MenuTheme.ACCENT_MINT);

        g.drawString(font, Component.translatable("screen.habitrain_core.scene_performance.title").getString(),
                cardX + 12, cardY + 11, MenuTheme.TEXT_PRIMARY, false);
        int textW = Math.max(20, cardW - 24);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(
                Component.translatable("screen.habitrain_core.scene_performance.description"), textW);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            g.drawString(font, lines.get(i), cardX + 12, cardY + 27 + i * 11,
                    MenuTheme.TEXT_SECONDARY, false);
        }

        rowHits.clear();
        java.util.Arrays.fill(rowBounds, null);
        int listTop = cardY + HEADER_H;
        int listBottom = cardY + cardH - 8;
        int rowW = Math.max(1, cardW - 24);
        int rowX = cardX + 12;
        visibleRows = Math.max(1, (listBottom - listTop) / ROW_STRIDE);
        int maxScroll = Math.max(0, (ROWS.length - visibleRows) * ROW_STRIDE);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        for (int i = 0; i < ROWS.length; i++) {
            int rowY = listTop + i * ROW_STRIDE - scroll;
            if (rowY + ROW_H > listBottom) break;
            if (rowY < listTop) continue; // 只画完整落在可视区内的行
            rowBounds[i] = new Hit(rowX, rowY, rowW, ROW_H);
            renderRow(g, ROWS[i], rowX, rowY, rowW, mx, my, i == focusedRow);
        }

        if (maxScroll > 0) {
            drawScrollbar(g, cardX + cardW - 6, listTop, listBottom - listTop, maxScroll);
        }

        if (saveFailed) {
            String error = Component.translatable("screen.habitrain_core.scene_performance.save_failed").getString();
            g.drawString(font, error, cardX + 12, cardY + cardH - 18, MenuTheme.DANGER, false);
        }
    }

    /** 极简滚动条：只表示"还有更多行"，不参与命中测试。 */
    private void drawScrollbar(GuiGraphics g, int x, int top, int height, int maxScroll) {
        int track = Math.max(1, height);
        g.fill(x, top, x + 3, top + track, MenuTheme.BG_EDIT);
        int thumbH = Math.max(12, track * visibleRows / ROWS.length);
        int travel = Math.max(0, track - thumbH);
        int thumbY = top + (maxScroll <= 0 ? 0 : travel * scroll / maxScroll);
        g.fill(x, thumbY, x + 3, thumbY + thumbH, MenuTheme.ACCENT_MINT);
    }

    private void renderRow(GuiGraphics g, Row row, int x, int y, int w, int mx, int my, boolean focused) {
        boolean hover = MenuTheme.inBounds(mx, my, x, y, w, ROW_H);
        g.fill(x, y, x + w, y + ROW_H, hover ? MenuTheme.BG_ROW_HOVER : MenuTheme.BG_ROW);
        if (focused) {
            MenuTheme.drawAccentStripe(g, x, y, ROW_H, MenuTheme.ACCENT_MINT);
        }
        g.drawString(font, label(row), x + 10, y + (ROW_H - 8) / 2, MenuTheme.TEXT_PRIMARY, false);
        String hint = hint(row);
        int hintX = x + 10 + font.width(label(row)) + 8;
        if (hintX + font.width(hint) < x + w - 150) {
            g.drawString(font, hint, hintX, y + (ROW_H - 8) / 2, MenuTheme.TEXT_DIM, false);
        }

        if (row == Row.RESUME) {
            int toggleW = Math.max(1, Math.min(150, w / 3));
            int toggleX = x + w - toggleW - 8;
            int toggleY = y + (ROW_H - 18) / 2;
            boolean on = ClientVisualPreferences.isPartialResumeEnabled();
            PillToggle.render(g, font, toggleX, toggleY, toggleW, 18, on,
                    Component.translatable("screen.habitrain_core.scene_performance.enabled").getString(),
                    Component.translatable("screen.habitrain_core.scene_performance.disabled").getString());
            rowHits.add(new Hit[] {new Hit(toggleX, toggleY, toggleW, 18)});
            return;
        }

        int plusX = x + w - BTN - 8;
        int minusX = plusX - BTN - 6;
        int btnY = y + (ROW_H - BTN) / 2;
        Hit minus = new Hit(minusX, btnY, BTN, BTN);
        Hit plus = new Hit(plusX, btnY, BTN, BTN);
        drawButton(g, minus, "-", minus.test(mx, my));
        drawButton(g, plus, "+", plus.test(mx, my));

        String value = valueText(row);
        g.drawString(font, value, minusX - font.width(value) - 8, y + (ROW_H - 8) / 2,
                MenuTheme.ACCENT_AMBER, false);
        rowHits.add(new Hit[] {minus, plus});
    }

    private void drawButton(GuiGraphics g, Hit hit, String glyph, boolean hover) {
        g.fill(hit.x(), hit.y(), hit.x() + hit.w(), hit.y() + hit.h(),
                hover ? MenuTheme.BG_ELEVATED : MenuTheme.BG_EDIT);
        g.fill(hit.x(), hit.y(), hit.x() + hit.w(), hit.y() + 1, MenuTheme.BORDER);
        g.drawString(font, glyph, hit.x() + (hit.w() - font.width(glyph)) / 2,
                hit.y() + (hit.h() - 8) / 2, MenuTheme.TEXT_PRIMARY, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (btn != 0) return false;
        for (int i = 0; i < ROWS.length; i++) {
            Hit bounds = rowBounds[i];
            if (bounds == null || i >= rowHits.size()) break;
            if (!bounds.test(mx, my)) continue;
            focusedRow = i;
            Hit[] hits = rowHits.get(i);
            if (hits.length == 1) {
                toggleResume();
                return true;
            }
            if (hits.length == 2) {
                if (hits[0].test(mx, my)) {
                    adjust(ROWS[i], -1);
                    return true;
                }
                if (hits[1].test(mx, my)) {
                    adjust(ROWS[i], 1);
                    return true;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        switch (key) {
            case 258 -> { // TAB：在可调节行之间移动焦点
                focusedRow = focusedRow < 0 ? 0 : (focusedRow + (mod == 1 ? ROWS.length - 1 : 1)) % ROWS.length;
                scrollRowIntoView(focusedRow);
                MenuSounds.playClick();
                return true;
            }
            case 263 -> { // LEFT
                if (focusedRow >= 0) {
                    if (ROWS[focusedRow] == Row.RESUME) toggleResume();
                    else adjust(ROWS[focusedRow], -1);
                    return true;
                }
                return false;
            }
            case 262 -> { // RIGHT
                if (focusedRow >= 0) {
                    if (ROWS[focusedRow] == Row.RESUME) toggleResume();
                    else adjust(ROWS[focusedRow], 1);
                    return true;
                }
                return false;
            }
            case 257, 335, 32 -> { // ENTER / KP_ENTER / SPACE
                if (focusedRow >= 0 && ROWS[focusedRow] == Row.RESUME) {
                    toggleResume();
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy,
                                           int x, int y, int w, int h) { return false; }
    @Override public boolean mouseReleased(double mx, double my, int btn) { return false; }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy,
                                 int x, int y, int w, int h) {
        int maxScroll = Math.max(0, (ROWS.length - visibleRows) * ROW_STRIDE);
        if (maxScroll <= 0) return false;
        int delta = (int) Math.signum(sy) * ROW_STRIDE;
        int next = Math.max(0, Math.min(maxScroll, scroll - delta));
        if (next == scroll) return false;
        scroll = next;
        return true;
    }

    @Override public boolean charTyped(char ch, int mod) { return false; }

    /** 键盘焦点移动后把目标行滚进可视区，避免"焦点在看不见的行上"。 */
    private void scrollRowIntoView(int row) {
        int maxScroll = Math.max(0, (ROWS.length - visibleRows) * ROW_STRIDE);
        if (maxScroll <= 0) return;
        int rowTop = row * ROW_STRIDE;
        if (rowTop < scroll) {
            scroll = rowTop;
        } else if (rowTop + ROW_H > scroll + visibleRows * ROW_STRIDE) {
            scroll = rowTop + ROW_H - visibleRows * ROW_STRIDE;
        }
        scroll = Math.max(0, Math.min(maxScroll, scroll));
    }

    // ---- 取值与写回 ----

    private String label(Row row) {
        return Component.translatable(key(row) + ".label").getString();
    }

    private String hint(Row row) {
        return Component.translatable(key(row) + ".hint").getString();
    }

    private static String key(Row row) {
        return switch (row) {
            case RESUME -> "screen.habitrain_core.scene_performance.resume";
            case DISK_QUOTA -> "screen.habitrain_core.scene_performance.disk_quota";
            case PARTIAL_QUOTA -> "screen.habitrain_core.scene_performance.partial_quota";
            case PARTIAL_EXPIRY -> "screen.habitrain_core.scene_performance.partial_expiry";
            case TIMEOUT -> "screen.habitrain_core.scene_performance.timeout";
            case RETRIES -> "screen.habitrain_core.scene_performance.retries";
            case WINDOW -> "screen.habitrain_core.scene_performance.window";
            case BUILD_BUDGET -> "screen.habitrain_core.scene_performance.build_budget";
            case MEMORY_QUOTA -> "screen.habitrain_core.scene_performance.mem_quota";
            case MESH_QUOTA -> "screen.habitrain_core.scene_performance.mesh_quota";
        };
    }

    private String valueText(Row row) {
        return switch (row) {
            case DISK_QUOTA -> ClientVisualPreferences.getDiskCacheQuotaMiB() + " MiB";
            case PARTIAL_QUOTA -> ClientVisualPreferences.getPartialCacheQuotaMiB() + " MiB";
            case PARTIAL_EXPIRY -> ClientVisualPreferences.getPartialExpiryHours() + " h";
            case TIMEOUT -> ClientVisualPreferences.getRequestTimeoutMs() + " ms";
            case RETRIES -> String.valueOf(ClientVisualPreferences.getMaxChunkRetries());
            case WINDOW -> Component.translatable(
                    "screen.habitrain_core.scene_performance.window.unit",
                    ClientVisualPreferences.getTransferWindowChunks()).getString();
            case BUILD_BUDGET -> ClientVisualPreferences.getMeshBuildBudgetMsPerFrame() + " ms";
            case MEMORY_QUOTA -> ClientVisualPreferences.getMemoryCacheQuotaMiB() + " MiB";
            case MESH_QUOTA -> ClientVisualPreferences.getMeshCacheQuotaMiB() + " MiB";
            case RESUME -> "";
        };
    }

    private void toggleResume() {
        boolean next = !ClientVisualPreferences.isPartialResumeEnabled();
        saveFailed = !ClientVisualPreferences.setPartialResumeEnabled(next);
        if (!saveFailed) applyNow();
        MenuSounds.playClick();
    }

    private void adjust(Row row, int direction) {
        int step = step(row);
        boolean ok = switch (row) {
            case RESUME -> false;
            case DISK_QUOTA -> ClientVisualPreferences.setDiskCacheQuotaMiB(
                    ClientVisualPreferences.getDiskCacheQuotaMiB() + direction * step);
            case PARTIAL_QUOTA -> ClientVisualPreferences.setPartialCacheQuotaMiB(
                    ClientVisualPreferences.getPartialCacheQuotaMiB() + direction * step);
            case PARTIAL_EXPIRY -> ClientVisualPreferences.setPartialExpiryHours(
                    ClientVisualPreferences.getPartialExpiryHours() + direction * step);
            case TIMEOUT -> ClientVisualPreferences.setRequestTimeoutMs(
                    ClientVisualPreferences.getRequestTimeoutMs() + direction * step);
            case RETRIES -> ClientVisualPreferences.setMaxChunkRetries(
                    ClientVisualPreferences.getMaxChunkRetries() + direction * step);
            case WINDOW -> ClientVisualPreferences.setTransferWindowChunks(
                    ClientVisualPreferences.getTransferWindowChunks() + direction * step);
            case BUILD_BUDGET -> ClientVisualPreferences.setMeshBuildBudgetMsPerFrame(
                    ClientVisualPreferences.getMeshBuildBudgetMsPerFrame() + direction * step);
            case MEMORY_QUOTA -> ClientVisualPreferences.setMemoryCacheQuotaMiB(
                    ClientVisualPreferences.getMemoryCacheQuotaMiB() + direction * step);
            case MESH_QUOTA -> ClientVisualPreferences.setMeshCacheQuotaMiB(
                    ClientVisualPreferences.getMeshCacheQuotaMiB() + direction * step);
        };
        saveFailed = !ok;
        if (ok) applyNow();
        MenuSounds.playClick();
    }

    private static int step(Row row) {
        return switch (row) {
            case TIMEOUT -> 500;
            case PARTIAL_EXPIRY -> 24;
            case PARTIAL_QUOTA -> 32;
            case DISK_QUOTA -> SceneClientPerformanceRules.MIN_DISK_CACHE_QUOTA_MIB;
            case MEMORY_QUOTA -> 64;
            case MESH_QUOTA -> 128;
            case RETRIES, RESUME, WINDOW, BUILD_BUDGET -> 1;
        };
    }

    /** 写盘成功后立刻让运行中的缓存用上新值，不必重开游戏。 */
    private static void applyNow() {
        com.habitrain.core.scene.client.SceneClientRuntime.applyClientConfig();
    }
}
