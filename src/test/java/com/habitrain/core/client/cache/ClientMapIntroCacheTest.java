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
}
