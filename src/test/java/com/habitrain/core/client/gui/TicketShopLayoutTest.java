package com.habitrain.core.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TicketShopLayoutTest {
    @Test
    void fullShopFitsWithRealHotbarAndEntirePlayerSelectorAcrossGuiSizes() {
        int accepted = 0;
        int fallback = 0;
        for (int width : new int[] {240, 320, 427, 640, 800, 960, 1920}) {
            for (int height : new int[] {180, 240, 270, 360, 450, 540, 1080}) {
                for (int count : new int[] {0, 1, 2, 7, 18, 27, 31, 100}) {
                    for (boolean players : new boolean[] {false, true}) {
                        var fitted = TicketShopLayout.fit(width, height, count, players);
                        if (fitted.isEmpty()) {
                            fallback++;
                            continue;
                        }
                        accepted++;
                        TicketShopLayout layout = fitted.get();
                        String size = width + "x" + height + ", entries=" + count + ", players=" + players;
                        assertTrue(layout.left() >= 8, size);
                        assertTrue(layout.left() + layout.width() <= width - 8, size);
                        assertTrue(layout.top() >= 20, size);
                        assertEquals(layout.top() + layout.height() + 6, layout.hotbarY(), size);
                        assertTrue(layout.hotbarY() + (players ? 140 : 32) <= height - 8, size);
                        assertTrue(layout.rows() >= 1 && layout.rows() <= 3, size);
                        assertEquals(0, layout.start(), size);
                        assertEquals(count, layout.end(), size);
                        assertEquals(1.0f, layout.scale(), size);
                        for (int i = 0; i < count; i++) {
                            assertTrue(layout.cardX(i) >= layout.left() + layout.padding(), size);
                            assertTrue(layout.cardX(i) + layout.cardWidth()
                                    <= layout.left() + layout.width() - layout.padding(), size);
                            assertTrue(layout.cardY(i) >= layout.top() + layout.header(), size);
                            assertTrue(layout.cardY(i) + layout.cardHeight() < layout.navY(), size);
                            assertTrue(layout.contains(layout.cardX(i) + 8, layout.cardY(i) + 8), size);
                            if (i + 1 < count && (i + 1) % layout.columns() != 0) {
                                assertTrue(layout.cardX(i) + layout.cardWidth() < layout.cardX(i + 1), size);
                            }
                            if (i + layout.columns() < count) {
                                assertTrue(layout.cardY(i) + layout.cardHeight()
                                        < layout.cardY(i + layout.columns()), size);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(accepted > 0);
        assertTrue(fallback > 0);
    }

    @Test
    void oneTwoAndThreeRowsMoveTheHotbarAndPlayersTogether() {
        var one = TicketShopLayout.fit(800, 450, 9, true).orElseThrow();
        var two = TicketShopLayout.fit(800, 450, 18, true).orElseThrow();
        var three = TicketShopLayout.fit(800, 450, 27, true).orElseThrow();
        assertEquals(1, one.rows());
        assertEquals(2, two.rows());
        assertEquals(3, three.rows());
        assertEquals(26, two.hotbarY() - one.hotbarY());
        assertEquals(26, three.hotbarY() - two.hotbarY());
        assertTrue(three.top() < two.top());
        assertTrue(two.top() < one.top());
        assertTrue(TicketShopLayout.fit(800, 450, 28, true).isEmpty());
    }

    @Test
    void playerNavigationAndGuiScalingCanForceUpstreamFallback() {
        assertTrue(TicketShopLayout.fit(800, 365, 27, true).isEmpty());
        var exactFit = TicketShopLayout.fit(800, 366, 27, true).orElseThrow();
        assertEquals(20, exactFit.top());
        assertEquals(358, exactFit.hotbarY() + TicketShopLayout.ROLE_SELECTION_BOTTOM);
        assertTrue(TicketShopLayout.fit(800, 270, 27, false).isPresent());
        assertTrue(TicketShopLayout.fit(800, 270, 27, true).isEmpty());
        // Use the effective GUI viewport: a smaller one must fall back instead of shrinking cards.
        assertTrue(TicketShopLayout.fit(800, 450, 18, true).isPresent());
        assertTrue(TicketShopLayout.fit(400, 225, 18, true).isEmpty());
        assertTrue(TicketShopLayout.fit(300, 600, 1, true).isEmpty());
        assertTrue(TicketShopLayout.fit(300, 600, 1, false).isPresent());
    }

    @Test
    void incompleteLastRowIsCenteredWithoutReorderingProducts() {
        var layout = TicketShopLayout.fit(800, 450, 11, true).orElseThrow();
        assertEquals(layout.cardY(0), layout.cardY(8));
        assertTrue(layout.cardY(9) > layout.cardY(8));
        assertEquals(layout.cardY(9), layout.cardY(10));
        assertEquals(800, layout.cardX(9) + layout.cardX(10) + layout.cardWidth());
    }

    @Test
    void emptyInvalidOrOversizedShopAlwaysChoosesUpstream() {
        assertTrue(TicketShopLayout.fit(800, 450, 0, false).isEmpty());
        assertTrue(TicketShopLayout.fit(800, 450, -1, true).isEmpty());
        assertTrue(TicketShopLayout.fit(0, 0, 1, true).isEmpty());
        assertTrue(TicketShopLayout.fit(800, 450, Integer.MAX_VALUE, true).isEmpty());
    }
}
