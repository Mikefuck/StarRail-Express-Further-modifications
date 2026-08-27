package com.habitrain.core.game.sre;

import java.util.OptionalInt;

/** Converts an upstream map-reset task counter to the percentage shown by SRE. */
final class MapResetProgressPercent {
    private MapResetProgressPercent() {}

    static OptionalInt from(int progress, int totalProgress) {
        if (totalProgress <= 0) {
            return OptionalInt.empty();
        }
        int percent = Math.round(progress * 100.0f / totalProgress);
        return OptionalInt.of(Math.max(0, Math.min(100, percent)));
    }
}
