package com.habitrain.core.scene;

import com.habitrain.core.scene.model.SceneEditorMapPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneEditorMapPolicyTest {
    private static final Set<String> CONFIGURED = Set.of("__default__", "map1", "map2");

    @Test
    void rememberedEditorMapWinsOverActiveRuntimeMap() {
        assertEquals("map1", SceneEditorMapPolicy.resolve("map1", "map2", "map2", CONFIGURED));
    }

    @Test
    void activeRuntimeMapSeedsEditorWhenNothingWasRemembered() {
        assertEquals("map2", SceneEditorMapPolicy.resolve("", "map2", "map1", CONFIGURED));
    }

    @Test
    void contextMapSeedsEditorOutsideMatch() {
        assertEquals("map2", SceneEditorMapPolicy.resolve("", "", "map2", CONFIGURED));
    }

    @Test
    void deletedRememberedMapFallsBackWithoutSelectingAnUnknownProfile() {
        assertEquals("map1", SceneEditorMapPolicy.resolve("deleted", "", "map1", CONFIGURED));
        assertEquals("__default__", SceneEditorMapPolicy.resolve("deleted", "", "forged", CONFIGURED));
    }

    @Test
    void selectionCanOnlyBeAppliedToItsOwningMap() {
        assertTrue(SceneEditorMapPolicy.selectionBelongsToEditor("map1", "map1"));
        assertFalse(SceneEditorMapPolicy.selectionBelongsToEditor("map2", "map1"));
        assertFalse(SceneEditorMapPolicy.selectionBelongsToEditor("map1", ""));
    }
}
