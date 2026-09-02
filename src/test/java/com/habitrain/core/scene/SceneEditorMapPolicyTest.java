package com.habitrain.core.scene;

import com.habitrain.core.scene.model.SceneEditorMapPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneEditorMapPolicyTest {
    private static final Set<String> CONFIGURED = Set.of("__default__", "map1", "map2");

    @Test
    void nonDefaultServerMapAlwaysWins() {
        assertEquals("map1", SceneEditorMapPolicy.resolve("map1", "map2", "map2", CONFIGURED));
    }

    @Test
    void activeRuntimeMapRepairsTemporaryServerFallback() {
        assertEquals("map2", SceneEditorMapPolicy.resolve("__default__", "map2", "map1", CONFIGURED));
    }

    @Test
    void configuredHudMapRepairsFallbackOutsideMatch() {
        assertEquals("map2", SceneEditorMapPolicy.resolve("__default__", "", "map2", CONFIGURED));
    }

    @Test
    void unknownHudMapCannotSelectArbitraryProfile() {
        assertEquals("__default__", SceneEditorMapPolicy.resolve("__default__", "", "forged", CONFIGURED));
    }
}
