package com.habitrain.core.network.replay;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.scene.model.SceneBounds;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;

public final class ReplayPackets {
    public static final UUID EMPTY = new UUID(0, 0);
    public static final int MAX_SUMMARY = 768 * 1024;
    public static final int FRAGMENT_BYTES = 24 * 1024;
    public static final int MAX_SECTION_BYTES = 256 * 1024;
    public static final int MAX_REQUESTED = 16;
    private static final int BITSET_BYTES = ReplayMapManifest.MAX_SECTIONS / 8;

    private ReplayPackets() {}

    /** Empty UUID means there is no completed round. A new revision publishes a newly available manifest. */
    public record Available(UUID roundId, boolean mapReady) implements CustomPacketPayload {
        public static final Type<Available> TYPE = typeId("last_match_available");
        static final StreamCodec<FriendlyByteBuf, Available> CODEC = codec(17,
                b -> new Available(b.readUUID(), b.readBoolean()),
                (b, p) -> { b.writeUUID(p.roundId); b.writeBoolean(p.mapReady); });
        @Override public Type<Available> type() { return TYPE; }
    }

    public record RequestSummary(BlockPos board, UUID roundId) implements CustomPacketPayload {
        public static final Type<RequestSummary> TYPE = typeId("last_match_request");
        static final StreamCodec<FriendlyByteBuf, RequestSummary> CODEC = codec(24,
                b -> new RequestSummary(b.readBlockPos(), b.readUUID()),
                (b, p) -> { b.writeBlockPos(p.board); b.writeUUID(p.roundId); });
        @Override public Type<RequestSummary> type() { return TYPE; }
    }

    public record LastMatchSummaryS2C(LastMatchReplay replay, byte[] tracks) implements CustomPacketPayload {
        public static final Type<LastMatchSummaryS2C> TYPE = typeId("last_match_summary");
        public static final StreamCodec<FriendlyByteBuf, LastMatchSummaryS2C> CODEC =
                codec(MAX_SUMMARY, ReplayPackets::readSummary, ReplayPackets::writeSummary);
        @Override public Type<LastMatchSummaryS2C> type() { return TYPE; }
    }

    /** One bounded batch; indices reference the exact round's manifest ordering. */
    public record ReplayMissingSectionsC2S(BlockPos board, UUID roundId, BitSet missing) implements CustomPacketPayload {
        public static final Type<ReplayMissingSectionsC2S> TYPE = typeId("replay_missing_sections");
        public static final StreamCodec<FriendlyByteBuf, ReplayMissingSectionsC2S> CODEC = codec(24 + BITSET_BYTES + 2,
                b -> {
                    BlockPos pos = b.readBlockPos(); UUID id = b.readUUID();
                    BitSet bits = BitSet.valueOf(b.readByteArray(BITSET_BYTES));
                    if (bits.isEmpty() || bits.cardinality() > MAX_REQUESTED) throw new DecoderException("Bad replay section count");
                    return new ReplayMissingSectionsC2S(pos, id, bits);
                },
                (b, p) -> {
                    if (p.missing.length() > ReplayMapManifest.MAX_SECTIONS || p.missing.isEmpty()
                            || p.missing.cardinality() > MAX_REQUESTED) throw new IllegalArgumentException("Bad missing sections");
                    b.writeBlockPos(p.board); b.writeUUID(p.roundId); b.writeByteArray(p.missing.toByteArray());
                });
        @Override public Type<ReplayMissingSectionsC2S> type() { return TYPE; }
    }

    public record ReplaySectionPatchS2C(UUID roundId, int index, int offset, int total, byte[] bytes)
            implements CustomPacketPayload {
        public static final Type<ReplaySectionPatchS2C> TYPE = typeId("replay_section_patch");
        public static final StreamCodec<FriendlyByteBuf, ReplaySectionPatchS2C> CODEC = codec(FRAGMENT_BYTES + 33,
                b -> new ReplaySectionPatchS2C(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                        b.readByteArray(FRAGMENT_BYTES)),
                (b, p) -> {
                    b.writeUUID(p.roundId); b.writeVarInt(p.index); b.writeVarInt(p.offset);
                    b.writeVarInt(p.total); b.writeByteArray(p.bytes);
                });
        public ReplaySectionPatchS2C {
            if (index < 0 || index >= ReplayMapManifest.MAX_SECTIONS || offset < 0 || offset % FRAGMENT_BYTES != 0
                    || total <= offset || total > MAX_SECTION_BYTES
                    || bytes.length != Math.min(FRAGMENT_BYTES, total - offset)) {
                throw new IllegalArgumentException("Bad replay section fragment");
            }
        }
        @Override public Type<ReplaySectionPatchS2C> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Available.TYPE, Available.CODEC);
        PayloadTypeRegistry.playS2C().register(LastMatchSummaryS2C.TYPE, LastMatchSummaryS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(ReplaySectionPatchS2C.TYPE, ReplaySectionPatchS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestSummary.TYPE, RequestSummary.CODEC);
        PayloadTypeRegistry.playC2S().register(ReplayMissingSectionsC2S.TYPE, ReplayMissingSectionsC2S.CODEC);
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeId(String id) {
        return new CustomPacketPayload.Type<>(HabiTrainCore.id(id));
    }

    private static <T> StreamCodec<FriendlyByteBuf, T> codec(int max, Function<FriendlyByteBuf, T> read,
                                                            BiConsumer<FriendlyByteBuf, T> write) {
        return new StreamCodec<>() {
            @Override public T decode(FriendlyByteBuf b) {
                if (b.readableBytes() > max) throw new DecoderException("Replay packet exceeds " + max);
                T result = read.apply(b);
                if (b.isReadable()) throw new DecoderException("Trailing replay packet bytes");
                return result;
            }

            @Override public void encode(FriendlyByteBuf b, T value) {
                int start = b.writerIndex();
                write.accept(b, value);
                if (b.writerIndex() - start > max) throw new IllegalArgumentException("Replay packet exceeds " + max);
            }
        };
    }

    private static void writeSummary(FriendlyByteBuf b, LastMatchSummaryS2C p) {
        LastMatchReplay s = p.replay;
        b.writeUUID(s.roundId()); b.writeLong(s.playbackAnchorTick()); b.writeVarInt(s.durationTicks());
        b.writeUtf(s.mapName(), 128); b.writeUtf(s.modeId(), 128); b.writeUtf(s.winStatus(), 64);
        b.writeEnum(s.winKind()); b.writeUtf(s.customWinId(), 128);
        b.writeUtf(s.customTitleJson(), 2048); b.writeUtf(s.customSubtitleJson(), 2048); b.writeInt(s.customColor());
        writeBounds(b, s.origin());
        b.writeBoolean(s.manifest() != null);
        if (s.manifest() != null) writeManifest(b, s.manifest());
        b.writeVarInt(s.participants().size());
        for (Participant player : s.participants()) {
            b.writeUUID(player.id()); b.writeUtf(player.name(), 64);
            b.writeUtf(player.startRole(), 128); b.writeInt(player.startColor());
            b.writeUtf(player.endRole(), 128); b.writeInt(player.endColor());
            b.writeEnum(player.faction()); b.writeBoolean(player.winner());
            b.writeVarInt(player.survivalSeconds()); b.writeVarInt(player.kills());
            b.writeVarInt(player.score()); b.writeVarInt(player.itemUses());
        }
        b.writeByteArray(p.tracks);
        b.writeVarInt(s.events().size());
        for (Node node : s.events()) {
            b.writeVarInt(node.tick()); b.writeEnum(node.kind()); writeId(b, node.actor()); writeId(b, node.target());
            writePosition(b, node.from()); writePosition(b, node.to()); b.writeUtf(node.detail(), 128); b.writeByte(node.flags());
        }
        b.writeVarInt(s.spans().size());
        for (Span span : s.spans()) {
            b.writeEnum(span.kind()); writeId(b, span.actor());
            b.writeVarInt(span.startTick()); b.writeVarInt(span.endTick());
        }
        writeId(b, s.mvp()); b.writeVarInt(s.killCount()); b.writeBoolean(s.limited());
    }

    private static LastMatchSummaryS2C readSummary(FriendlyByteBuf b) {
        UUID id = b.readUUID();
        long anchor = b.readLong();
        int duration = count(b, MAX_TICKS);
        String map = b.readUtf(128), mode = b.readUtf(128), status = b.readUtf(64);
        MatchWinKind win = b.readEnum(MatchWinKind.class);
        String custom = b.readUtf(128), title = b.readUtf(2048), subtitle = b.readUtf(2048);
        int color = b.readInt();
        SceneBounds origin = readBounds(b);
        ReplayMapManifest manifest = b.readBoolean() ? readManifest(b) : null;
        int players = count(b, MAX_PLAYERS);
        List<Participant> people = new ArrayList<>(players);
        for (int i = 0; i < players; i++) {
            people.add(new Participant(b.readUUID(), b.readUtf(64), b.readUtf(128), b.readInt(),
                    b.readUtf(128), b.readInt(), b.readEnum(MatchWinFaction.class), b.readBoolean(),
                    b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), List.of()));
        }
        // Decode tracks on the receiving game thread only when the round is still current.
        byte[] tracks = b.readByteArray(ReplayTracks.MAX_BYTES);
        int eventCount = count(b, MAX_EVENTS);
        List<Node> events = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            events.add(new Node(count(b, duration), b.readEnum(Kind.class), readId(b), readId(b),
                    readPosition(b), readPosition(b), b.readUtf(128), b.readUnsignedByte()));
        }
        int spanCount = count(b, MAX_SPANS);
        List<Span> spans = new ArrayList<>(spanCount);
        for (int i = 0; i < spanCount; i++) {
            SpanKind kind = b.readEnum(SpanKind.class);
            UUID actor = readId(b);
            int start = count(b, duration), end = count(b, duration);
            if (end < start) throw new DecoderException("Inverted replay span");
            spans.add(new Span(kind, actor, start, end));
        }
        return new LastMatchSummaryS2C(new LastMatchReplay(id, anchor, duration, map, mode, status, win,
                custom, title, subtitle, color, origin, manifest, people, events, spans,
                readId(b), b.readVarInt(), b.readBoolean()), tracks);
    }

    private static void writeManifest(FriendlyByteBuf b, ReplayMapManifest m) {
        b.writeUtf(m.mapKey(), 128); b.writeUtf(m.dimension(), 128); writeBounds(b, m.sourceBounds());
        b.writeUtf(m.hash(), 64); b.writeVarInt(m.sections().size());
        for (var entry : m.sections()) {
            var s = entry.section();
            b.writeVarInt(s.x()); b.writeVarInt(s.y()); b.writeVarInt(s.z()); b.writeInt(entry.crc32());
        }
    }

    private static ReplayMapManifest readManifest(FriendlyByteBuf b) {
        String key = b.readUtf(128), dimension = b.readUtf(128);
        SceneBounds bounds = readBounds(b);
        String hash = b.readUtf(64);
        int n = count(b, ReplayMapManifest.MAX_SECTIONS);
        List<ReplayMapManifest.Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(new ReplayMapManifest.Entry(
                    new ReplayMapManifest.Section(b.readVarInt(), b.readVarInt(), b.readVarInt()), b.readInt()));
        }
        return new ReplayMapManifest(key, dimension, bounds, hash, entries);
    }

    private static int count(FriendlyByteBuf b, int max) {
        int value = b.readVarInt();
        if (value < 0 || value > max) throw new DecoderException("Bad replay count: " + value);
        return value;
    }

    private static void writeBounds(FriendlyByteBuf b, SceneBounds bounds) {
        b.writeInt(bounds.minX()).writeInt(bounds.minY()).writeInt(bounds.minZ());
        b.writeInt(bounds.maxX()).writeInt(bounds.maxY()).writeInt(bounds.maxZ());
    }

    private static SceneBounds readBounds(FriendlyByteBuf b) {
        int x = b.readInt(), y = b.readInt(), z = b.readInt(), xx = b.readInt(), yy = b.readInt(), zz = b.readInt();
        if (x > xx || y > yy || z > zz) throw new DecoderException("Inverted replay bounds");
        return new SceneBounds(x, y, z, xx, yy, zz);
    }

    private static void writeId(FriendlyByteBuf b, UUID id) {
        b.writeBoolean(id != null);
        if (id != null) b.writeUUID(id);
    }
    private static UUID readId(FriendlyByteBuf b) { return b.readBoolean() ? b.readUUID() : null; }
    private static void writePosition(FriendlyByteBuf b, Position p) {
        b.writeBoolean(p != null);
        if (p != null) b.writeShort(p.x()).writeShort(p.y()).writeShort(p.z());
    }
    private static Position readPosition(FriendlyByteBuf b) {
        return b.readBoolean() ? new Position(b.readShort(), b.readShort(), b.readShort()) : null;
    }
}
