package com.habitrain.core.scene.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneMeshBuilderMathTest {
    @Test
    void sourceMinimumAlwaysUsesTheActualBlockBoundNotTheSectionBoundary() {
        assertEquals(-3, SceneMeshBuilder.localSectionOrigin(0, 3));
        assertEquals(-15, SceneMeshBuilder.localSectionOrigin(0, -1));
        assertEquals(0, SceneMeshBuilder.localSectionOrigin(0, 16));
        assertEquals(13, SceneMeshBuilder.localSectionOrigin(1, 3));
    }

    @Test
    void capturedLightNibblesKeepBothValuesInEachByte() {
        byte[] light = new byte[2048];
        light[0] = (byte) 0xA3;

        assertEquals(3, SceneMeshBuilder.readNibble(light, 0));
        assertEquals(10, SceneMeshBuilder.readNibble(light, 1));
        assertEquals(0, SceneMeshBuilder.readNibble(null, 0));
    }
}
