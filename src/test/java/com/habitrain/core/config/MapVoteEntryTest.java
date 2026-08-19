package com.habitrain.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapVoteEntryTest {
    @Test
    void profileOverrideRoundTripsWithAllInformationSheetFields() {
        MapVoteEntry entry = MapVoteEntry.createDefault();
        entry.displayName = "中央车站";
        entry.minPlayers = 4;
        entry.maxPlayers = 12;
        entry.profile = MapVoteProfileSettings.createDefault();
        entry.profile.description = "适合中型大厅的双层地图";
        entry.profile.tags = List.of("双层", "近战");
        entry.profile.previewPath = "previews/station.png";

        MapVoteEntry decoded = MapVoteEntry.fromJson(entry.toJson());

        assertEquals("中央车站", decoded.displayName);
        assertEquals(4, decoded.minPlayers);
        assertEquals(12, decoded.maxPlayers);
        assertNotNull(decoded.profile);
        assertEquals("适合中型大厅的双层地图", decoded.profile.description);
        assertEquals(List.of("双层", "近战"), decoded.profile.tags);
        assertEquals("previews/station.png", decoded.profile.previewPath);
    }

    @Test
    void missingProfileKeepsWorldFileAsSource() {
        MapVoteEntry entry = MapVoteEntry.fromJson(MapVoteEntry.createDefault().toJson());

        assertNull(entry.profile);
        assertFalse(entry.toJson().has("profile"));
    }

    @Test
    void tagNormalizationDeduplicatesAndCapsPayloadSize() {
        List<String> tags = MapVoteProfileSettings.normalizedTags(List.of(
                "  一层  ", "一层", "二层", "三层", "四层", "五层",
                "六层", "七层", "八层", "九层"));

        assertEquals(MapVoteProfileSettings.MAX_TAGS, tags.size());
        assertEquals("一层", tags.getFirst());
        assertTrue(tags.stream().allMatch(tag -> tag.length() <= MapVoteProfileSettings.MAX_TAG_LENGTH));
    }
}
