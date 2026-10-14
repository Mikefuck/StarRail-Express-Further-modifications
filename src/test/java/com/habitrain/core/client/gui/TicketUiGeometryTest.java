package com.habitrain.core.client.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TicketUiGeometryTest {
    @Test
    void opStampsMoveRightWithinTheirRowsWithoutMovingTheLocalModeToAnotherRow() {
        List<String> entries = new ArrayList<>(List.of(
                "roles", "maps", "op-map-rotation", "records", "skins",
                "backpack", "op-tasks", "op-game", "op-roster", "local-mode"));
        TicketUiGeometry.sortStampRows(entries, 12, entry -> entry.startsWith("op-"));
        assertEquals(List.of("roles", "maps", "records", "skins", "op-map-rotation",
                "backpack", "local-mode", "op-tasks", "op-game", "op-roster"), entries);
    }

    @Test
    void stampSortingPreservesPagesOddRowsAndEveryOriginalEntry() {
        List<Integer> entries = new ArrayList<>();
        for (int i = 0; i < 17; i++) entries.add(i);
        TicketUiGeometry.sortStampRows(entries, 6, entry -> entry % 2 == 0);
        assertEquals(List.of(1, 0, 2, 3, 5, 4, 7, 6, 8, 9, 11, 10, 13, 12, 15, 14, 16), entries);
        List<Integer> sorted = new ArrayList<>(entries);
        TicketUiGeometry.sortStampRows(entries, 6, entry -> entry % 2 == 0);
        assertEquals(sorted, entries, "repeated layout must keep keyboard order stable");
        List<String> single = new ArrayList<>(List.of("local-mode"));
        TicketUiGeometry.sortStampRows(single, 0, entry -> false);
        assertEquals(List.of("local-mode"), single);
    }

    @Test
    void liftedStampsAndFooterStayInsideEverySupportedWindow() {
        for (int width : new int[] {240, 320, 427, 640, 800, 960, 1920}) {
            for (int height : new int[] {180, 240, 270, 360, 450, 540, 1080}) {
                for (double guiScale : new double[] {1, 2, 3, 4, 6}) {
                    float scale = TicketUiGeometry.scale(width, height, guiScale, 1.6f);
                    assertTrue(scale > 0);
                    assertTrue(470 * scale <= width + 0.001, "horizontal clipping");
                    assertTrue(300 * scale <= height + 0.001, "lifted stamp/footer clipping");
                    float fitted = Math.min(1.6f, Math.min(width / 470.0f, height / 300.0f));
                    assertTrue(scale >= fitted * 0.8f, "pixel snapping must not halve a small UI");
                }
            }
        }
        assertTrue(TicketUiGeometry.scale(320, 180, 3, 1.6f) >= 0.55f);
    }

    @Test
    void hitTestFollowsRotatedAndEnlargedPaperIncludingItsNewEdges() {
        double angle = Math.toRadians(12);
        // Inside the enlarged right edge, outside the original 48 DU paper.
        double localX = 23.9 * 1.05;
        double localY = -27 * 1.05;
        double x = 100 + 24 + localX * Math.cos(angle) - localY * Math.sin(angle);
        double y = 50 + 30 + localX * Math.sin(angle) + localY * Math.cos(angle);
        assertTrue(TicketUiGeometry.contains(x, y, 100, 50, 48, 60, 1.05f, 12));
        assertFalse(TicketUiGeometry.contains(x, y, 100, 50, 48, 60, 1.0f, 12));
        assertFalse(TicketUiGeometry.contains(70, 30, 100, 50, 48, 60, 1.05f, 12));
    }

    @Test
    void arbitrarilyManyMenuEntriesRemainReachableInBoundedPages() {
        int rows = TicketUiGeometry.trayRows(240, 0.75f);
        int columns = TicketUiGeometry.trayColumns(320, 0.75f);
        int capacity = rows * columns;
        assertTrue(rows * 29 + 60 <= 240 / 0.75f);
        assertTrue(columns <= 2);
        for (int count : new int[] {0, 1, capacity, capacity + 1, 101, 10000}) {
            int pages = TicketUiGeometry.pages(count, capacity);
            assertTrue(pages >= 1);
            assertTrue((long) pages * capacity >= count);
            if (count > 0) assertTrue((long) (pages - 1) * capacity < count);
        }
        assertEquals(1, TicketUiGeometry.pages(0, 0));
    }
}
