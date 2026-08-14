package com.habitrain.core.vote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapVoteProfileStoreTest {
    @Test
    void reservedMapIdMatchesOnlyTheReservedPathSegment() {
        assertTrue(MapVoteProfileStore.isReservedMapId("map_vote"));
        assertTrue(MapVoteProfileStore.isReservedMapId("map_vote/maps"));
        assertTrue(MapVoteProfileStore.isReservedMapId("map_vote\\maps"));
        assertFalse(MapVoteProfileStore.isReservedMapId("map_vote_arena"));
        assertFalse(MapVoteProfileStore.isReservedMapId("map_voter"));
    }

    @Test
    void escapedPreviewNamesDoNotCollideAfterSanitizing() {
        String slash = MapVoteProfileStore.escapeId("station/a:b");
        String underscore = MapVoteProfileStore.escapeId("station_a_b");

        assertNotEquals(slash, underscore);
        assertTrue(slash.matches("[a-zA-Z0-9._-]+"));
        assertTrue(underscore.matches("[a-zA-Z0-9._-]+"));
    }
}
