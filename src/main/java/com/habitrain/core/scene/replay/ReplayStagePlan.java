package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.ReplayTimeline;

/**
 * Pure presentation plan for the four-act loop. Every value is a deterministic function of the
 * synchronized {@link ReplayTimeline.Frame}, so all clients that agree on the world clock agree on
 * the choreography too. The renderer only maps these scalars onto geometry.
 */
public final class ReplayStagePlan {
    private ReplayStagePlan() {}

    /** Distance (blocks) at which the board starts to fade, and where it is no longer submitted. */
    public static final double FADE_START = 48.0, FADE_END = 64.0;
    /** Below this distance the board submits fine detail (name plates, briefings, rivets). */
    public static final double DETAIL_DISTANCE = 24.0;

    /**
     * @param trayGlow    brass lamp strip brightness 0..1
     * @param scan        fraction of the diorama revealed along its long axis (1 = all)
     * @param plaqueFlip  flip-board title reveal 0..1
     * @param actors      avatar / trail visibility 0..1
     * @param reviewWave  position 0..1 of the golden settlement wave, or -1 when absent
     * @param history     how strongly the full-match history (all trails, all nodes) is shown 0..1
     * @param mapSink     honors-phase sink/dim of the diorama 0..1
     * @param honors      honors screen unfold 0..1
     * @param honorsTime  seconds since the honors act began (for staggered rows)
     */
    public record Stage(double trayGlow, double scan, double plaqueFlip, double actors, double reviewWave,
                        double history, double mapSink, double honors, double honorsTime) {}

    public static Stage stage(ReplayTimeline.Frame frame) {
        double p = clamp01(frame.progress());
        return switch (frame.phase()) {
            case BUILD -> new Stage(easeOutCubic(clamp01(p / 0.4)), easeInOutQuart(clamp01((p - 0.12) / 0.82)),
                    easeOutCubic(clamp01((p - 0.45) / 0.5)), easeOutCubic(clamp01((p - 0.85) / 0.15)) * 0.6,
                    -1, 0, 0, 0, 0);
            case PLAY -> new Stage(1, 1, 1, Math.max(0.6, easeOutCubic(clamp01(p / 0.03))), -1, 0, 0, 0, 0);
            case REVIEW -> new Stage(1, 1, 1, 1, p < 0.62 ? easeInOutQuart(p / 0.62) : -1,
                    easeOutCubic(clamp01((p - 0.08) / 0.5)), 0, 0, 0);
            case HONORS -> {
                double sink = easeInOutQuart(clamp01(p / 0.12));
                double unfold = easeOutCubic(clamp01((p - 0.05) / 0.14));
                double scan = 1;
                if (p > 0.9) {
                    double out = easeInOutQuart(clamp01((p - 0.9) / 0.1));
                    unfold *= 1 - out;
                    scan = 1 - out;
                    sink *= 1 - out * 0.35;
                }
                yield new Stage(1, scan, 1, 1 - sink * 0.85, -1, 1 - sink, sink, unfold,
                        p * ReplayTimeline.HONORS / 20.0);
            }
        };
    }

    /** Opacity multiplier from the camera distance: 1 inside {@link #FADE_START}, 0 beyond {@link #FADE_END}. */
    public static double distanceFade(double distance) {
        return 1 - smoothstep(FADE_START, FADE_END, distance);
    }

    /** Approximate replay ticks advanced per presentation tick during the fast portion of the action act. */
    public static double playbackRate(int durationTicks, int cycleTicks) {
        int action = cycleTicks - ReplayTimeline.INTRO - ReplayTimeline.REVIEW - ReplayTimeline.HONORS;
        if (action <= 0) return 1;
        return Math.max(1, durationTicks / (double) action);
    }

    /** Comet tail length in replay ticks: 10 s of match time, never shorter than ~1.2 s on screen. */
    public static double cometTicks(double playbackRate) {
        return Math.max(200, 24 * playbackRate);
    }

    /** Effect window in replay ticks for an effect meant to last {@code seconds} of presentation time. */
    public static double effectWindow(double seconds, double playbackRate) {
        return Math.max(4, seconds * 20 * playbackRate);
    }

    /** 1 at the event, decaying to 0 after {@code window}; 0 before the event. */
    public static double pulse(double age, double window) {
        if (age < 0 || window <= 0 || age > window) return 0;
        return 1 - easeOutCubic(age / window);
    }

    public static String clock(double ticks) {
        long seconds = Math.max(0, (long) Math.floor(ticks / 20.0));
        return String.format(java.util.Locale.ROOT, "%02d:%02d", Math.min(99, seconds / 60), seconds % 60);
    }

    public static double easeOutCubic(double t) {
        t = clamp01(t);
        double inv = 1 - t;
        return 1 - inv * inv * inv;
    }

    public static double easeInOutQuart(double t) {
        t = clamp01(t);
        return t < 0.5 ? 8 * t * t * t * t : 1 - Math.pow(-2 * t + 2, 4) / 2;
    }

    public static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3 - 2 * t);
    }

    public static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
}
