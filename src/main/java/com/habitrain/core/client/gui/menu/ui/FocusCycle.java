package com.habitrain.core.client.gui.menu.ui;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One ordered focus graph for both text fields and manually rendered controls. */
public final class FocusCycle {
    public record Target(BooleanSupplier focused, Runnable focus) {
        public Target {
            Objects.requireNonNull(focused, "focused");
            Objects.requireNonNull(focus, "focus");
        }
    }

    private FocusCycle() {}

    public static boolean move(List<Target> available, int direction, Runnable clearFocus) {
        if (available.isEmpty()) return false;
        int current = -1;
        for (int i = 0; i < available.size(); i++) if (available.get(i).focused().getAsBoolean()) {
            current = i;
            break;
        }
        int next = current < 0 ? (direction < 0 ? available.size() - 1 : 0)
                : Math.floorMod(current + (direction < 0 ? -1 : 1), available.size());
        clearFocus.run();
        available.get(next).focus().run();
        return true;
    }
}
