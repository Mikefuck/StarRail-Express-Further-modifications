package com.habitrain.core.client.gui;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Shared layout and hit geometry, independent of the client/render thread. */
final class TicketUiGeometry {
    private TicketUiGeometry() { }

    /** Includes both fully lifted stamp rows and the navigation footer. */
    static float scale(int width, int height, double guiScale, float cap) {
        double pixels = Math.max(1.0, guiScale);
        float fit = Math.max(0.01f, Math.min(cap, Math.min(width / 470.0f, height / 300.0f)));
        float snapped = (float) (Math.floor(fit * pixels + 0.0001) / pixels);
        // At tiny windows an integer-pixel snap can halve the whole interface. Keep it legible;
        // individual text runs still choose their own crisp font scale.
        return snapped >= fit * 0.8f ? snapped : fit;
    }

    /** Inverts the exact center-based rotation and hover scale used to draw a stamp. */
    static boolean contains(double mouseX, double mouseY, float x, float y, float width, float height,
                            float hoverScale, float degrees) {
        double dx = mouseX - x - width / 2.0;
        double dy = mouseY - y - height / 2.0;
        double angle = Math.toRadians(degrees);
        double localX = (dx * Math.cos(angle) + dy * Math.sin(angle)) / hoverScale;
        double localY = (-dx * Math.sin(angle) + dy * Math.cos(angle)) / hoverScale;
        return localX >= -width / 2.0 && localX < width / 2.0
                && localY >= -height / 2.0 && localY < height / 2.0;
    }

    static int pages(int entries, int perPage) {
        return entries <= 0 ? 1 : 1 + (entries - 1) / Math.max(1, perPage);
    }

    /** Keep page/row membership and relative order, moving OP entries to each row's right edge. */
    static <T> void sortStampRows(List<T> entries, int perPage, Predicate<? super T> restricted) {
        int capacity = Math.max(1, perPage);
        Comparator<T> byPermission = Comparator.comparing(restricted::test);
        for (int start = 0; start < entries.size(); start += capacity) {
            int end = Math.min(entries.size(), start + capacity);
            int middle = start + (end - start) / 2;
            entries.subList(start, middle).sort(byPermission);
            entries.subList(middle, end).sort(byPermission);
        }
    }

    /** Two columns at most: additional options turn pages instead of covering the whole screen. */
    static int trayRows(int height, float scale) {
        return Math.max(1, Math.min(8, (int) ((height / scale - 60.0f) / 29.0f)));
    }

    static int trayColumns(int width, float scale) {
        return Math.max(1, Math.min(2, (int) ((width / scale - 20.0f) / 89.0f)));
    }
}
