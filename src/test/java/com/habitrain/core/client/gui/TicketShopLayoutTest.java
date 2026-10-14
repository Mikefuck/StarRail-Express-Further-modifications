package com.habitrain.core.client.gui;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TicketShopLayoutTest {
    @Test
    void shopAndClickableCardsStayAboveHotbarAcrossGuiSizes() {
        for (int width : new int[] {240, 320, 427, 640, 960, 1920}) {
            for (int height : new int[] {180, 240, 270, 360, 540, 1080}) {
                for (int count : new int[] {0, 1, 2, 7, 31, 100}) {
                    int hotbarY = (height - 32) / 2;
                    TicketShopLayout first = TicketShopLayout.fit(width, hotbarY, count, 0);
                    for (int page = 0; page < first.pages(); page++) {
                        TicketShopLayout layout = TicketShopLayout.fit(width, hotbarY, count, page);
                        String size = width + "x" + height + ", entries=" + count + ", page=" + page;
                        assertTrue(layout.left() >= 0, size);
                        assertTrue(layout.left() + layout.width() <= width, size);
                        assertTrue(layout.top() >= 0, size);
                        assertTrue(layout.top() + layout.height() <= hotbarY - 6, size);
                        for (int i = layout.start(); i < layout.end(); i++) {
                            assertTrue(layout.cardX(i) >= layout.left() + layout.padding(), size);
                            assertTrue(layout.cardX(i) + layout.cardWidth()
                                    <= layout.left() + layout.width() - layout.padding(), size);
                            assertTrue(layout.cardY(i) >= layout.top() + layout.header(), size);
                            assertTrue(layout.cardY(i) + layout.cardHeight() < layout.navY(), size);
                            if (i + 1 < layout.end() && (i - layout.start() + 1) % layout.columns() != 0) {
                                assertTrue(layout.cardX(i) + layout.cardWidth() < layout.cardX(i + 1), size);
                            }
                        }
                        assertTrue(layout.navY() + layout.navSize() < hotbarY, size);
                    }
                }
            }
        }
    }

    @Test
    void allProductsRemainReachableExactlyOnceAfterRepagination() {
        for (int width : new int[] {240, 427, 960}) {
            for (int count : new int[] {0, 1, 7, 29, 101, 1000}) {
                TicketShopLayout first = TicketShopLayout.fit(width, 74, count, -1);
                assertEquals(0, first.page());
                Set<Integer> seen = new HashSet<>();
                for (int page = 0; page < first.pages(); page++) {
                    TicketShopLayout layout = TicketShopLayout.fit(width, 74, count, page);
                    for (int i = layout.start(); i < layout.end(); i++) assertTrue(seen.add(i));
                }
                assertEquals(count, seen.size());
                TicketShopLayout last = TicketShopLayout.fit(width, 74, count, Integer.MAX_VALUE);
                assertEquals(first.pages() - 1, last.page());
                assertEquals(count, last.end());
            }
        }
    }
}
