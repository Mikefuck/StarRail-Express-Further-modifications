package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.scene.model.SceneMapDropdownModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Manual, overlay-aware map dropdown used by the scene editor page. */
public final class SceneMapDropdown {
    public static final int SEARCH_THRESHOLD = 8;
    public static final int MAX_VISIBLE_ROWS = 8;
    private static final int ROW_HEIGHT = 30;
    private static final int SEARCH_HEIGHT = 18;
    private static final int GAP = 3;
    private static final float OVERLAY_Z = 400.0f;

    private final Font font;
    private final Consumer<String> selectionListener;
    private final EditBox searchBox;
    private final String kind;

    private List<SceneMapDropdownModel.Entry> entries = List.of();
    private List<SceneMapDropdownModel.Entry> filteredEntries = List.of();
    private String selectedKey = "";
    private String query = "";
    private boolean open;
    private boolean focused;
    private int highlightedIndex = -1;
    private int scrollOffset;
    private int visibleRows = 1;
    private Rect buttonBounds = Rect.EMPTY;
    private Rect panelBounds = Rect.EMPTY;
    private Rect searchBounds = Rect.EMPTY;
    private int rowsTop;

    public SceneMapDropdown(Font font, Consumer<String> selectionListener) {
        this(font, selectionListener, "map");
    }

    public SceneMapDropdown(Font font, Consumer<String> selectionListener, String kind) {
        this.font = font;
        this.selectionListener = selectionListener;
        this.kind = kind == null || kind.isBlank() ? "map" : kind;
        this.searchBox = new EditBox(font, 0, 0, 100, SEARCH_HEIGHT,
                Component.translatable("screen.habitrain_core.scene_motion." + this.kind + "_search"));
        this.searchBox.setMaxLength(64);
        this.searchBox.setHint(Component.translatable("screen.habitrain_core.scene_motion." + this.kind + "_search_hint"));
        this.searchBox.setResponder(value -> {
            query = value;
            rebuildFilter();
        });
    }

    public void setEntries(List<SceneMapDropdownModel.Entry> entries, String selectedKey) {
        List<SceneMapDropdownModel.Entry> nextEntries = entries == null ? List.of() : List.copyOf(entries);
        String nextSelectedKey = selectedKey == null ? "" : selectedKey;
        if (this.entries.equals(nextEntries) && Objects.equals(this.selectedKey, nextSelectedKey)) return;
        this.entries = nextEntries;
        this.selectedKey = nextSelectedKey;
        rebuildFilter();
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isFocused() {
        return focused;
    }

    public void focus() {
        focused = true;
        narrate(Component.translatable("screen.habitrain_core.scene_motion.map_dropdown_narration",
                selectedSummary()));
    }

    public void blur() {
        focused = false;
        searchBox.setFocused(false);
    }

    /** Closes this popup when another selector takes ownership of the overlay layer. */
    public void dismiss() {
        close();
        blur();
    }

    public void renderButton(GuiGraphics graphics, int mouseX, int mouseY,
                             int x, int y, int width, int height) {
        buttonBounds = new Rect(x, y, Math.max(1, width), Math.max(1, height));
        boolean hovered = buttonBounds.contains(mouseX, mouseY);
        String arrow = open ? "▴" : "▾";
        String prefix = Component.translatable("screen.habitrain_core.scene_motion." + kind).getString();
        String label = fit(prefix + " " + selectedSummary(), Math.max(0, width - 24));
        MenuTheme.button(graphics, font, label, x, y, width, height,
                MenuTheme.ACCENT_BLUE, true, hovered || focused || open);
        graphics.drawString(font, arrow, x + width - 14,
                y + (height - font.lineHeight) / 2, MenuTheme.TEXT_SECONDARY, false);
        if (focused) MenuTheme.outline(graphics, x - 1, y - 1, width + 2, height + 2,
                MenuTheme.ACCENT_MINT);
    }

    /** Draw after the page content so the dropdown stays above the scrollable editor. */
    public void renderOverlay(GuiGraphics graphics, int mouseX, int mouseY, float delta, int bottom) {
        if (!open) return;
        // Text fields and colored rectangles use different buffered render types. Without a
        // flush, an earlier EditBox glyph batch may be submitted after this panel and appear
        // above it. Commit the base layer first, then render the whole popup at an explicit Z.
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0f, 0.0f, OVERLAY_Z);
        try {
            boolean hasSearch = entries.size() > SEARCH_THRESHOLD;
            int panelX = buttonBounds.x();
            int panelY = buttonBounds.y() + buttonBounds.height() + GAP;
            int availableRows = Math.max(1,
                    (bottom - panelY - 4 - (hasSearch ? SEARCH_HEIGHT + GAP : 0)) / ROW_HEIGHT);
            visibleRows = Math.max(1, Math.min(MAX_VISIBLE_ROWS, availableRows));
            int renderedRows = Math.max(1, Math.min(visibleRows, filteredEntries.size()));
            int panelHeight = 4 + (hasSearch ? SEARCH_HEIGHT + GAP : 0) + renderedRows * ROW_HEIGHT;
            panelBounds = new Rect(panelX, panelY, buttonBounds.width(), panelHeight);
            graphics.fill(panelX, panelY, panelX + panelBounds.width(), panelY + panelHeight,
                    MenuTheme.BG_SIDEBAR);
            MenuTheme.outline(graphics, panelX, panelY, panelBounds.width(), panelHeight, MenuTheme.ACCENT_BLUE);

            int cursorY = panelY + 2;
            if (hasSearch) {
                searchBounds = new Rect(panelX + 3, cursorY, panelBounds.width() - 6, SEARCH_HEIGHT);
                searchBox.setX(searchBounds.x());
                searchBox.setY(searchBounds.y());
                searchBox.setWidth(searchBounds.width());
                searchBox.render(graphics, mouseX, mouseY, delta);
                cursorY += SEARCH_HEIGHT + GAP;
            } else {
                searchBounds = Rect.EMPTY;
            }

            rowsTop = cursorY;
            scrollOffset = SceneMapDropdownModel.keepVisible(
                    scrollOffset, Math.max(0, highlightedIndex), visibleRows, filteredEntries.size());
            if (filteredEntries.isEmpty()) {
                String empty = Component.translatable("screen.habitrain_core.scene_motion.map_no_results").getString();
                graphics.drawString(font, fit(empty, panelBounds.width() - 12), panelX + 6,
                        cursorY + (ROW_HEIGHT - font.lineHeight) / 2, MenuTheme.TEXT_DIM, false);
                return;
            }

            int end = Math.min(filteredEntries.size(), scrollOffset + visibleRows);
            for (int index = scrollOffset; index < end; index++) {
                SceneMapDropdownModel.Entry entry = filteredEntries.get(index);
                int rowY = cursorY + (index - scrollOffset) * ROW_HEIGHT;
                Rect row = new Rect(panelX + 2, rowY, panelBounds.width() - 4, ROW_HEIGHT);
                boolean selected = entry.key().equals(selectedKey);
                boolean highlighted = index == highlightedIndex;
                MenuTheme.row(graphics, row.x(), row.y(), row.width(), row.height(),
                        row.contains(mouseX, mouseY) || highlighted, selected);

                int textWidth = row.width() - 12;
                graphics.drawString(font, fit(entry.label(), textWidth), row.x() + 6, row.y() + 4,
                        selected ? MenuTheme.ACCENT_MINT : MenuTheme.TEXT_PRIMARY, false);
                String detail = detailLine(entry);
                graphics.drawString(font, fit(detail, textWidth), row.x() + 6,
                        row.y() + 17, MenuTheme.TEXT_SECONDARY, false);
            }

            if (filteredEntries.size() > visibleRows) {
                int trackX = panelX + panelBounds.width() - 3;
                int rowsHeight = visibleRows * ROW_HEIGHT;
                int maxOffset = filteredEntries.size() - visibleRows;
                MenuTheme.drawScrollbar(graphics, trackX, rowsTop, rowsHeight,
                        scrollOffset * (double) ROW_HEIGHT, maxOffset * (double) ROW_HEIGHT, 2);
            }
        } finally {
            // Flush while the translated pose is still active; otherwise buffered dropdown
            // text could inherit the restored base transform and lose the overlay guarantee.
            graphics.flush();
            graphics.pose().popPose();
        }
    }

    public void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!open && buttonBounds.contains(mouseX, mouseY)) {
            graphics.renderTooltip(font,
                    Component.translatable("screen.habitrain_core.scene_motion." + kind + "_dropdown_tooltip"),
                    mouseX, mouseY);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return open;
        if (!open && buttonBounds.contains(mouseX, mouseY)) {
            focused = true;
            open();
            return true;
        }
        if (!open) return false;
        if (searchBounds.contains(mouseX, mouseY)) {
            searchBox.mouseClicked(mouseX, mouseY, button);
            searchBox.setFocused(true);
            return true;
        }
        if (panelBounds.contains(mouseX, mouseY) && mouseY >= rowsTop && !filteredEntries.isEmpty()) {
            int row = (int) ((mouseY - rowsTop) / ROW_HEIGHT);
            int index = scrollOffset + row;
            if (index >= 0 && index < filteredEntries.size()) {
                choose(index);
            }
            return true;
        }
        close();
        return true;
    }

    /** An open dropdown owns the wheel even if the pointer has just left its panel. */
    public boolean mouseScrolled(double amount) {
        if (!open) return false;
        if (filteredEntries.size() > visibleRows && amount != 0.0) {
            int maxOffset = Math.max(0, filteredEntries.size() - visibleRows);
            scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset + (amount < 0 ? 1 : -1)));
            highlightedIndex = Math.max(scrollOffset,
                    Math.min(highlightedIndex, scrollOffset + visibleRows - 1));
        }
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (open && searchBox.isFocused() && searchBox.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == 256 && open) {
            close();
            return true;
        }
        if (!open) {
            if (focused && (keyCode == 257 || keyCode == 32 || keyCode == 264 || keyCode == 265)) {
                open();
                if (keyCode == 264) moveHighlight(1);
                if (keyCode == 265) moveHighlight(-1);
                return true;
            }
            return false;
        }
        if (keyCode == 264) {
            searchBox.setFocused(false);
            moveHighlight(1);
            return true;
        }
        if (keyCode == 265) {
            searchBox.setFocused(false);
            moveHighlight(-1);
            return true;
        }
        if (keyCode == 268 && !filteredEntries.isEmpty()) {
            searchBox.setFocused(false);
            highlightedIndex = 0;
            scrollOffset = 0;
            narrateHighlighted();
            return true;
        }
        if (keyCode == 269 && !filteredEntries.isEmpty()) {
            searchBox.setFocused(false);
            highlightedIndex = filteredEntries.size() - 1;
            scrollOffset = Math.max(0, filteredEntries.size() - visibleRows);
            narrateHighlighted();
            return true;
        }
        if (keyCode == 32 && searchBox.isFocused()) {
            return true;
        }
        if ((keyCode == 257 || keyCode == 32) && highlightedIndex >= 0) {
            choose(highlightedIndex);
            return true;
        }
        if (keyCode == 258) {
            close();
            return false;
        }
        return true;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        return open && searchBox.isFocused() && searchBox.charTyped(codePoint, modifiers);
    }

    private void open() {
        open = true;
        rebuildFilter();
        if (entries.size() > SEARCH_THRESHOLD) searchBox.setFocused(true);
        narrate(Component.translatable("screen.habitrain_core.scene_motion.map_dropdown_opened",
                filteredEntries.size()));
    }

    private void close() {
        open = false;
        searchBox.setFocused(false);
    }

    private void rebuildFilter() {
        filteredEntries = SceneMapDropdownModel.filter(entries, query);
        highlightedIndex = SceneMapDropdownModel.selectedIndex(filteredEntries, selectedKey);
        scrollOffset = SceneMapDropdownModel.keepVisible(
                scrollOffset, Math.max(0, highlightedIndex), visibleRows, filteredEntries.size());
    }

    private void moveHighlight(int delta) {
        highlightedIndex = SceneMapDropdownModel.moveIndex(
                highlightedIndex, delta, filteredEntries.size());
        scrollOffset = SceneMapDropdownModel.keepVisible(
                scrollOffset, Math.max(0, highlightedIndex), visibleRows, filteredEntries.size());
        narrateHighlighted();
    }

    private void choose(int index) {
        if (index < 0 || index >= filteredEntries.size()) return;
        SceneMapDropdownModel.Entry entry = filteredEntries.get(index);
        selectedKey = entry.key();
        close();
        searchBox.setValue("");
        selectionListener.accept(entry.key());
        narrate(Component.translatable("screen.habitrain_core.scene_motion.map_selected_narration",
                entry.label(), detailLine(entry)));
    }

    private void narrateHighlighted() {
        if (highlightedIndex < 0 || highlightedIndex >= filteredEntries.size()) return;
        SceneMapDropdownModel.Entry entry = filteredEntries.get(highlightedIndex);
        narrate(Component.literal(entry.label() + ". " + detailLine(entry)));
    }

    private void narrate(Component message) {
        Minecraft.getInstance().getNarrator().sayNow(message);
    }

    private String selectedSummary() {
        for (SceneMapDropdownModel.Entry entry : entries) {
            if (entry.key().equals(selectedKey)) {
                if (entry.key().equals("__default__")) return entry.label();
                return entry.label().equals(entry.key())
                        ? entry.key() : entry.label() + " (" + entry.key() + ")";
            }
        }
        return selectedKey;
    }

    private String detailLine(SceneMapDropdownModel.Entry entry) {
        StringBuilder detail = new StringBuilder();
        if (!entry.key().equals("__default__")) detail.append(entry.key()).append("  ·  ");
        detail.append(Component.translatable(entry.enabled()
                ? "screen.habitrain_core.scene_motion.map_scene_enabled"
                : "screen.habitrain_core.scene_motion.map_scene_disabled").getString());
        if (entry.runtime()) detail.append("  ·  ").append(Component.translatable(
                "screen.habitrain_core.scene_motion.map_runtime").getString());
        if (entry.editing()) detail.append("  ·  ").append(Component.translatable(
                "screen.habitrain_core.scene_motion.map_editing").getString());
        return detail.toString();
    }

    private String fit(String value, int width) {
        if (value == null || width <= 0) return "";
        if (font.width(value) <= width) return value;
        String ellipsis = "…";
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width(ellipsis))) + ellipsis;
    }

    private record Rect(int x, int y, int width, int height) {
        private static final Rect EMPTY = new Rect(0, 0, 0, 0);

        boolean contains(double mouseX, double mouseY) {
            return width > 0 && height > 0
                    && MenuTheme.inBounds(mouseX, mouseY, x, y, width, height);
        }
    }
}
