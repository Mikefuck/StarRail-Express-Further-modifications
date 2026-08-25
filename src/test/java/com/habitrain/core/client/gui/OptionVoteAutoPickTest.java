package com.habitrain.core.client.gui;

import com.habitrain.core.network.OptionVotePayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionVoteAutoPickTest {
    @AfterEach
    void clearState() {
        OptionVoteState.clear();
    }

    @Test
    void unselectedMapVoteArmsAnimationAtThreeSecondsAndPicksAtOne() {
        OptionVoteState.update(payload("map", 3));
        assertTrue(OptionVoteState.isAutoPickAnimating());
        assertNull(OptionVoteState.autoPickRandomMapIfNeeded(bound -> 1));

        OptionVoteState.update(payload("map", 1));
        assertEquals("map_b", OptionVoteState.autoPickRandomMapIfNeeded(bound -> 1));
        assertEquals("map_b", OptionVoteState.getSelectedOptionId());
        assertNull(OptionVoteState.autoPickRandomMapIfNeeded(bound -> 0));
    }

    @Test
    void activePlayerSelectionSuppressesAutoPick() {
        OptionVoteState.update(payload("map", 1));
        OptionVoteState.select("map_a");

        assertNull(OptionVoteState.autoPickRandomMapIfNeeded(bound -> 1));
    }

    @Test
    void modeVoteNeverUsesMapAutoPick() {
        OptionVoteState.update(payload("mode", 1));

        assertNull(OptionVoteState.autoPickRandomMapIfNeeded(bound -> 1));
    }

    private static OptionVotePayload payload(String voteId, int remainingSeconds) {
        return new OptionVotePayload(
                voteId, true, remainingSeconds, 10, 1, voteId, "", "",
                List.of(
                        new OptionVotePayload.Entry("map_a", "Map A", 0),
                        new OptionVotePayload.Entry("map_b", "Map B", 0)));
    }
}
