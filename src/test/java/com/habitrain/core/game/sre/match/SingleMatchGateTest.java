package com.habitrain.core.game.sre.match;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SingleMatchGateTest {
    @Test void cancellationTokenCannotReleaseAReplacementVote() {
        SingleMatchGate gate = new SingleMatchGate();
        assertTrue(gate.vote("a"));
        var cancelled = gate.snapshot();
        gate.releaseVote(cancelled);
        assertTrue(gate.vote("a"));
        var replacement = gate.snapshot();
        gate.releaseVote(cancelled);
        assertEquals(replacement, gate.snapshot());
        assertFalse(gate.nativeStart("b"));
    }
    @Test void voteTokenCannotReleaseLoadingOrAnotherServerInstance() {
        SingleMatchGate gate = new SingleMatchGate();
        assertTrue(gate.vote("a"));
        var old = gate.snapshot();
        assertTrue(gate.load("a"));
        gate.releaseVote(old);
        assertEquals(SingleMatchGate.Phase.LOADING, gate.snapshot().phase());
        gate.reset();
        assertTrue(gate.vote("a"));
        var current = gate.snapshot();
        gate.releaseVote(old);
        assertEquals(current, gate.snapshot());
    }
    @Test void voteAndLoadReserveTheEntireServerBeforeTheGameIsRunning() {
        SingleMatchGate gate = new SingleMatchGate();
        assertTrue(gate.vote("minecraft:overworld"));
        long round = gate.snapshot().roundId();
        assertFalse(gate.vote("minecraft:the_nether"));
        assertFalse(gate.load("minecraft:the_nether"));
        assertFalse(gate.start("minecraft:the_nether"));
        assertFalse(gate.nativeStart("minecraft:the_nether"));
        assertTrue(gate.load("minecraft:overworld"));
        assertTrue(gate.start("minecraft:overworld"));
        assertEquals(round, gate.snapshot().roundId());
        assertFalse(gate.start("minecraft:overworld"), "independent duplicate start is rejected");
        assertTrue(gate.nativeStart("minecraft:overworld"), "registered mode can enter native SRE once");
        assertFalse(gate.nativeStart("minecraft:overworld"));
        assertTrue(gate.nativeConfirm("minecraft:overworld"));
        assertFalse(gate.nativeConfirm("minecraft:overworld"));
    }
    @Test void runningAndSettlementHoldOwnershipUntilCleanup() {
        SingleMatchGate gate = new SingleMatchGate();
        assertTrue(gate.nativeConfirm("a"), "direct upstream trueStartGame has the same guard");
        gate.transition("a", SingleMatchGate.Phase.RUNNING);
        gate.abortPreparing("a");
        gate.releaseVote("a");
        assertTrue(gate.owns("a"));
        gate.transition("a", SingleMatchGate.Phase.SETTLING);
        assertFalse(gate.vote("b"));
        gate.release("b");
        assertTrue(gate.owns("a"), "cleanup from another dimension cannot release the owner");
        gate.release("a");
        assertTrue(gate.vote("b"));
    }
    @Test void abortAndServerReplacementCannotReuseTheOldRoundIdentity() {
        SingleMatchGate gate = new SingleMatchGate();
        assertTrue(gate.load("a"));
        var previous = gate.snapshot();
        gate.abortPreparing("a");
        assertTrue(gate.start("b"));
        assertTrue(gate.snapshot().roundId() > previous.roundId());
        gate.reset();
        assertEquals(SingleMatchGate.Phase.LOBBY, gate.snapshot().phase());
        assertNotEquals(previous.serverId(), gate.snapshot().serverId());
        assertTrue(gate.nativeConfirm("a"));
    }
}
