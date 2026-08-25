package com.habitrain.core.vote;

import com.habitrain.core.api.VoteOption;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionVoteManagerTest {

    @Test
    void mapVoteIdIsOnlyTheMapPhaseToken() {
        assertTrue(OptionVoteManager.isMapVoteId("map"));
        assertFalse(OptionVoteManager.isMapVoteId("mode"));
        assertFalse(OptionVoteManager.isMapVoteId(""));
        assertFalse(OptionVoteManager.isMapVoteId(null));
    }

    @Test
    void lockedWinnerFallsToHighestUnlockedTally() {
        List<VoteOption> options = List.of(
                new VoteOption("locked_map", "Locked"),
                new VoteOption("second", "Second"),
                new VoteOption("third", "Third"));
        Map<UUID, String> votes = new HashMap<>();
        votes.put(UUID.randomUUID(), "locked_map");
        votes.put(UUID.randomUUID(), "locked_map");
        votes.put(UUID.randomUUID(), "locked_map");
        votes.put(UUID.randomUUID(), "second");
        votes.put(UUID.randomUUID(), "second");
        votes.put(UUID.randomUUID(), "third");

        OptionVoteManager.WinnerPick pick = OptionVoteManager.pickWinner(
                options, votes, id -> "locked_map".equals(id), n -> 0);

        assertEquals("second", pick.winnerId());
        assertFalse(pick.randomPick());
    }

    @Test
    void allLockedMapsCancelWithNullWinner() {
        List<VoteOption> options = List.of(
                new VoteOption("a", "A"),
                new VoteOption("b", "B"));
        Map<UUID, String> votes = new HashMap<>();
        votes.put(UUID.randomUUID(), "a");
        votes.put(UUID.randomUUID(), "b");

        OptionVoteManager.WinnerPick pick = OptionVoteManager.pickWinner(
                options, votes, id -> true, n -> 0);

        assertNull(pick.winnerId());
        assertFalse(pick.randomPick());
    }

    @Test
    void votesForLockedMapsDoNotElectThemWhenUnlockedHaveZero() {
        List<VoteOption> options = List.of(
                new VoteOption("locked_map", "Locked"),
                new VoteOption("open_a", "Open A"),
                new VoteOption("open_b", "Open B"));
        Map<UUID, String> votes = new HashMap<>();
        votes.put(UUID.randomUUID(), "locked_map");
        votes.put(UUID.randomUUID(), "locked_map");

        OptionVoteManager.WinnerPick pick = OptionVoteManager.pickWinner(
                options, votes, id -> "locked_map".equals(id), n -> 0);

        assertTrue(pick.randomPick());
        assertTrue(Set.of("open_a", "open_b").contains(pick.winnerId()));
    }

    @Test
    void modeVoteDoesNotDropACandidateWhenPredicateIsOpen() {
        List<VoteOption> options = List.of(
                new VoteOption("habitrain_core:sre:murder", "Murder"),
                new VoteOption("habitrain_core:sre:repair", "Repair"));
        Map<UUID, String> votes = new HashMap<>();
        votes.put(UUID.randomUUID(), "habitrain_core:sre:repair");
        votes.put(UUID.randomUUID(), "habitrain_core:sre:repair");
        votes.put(UUID.randomUUID(), "habitrain_core:sre:murder");

        OptionVoteManager.WinnerPick pick = OptionVoteManager.pickWinner(
                options, votes, id -> false, n -> 0);

        assertEquals("habitrain_core:sre:repair", pick.winnerId());
        assertFalse(pick.randomPick());
    }

    @Test
    void emptyOptionsCancel() {
        OptionVoteManager.WinnerPick pick = OptionVoteManager.pickWinner(
                List.of(), Map.of(), id -> false, n -> 0);
        assertNull(pick.winnerId());
        assertFalse(pick.randomPick());
    }

    @Test
    void uniqueMaxAmongUnlockedIsNotRandom() {
        List<VoteOption> options = List.of(
                new VoteOption("a", "A"),
                new VoteOption("b", "B"));
        Map<UUID, String> votes = new HashMap<>();
        votes.put(UUID.randomUUID(), "a");
        votes.put(UUID.randomUUID(), "b");
        votes.put(UUID.randomUUID(), "b");

        OptionVoteManager.WinnerPick pick = OptionVoteManager.pickWinner(
                options, votes, null, n -> 99);

        assertEquals("b", pick.winnerId());
        assertFalse(pick.randomPick());
    }

    @Test
    void disconnectKeepsBallotWhileVoteIsActive() {
        assertFalse(OptionVoteManager.dropBallotOnVoterExit(true, false));
        assertTrue(OptionVoteManager.dropBallotOnVoterExit(true, true));
        assertTrue(OptionVoteManager.dropBallotOnVoterExit(false, false));
        assertTrue(OptionVoteManager.dropBallotOnVoterExit(false, true));
    }
}
