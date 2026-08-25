package com.habitrain.core.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MapVoteInteractionPolicyTest {
    @Test
    void onlyExplicitLeftClickConfirmationOpensDetails() {
        assertEquals(true, MapVoteInteractionPolicy.detailOpenAfter(false,
                MapVoteInteractionPolicy.Event.LEFT_CLICK_CONFIRM));
        assertEquals(false, MapVoteInteractionPolicy.detailOpenAfter(false,
                MapVoteInteractionPolicy.Event.BROWSE));
        assertEquals(false, MapVoteInteractionPolicy.detailOpenAfter(false,
                MapVoteInteractionPolicy.Event.AUTO_PICK));
        assertEquals(false, MapVoteInteractionPolicy.detailOpenAfter(true,
                MapVoteInteractionPolicy.Event.BROWSE));
        assertEquals(true, MapVoteInteractionPolicy.detailOpenAfter(true,
                MapVoteInteractionPolicy.Event.READ_DETAIL));
    }

    @Test
    void browsingWheelNeverOpensTheDetailPane() {
        assertEquals(MapVoteInteractionPolicy.ScrollIntent.BROWSE_PREVIOUS,
                MapVoteInteractionPolicy.scrollIntent(false, false, 1.0));
        assertEquals(MapVoteInteractionPolicy.ScrollIntent.BROWSE_NEXT,
                MapVoteInteractionPolicy.scrollIntent(false, true, -1.0));
    }

    @Test
    void openDetailConsumesWheelOnlyInsideTheDetailPane() {
        assertEquals(MapVoteInteractionPolicy.ScrollIntent.READ_PREVIOUS,
                MapVoteInteractionPolicy.scrollIntent(true, true, 1.0));
        assertEquals(MapVoteInteractionPolicy.ScrollIntent.READ_NEXT,
                MapVoteInteractionPolicy.scrollIntent(true, true, -1.0));
    }

    @Test
    void openDetailReturnsToBrowsingWhenWheelIsOnTheLeft() {
        assertEquals(MapVoteInteractionPolicy.ScrollIntent.BROWSE_PREVIOUS,
                MapVoteInteractionPolicy.scrollIntent(true, false, 1.0));
        assertEquals(MapVoteInteractionPolicy.ScrollIntent.BROWSE_NEXT,
                MapVoteInteractionPolicy.scrollIntent(true, false, -1.0));
    }
}
