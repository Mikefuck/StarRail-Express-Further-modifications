package com.habitrain.core.scene.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.api.scene.model.SceneBounds;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.habitrain.core.api.match.replay.LastMatchReplay.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayFanCutawayTest {
    private static final SceneBounds BOUNDS = new SceneBounds(0, 0, 0, 64, 32, 64);

    @Test void opensAnEightyDegreeFanTowardsTheViewerAndKeepsTheFloorAndRear() {
        var cut = new ReplayFanCutaway();
        cut.direction(0, 1);
        cut.add(pose(16, 4, 16), BOUNDS, BOUNDS, 1.25);
        assertTrue(cut.removes(16, 5.5, 24), "front wall opens");
        assertTrue(cut.removes(16, 9.5, 16), "rounded apex opens the roof above the head");
        assertFalse(cut.removes(16, 3.5, 24), "supporting floor remains");
        assertFalse(cut.removes(16, 5.5, 8), "rear wall remains");
        assertTrue(cut.removes(16 + 10 * Math.sin(Math.toRadians(35)), 6, 16 + 10 * Math.cos(Math.toRadians(35))));
        assertFalse(cut.removes(16 + 10 * Math.sin(Math.toRadians(55)), 6, 16 + 10 * Math.cos(Math.toRadians(55))));
    }

    @Test void turningTheViewReversesTheFanWithoutChangingItsAvatarAnchor() {
        var cut = new ReplayFanCutaway();
        cut.add(pose(16, 4, 16), BOUNDS, BOUNDS, 1.25);
        cut.direction(0, 1);
        assertTrue(cut.removes(16, 5.5, 24));
        cut.direction(0, -1);
        assertFalse(cut.removes(16, 5.5, 24));
        assertTrue(cut.removes(16, 5.5, 8));
        assertEquals(1, cut.count());
    }

    @Test void movingAndChangingFloorsUpdatesTheOpeningFromTheInterpolatedReplayPose() {
        var track = new ReplayTrackSampler(List.of(
                new Sample(0, Position.quantize(4, 2, 4), (byte) 0, ALIVE),
                new Sample(20, Position.quantize(24, 8, 4), (byte) 0, ALIVE)));
        var cut = new ReplayFanCutaway();
        cut.add(track.sample(0), BOUNDS, BOUNDS, 1.25);
        assertTrue(cut.removes(4, 3.5, 4));
        cut.clear();
        cut.add(track.sample(10), BOUNDS, BOUNDS, 1.25);
        assertEquals(14, cut.anchors()[0], 1e-6);
        assertEquals(5, cut.anchors()[1], 1e-6);
        assertFalse(cut.removes(4, 3.5, 4), "old location is restored");
        assertFalse(cut.removes(14, 4.5, 4), "floor below new height remains");
        assertTrue(cut.removes(14, 6.5, 4));
    }

    @Test void independentParticipantsOnDifferentFloorsProduceIndependentOpenings() {
        var cut = new ReplayFanCutaway();
        cut.add(pose(4, 2, 4), BOUNDS, BOUNDS, 1.25);
        cut.add(pose(40, 12, 4), BOUNDS, BOUNDS, 1.25);
        assertTrue(cut.removes(4, 3.5, 6));
        assertTrue(cut.removes(40, 13.5, 6));
        assertFalse(cut.removes(40, 11.5, 6));
        assertFalse(cut.removes(24, 13.5, 6), "no global horizontal plane");
    }

    @Test void sourceAndReplayOriginsNeedNotMatchAndMayBeNegative() {
        var source = new SceneBounds(-100, -64, 200, -36, 36, 264);
        var origin = new SceneBounds(-90, -60, 207, -58, -20, 239);
        var cut = new ReplayFanCutaway();
        cut.add(pose(2, 3, 4), origin, source, 1.25);
        assertEquals(12, cut.anchors()[0]);
        assertEquals(7, cut.anchors()[1]);
        assertEquals(11, cut.anchors()[2]);
        assertTrue(cut.removes(12, 8.5, 11));
        assertFalse(cut.removes(12, 6.5, 11));
    }

    @Test void transformedBoardsAndQuarterTurnMapsStillOpenTowardsTheViewer() {
        for (int facing = 0; facing < 360; facing += 90) {
            for (int mapRotation : new int[]{0, 90}) {
                Matrix4f model = new Matrix4f().translate(-8, -3, -12)
                        .rotateY((float) Math.toRadians(facing + 37 + mapRotation))
                        .scale(0.3f).translate(-32, 0, -32);
                Vector3f expected = new Vector3f(0.6f, 0.4f, 0.8f).normalize();
                Vector3f look = model.transformDirection(new Vector3f(expected)).normalize().negate();
                var cut = new ReplayFanCutaway();
                cut.faceViewer(model, look, BOUNDS);
                assertEquals(0.6, cut.directionX(), 1e-5);
                assertEquals(0.8, cut.directionZ(), 1e-5);
            }
        }
    }

    @Test void overheadAndInvalidDirectionsStayFiniteAndDeterministic() {
        var cut = new ReplayFanCutaway();
        Matrix4f model = new Matrix4f().translate(0, -5, -8).scale(0.25f).translate(-32, 0, -32);
        cut.faceViewer(model, new Vector3f(0, -1, 0), BOUNDS);
        assertEquals(0, cut.directionX(), 1e-6);
        assertEquals(1, cut.directionZ(), 1e-6);
        cut.direction(Double.NaN, 0);
        assertEquals(0, cut.directionX());
        assertEquals(1, cut.directionZ());
    }

    @Test void hiddenAndDepartedPlayersDoNotCutButVisibleDeathMarkersDo() {
        var cut = new ReplayFanCutaway();
        for (var state : ReplayTrackSampler.State.values()) {
            cut.add(new ReplayTrackSampler.Pose(state, 4, 2, 4, 0, 0, 0, 0, false), BOUNDS, BOUNDS, 1.25);
        }
        assertEquals(2, cut.count());
        cut.clear();
        assertEquals(0, cut.count());
        assertFalse(cut.removes(4, 3.5, 4), "full body/no active heads restores geometry");
    }

    @Test void everySupportedPlayerFitsTheShaderBufferAndExcessEntriesAreIgnored() {
        var cut = new ReplayFanCutaway();
        for (int i = 0; i < MAX_PLAYERS + 3; i++) cut.add(pose(i, 2, 4), BOUNDS, BOUNDS, 1.25);
        assertEquals(MAX_PLAYERS, cut.count());
        assertEquals(32 * 16, cut.anchors().length);
        assertEquals(MAX_PLAYERS - 1, cut.anchors()[(MAX_PLAYERS - 1) * 4]);
    }

    private static ReplayTrackSampler.Pose pose(double x, double y, double z) {
        return new ReplayTrackSampler.Pose(ReplayTrackSampler.State.ALIVE, x, y, z, 0, ALIVE, 0, 0, false);
    }
}
