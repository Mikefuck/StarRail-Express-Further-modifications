package com.habitrain.core.client.cache;

import com.google.gson.JsonObject;
import io.wifi.starrailexpress.client.gui.screen.maprotation.MapIntroDetail;
import io.wifi.starrailexpress.network.MapIntroSyncPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClientMapIntroCacheTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        ClientMapIntroCache.clear();
    }

    @Test
    void testInitialEmptyState() {
        assertFalse(ClientMapIntroCache.hasData());
        assertNull(ClientMapIntroCache.getLatestPayload());
        assertNull(ClientMapIntroCache.getMapJson("unknown_map"));
        assertNull(ClientMapIntroCache.getVoteMap("unknown_map"));

        MapIntroDetail.SpecialSets sets = ClientMapIntroCache.getSpecialSets();
        assertNotNull(sets);
        assertTrue(sets.bag().isEmpty());
        assertTrue(sets.police().isEmpty());
        assertTrue(sets.underwater().isEmpty());
        assertTrue(sets.air().isEmpty());
        assertTrue(sets.trap().isEmpty());
        assertTrue(sets.horse().isEmpty());
    }

    @Test
    void testUpdateRawParsesCorrectly() {
        String testJson = "{\"roomCount\": 4, \"canSwim\": true, \"disabledTasks\": [\"wires\"]}";
        MapIntroSyncPayload.MapJson mapJson = new MapIntroSyncPayload.MapJson("qiyucun", testJson);
        MapIntroSyncPayload.VoteMap voteMap = new MapIntroSyncPayload.VoteMap(
                "qiyucun", "启雨村", 1, 8, true, List.of("classic", "blackout"));

        ClientMapIntroCache.updateRaw(
                List.of(mapJson),
                List.of(voteMap),
                List.of("thief"),
                List.of("sheriff"),
                List.of("diver"),
                List.of("pilot"),
                List.of("trapper"),
                List.of("knight")
        );

        assertTrue(ClientMapIntroCache.hasData());

        JsonObject parsedJson = ClientMapIntroCache.getMapJson("qiyucun");
        assertNotNull(parsedJson);
        assertEquals(4, parsedJson.get("roomCount").getAsInt());
        assertTrue(parsedJson.get("canSwim").getAsBoolean());

        MapIntroSyncPayload.VoteMap cachedVoteMap = ClientMapIntroCache.getVoteMap("qiyucun");
        assertNotNull(cachedVoteMap);
        assertEquals("启雨村", cachedVoteMap.displayName());
        assertEquals(1, cachedVoteMap.minCount());
        assertEquals(8, cachedVoteMap.maxCount());
        assertTrue(cachedVoteMap.canSelect());
        assertEquals(List.of("classic", "blackout"), cachedVoteMap.gameModes());

        MapIntroDetail.SpecialSets sets = ClientMapIntroCache.getSpecialSets();
        assertEquals(Set.of("thief"), sets.bag());
        assertEquals(Set.of("sheriff"), sets.police());
        assertEquals(Set.of("diver"), sets.underwater());
        assertEquals(Set.of("pilot"), sets.air());
        assertEquals(Set.of("trapper"), sets.trap());
        assertEquals(Set.of("knight"), sets.horse());
    }

    @Test
    void mergeAddingMapBDoesNotDropMapA() {
        MapIntroSyncPayload.MapJson mapA = new MapIntroSyncPayload.MapJson(
                "map_a", "{\"roomCount\": 1}");
        MapIntroSyncPayload.VoteMap voteA = new MapIntroSyncPayload.VoteMap(
                "map_a", "A", 1, 8, true, List.of("classic"));
        ClientMapIntroCache.updateRaw(
                List.of(mapA),
                List.of(voteA),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertNotNull(ClientMapIntroCache.getMapJson("map_a"));

        MapIntroSyncPayload.MapJson mapB = new MapIntroSyncPayload.MapJson(
                "map_b", "{\"roomCount\": 2}");
        MapIntroSyncPayload.VoteMap voteB = new MapIntroSyncPayload.VoteMap(
                "map_b", "B", 1, 8, true, List.of("classic"));
        ClientMapIntroCache.updateRawMerge(
                List.of(mapB),
                List.of(voteA, voteB),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        JsonObject keptA = ClientMapIntroCache.getMapJson("map_a");
        JsonObject addedB = ClientMapIntroCache.getMapJson("map_b");
        assertNotNull(keptA, "partial merge must not drop map A");
        assertEquals(1, keptA.get("roomCount").getAsInt());
        assertNotNull(addedB);
        assertEquals(2, addedB.get("roomCount").getAsInt());
        assertNotNull(ClientMapIntroCache.getVoteMap("map_a"));
        assertNotNull(ClientMapIntroCache.getVoteMap("map_b"));
    }

    @Test
    void fullReplaceStillDropsMapsAbsentFromPayload() {
        ClientMapIntroCache.updateRaw(
                List.of(new MapIntroSyncPayload.MapJson("map_a", "{}")),
                List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        ClientMapIntroCache.updateRaw(
                List.of(new MapIntroSyncPayload.MapJson("map_b", "{}")),
                List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertNull(ClientMapIntroCache.getMapJson("map_a"));
        assertNotNull(ClientMapIntroCache.getMapJson("map_b"));
    }
}
