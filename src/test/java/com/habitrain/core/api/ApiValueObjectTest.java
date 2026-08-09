package com.habitrain.core.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiValueObjectTest {

    @Test
    void winResultKeepsDefensiveWinnerSnapshot() {
        UUID winner = UUID.randomUUID();
        List<UUID> source = new ArrayList<>();
        source.add(winner);

        WinResult result = new WinResult(source, "test");
        source.clear();

        assertEquals(List.of(winner), result.getWinners());
        assertThrows(UnsupportedOperationException.class,
                () -> result.getWinners().add(UUID.randomUUID()));
    }

    @Test
    void voteResultKeepsDefensiveTalliesSnapshot() {
        Map<String, Integer> source = new HashMap<>();
        source.put("arena", 2);

        VoteResult result = new VoteResult("mode", "arena", source, false);
        source.put("arena", 9);

        assertEquals(Map.of("arena", 2), result.tallies());
        assertThrows(UnsupportedOperationException.class,
                () -> result.tallies().put("repair", 1));
    }

    @Test
    void nullCollectionsRemainEmpty() {
        assertTrue(new WinResult(null, "none").getWinners().isEmpty());
        assertTrue(new VoteResult("mode", null, null, false).tallies().isEmpty());
    }

    @Test
    void voteApisRejectMissingLevel() {
        assertFalse(OptionVoteApi.cast(null, UUID.randomUUID(), "arena"));
        assertFalse(ModeMapVoteApi.cancel(null));
        assertTrue(ModeMapVoteApi.getSnapshot(null).isEmpty());
    }
}
