package com.habitrain.core.api.match.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.scene.model.SceneBounds;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Immutable view of the last completed SRE match. All positions are relative to origin bounds' minimum. */
public record LastMatchReplay(UUID roundId, long playbackAnchorTick, int durationTicks,
                              String mapName, String modeId, String winStatus, MatchWinKind winKind,
                              String customWinId, String customTitleJson, String customSubtitleJson, int customColor,
                              SceneBounds origin, @Nullable ReplayMapManifest manifest,
                              List<Participant> participants, List<Node> events, List<Span> spans,
                              @Nullable UUID mvp, int killCount, boolean limited) {
    public static final int MAX_PLAYERS = 128;
    public static final int MAX_EVENTS = 768;
    public static final int MAX_SPANS = 384;
    public static final int MAX_TICKS = 24 * 60 * 60 * 20;

    public LastMatchReplay {
        participants = List.copyOf(participants);
        events = List.copyOf(events);
        spans = List.copyOf(spans);
    }

    public record Participant(UUID id, String name, String startRole, int startColor, String endRole, int endColor,
                              MatchWinFaction faction, boolean winner, int survivalSeconds, int kills,
                              int score, int itemUses, List<Sample> track) {
        public Participant { track = List.copyOf(track); }
    }

    public static final byte ALIVE = 1, SPRINT = 2, SNEAK = 4, DISGUISED = 8, PSYCHO = 16, ASLEEP = 32,
            /** A player disappeared, or jumped too far to interpolate. */ BREAK = 64;

    public record Position(short x, short y, short z) {
        public static @Nullable Position quantize(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
            long qx = Math.round(x * 16), qy = Math.round(y * 16), qz = Math.round(z * 16);
            if (outside(qx) || outside(qy) || outside(qz)) return null;
            return new Position((short) qx, (short) qy, (short) qz);
        }

        private static boolean outside(long v) { return v < Short.MIN_VALUE || v > Short.MAX_VALUE; }
        public double blockX() { return x / 16.0; }
        public double blockY() { return y / 16.0; }
        public double blockZ() { return z / 16.0; }
    }

    public record Sample(int tick, Position position, byte yaw, byte flags) {
        public float yawDegrees() { return Byte.toUnsignedInt(yaw) * 360.0f / 256; }
    }

    public enum Kind {
        KILL, GUNSHOT, POISON, GRENADE_THROWN, GRENADE_EXPLOSION, BOMB_PLANT, BOMB_DEFUSE, BOMB_DETONATE,
        SHIELD_BROKEN, LOCKPICK_ATTEMPT, DOOR_PRY, DOOR_SEAL, ITEM_USED, SKILL_RELEASE, DISGUISE,
        PLAYER_REVIVAL, CHANGE_ROLE, BLACKOUT_START, BLACKOUT_END, PSYCHO_STATE_CHANGE,
        TRAP_TRIGGERED, ROPE_PULL, DOOR_LOCK, DOOR_UNLOCK, DOOR_OPEN, DOOR_CLOSE
    }

    public static final int HIT = 1, BACKFIRE = 2, GUN_DROP = 4, SUCCESS = 8, APPROXIMATE = 16;
    public record Node(int tick, Kind kind, @Nullable UUID actor, @Nullable UUID target,
                       @Nullable Position from, @Nullable Position to, String detail, int flags) {
        public boolean highlight() {
            return kind == Kind.KILL || kind == Kind.BOMB_DETONATE || kind == Kind.GRENADE_EXPLOSION
                    || kind == Kind.GUNSHOT && (flags & HIT) != 0;
        }
    }
    public enum SpanKind { BLACKOUT, PSYCHO }
    public record Span(SpanKind kind, @Nullable UUID actor, int startTick, int endTick) {}

    public @Nullable Node firstKill() {
        return events.stream().filter(e -> e.kind() == Kind.KILL).findFirst().orElse(null);
    }
    public @Nullable Node lastKill() {
        for (int i = events.size() - 1; i >= 0; i--) if (events.get(i).kind() == Kind.KILL) return events.get(i);
        return null;
    }
}
