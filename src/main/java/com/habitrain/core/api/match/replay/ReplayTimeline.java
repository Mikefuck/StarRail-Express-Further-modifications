package com.habitrain.core.api.match.replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure, deterministic playback. Feed it synchronized vanilla world gameTime plus partial tick. */
public final class ReplayTimeline {
    public static final int INTRO = 50, REVIEW = 70, HONORS = 170;
    private final long anchor;
    private final int duration, action, cycle;
    private final double fastRate;
    private final List<Window> windows;
    private static final double SLOW = 0.35;
    private record Window(double start, double end) {}

    public ReplayTimeline(LastMatchReplay replay) {
        this(replay.playbackAnchorTick(), replay.durationTicks(),
                replay.events().stream().filter(LastMatchReplay.Node::highlight).map(LastMatchReplay.Node::tick).toList());
    }

    public ReplayTimeline(long anchor, int duration, List<Integer> highlights) {
        this.anchor = anchor;
        this.duration = Math.max(0, duration);
        action = 320 + Math.min(160, this.duration / 60);
        cycle = INTRO + action + REVIEW + HONORS;
        var chosen = new ArrayList<Window>();
        double slowTicks = 0;
        for (int center : highlights.stream().distinct().sorted(Comparator.naturalOrder()).toList()) {
            if (center < 0 || center > this.duration || chosen.size() == 7) continue;
            double start = Math.max(0, center - 2), end = Math.min(this.duration, center + 3);
            if (start >= end || (!chosen.isEmpty() && start <= chosen.getLast().end())) continue;
            chosen.add(new Window(start, end));
            slowTicks += end - start;
        }
        windows = List.copyOf(chosen);
        fastRate = (this.duration - slowTicks) / (action - slowTicks / SLOW);
    }

    public enum Phase { BUILD, PLAY, REVIEW, HONORS }
    public record Frame(Phase phase, double progress, double replayTick, boolean slowMotion) {}

    public int cycleTicks() { return cycle; }

    /** Presentation ticks since PLAY began at a source tick, including every slow-motion window. */
    public double actionTickAt(double replayTick) {
        double target = Math.clamp(replayTick, 0, duration), source = 0, elapsed = 0;
        for (Window w : windows) {
            if (target <= w.start()) return elapsed + (fastRate > 0 ? (target - source) / fastRate : 0);
            elapsed += fastRate > 0 ? (w.start() - source) / fastRate : 0;
            if (target <= w.end()) return elapsed + (target - w.start()) / SLOW;
            elapsed += (w.end() - w.start()) / SLOW;
            source = w.end();
        }
        return elapsed + (fastRate > 0 ? (target - source) / fastRate : 0);
    }

    public Frame at(long gameTick, float partialTick) {
        double cursor = (Math.floorMod(gameTick - anchor, cycle) + Math.clamp(partialTick, 0, 1)) % cycle;
        if (gameTick < anchor) cursor = 0;
        if (cursor < INTRO) return new Frame(Phase.BUILD, cursor / INTRO, 0, false);
        cursor -= INTRO;
        if (cursor < action) {
            double left = cursor, source = 0;
            for (Window w : windows) {
                double fastTime = fastRate > 0 ? (w.start() - source) / fastRate : 0;
                if (left < fastTime) return new Frame(Phase.PLAY, cursor / action, source + left * fastRate, false);
                left -= fastTime;
                double slowTime = (w.end() - w.start()) / SLOW;
                if (left < slowTime) return new Frame(Phase.PLAY, cursor / action, w.start() + left * SLOW, true);
                left -= slowTime;
                source = w.end();
            }
            return new Frame(Phase.PLAY, cursor / action, Math.min(duration, source + left * fastRate), false);
        }
        cursor -= action;
        if (cursor < REVIEW) return new Frame(Phase.REVIEW, cursor / REVIEW, duration, false);
        return new Frame(Phase.HONORS, (cursor - REVIEW) / HONORS, duration, false);
    }
}
