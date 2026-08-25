package com.habitrain.core.game.sre;

import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure reconnect-table policy for the rest area. Does not construct a MinecraftServer.
 */
class EliminatedRestAreaServiceTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c4");

    @BeforeEach
    @AfterEach
    void reset() {
        EliminatedRestAreaService.resetTablesForTest();
    }

    @Test
    void disconnectDropsRestAndPromptButKeepsEliminated() {
        EliminatedRestAreaService.seedDisconnectFixture(PLAYER, Level.OVERWORLD);

        EliminatedRestAreaService.handleDisconnect(PLAYER);

        assertTrue(EliminatedRestAreaService.hasEliminated(PLAYER),
                "ELIMINATED_PLAYERS must survive disconnect for same-round re-entry");
        assertFalse(EliminatedRestAreaService.hasRestOccupancy(PLAYER));
        assertFalse(EliminatedRestAreaService.hasTransientDisconnectState(PLAYER));
    }

    @Test
    void joinFromLobbyKeepsEliminatedAndDoesNotTreatUnknownWorldAsEnded() {
        EliminatedRestAreaService.seedDisconnectFixture(PLAYER, Level.OVERWORLD);
        EliminatedRestAreaService.handleDisconnect(PLAYER);

        EliminatedRestAreaService.applyJoinReconnect(PLAYER, false, false, false, true);

        assertTrue(EliminatedRestAreaService.hasEliminated(PLAYER));
        assertFalse(EliminatedRestAreaService.hasRestOccupancy(PLAYER));
    }

    @Test
    void joinDropsStaleRestKeyWhenMatchWorldIsGone() {
        EliminatedRestAreaService.seedDisconnectFixture(PLAYER, Level.OVERWORLD);

        EliminatedRestAreaService.applyJoinReconnect(PLAYER, true, false, true, true);

        assertFalse(EliminatedRestAreaService.hasRestOccupancy(PLAYER));
        assertFalse(EliminatedRestAreaService.hasEliminated(PLAYER),
                "ended/missing match must not leave a stale elimination entry");
    }

    @Test
    void joinDoesNotRestoreRestOccupancyWhenMatchStillRuns() {
        EliminatedRestAreaService.seedDisconnectFixture(PLAYER, Level.OVERWORLD);

        EliminatedRestAreaService.applyJoinReconnect(PLAYER, true, true, false, true);

        assertFalse(EliminatedRestAreaService.hasRestOccupancy(PLAYER),
                "JOIN must not auto-restore rest occupancy");
        assertTrue(EliminatedRestAreaService.hasEliminated(PLAYER));
    }

    @Test
    void joinDropsEliminatedWhenCurrentMatchWorldIsKnownStopped() {
        EliminatedRestAreaService.seedDisconnectFixture(PLAYER, Level.OVERWORLD);
        EliminatedRestAreaService.handleDisconnect(PLAYER);

        EliminatedRestAreaService.applyJoinReconnect(PLAYER, false, false, true, true);

        assertFalse(EliminatedRestAreaService.hasEliminated(PLAYER));
        assertFalse(EliminatedRestAreaService.hasRestOccupancy(PLAYER));
    }

    @Test
    void finishUpstreamRevivalWithoutPrepareDoesNotDropEliminated() {
        EliminatedRestAreaService.seedRestingEliminated(PLAYER, Level.OVERWORLD);

        assertFalse(EliminatedRestAreaService.completeUpstreamRevival(PLAYER));

        assertTrue(EliminatedRestAreaService.hasEliminated(PLAYER));
        assertTrue(EliminatedRestAreaService.hasRestOccupancy(PLAYER));
        assertFalse(EliminatedRestAreaService.isMarkedReviving(PLAYER));
    }

    @Test
    void finishUpstreamRevivalOnEliminatedSpectatorDoesNotDropEliminated() {
        EliminatedRestAreaService.markEliminated(PLAYER);

        assertFalse(EliminatedRestAreaService.beginUpstreamRevival(PLAYER));
        assertFalse(EliminatedRestAreaService.completeUpstreamRevival(PLAYER));

        assertTrue(EliminatedRestAreaService.hasEliminated(PLAYER));
        assertFalse(EliminatedRestAreaService.hasRestOccupancy(PLAYER));
    }

    @Test
    void prepareThenFinishDropsRestAndEliminated() {
        EliminatedRestAreaService.seedRestingEliminated(PLAYER, Level.OVERWORLD);

        assertTrue(EliminatedRestAreaService.beginUpstreamRevival(PLAYER));
        assertTrue(EliminatedRestAreaService.isMarkedReviving(PLAYER));
        assertTrue(EliminatedRestAreaService.completeUpstreamRevival(PLAYER));

        assertFalse(EliminatedRestAreaService.hasEliminated(PLAYER));
        assertFalse(EliminatedRestAreaService.hasRestOccupancy(PLAYER));
        assertFalse(EliminatedRestAreaService.isMarkedReviving(PLAYER));
    }

    @Test
    void enteringRestDoesNotCountAsRevival() {
        EliminatedRestAreaService.seedRestingEliminated(PLAYER, Level.OVERWORLD);
        EliminatedRestAreaService.seedEnteringRest(PLAYER);

        assertFalse(EliminatedRestAreaService.beginUpstreamRevival(PLAYER));
        assertFalse(EliminatedRestAreaService.isMarkedReviving(PLAYER));
        assertFalse(EliminatedRestAreaService.completeUpstreamRevival(PLAYER));

        assertTrue(EliminatedRestAreaService.hasEliminated(PLAYER));
        assertTrue(EliminatedRestAreaService.hasRestOccupancy(PLAYER));
    }

    @Test
    void gameEndClearsOnlyMatchingDimension() {
        EliminatedRestAreaService.seedRestingEliminated(PLAYER, Level.OVERWORLD);
        EliminatedRestAreaService.seedRestingEliminated(OTHER, Level.NETHER);

        EliminatedRestAreaService.clearRoundStateForDimension(Level.OVERWORLD);

        assertFalse(EliminatedRestAreaService.hasEliminated(PLAYER));
        assertFalse(EliminatedRestAreaService.hasRestOccupancy(PLAYER));
        assertTrue(EliminatedRestAreaService.hasEliminated(OTHER),
                "nether rester must survive overworld round clear");
        assertTrue(EliminatedRestAreaService.hasRestOccupancy(OTHER));
    }

    @Test
    void uuidOnlyEliminatedWithoutDimSurvivesOtherDimensionClear() {
        EliminatedRestAreaService.markEliminated(PLAYER);
        EliminatedRestAreaService.seedRestingEliminated(OTHER, Level.NETHER);

        EliminatedRestAreaService.clearRoundStateForDimension(Level.NETHER);

        assertTrue(EliminatedRestAreaService.hasEliminated(PLAYER),
                "UUID-only markEliminated has no match dim and must not be stripped by another level");
        assertFalse(EliminatedRestAreaService.hasEliminated(OTHER));
    }

    @Test
    void disconnectKeepsEliminatedTiedToMatchAfterOccupancyDrop() {
        EliminatedRestAreaService.seedRestingEliminated(PLAYER, Level.OVERWORLD);
        EliminatedRestAreaService.seedRestingEliminated(OTHER, Level.NETHER);
        EliminatedRestAreaService.handleDisconnect(PLAYER);

        assertTrue(EliminatedRestAreaService.hasEliminated(PLAYER));
        assertFalse(EliminatedRestAreaService.hasRestOccupancy(PLAYER));

        EliminatedRestAreaService.clearRoundStateForDimension(Level.NETHER);

        assertTrue(EliminatedRestAreaService.hasEliminated(PLAYER),
                "overworld elimination must survive nether end after disconnect dropped occupancy");

        EliminatedRestAreaService.clearRoundStateForDimension(Level.OVERWORLD);
        assertFalse(EliminatedRestAreaService.hasEliminated(PLAYER),
                "overworld end must still drop elim dim after occupancy was disconnected");
    }
}
