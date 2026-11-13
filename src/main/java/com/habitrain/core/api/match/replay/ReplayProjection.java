package com.habitrain.core.api.match.replay;

import com.habitrain.core.api.scene.model.SceneBounds;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;

/** Renderer-independent coordinates for the diorama above a board. */
public final class ReplayProjection {
    private ReplayProjection() {}

    public record Point(double x, double y, double z) {}

    public record Layout(SceneBounds source, SceneBounds origin, double scale) {
        /** Coordinates are relative to the center of the tray, one block above the marker. */
        public Point project(LastMatchReplay.Position p) {
            return new Point((origin.minX() - source.minX() + p.blockX() - source.sizeX() * .5) * scale,
                    (origin.minY() - source.minY() + p.blockY()) * scale,
                    (origin.minZ() - source.minZ() + p.blockZ() - source.sizeZ() * .5) * scale);
        }
    }

    public static @Nullable Layout layout(LastMatchReplay replay) {
        SceneBounds source = replay.manifest() == null ? replay.origin() : replay.manifest().sourceBounds();
        if (source.isEmpty()) return null;
        return new Layout(source, replay.origin(), 6.25 / Math.max(source.sizeX(), source.sizeZ()));
    }

    /** Candidate ceiling height relative to the asset's minimum Y. The renderer chooses how to cut. */
    public static OptionalDouble roofHint(LastMatchReplay replay) {
        Layout layout = layout(replay);
        if (layout == null || layout.source().sizeY() < 4 || layout.source().sizeY() > 512) return OptionalDouble.empty();
        int[] observed = new int[layout.source().sizeY()];
        for (var player : replay.participants()) {
            LastMatchReplay.Sample previous = null;
            for (var sample : player.track()) {
                if (previous != null && (previous.flags() & LastMatchReplay.ALIVE) != 0
                        && (sample.flags() & LastMatchReplay.BREAK) == 0) {
                    int y = (int) Math.floor(layout.origin().minY() - layout.source().minY() + previous.position().blockY());
                    if (y >= 0 && y < observed.length) observed[y] += Math.min(200, sample.tick() - previous.tick());
                }
                previous = sample;
            }
        }
        int count = java.util.Arrays.stream(observed).sum();
        if (count == 0) return OptionalDouble.empty();
        int accumulated = 0;
        for (int y = 0; y < observed.length; y++) {
            accumulated += observed[y];
            if (accumulated * 10L >= count * 9L) {
                int ceiling = y + 3;
                return ceiling < observed.length ? OptionalDouble.of(ceiling) : OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }
}
