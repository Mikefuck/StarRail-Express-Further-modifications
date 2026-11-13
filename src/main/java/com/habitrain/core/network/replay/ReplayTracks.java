package com.habitrain.core.network.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay.Position;
import com.habitrain.core.api.match.replay.LastMatchReplay.Sample;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;

/** Tracks use per-point time/coordinate deltas; decimation is deterministic and bounded. */
public final class ReplayTracks {
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_TRACK = 2048;
    private ReplayTracks() {}

    public static void append(List<Sample> track, Sample point) {
        if (!track.isEmpty() && point.tick() < track.getLast().tick()) return;
        int n = track.size();
        if (n >= 2 && redundant(track.get(n - 2), track.getLast(), point)) track.set(n - 1, point);
        else track.add(point);
        if (track.size() > MAX_TRACK) thin(track);
    }

    private static boolean redundant(Sample a, Sample b, Sample c) {
        if (a.flags() != b.flags() || b.flags() != c.flags() || (b.flags() & BREAK) != 0
                || a.yaw() != b.yaw() || b.yaw() != c.yaw() || c.tick() <= a.tick()) return false;
        double f = (double) (b.tick() - a.tick()) / (c.tick() - a.tick());
        return interpolated(a.position().x(), b.position().x(), c.position().x(), f)
                && interpolated(a.position().y(), b.position().y(), c.position().y(), f)
                && interpolated(a.position().z(), b.position().z(), c.position().z(), f);
    }

    private static boolean interpolated(int a, int b, int c, double f) {
        return Math.abs(a + (c - a) * f - b) < 0.01;
    }

    public static void compact(List<Sample> track) {
        if (track.size() < 3) return;
        // Preserve transitions and both ends as long as there is space to do so.
        boolean remove = false;
        int write = 1;
        for (int i = 1; i < track.size() - 1; i++) {
            Sample p = track.get(i);
            boolean stable = track.get(i - 1).flags() == p.flags() && p.flags() == track.get(i + 1).flags();
            if (!stable || (remove = !remove)) track.set(write++, p);
        }
        track.set(write++, track.getLast());
        track.subList(write, track.size()).clear();
    }

    public record Encoding(byte[] bytes, boolean thinned) {}

    public static byte[] encode(List<? extends List<Sample>> tracks) {
        return encodeWithStatus(tracks).bytes();
    }

    public static Encoding encodeWithStatus(List<? extends List<Sample>> tracks) {
        var work = new ArrayList<List<Sample>>();
        for (var track : tracks) work.add(new ArrayList<>(track));
        boolean thinned = false;
        while (true) {
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                for (var track : work) {
                    buf.writeVarInt(track.size());
                    int t = 0, x = 0, y = 0, z = 0;
                    for (Sample point : track) {
                        buf.writeVarInt(point.tick() - t);
                        signed(buf, point.position().x() - x);
                        signed(buf, point.position().y() - y);
                        signed(buf, point.position().z() - z);
                        buf.writeByte(point.yaw()).writeByte(point.flags());
                        t = point.tick(); x = point.position().x(); y = point.position().y(); z = point.position().z();
                    }
                    if (buf.readableBytes() > MAX_BYTES) break;
                }
                if (buf.readableBytes() <= MAX_BYTES) {
                    byte[] result = new byte[buf.readableBytes()];
                    buf.readBytes(result);
                    return new Encoding(result, thinned);
                }
            } finally { buf.release(); }
            thinned = true;
            for (var track : work) thin(track);
        }
    }

    private static void thin(List<Sample> track) {
        int n = track.size();
        compact(track);
        if (n > 2 && track.size() == n) {
            int write = 1;
            for (int i = 2; i < n; i += 2) {
                Sample next = track.get(i);
                if (track.get(i - 1).flags() != next.flags()) {
                    next = new Sample(next.tick(), next.position(), next.yaw(), (byte) (next.flags() | BREAK));
                }
                track.set(write++, next);
            }
            if (n % 2 == 0) track.set(write++, track.getLast());
            track.subList(write, n).clear();
        }
    }

    public static List<List<Sample>> decode(byte[] bytes, int players, int duration) {
        if (bytes.length > MAX_BYTES || players < 0 || players > MAX_PLAYERS) throw new IllegalArgumentException("Tracks exceed limits");
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            List<List<Sample>> result = new ArrayList<>(players);
            for (int i = 0; i < players; i++) {
                int count = buf.readVarInt();
                if (count < 0 || count > MAX_TRACK || count > buf.readableBytes() / 6) throw new IllegalArgumentException("Bad track count");
                List<Sample> track = new ArrayList<>(count);
                int t = 0, x = 0, y = 0, z = 0;
                for (int n = 0; n < count; n++) {
                    int dt = buf.readVarInt();
                    if (dt < 0 || dt > duration - t) throw new IllegalArgumentException("Bad track time");
                    t += dt;
                    x = coordinate(x, buf); y = coordinate(y, buf); z = coordinate(z, buf);
                    track.add(new Sample(t, new Position((short) x, (short) y, (short) z), buf.readByte(), buf.readByte()));
                }
                result.add(List.copyOf(track));
            }
            if (buf.isReadable()) throw new IllegalArgumentException("Trailing replay track data");
            return List.copyOf(result);
        } finally { buf.release(); }
    }

    private static void signed(FriendlyByteBuf buf, int n) { buf.writeVarInt((n << 1) ^ (n >> 31)); }
    private static int coordinate(int prev, FriendlyByteBuf buf) {
        int v = buf.readVarInt();
        if (v < 0 || v > 131070) throw new IllegalArgumentException("Bad coordinate delta");
        int n = prev + ((v >>> 1) ^ -(v & 1));
        if (n < Short.MIN_VALUE || n > Short.MAX_VALUE) throw new IllegalArgumentException("Bad coordinate");
        return n;
    }
}
