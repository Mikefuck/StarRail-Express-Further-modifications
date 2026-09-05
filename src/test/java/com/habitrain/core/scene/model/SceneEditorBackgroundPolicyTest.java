package com.habitrain.core.scene.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneEditorBackgroundPolicyTest {
    private static final List<String> AVAILABLE = List.of(
            SceneBackgroundKey.DEFAULT_ID, "mountains", "city_night");

    @Test
    void keepsRememberedCustomBackgroundWhenReopenPayloadStillReportsDefault() {
        assertEquals("mountains", SceneEditorBackgroundPolicy.resolve(
                SceneBackgroundKey.DEFAULT_ID, "mountains", AVAILABLE));
    }

    @Test
    void explicitCustomServerBackgroundWinsOverRememberedSelection() {
        assertEquals("city_night", SceneEditorBackgroundPolicy.resolve(
                "city_night", "mountains", AVAILABLE));
    }

    @Test
    void removedRememberedBackgroundFallsBackToDefault() {
        assertEquals(SceneBackgroundKey.DEFAULT_ID, SceneEditorBackgroundPolicy.resolve(
                SceneBackgroundKey.DEFAULT_ID, "removed", AVAILABLE));
    }
}
