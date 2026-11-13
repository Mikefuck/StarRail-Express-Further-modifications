package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.ReplayTimeline;
import com.habitrain.core.api.scene.asset.SceneAssetCodec;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.scene.block.BoardShapeMath;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;
import static org.junit.jupiter.api.Assertions.*;

/** Renderer-independent front-end logic of the settlement board (no client classes are loaded). */
class ReplayFrontendTest {

    // ------------------------------------------------------------------ track sampling

    @Test void interpolationStaysInsideContinuousSegments() {
        var sampler = new ReplayTrackSampler(List.of(
                sample(0, 0, 0, ALIVE), sample(20, 32, 0, ALIVE), sample(40, 160, 0, (byte) (ALIVE | BREAK))));
        var mid = sampler.sample(10);
        assertEquals(ReplayTrackSampler.State.ALIVE, mid.state());
        assertTrue(mid.x() > 0.5 && mid.x() < 1.5, "eased midpoint between 0 and 2 blocks: " + mid.x());
        // 20..40 must not slide toward the teleport target across the BREAK
        assertEquals(2.0, sampler.sample(30).x(), 1e-9);
        assertEquals(10.0, sampler.sample(40).x(), 1e-9);
        assertTrue(sampler.connects(1));
        assertFalse(sampler.connects(2));
        assertEquals(ReplayTrackSampler.State.HIDDEN, sampler.sample(-1).state());
    }

    @Test void mergedStationaryGapNeverDriftsOrOvershoots() {
        // still from tick 5 to tick 400 (the server merged the still samples), then moves on
        var sampler = new ReplayTrackSampler(List.of(sample(0, 0, 0, ALIVE), sample(5, 80, 0, ALIVE),
                sample(400, 80, 0, ALIVE), sample(405, 96, 0, ALIVE)));
        for (int t = 5; t <= 400; t += 15) assertEquals(5.0, sampler.sample(t).x(), 1e-9, "tick " + t);
        for (double t = 0; t <= 5; t += 0.25) {
            double x = sampler.sample(t).x();
            assertTrue(x >= -1e-9 && x <= 5 + 1e-9, "no overshoot at " + t + ": " + x);
        }
        assertEquals(0.0, sampler.sample(200).speed(), 1e-12);
    }

    @Test void deathTombstoneRevivalAndDepartureStates() {
        var sampler = new ReplayTrackSampler(List.of(sample(0, 0, 0, ALIVE), sample(50, 16, 0, (byte) 0),
                sample(120, 32, 0, (byte) (ALIVE | BREAK)), sample(160, 48, 0, BREAK)));
        var dead = sampler.sample(70);
        assertEquals(ReplayTrackSampler.State.DEAD, dead.state());
        assertEquals(50, dead.stateSince());
        assertEquals(1.0, dead.x(), 1e-9);
        // walking into the death point is interpolated, never past it
        assertTrue(sampler.sample(25).x() <= 1.0 + 1e-9);
        var revived = sampler.sample(130);
        assertEquals(ReplayTrackSampler.State.ALIVE, revived.state());
        assertTrue(revived.revived());
        assertEquals(120, revived.stateSince());
        assertFalse(sampler.sample(10).revived());
        assertEquals(ReplayTrackSampler.State.LEFT, sampler.sample(170).state());
        assertFalse(sampler.sample(170).visible());
    }

    @Test void sameTickDuplicatesUseTheLastSample() {
        var sampler = new ReplayTrackSampler(List.of(sample(0, 0, 0, ALIVE), sample(10, 16, 0, ALIVE),
                sample(10, 16, 0, (byte) 0)));
        assertEquals(ReplayTrackSampler.State.DEAD, sampler.sample(10).state());
        assertEquals(2, sampler.indexAt(10));
        assertEquals(-1, sampler.indexAt(-0.5));
    }

    @Test void yawTakesTheShortestArc() {
        assertEquals(0f, ReplayTrackSampler.lerpYaw(350f, 10f, 0.5f) % 360f, 1e-4f);
        assertEquals(0f, ReplayTrackSampler.lerpYaw(10f, 350f, 0.5f) % 360f, 1e-4f);
        assertEquals(135f, ReplayTrackSampler.lerpYaw(90f, 180f, 0.5f), 1e-4f);
        float y = ReplayTrackSampler.lerpYaw(-170f, 170f, 0.25f);
        assertTrue(y >= 0 && y < 360);
        assertEquals(185f, y, 1e-3f);
    }

    // ------------------------------------------------------------------ choreography

    @Test void stagePlanIsDeterministicBoundedAndLoopsSeamlessly() {
        var timeline = new ReplayTimeline(1_000, 6_000, List.of(900, 2_000, 2_001, 4_000));
        for (long tick = 1_000; tick < 1_000 + timeline.cycleTicks() * 2L; tick += 7) {
            var a = ReplayStagePlan.stage(timeline.at(tick, 0.3f));
            var b = ReplayStagePlan.stage(timeline.at(tick, 0.3f));
            assertEquals(a, b, "same world tick must yield the same choreography");
            for (double v : new double[]{a.trayGlow(), a.scan(), a.plaqueFlip(), a.actors(), a.history(), a.mapSink(), a.honors()}) {
                assertTrue(v >= 0 && v <= 1, "bounded at " + tick + ": " + a);
            }
        }
        var start = ReplayStagePlan.stage(timeline.at(1_000, 0));
        assertEquals(ReplayTimeline.Phase.BUILD, timeline.at(1_000, 0).phase());
        assertEquals(0, start.scan(), 1e-9);
        var end = ReplayStagePlan.stage(timeline.at(1_000 + timeline.cycleTicks() - 1, 0.999f));
        assertEquals(ReplayTimeline.Phase.HONORS, timeline.at(1_000 + timeline.cycleTicks() - 1, 0.999f).phase());
        assertTrue(end.scan() < 0.01 && end.honors() < 0.01, "honors fold and map retract before the next BUILD: " + end);
        // PLAY: fully built map; REVIEW: settlement wave advances monotonically
        long playTick = 1_000 + ReplayTimeline.INTRO + 40;
        assertEquals(1, ReplayStagePlan.stage(timeline.at(playTick, 0)).scan(), 1e-9);
        int action = timeline.cycleTicks() - ReplayTimeline.INTRO - ReplayTimeline.REVIEW - ReplayTimeline.HONORS;
        long review = 1_000 + ReplayTimeline.INTRO + action;
        double previous = -1;
        for (int i = 0; i < 40; i++) {
            double wave = ReplayStagePlan.stage(timeline.at(review + i, 0)).reviewWave();
            if (wave < 0) break;
            assertTrue(wave >= previous);
            previous = wave;
        }
        assertTrue(previous > 0.5);
    }

    @Test void slowMotionWindowsAdvanceAtPointThreeFiveAndFastPartsCatchUp() {
        var timeline = new ReplayTimeline(0, 8_000, List.of(3_000));
        double slowStep = Double.NaN, fastStep = Double.NaN;
        for (long tick = ReplayTimeline.INTRO; tick < timeline.cycleTicks(); tick++) {
            var a = timeline.at(tick, 0);
            var b = timeline.at(tick, 0.5f);
            if (a.phase() != ReplayTimeline.Phase.PLAY || b.phase() != ReplayTimeline.Phase.PLAY) continue;
            double step = (b.replayTick() - a.replayTick()) * 2;
            if (a.slowMotion() && b.slowMotion()) slowStep = step;
            else if (!a.slowMotion() && !b.slowMotion() && Double.isNaN(fastStep)) fastStep = step;
        }
        assertEquals(0.35, slowStep, 1e-6);
        assertTrue(fastStep > 10, "eight-minute match compressed into ~20 s: " + fastStep);
        double rate = ReplayStagePlan.playbackRate(8_000, timeline.cycleTicks());
        assertEquals(fastStep, rate, fastStep * 0.05);
        assertTrue(ReplayStagePlan.cometTicks(rate) >= 200);
    }

    @Test void distanceFadeAndHelpers() {
        assertEquals(1, ReplayStagePlan.distanceFade(10), 1e-9);
        assertEquals(1, ReplayStagePlan.distanceFade(48), 1e-9);
        assertEquals(0, ReplayStagePlan.distanceFade(64), 1e-9);
        double mid = ReplayStagePlan.distanceFade(56);
        assertTrue(mid > 0.3 && mid < 0.7);
        assertEquals("01:05", ReplayStagePlan.clock(65 * 20 + 19));
        assertEquals("00:00", ReplayStagePlan.clock(-40));
        assertEquals(0, ReplayStagePlan.pulse(-1, 10));
        assertEquals(1, ReplayStagePlan.pulse(0, 10), 1e-9);
        assertEquals(0, ReplayStagePlan.pulse(11, 10));
        assertEquals(1, ReplayStagePlan.easeInOutQuart(1), 1e-12);
        assertEquals(0.5, ReplayStagePlan.easeInOutQuart(0.5), 1e-12);
    }

    // ------------------------------------------------------------------ roof cut-away

    @Test void roofCutawayRemovesOnlyLayersAtOrAboveThePlaneAndNeverMutatesTheSource() {
        // minY = 5: section relY 0 covers local y -5..10, relY 1 covers 11..26, relY 2 covers 27..42
        SceneBounds bounds = new SceneBounds(0, 5, 0, 16, 40, 16);
        short[] low = new short[4096], mid = new short[4096], high = new short[4096];
        low[cell(1, 6, 1)] = 1;          // local y = 1
        mid[cell(2, 0, 2)] = 1;          // local y = 11 (kept)
        mid[cell(2, 1, 2)] = 1;          // local y = 12 (removed by cut 12)
        mid[cell(3, 15, 3)] = 1;         // local y = 26 (removed)
        high[cell(0, 0, 0)] = 1;         // local y = 27 (section dropped)
        var palette = List.of(SceneAssetCodec.AIR_BLOCK_ID, "minecraft:stone");
        var lowSection = new SceneAssetCodec.SectionData(0, 0, 0, palette, low, null, null);
        var midSection = new SceneAssetCodec.SectionData(0, 1, 0, palette, mid, null, null);
        var highSection = new SceneAssetCodec.SectionData(0, 2, 0, palette, high, null, null);
        var asset = new SceneAssetCodec.AssetData(1, "minecraft:overworld", bounds, "f".repeat(64),
                List.of(lowSection, midSection, highSection));
        var cut = ReplayRoofCutaway.apply(asset, 12);
        assertEquals(2, cut.sections.size());
        assertSame(lowSection, cut.sections.get(0), "sections entirely below the plane are shared");
        var copy = cut.sections.get(1);
        assertNotSame(mid, copy.blockIndices);
        assertEquals(1, copy.blockIndices[cell(2, 0, 2)]);
        assertEquals(0, copy.blockIndices[cell(2, 1, 2)]);
        assertEquals(0, copy.blockIndices[cell(3, 15, 3)]);
        assertEquals(1, mid[cell(2, 1, 2)], "source untouched");
        assertNotEquals(asset.fingerprint, cut.fingerprint);
        assertSame(asset, ReplayRoofCutaway.apply(asset, -1));
        assertSame(asset, ReplayRoofCutaway.apply(asset, 35));
    }

    @Test void roofCutLayerComesFromTheCeilingHint() {
        assertEquals(5, ReplayRoofCutaway.cutLayer(OptionalDouble.of(6), 20));
        assertEquals(2, ReplayRoofCutaway.cutLayer(OptionalDouble.of(2), 20));
        assertEquals(-1, ReplayRoofCutaway.cutLayer(OptionalDouble.empty(), 20));
        assertEquals(-1, ReplayRoofCutaway.cutLayer(OptionalDouble.of(25), 20));
        assertEquals(-1, ReplayRoofCutaway.cutLayer(OptionalDouble.of(6), 3));
    }

    // ------------------------------------------------------------------ icons

    @Test void deathReasonsMapToWeaponIcons() {
        assertEquals(ReplayDeathIcons.Icon.KNIFE, ReplayDeathIcons.classify("starrailexpress:knife_stab"));
        assertEquals(ReplayDeathIcons.Icon.REVOLVER, ReplayDeathIcons.classify("starrailexpress:revolver_shot"));
        assertEquals(ReplayDeathIcons.Icon.REVOLVER, ReplayDeathIcons.classify("starrailexpress:gun_shot"));
        assertEquals(ReplayDeathIcons.Icon.DERRINGER, ReplayDeathIcons.classify("starrailexpress:derringer_shot"));
        assertEquals(ReplayDeathIcons.Icon.SNIPER, ReplayDeathIcons.classify("starrailexpress:sniper_rifle"));
        assertEquals(ReplayDeathIcons.Icon.BAT, ReplayDeathIcons.classify("starrailexpress:bat_hit"));
        assertEquals(ReplayDeathIcons.Icon.GRENADE, ReplayDeathIcons.classify("starrailexpress:grenade"));
        assertEquals(ReplayDeathIcons.Icon.EXPLOSION, ReplayDeathIcons.classify("noellesroles:bomb_death"));
        assertEquals(ReplayDeathIcons.Icon.POISON, ReplayDeathIcons.classify("starrailexpress:poison"));
        assertEquals(ReplayDeathIcons.Icon.THROWING_KNIFE, ReplayDeathIcons.classify("noellesroles:throwing_knife_hit"));
        assertEquals(ReplayDeathIcons.Icon.BACKFIRE, ReplayDeathIcons.classify("starrailexpress:sniper_rifle_backfire"));
        assertEquals(ReplayDeathIcons.Icon.FALL, ReplayDeathIcons.classify("starrailexpress:fell_out_of_train"));
        assertEquals(ReplayDeathIcons.Icon.SIN, ReplayDeathIcons.classify("habitrain_core:wrath_exhaustion"));
        assertEquals(ReplayDeathIcons.Icon.SKULL, ReplayDeathIcons.classify(""));
        assertEquals(ReplayDeathIcons.Icon.SKULL, ReplayDeathIcons.classify(null));
        for (ReplayDeathIcons.Icon icon : ReplayDeathIcons.Icon.values()) {
            List<String> candidates = ReplayDeathIcons.itemCandidates(icon);
            assertTrue(candidates.getLast().startsWith("minecraft:"), icon + " needs a vanilla fallback");
        }
    }

    // ------------------------------------------------------------------ dedicated server isolation

    @Test void commonFrontendClassesHaveNoClientLinks() throws Exception {
        String pkg = "com/habitrain/core/";
        List<String> names = new ArrayList<>(List.of("scene/block/SettlementSummaryBoard",
                "scene/block/SettlementSummaryBoard$Marker", "scene/block/BoardShapeMath", "scene/replay/ReplayTrackSampler",
                "scene/replay/ReplayTrackSampler$Pose", "scene/replay/ReplayStagePlan", "scene/replay/ReplayStagePlan$Stage",
                "scene/replay/ReplayRoofCutaway", "scene/replay/ReplayFanCutaway",
                "scene/replay/ReplayDeathIcons", "scene/replay/ReplayDeathIcons$Icon",
                "scene/replay/ReplayFocusPlan", "scene/replay/ReplayFocusPlan$Pose", "scene/replay/ReplayMapViewport"));
        for (String name : names) {
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

    @Test void boardShapeIsRotatedPerFacing() {
        double[] box = {1, 0, 2, 15, 3, 5};
        assertArrayEquals(new double[]{1, 0, 2, 15, 3, 5}, BoardShapeMath.rotate(box, Direction.NORTH), 1e-9);
        assertArrayEquals(new double[]{11, 0, 1, 14, 3, 15}, BoardShapeMath.rotate(box, Direction.EAST), 1e-9);
        assertArrayEquals(new double[]{1, 0, 11, 15, 3, 14}, BoardShapeMath.rotate(box, Direction.SOUTH), 1e-9);
        assertArrayEquals(new double[]{2, 0, 1, 5, 3, 15}, BoardShapeMath.rotate(box, Direction.WEST), 1e-9);
        // four quarter turns are the identity
        double[] r = box;
        for (int i = 0; i < 4; i++) r = BoardShapeMath.rotate(r, Direction.EAST);
        assertArrayEquals(box, r, 1e-9);
    }

    private static int cell(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    private static Sample sample(int tick, int x16, int z16, byte flags) {
        return new Sample(tick, new Position((short) x16, (short) 0, (short) z16), (byte) 0, flags);
    }
}
