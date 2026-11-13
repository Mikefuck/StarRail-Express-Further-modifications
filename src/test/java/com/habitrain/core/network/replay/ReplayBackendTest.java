package com.habitrain.core.network.replay;

import com.habitrain.core.api.match.MatchWinFaction;
import com.habitrain.core.api.match.MatchWinKind;
import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.match.replay.ReplayMapManifest;
import com.habitrain.core.api.match.replay.ReplayProjection;
import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.replay.ReplayMapCapture;
import com.habitrain.core.scene.replay.ReplaySectionGeometry;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;
import static com.habitrain.core.network.replay.ReplayPackets.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayBackendTest {
    private static final UUID ROUND = new UUID(1, 2);
    private static final UUID PLAYER = new UUID(3, 4);
    private static final String HASH = "a".repeat(64);
    private static final SceneBounds BOUNDS = new SceneBounds(-19, 6, 2, 17, 14, 8);

    @Test void quantizationRejectsOverflowAndResolvesSignedSixteenths() {
        var p = Position.quantize(-2.20, 12.0625, 0.03125);
        assertNotNull(p);
        assertEquals(-2.1875, p.blockX());
        assertEquals(12.0625, p.blockY());
        assertEquals(0.0625, p.blockZ());
        assertNull(Position.quantize(2048, 0, 0));
        assertNotNull(Position.quantize(-2048, 0, 0));
        assertNull(Position.quantize(Double.NaN, 0, 0));
    }

    @Test void trackSimplificationRetainsCornersAndStateChanges() {
        List<Sample> path = new ArrayList<>();
        ReplayTracks.append(path, point(0, 0, 0, ALIVE));
        ReplayTracks.append(path, point(5, 16, 0, ALIVE));
        ReplayTracks.append(path, point(10, 32, 0, ALIVE));
        ReplayTracks.append(path, point(15, 32, -16, ALIVE));
        ReplayTracks.append(path, point(16, 32, -16, (byte) 0));
        ReplayTracks.append(path, point(25, 48, -16, (byte) (ALIVE | BREAK)));
        assertEquals(List.of(0, 10, 15, 16, 25), path.stream().map(Sample::tick).toList());
        byte[] wire = ReplayTracks.encode(List.of(path, List.of()));
        assertEquals(List.of(path, List.of()), ReplayTracks.decode(wire, 2, 25));
        assertThrows(RuntimeException.class, () -> ReplayTracks.decode(wire, 2, 24));
    }

    @Test void oversizedTrackBudgetStillRetainsEveryPlayersEndpoints() {
        List<List<Sample>> tracks = new ArrayList<>();
        for (int p = 0; p < MAX_PLAYERS; p++) {
            List<Sample> samples = new ArrayList<>();
            for (int i = 0; i < 2000; i++) ReplayTracks.append(samples,
                    point(i * 5, (short) (i * 67), (short) (i * -221), i % 2 == 0 ? ALIVE : (byte) 0));
            tracks.add(samples);
        }
        byte[] wire = ReplayTracks.encode(tracks);
        assertTrue(wire.length <= ReplayTracks.MAX_BYTES);
        var decoded = ReplayTracks.decode(wire, MAX_PLAYERS, 10_000);
        for (var track : decoded) {
            assertEquals(0, track.getFirst().tick());
            assertEquals(9995, track.getLast().tick());
        }
    }

    @Test void manifestComputesOnlyMissingOrChangedSections() {
        var a = new ReplayMapManifest.Section(0, 0, 0);
        var b = new ReplayMapManifest.Section(1, 0, 0);
        var c = new ReplayMapManifest.Section(2, 0, 0);
        var manifest = new ReplayMapManifest("train", "minecraft:overworld", BOUNDS, HASH,
                List.of(new ReplayMapManifest.Entry(a, 1), new ReplayMapManifest.Entry(b, 2), new ReplayMapManifest.Entry(c, 3)));
        Map<ReplayMapManifest.Section, Integer> cached = Map.of(a, 1, b, 99);
        assertArrayEquals(new int[]{1, 2}, manifest.missing(cached::get).stream().toArray());
        assertTrue(manifest.missing(Map.of(a, 1, b, 2, c, 3)::get).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ReplayMapManifest("train", "d", BOUNDS, HASH,
                List.of(new ReplayMapManifest.Entry(a, 1), new ReplayMapManifest.Entry(a, 2))));
    }

    @Test void sceneV3PatchRetainsItsGeometryFingerprintAndExactLocalOffsets() throws Exception {
        short[] cells = new short[4096];
        cells[255] = 1;
        var world = new SceneAssetCodec.SectionData(-1, 0, 0, List.of("minecraft:air", "minecraft:stone"), cells, null, null);
        var rel = ReplaySectionGeometry.relative(world, BOUNDS);
        assertEquals(1, rel.relX);
        var another = new SceneAssetCodec.SectionData(0, 0, 0, rel.palette, cells, null, null);
        var asset = new SceneAssetCodec.AssetData(3955, "minecraft:overworld", BOUNDS, HASH, List.of(rel));
        var decoded = SceneAssetCodec.decodeGeometry(SceneAssetCodec.encode(asset), 1).sections.getFirst();
        assertEquals(ReplaySectionGeometry.crc(world), ReplaySectionGeometry.crc(decoded));
        assertArrayEquals(cells, decoded.blockIndices);
        cells[255] = 0;
        assertNotEquals(ReplaySectionGeometry.crc(world), ReplaySectionGeometry.crc(decoded));
        var two = new SceneAssetCodec.AssetData(3955, "minecraft:overworld", BOUNDS, HASH, List.of(rel, another));
        assertThrows(IOException.class, () -> SceneAssetCodec.decodeGeometry(SceneAssetCodec.encode(two), 1));
    }

    @Test void unfinishedMapPublishesCapturedSectionsWithoutShrinkingThePlayingArea() throws Exception {
        SharedConstants.tryDetectVersion();
        short[] cells = new short[4096];
        cells[(6 << 8) | (2 << 4)] = 1;
        var world = new SceneAssetCodec.SectionData(-1, 0, 0,
                List.of("minecraft:air", "minecraft:stone"), cells, null, null);
        var occupied = new SceneBounds(-16, 6, 2, -15, 7, 3);
        var snapshot = new ReplaySectionGeometry.Snapshot(world, ReplaySectionGeometry.crc(world), occupied);
        var partial = ReplayMapCapture.encode(new ReplayMapCapture.Raw("train", "minecraft:overworld",
                List.of(snapshot), BOUNDS), Map.of());
        assertEquals(BOUNDS, partial.manifest().sourceBounds());
        assertEquals(List.of(new ReplayMapManifest.Entry(new ReplayMapManifest.Section(1, 0, 0), snapshot.crc())),
                partial.manifest().sections());
        var patch = SceneAssetCodec.decodeGeometry(partial.patches().getFirst(), 1);
        assertEquals(BOUNDS, patch.sourceBounds);
        assertEquals(1, patch.sections.getFirst().relX);
        assertArrayEquals(cells, patch.sections.getFirst().blockIndices);

        var complete = ReplayMapCapture.encode(new ReplayMapCapture.Raw("train", "minecraft:overworld",
                List.of(snapshot), null), Map.of(partial.manifest().hash(), partial));
        assertEquals(occupied, complete.manifest().sourceBounds());
        assertNotEquals(partial.manifest().hash(), complete.manifest().hash());
    }

    @Test void timelineRepeatsDeterministicallyAndHasActualSlowMotion() {
        var clock = new ReplayTimeline(1000, 1200, List.of(200, 600, 601, 1000));
        assertEquals(ReplayTimeline.Phase.BUILD, clock.at(999, .5f).phase());
        assertEquals(ReplayTimeline.Phase.PLAY, clock.at(1050, 0).phase());
        double prior = 0;
        boolean sawSlow = false;
        boolean sawReview = false, sawHonors = false;
        for (int t = 0; t < clock.cycleTicks(); t++) {
            var frame = clock.at(1000L + t, .25f);
            assertEquals(frame, clock.at(1000L + t + 5L * clock.cycleTicks(), .25f));
            assertTrue(frame.replayTick() >= prior);
            prior = frame.replayTick();
            var next = clock.at(1001L + t, .25f);
            if (frame.slowMotion() && next.slowMotion()) {
                sawSlow = true;
                assertEquals(.35, next.replayTick() - frame.replayTick(), 1e-8);
            }
            sawReview |= frame.phase() == ReplayTimeline.Phase.REVIEW;
            sawHonors |= frame.phase() == ReplayTimeline.Phase.HONORS;
        }
        assertTrue(sawSlow && sawReview && sawHonors);
        assertEquals(1200.0, prior);
    }

    @Test void summaryWirePreservesWinnerProjectionEventsAndTrackBlob() {
        var where = Position.quantize(16, 1, 3);
        var actor = new Participant(PLAYER, "Mike", "test:old_role", 0xffaa0000, "test:new_role", 0xff00aa00,
                MatchWinFaction.NEUTRAL, true, 2, 1, 4, 3, List.of(new Sample(10, where, (byte) 9, ALIVE)));
        var shot = new Node(10, Kind.GUNSHOT, PLAYER, null, where, where, "sre:revolver", HIT | GUN_DROP);
        var manifest = new ReplayMapManifest("train", "minecraft:overworld", BOUNDS, HASH,
                List.of(new ReplayMapManifest.Entry(new ReplayMapManifest.Section(0, 0, 0), 123)));
        var replay = new LastMatchReplay(ROUND, 1000, 40, "Train", "sre:murder", "CUSTOM", MatchWinKind.CUSTOM,
                "test:winner", "{\"translate\":\"test.winner\"}", "", 0xff00aa00, BOUNDS, manifest,
                List.of(actor), List.of(shot), List.of(new Span(SpanKind.BLACKOUT, null, 5, 20)), PLAYER, 1, false);
        var layout = ReplayProjection.layout(replay);
        assertNotNull(layout);
        assertEquals((16 - 18) * layout.scale(), layout.project(where).x(), 1e-8);

        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            byte[] track = ReplayTracks.encode(List.of(actor.track()));
            LastMatchSummaryS2C.CODEC.encode(buf, new LastMatchSummaryS2C(replay, track));
            var read = LastMatchSummaryS2C.CODEC.decode(buf);
            assertEquals(replay.manifest(), read.replay().manifest());
            assertEquals(replay.events(), read.replay().events());
            assertEquals(replay.spans(), read.replay().spans());
            assertEquals(PLAYER, read.replay().mvp());
            assertTrue(read.replay().participants().getFirst().winner());
            assertEquals(List.of(actor.track()), ReplayTracks.decode(read.tracks(), 1, 40));
        } finally { buf.release(); }
    }

    @Test void invalidMissingRequestsAndSectionFragmentsAreRejected() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BitSet tooMany = new BitSet();
            tooMany.set(0, MAX_REQUESTED + 1);
            buf.writeBlockPos(BlockPos.ZERO); buf.writeUUID(ROUND); buf.writeByteArray(tooMany.toByteArray());
            assertThrows(DecoderException.class, () -> ReplayMissingSectionsC2S.CODEC.decode(buf));
        } finally { buf.release(); }
        assertThrows(IllegalArgumentException.class, () -> new ReplaySectionPatchS2C(ROUND, 0, 1, 2, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new ReplaySectionPatchS2C(ROUND, 0, 0, MAX_SECTION_BYTES + 1, new byte[FRAGMENT_BYTES]));
    }

    @Test void dedicatedReplayBytecodeHasNoClientLinks() throws Exception {
        var pkg = "com/habitrain/core/";
        for (String name : List.of("api/match/replay/LastMatchReplay", "api/match/replay/ReplayMapManifest",
                "api/match/replay/ReplayProjection", "api/match/replay/ReplayTimeline", "network/replay/ReplayPackets",
                "network/replay/ReplayTracks", "scene/block/SettlementSummaryBoard", "scene/replay/ReplayMapCapture",
                "scene/replay/ReplaySectionGeometry", "game/sre/replay/MatchReplayRecorder", "game/sre/replay/ReplayBoardService")) {
            try (var stream = getClass().getClassLoader().getResourceAsStream(pkg + name + ".class")) {
                assertNotNull(stream, name);
                String constants = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
                for (String forbidden : List.of("net/minecraft/client/", "com/mojang/blaze3d/",
                        "net/fabricmc/fabric/api/client/", pkg + "client/", pkg + "scene/client/")) {
                    assertFalse(constants.contains(forbidden), name + " links to " + forbidden);
                }
            }
        }
    }

    private static Sample point(int tick, int x, int z, byte flags) {
        return new Sample(tick, new Position((short) x, (short) 0, (short) z), (byte) 0, flags);
    }
}
