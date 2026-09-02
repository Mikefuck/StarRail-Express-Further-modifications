package com.habitrain.core.game.sre.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SreSceneContextResolverTest {
    @Test
    void votedMapWinsWhileSreComponentStillContainsPreviousMap() {
        assertEquals("voted_snow_map",
                SreSceneContextResolver.resolveMapKey(" voted_snow_map ", "old_desert_map"));
    }

    @Test
    void regularLaunchFallsBackToSreMapComponent() {
        assertEquals("desert_map", SreSceneContextResolver.resolveMapKey(null, " desert_map "));
    }

    @Test
    void missingContextsUseDefaultProfile() {
        assertEquals("__default__", SreSceneContextResolver.resolveMapKey(" ", null));
    }
}
