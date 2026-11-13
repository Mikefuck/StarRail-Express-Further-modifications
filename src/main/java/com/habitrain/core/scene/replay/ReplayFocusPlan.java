package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayProjection;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic event close-ups, timed in presentation ticks rather than compressed match time. */
public final class ReplayFocusPlan {
    public static final double MAX_ZOOM = 2.4;
    public static final double ATTACK = 6, HOLD = 16, RELEASE = 14;
    public static final double LENGTH = HOLD + RELEASE;
    public static final Pose WIDE = new Pose(1, 0, 0, 0);

    /** Offset is in the unscaled diorama frame. The tray, plaque and honors screen never move. */
    public record Pose(double zoom, double x, double y, double z) {
        public Matrix4f apply(Matrix4f base) {
            return new Matrix4f(base).translate((float) x, (float) y, (float) z).scale((float) zoom);
        }
    }

    private record Cue(double tick, int priority, Pose incoming, Pose target) {
        Pose at(double tick) {
            double age = tick - this.tick;
            if (age < 0) return incoming;
            if (age >= LENGTH) return WIDE;
            if (age < ATTACK) {
                // A fast punch, ~12% overshoot, then recoil into the held framing.
                double zoom = impact(age / ATTACK);
                return mix(incoming, target, zoom, ReplayStagePlan.easeOutCubic(age / ATTACK));
            }
            if (age <= HOLD) return target;
            double out = ReplayStagePlan.easeInOutQuart((age - HOLD) / RELEASE);
            return mix(target, WIDE, out, out);
        }
    }

    private final List<Cue> cues;
    private final double actionLength;

    public ReplayFocusPlan(LastMatchReplay replay, ReplayTimeline timeline) {
        actionLength = timeline.cycleTicks() - ReplayTimeline.INTRO - ReplayTimeline.REVIEW - ReplayTimeline.HONORS;
        ReplayProjection.Layout layout = ReplayProjection.layout(replay);
        List<Cue> built = new ArrayList<>();
        if (layout != null) {
            List<LastMatchReplay.Node> sorted = replay.events().stream()
                    .filter(e -> priority(e) > 0 && (e.from() != null || e.to() != null))
                    .sorted(Comparator.comparingInt(LastMatchReplay.Node::tick)
                            .thenComparing(Comparator.comparingInt(ReplayFocusPlan::priority).reversed())).toList();
            for (var event : sorted) {
                double at = timeline.actionTickAt(event.tick());
                Cue previous = built.isEmpty() ? null : built.getLast();
                int priority = priority(event);
                // Simultaneous events form one shot. Incidental actions cannot steal a kill close-up.
                if (previous != null && (at - previous.tick < ATTACK
                        || at - previous.tick < HOLD && priority < previous.priority)) continue;
                Pose incoming = previous == null ? WIDE : previous.at(at);
                built.add(new Cue(at, priority, incoming, target(layout, event, priority)));
            }
        }
        cues = List.copyOf(built);
    }

    public Pose at(ReplayTimeline.Frame frame) {
        if (frame == null || frame.phase() != ReplayTimeline.Phase.PLAY) return WIDE;
        double tick = frame.progress() * actionLength;
        Cue selected = null;
        for (Cue cue : cues) {
            if (cue.tick > tick) break;
            selected = cue;
        }
        if (selected == null) return WIDE;
        Pose pose = selected.at(tick);
        // Even a last-tick kill must hand the complete map back to the review act without a cut.
        double exit = ReplayStagePlan.easeInOutQuart(Math.max(0, (tick - (actionLength - RELEASE)) / RELEASE));
        return mix(pose, WIDE, exit, exit);
    }

    public static int priority(LastMatchReplay.Node event) {
        return switch (event.kind()) {
            case KILL -> 4;
            case BOMB_DETONATE, GRENADE_EXPLOSION, PLAYER_REVIVAL, BOMB_DEFUSE -> 3;
            case GUNSHOT -> (event.flags() & (LastMatchReplay.HIT | LastMatchReplay.BACKFIRE)) != 0 ? 3 : 1;
            case POISON, SHIELD_BROKEN, BOMB_PLANT, SKILL_RELEASE, TRAP_TRIGGERED, ROPE_PULL -> 2;
            case GRENADE_THROWN, DISGUISE, CHANGE_ROLE, ITEM_USED, LOCKPICK_ATTEMPT, DOOR_PRY, DOOR_SEAL -> 1;
            default -> 0;
        };
    }

    private static Pose target(ReplayProjection.Layout layout, LastMatchReplay.Node event, int priority) {
        var a = layout.project(event.from() == null ? event.to() : event.from());
        var b = layout.project(event.to() == null ? event.from() : event.to());
        double x = (a.x() + b.x()) / 2, z = (a.z() + b.z()) / 2;
        // Keep attacker and target in view, including ranged kills across several carriages.
        double zoom = Math.min(priority == 4 ? MAX_ZOOM : priority == 3 ? 2.1 : priority == 2 ? 1.8 : 1.55,
                Math.min(5.6 / (Math.abs(a.x() - b.x()) + 0.65), 5.6 / (Math.abs(a.z() - b.z()) + 0.65)));
        zoom = Math.max(1, zoom);
        if (layout.source().sizeZ() > layout.source().sizeX()) {
            double oldX = x;
            x = z;
            z = -oldX;
        }
        double y = (a.y() + b.y()) / 2;
        return new Pose(zoom, -x * zoom, -y * (zoom - 1), -z * zoom);
    }

    public static double impact(double t) {
        t = ReplayStagePlan.clamp01(t) - 1;
        return 1 + 2.9 * t * t * t + 1.9 * t * t;
    }

    private static Pose mix(Pose a, Pose b, double zoom, double pan) {
        return new Pose(a.zoom + (b.zoom - a.zoom) * zoom, a.x + (b.x - a.x) * pan,
                a.y + (b.y - a.y) * pan, a.z + (b.z - a.z) * pan);
    }
}
