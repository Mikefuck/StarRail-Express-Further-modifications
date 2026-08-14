package com.habitrain.core.client.gui;

import com.habitrain.core.network.MapVoteProfilePayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OptionVoteProfileStateTest {
    @AfterEach
    void clearState() {
        OptionVoteState.clear();
    }

    @Test
    void profileFragmentsMergeInsteadOfReplacingEarlierMaps() {
        OptionVoteState.applyProfiles(new MapVoteProfilePayload(Map.of(
                "map_a", profile("A"))));
        OptionVoteState.applyProfiles(new MapVoteProfilePayload(Map.of(
                "map_b", profile("B"))));

        assertNotNull(OptionVoteState.getProfile("map_a"));
        assertNotNull(OptionVoteState.getProfile("map_b"));
        assertEquals("A", OptionVoteState.getProfile("map_a").description());
        assertEquals("B", OptionVoteState.getProfile("map_b").description());
    }

    private static MapVoteProfilePayload.MapProfile profile(String description) {
        return new MapVoteProfilePayload.MapProfile(description, List.of(), 0, 0, new byte[0]);
    }
}
