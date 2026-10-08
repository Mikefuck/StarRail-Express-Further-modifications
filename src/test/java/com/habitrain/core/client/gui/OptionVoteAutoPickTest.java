package com.habitrain.core.client.gui;

import com.habitrain.core.network.OptionVotePayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertNull(OptionVoteState.autoPickRandomOptionIfNeeded(bound -> 1));

        OptionVoteState.update(payload("map", 1));
        assertEquals("map_b", OptionVoteState.autoPickRandomOptionIfNeeded(bound -> 1));
        assertEquals("map_b", OptionVoteState.getSelectedOptionId());
        assertNull(OptionVoteState.autoPickRandomOptionIfNeeded(bound -> 0));
    }

    @Test
    void activePlayerSelectionSuppressesAutoPick() {
        OptionVoteState.update(payload("map", 1));
        OptionVoteState.select("map_a");

        assertNull(OptionVoteState.autoPickRandomOptionIfNeeded(bound -> 1));
    }

    @Test
    void modeVoteNeverUsesMapAutoPick() {
        OptionVoteState.update(payload("mode", 1));

        assertNull(OptionVoteState.autoPickRandomOptionIfNeeded(bound -> 1));
    }

    @Test
    void hidingUnselectedVotePicksImmediatelyForModeAndMap() {
        for (String voteId : List.of("mode", "map")) {
            OptionVoteState.clear();
            OptionVoteState.update(payload(voteId, 10));
            assertNull(OptionVoteState.autoPickRandomOptionIfNeeded(bound -> 1));

            OptionVoteState.markUiHiddenByUser();
            assertEquals("map_b", OptionVoteState.autoPickRandomOptionIfNeeded(bound -> {
                assertEquals(2, bound);
                return 1;
            }));
            assertEquals("map_b", OptionVoteState.getSelectedOptionId());
            assertEquals("map_b", OptionVoteState.getAutoPickedOptionId());

            // 重复隐藏和服务端重发状态都不得改投或再次发送。
            OptionVoteState.markUiHiddenByUser();
            OptionVoteState.update(payload(voteId, 9));
            assertNull(OptionVoteState.autoPickRandomOptionIfNeeded(bound -> 0));
            assertEquals("map_b", OptionVoteState.getSelectedOptionId());
        }
    }

    @Test
    void hidingPreservesExistingPlayerSelection() {
        OptionVoteState.update(payload("map", 10));
        OptionVoteState.select("map_a");
        OptionVoteState.markUiHiddenByUser();

        assertNull(OptionVoteState.autoPickRandomOptionIfNeeded(bound -> 1));
        assertEquals("map_a", OptionVoteState.getSelectedOptionId());
        assertNull(OptionVoteState.getAutoPickedOptionId());
    }

    @Test
    void hiddenModeToMapHandoffPicksNewPhaseWithoutReopening() {
        OptionVoteState.update(payload("mode", 10));
        OptionVoteState.markUiHiddenByUser();
        assertEquals("map_a", OptionVoteState.autoPickRandomOptionIfNeeded(bound -> 0));

        OptionVoteState.UpdateResult result = OptionVoteState.update(payload("map", 10));
        assertFalse(result.shouldAutoOpen());
        assertTrue(OptionVoteState.isUiHiddenByUser());
        assertEquals("map_b", OptionVoteState.autoPickRandomOptionIfNeeded(bound -> 1));
    }

    @Test
    void hidingInactiveOrEmptyVoteCannotPick() {
        OptionVoteState.markUiHiddenByUser();
        assertNull(OptionVoteState.autoPickRandomOptionIfNeeded(bound -> 0));

        OptionVoteState.update(new OptionVotePayload(
                "map", true, 10, 10, 1, "map", "", "", List.of()));
        OptionVoteState.markUiHiddenByUser();
        assertNull(OptionVoteState.autoPickRandomOptionIfNeeded(bound -> 0));
    }

    private static OptionVotePayload payload(String voteId, int remainingSeconds) {
        return new OptionVotePayload(
                voteId, true, remainingSeconds, 10, 1, voteId, "", "",
                List.of(
                        new OptionVotePayload.Entry("map_a", "Map A", 0),
                        new OptionVotePayload.Entry("map_b", "Map B", 0)));
    }
}
