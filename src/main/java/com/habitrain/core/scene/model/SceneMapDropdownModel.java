package com.habitrain.core.scene.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure state helpers for the scene editor map dropdown.
 *
 * <p>The actual widget lives on the physical client, while filtering and keyboard-window
 * calculations remain here so they can be regression tested without starting Minecraft.</p>
 */
public final class SceneMapDropdownModel {
    private SceneMapDropdownModel() {}

    public record Entry(String key, String label, boolean enabled, boolean runtime, boolean editing) {
        public Entry {
            key = key == null ? "" : key;
            label = label == null ? "" : label;
        }
    }

    public static List<Entry> filter(List<Entry> entries, String query) {
        if (entries == null || entries.isEmpty()) return List.of();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return List.copyOf(entries);
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry == null) continue;
            if (entry.key().toLowerCase(Locale.ROOT).contains(needle)
                    || entry.label().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(entry);
            }
        }
        return List.copyOf(result);
    }

    public static int selectedIndex(List<Entry> entries, String selectedKey) {
        if (entries == null || entries.isEmpty()) return -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).key().equals(selectedKey)) return i;
        }
        return 0;
    }

    public static int moveIndex(int current, int delta, int size) {
        if (size <= 0) return -1;
        int base = current < 0 || current >= size ? 0 : current;
        return Math.floorMod(base + delta, size);
    }

    public static int keepVisible(int offset, int selected, int visibleRows, int size) {
        if (size <= 0 || visibleRows <= 0) return 0;
        int maxOffset = Math.max(0, size - visibleRows);
        int next = Math.max(0, Math.min(offset, maxOffset));
        int safeSelected = Math.max(0, Math.min(selected, size - 1));
        if (safeSelected < next) next = safeSelected;
        if (safeSelected >= next + visibleRows) next = safeSelected - visibleRows + 1;
        return Math.max(0, Math.min(next, maxOffset));
    }
}
