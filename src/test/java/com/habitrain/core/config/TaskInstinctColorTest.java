package com.habitrain.core.config;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskInstinctColorTest {

    private static final int PINK = 0xC8FFB6C1;
    private static final int RED = 0xC8FF0000;
    private static final int GRAY = TaskConfigEntry.DEFAULT_INSTINCT_COLOR;

    @Test
    void missingConfigUsesDefinitionColor() {
        assertEquals(PINK, TaskInstinctColor.resolveArgb(null, PINK));
    }

    @Test
    void unsetOverrideUsesDefinitionColor() {
        TaskConfigEntry cfg = new TaskConfigEntry(true);
        assertFalse(cfg.hasInstinctColor);
        assertEquals(PINK, TaskInstinctColor.resolveArgb(cfg, PINK));
    }

    @Test
    void explicitOverrideUsesConfigColor() {
        TaskConfigEntry cfg = new TaskConfigEntry(true);
        cfg.hasInstinctColor = true;
        cfg.instinctColor = RED;
        assertEquals(RED, TaskInstinctColor.resolveArgb(cfg, PINK));
    }

    @Test
    void legacyGrayDoesNotStompNonGrayDefinition() {
        TaskConfigEntry cfg = TaskConfigEntry.fromJson(legacyColorJson(GRAY));
        assertEquals(PINK, TaskInstinctColor.resolveArgb(cfg, PINK));
    }

    @Test
    void legacyNonGrayKeepsSavedColor() {
        TaskConfigEntry cfg = TaskConfigEntry.fromJson(legacyColorJson(RED));
        assertEquals(RED, TaskInstinctColor.resolveArgb(cfg, PINK));
    }

    @Test
    void legacyGrayKeepsGrayWhenDefinitionIsAlsoGray() {
        TaskConfigEntry cfg = TaskConfigEntry.fromJson(legacyColorJson(GRAY));
        assertEquals(GRAY, TaskInstinctColor.resolveArgb(cfg, GRAY));
    }

    @Test
    void explicitHasFlagGrayIsKeptEvenIfDefinitionDiffers() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", true);
        json.addProperty("instinctColor", GRAY);
        json.addProperty("hasInstinctColor", true);
        TaskConfigEntry cfg = TaskConfigEntry.fromJson(json);
        assertEquals(GRAY, TaskInstinctColor.resolveArgb(cfg, PINK));
    }

    @Test
    void roundTripPreservesExplicitOverride() {
        TaskConfigEntry cfg = new TaskConfigEntry(true);
        cfg.hasInstinctColor = true;
        cfg.instinctColor = RED;
        TaskConfigEntry restored = TaskConfigEntry.fromJson(cfg.toJson());
        assertTrue(restored.hasInstinctColor);
        assertEquals(RED, TaskInstinctColor.resolveArgb(restored, PINK));
    }

    @Test
    void roundTripUnsetOverrideStillUsesDefinition() {
        TaskConfigEntry cfg = TaskConfigEntry.createDefault();
        TaskConfigEntry restored = TaskConfigEntry.fromJson(cfg.toJson());
        assertFalse(restored.hasInstinctColor);
        assertEquals(PINK, TaskInstinctColor.resolveArgb(restored, PINK));
    }

    @Test
    void seedFromDefinitionDoesNotCountAsOverride() {
        TaskConfigEntry cfg = TaskInstinctColor.seedFromDefinition(PINK);
        assertFalse(cfg.hasInstinctColor);
        assertEquals(PINK, TaskInstinctColor.resolveArgb(cfg, PINK));
        assertEquals(PINK, cfg.instinctColor);
    }

    @Test
    void normalizeLegacyGrayClearsOverrideFlag() {
        TaskConfigEntry cfg = TaskConfigEntry.fromJson(legacyColorJson(GRAY));
        TaskInstinctColor.normalizeLoadedEntry(cfg, PINK);
        assertFalse(cfg.hasInstinctColor);
        assertFalse(cfg.instinctColorFromLegacyJson);
        assertEquals(PINK, cfg.instinctColor);
    }

    @Test
    void normalizeLegacyCustomColorKeepsOverride() {
        TaskConfigEntry cfg = TaskConfigEntry.fromJson(legacyColorJson(RED));
        TaskInstinctColor.normalizeLoadedEntry(cfg, PINK);
        assertTrue(cfg.hasInstinctColor);
        assertFalse(cfg.instinctColorFromLegacyJson);
        assertEquals(RED, cfg.instinctColor);
    }

    private static JsonObject legacyColorJson(int color) {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", true);
        json.addProperty("instinctColor", color);
        return json;
    }
}
