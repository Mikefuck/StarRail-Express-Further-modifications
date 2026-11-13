package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;

import java.util.List;

/**
 * Side-neutral, allocation-light sampler for one participant's simplified track.
 *
 * <p>Semantics follow the backend contract: {@code ALIVE} marks a renderable avatar,
 * {@code BREAK} forbids connecting / interpolating from the previous point, a bare
 * {@code BREAK} means the player left, and a point with neither flag is a corpse.
 * Interpolation is a time-parameterised cubic Hermite (Catmull-Rom style) restricted to
 * the current continuous segment; tangents are clamped so long stationary gaps left by
 * the server's collinear / still-point merging never drift or overshoot.</p>
 */
public final class ReplayTrackSampler {
    public enum State { HIDDEN, ALIVE, DEAD, LEFT }

    /**
     * Sampled pose in block units relative to the replay origin.
     *
     * @param stateSince replay tick at which the current state run began (alive-run start / death tick)
     * @param speed      horizontal + vertical speed in blocks per replay tick (0 when stationary)
     * @param revived    whether the current alive run follows a death (revival effect)
     */
    public record Pose(State state, double x, double y, double z, float yaw, int flags,
                       int stateSince, double speed, boolean revived) {
        public boolean visible() { return state == State.ALIVE || state == State.DEAD; }
        public boolean has(int flag) { return (flags & flag) != 0; }
    }

    private static final Pose HIDDEN = new Pose(State.HIDDEN, 0, 0, 0, 0, 0, 0, 0, false);

    private final int[] ticks;
    private final double[] xs, ys, zs;
    private final float[] yaws;
    private final int[] flags;
    private final int[] runStart;
    private final boolean[] revivedRun;

    public ReplayTrackSampler(List<LastMatchReplay.Sample> track) {
        int n = track.size();
        ticks = new int[n];
        xs = new double[n];
        ys = new double[n];
        zs = new double[n];
        yaws = new float[n];
        flags = new int[n];
        runStart = new int[n];
        revivedRun = new boolean[n];
        boolean seenDeath = false;
        for (int i = 0; i < n; i++) {
            var s = track.get(i);
            ticks[i] = s.tick();
            xs[i] = s.position().blockX();
            ys[i] = s.position().blockY();
            zs[i] = s.position().blockZ();
            yaws[i] = s.yawDegrees();
            flags[i] = s.flags() & 0xFF;
            State state = stateOf(flags[i]);
            State previous = i == 0 ? null : stateOf(flags[i - 1]);
            if (state == State.DEAD) seenDeath = true;
            boolean continues = previous == state && (state != State.ALIVE || !has(flags[i], LastMatchReplay.BREAK));
            if (i > 0 && continues) {
                runStart[i] = runStart[i - 1];
                revivedRun[i] = revivedRun[i - 1];
            } else {
                runStart[i] = ticks[i];
                revivedRun[i] = state == State.ALIVE && previous == State.DEAD && seenDeath;
            }
        }
    }

    public int size() { return ticks.length; }
    public int tick(int i) { return ticks[i]; }
    public double x(int i) { return xs[i]; }
    public double y(int i) { return ys[i]; }
    public double z(int i) { return zs[i]; }
    public int flags(int i) { return flags[i]; }

    /** Whether a trail may be drawn from sample {@code i - 1} to sample {@code i}. */
    public boolean connects(int i) {
        return i > 0 && i < ticks.length && !has(flags[i], LastMatchReplay.BREAK)
                && has(flags[i - 1], LastMatchReplay.ALIVE);
    }

    /** Index of the last sample whose tick is {@code <= tick}; -1 before the first sample. */
    public int indexAt(double tick) {
        int lo = 0, hi = ticks.length - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ticks[mid] <= tick) { found = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return found;
    }

    public Pose sample(double tick) {
        int i = indexAt(tick);
        if (i < 0) return HIDDEN;
        State state = stateOf(flags[i]);
        if (state == State.LEFT) return new Pose(State.LEFT, xs[i], ys[i], zs[i], yaws[i], flags[i], runStart[i], 0, false);
        if (state == State.DEAD) {
            return new Pose(State.DEAD, xs[i], ys[i], zs[i], yaws[i], flags[i], runStart[i], 0, false);
        }
        int j = i + 1;
        if (j >= ticks.length || has(flags[j], LastMatchReplay.BREAK) || ticks[j] <= ticks[i]) {
            return new Pose(State.ALIVE, xs[i], ys[i], zs[i], yaws[i], flags[i], runStart[i], 0, revivedRun[i]);
        }
        double t1 = ticks[i], t2 = ticks[j];
        double u = clamp01((tick - t1) / (t2 - t1));
        double dt = t2 - t1;
        double dx = xs[j] - xs[i], dy = ys[j] - ys[i], dz = zs[j] - zs[i];
        double chord = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double[] m1 = new double[]{dx, dy, dz};
        double[] m2 = new double[]{dx, dy, dz};
        int h = i - 1;
        if (h >= 0 && !has(flags[i], LastMatchReplay.BREAK) && has(flags[h], LastMatchReplay.ALIVE) && ticks[h] < t1) {
            double k = dt / (t2 - ticks[h]);
            m1[0] = (xs[j] - xs[h]) * k; m1[1] = (ys[j] - ys[h]) * k; m1[2] = (zs[j] - zs[h]) * k;
        }
        int l = i + 2;
        if (l < ticks.length && !has(flags[l], LastMatchReplay.BREAK) && has(flags[j], LastMatchReplay.ALIVE) && ticks[l] > t2) {
            double k = dt / (ticks[l] - t1);
            m2[0] = (xs[l] - xs[i]) * k; m2[1] = (ys[l] - ys[i]) * k; m2[2] = (zs[l] - zs[i]) * k;
        }
        clampLength(m1, chord);
        clampLength(m2, chord);
        double u2 = u * u, u3 = u2 * u;
        double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
        double x = h00 * xs[i] + h10 * m1[0] + h01 * xs[j] + h11 * m2[0];
        double y = h00 * ys[i] + h10 * m1[1] + h01 * ys[j] + h11 * m2[1];
        double z = h00 * zs[i] + h10 * m1[2] + h01 * zs[j] + h11 * m2[2];
        float yaw = lerpYaw(yaws[i], yaws[j], (float) u);
        return new Pose(State.ALIVE, x, y, z, yaw, flags[i], runStart[i], chord / dt, revivedRun[i]);
    }

    /** Shortest-arc yaw interpolation in degrees, result normalised to [0, 360). */
    public static float lerpYaw(float from, float to, float t) {
        float delta = ((to - from) % 360f + 540f) % 360f - 180f;
        float r = from + delta * t;
        r %= 360f;
        return r < 0 ? r + 360f : r;
    }

    private static void clampLength(double[] v, double max) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (max < 1.0e-6) { v[0] = v[1] = v[2] = 0; return; }
        if (len > max) {
            double k = max / len;
            v[0] *= k; v[1] *= k; v[2] *= k;
        }
    }

    private static State stateOf(int flags) {
        if (has(flags, LastMatchReplay.ALIVE)) return State.ALIVE;
        if (has(flags, LastMatchReplay.BREAK)) return State.LEFT;
        return State.DEAD;
    }

    private static boolean has(int flags, int flag) { return (flags & flag) != 0; }
    private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
}
