package com.habitrain.core.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionVoteLayoutTest {
    private static final int[] WIDTHS = {320, 427, 480, 640, 854, 960, 1280, 1920};
    private static final int[] HEIGHTS = {180, 240, 270, 360, 480, 540, 720, 1080};

    @Test
    void panelStaysCenteredInTheUpperHalfAtEveryGuiScale() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                for (int count : new int[] {0, 1, 3, 6, 20}) {
                    var panel = OptionVoteLayout.panel(width, height, count);
                    assertTrue(panel.x() >= 0 && panel.right() <= width, "Panel must stay on screen");
                    assertTrue(Math.abs(panel.x() - (width - panel.right())) <= 1, "Panel must be horizontally centered");
                    // 自动 GUI 缩放下高度至少 240；更小的高度只出现在强制大缩放时，允许略超半屏
                    int limit = height >= 240 ? height / 2 : Math.round(height * 0.55f);
                    assertTrue(panel.height() <= limit, "Panel must stay in the upper half: " + width + "x" + height);
                    assertTrue(panel.width() <= OptionVoteLayout.MAX_PANEL_WIDTH, "Panel must stay compact");
                    assertTrue(panel.cardH() >= (height >= 240 ? 46 : 38), "Tickets must remain readable");
                    assertTrue(panel.stripBottom() < panel.footerY(), "Tickets must clear the hint row");
                    assertTrue(panel.footerY() + 9 <= panel.height(), "Hint row must fit inside the panel");
                    assertTrue(panel.stripX() >= panel.x() && panel.stripX() + panel.stripW() <= panel.right(),
                            "Ticket strip must stay inside the panel");
                }
            }
        }
    }

    @Test
    void everyFocusedTicketIsFullyVisibleInALongStrip() {
        for (int width : WIDTHS) {
            for (int count : new int[] {1, 2, 4, 9, 30}) {
                var panel = OptionVoteLayout.panel(width, 270, count);
                int step = panel.cardW() + OptionVoteLayout.GAP;
                for (int index = 0; index < count; index++) {
                    float scroll = OptionVoteLayout.scrollTarget(panel, index, count);
                    float cardLeft = panel.stripX() - scroll + index * step;
                    assertTrue(cardLeft >= panel.stripX() - 0.01f, "Focused ticket must clear the left edge");
                    assertTrue(cardLeft + panel.cardW() <= panel.stripX() + panel.stripW() + 0.01f,
                            "Focused ticket must clear the right edge");
                }
            }
        }
    }

    @Test
    void shortStripsAreCenteredWithoutArrows() {
        var panel = OptionVoteLayout.panel(854, 480, 2);
        assertEquals(false, panel.arrows());
        float scroll = OptionVoteLayout.scrollTarget(panel, 0, 2);
        float left = panel.stripX() - scroll;
        float right = left + panel.contentWidth(2);
        assertEquals(panel.stripX() + panel.stripW() - right, left - panel.stripX(), 0.51f);
        assertTrue(OptionVoteLayout.panel(427, 240, 12).arrows(), "Long candidate lists page horizontally");
    }
}
