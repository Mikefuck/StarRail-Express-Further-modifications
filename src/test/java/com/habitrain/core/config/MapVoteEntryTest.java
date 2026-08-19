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

    @Test
    void ensureMapDefaultsWithInfoCreatesMissingAndPreservesExisting() {
        ModeMapVoteSettings settings = ModeMapVoteSettings.createDefault();

        // Pre-existing customized map
        MapVoteEntry customized = MapVoteEntry.createDefault();
        customized.enabled = false;
        customized.displayName = ""; // blank name to be filled
        customized.minPlayers = 2;
        customized.maxPlayers = 8;
        settings.maps.put("custom_map", customized);

        var discovered = java.util.Map.of(
                "custom_map", new SREIntegration.DiscoveredMapInfo(
                        "custom_map", "gui.map.custom", 4, 16, true, "desc", "0xFFFFFFFF", List.of()
                ),
                "new_map", new SREIntegration.DiscoveredMapInfo(
                        "new_map", "gui.map.new", 6, 20, false, "desc2", "0xFF00FF00", List.of("murder")
                )
        );

        boolean changed = settings.ensureMapDefaultsWithInfo(List.of("test_mode"), discovered);
        assertTrue(changed);

        // Pre-existing map preserves custom enabled/min/maxPlayers, but inherits upstream displayName
        MapVoteEntry resCustom = settings.maps.get("custom_map");
        assertNotNull(resCustom);
        assertFalse(resCustom.enabled, "Custom enabled=false should be preserved");
        assertEquals("gui.map.custom", resCustom.displayName, "Blank displayName should be populated from upstream");
        assertEquals(2, resCustom.minPlayers, "Custom minPlayers should be preserved");
        assertEquals(8, resCustom.maxPlayers, "Custom maxPlayers should be preserved");

        // New map inherits all discovered properties
        MapVoteEntry resNew = settings.maps.get("new_map");
        assertNotNull(resNew);
        assertFalse(resNew.enabled, "Discovered enabled=false should be applied");
        assertEquals("gui.map.new", resNew.displayName);
        assertEquals(6, resNew.minPlayers);
        assertEquals(20, resNew.maxPlayers);

        // Modes ensured as well
        assertTrue(settings.modes.containsKey("test_mode"));
    }

    @Test
    void syncAndPruneMapsRemovesOrphanMapsAndCleansAllowedMaps() {
        ModeMapVoteSettings settings = ModeMapVoteSettings.createDefault();

        // Stale map in maps list
        settings.maps.put("stale_map", MapVoteEntry.createDefault());
        settings.maps.put("active_map", MapVoteEntry.createDefault());

        // Mode containing references to both maps
        ModeVoteEntry mode = ModeVoteEntry.createDefault();
        mode.allowedMaps = new java.util.ArrayList<>(List.of("stale_map", "active_map"));
        settings.modes.put("test_mode", mode);

        var activeMaps = java.util.Map.of(
                "active_map", new SREIntegration.DiscoveredMapInfo(
                        "active_map", "Active Map", 2, 10, true, "desc", "0xFF", List.of()
                ),
                "brand_new_map", new SREIntegration.DiscoveredMapInfo(
                        "brand_new_map", "Brand New", 4, 12, true, "desc2", "0xFF", List.of()
                )
        );

        boolean changed = settings.syncAndPruneMaps(List.of("test_mode"), activeMaps);
        assertTrue(changed);

        // Stale map is pruned
        assertFalse(settings.maps.containsKey("stale_map"), "Orphan stale_map should be pruned");
        assertTrue(settings.maps.containsKey("active_map"));
        assertTrue(settings.maps.containsKey("brand_new_map"));

        // Mode's allowedMaps is pruned of stale_map
        assertEquals(List.of("active_map"), mode.allowedMaps, "stale_map should be pruned from mode allowedMaps");
    }

    @Test
    void removeMapCleansFromMapsAndAllModesAllowedMaps() {
        ModeMapVoteSettings settings = ModeMapVoteSettings.createDefault();
        settings.maps.put("target_map", MapVoteEntry.createDefault());
        settings.maps.put("other_map", MapVoteEntry.createDefault());

        ModeVoteEntry mode1 = ModeVoteEntry.createDefault();
        mode1.allowedMaps = new java.util.ArrayList<>(List.of("target_map", "other_map"));
        settings.modes.put("m1", mode1);

        boolean removed = settings.removeMap("target_map");
        assertTrue(removed);
        assertFalse(settings.maps.containsKey("target_map"));
        assertEquals(List.of("other_map"), mode1.allowedMaps);
    }
}
