package com.habitrain.core.api.scene;

import com.google.gson.JsonParser;
import com.habitrain.core.api.scene.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class S5SceneValuesTest {
    @Test void jsonNullAndWhitespaceUseTheSameMapForPrimaryAndAdditionalBackgrounds() {
        var settings = SceneMotionSettings.fromJson(JsonParser.parseString("""
                {"enabled":null,"schemaVersion":null,"defaultMapKey":null,
                 "profiles":{" map ":{"enabled":true,"direction":[null,null,null],"speedBlocksPerSecond":null}},
                 "backgrounds":{" map ":{"extra":{"profile":{"enabled":true}}}}}
                """).getAsJsonObject());
        settings.defaultMapKey = "map";
        assertTrue(settings.getProfile(" map ").isEnabled());
        assertEquals(2, settings.getResolvedBackgrounds(null).size());
        assertEquals(2, settings.getResolvedBackgrounds(" map ").size());
        assertSame(settings.getProfile(null), settings.getBackgroundProfile(" ", "default"));
    }

    @Test void rotatedAutoLoopAndLargeDirectionRemainFiniteAndCorrect() {
        var profile = SceneProfile.createDefault();
        profile.setSourceBounds(new SceneBounds(0, 0, 0, 16, 16, 128));
        profile.setDirection(1e200, 0, 0);
        var distances = SceneMotionMath.resolveLoopDistances(profile.getSourceBounds(), new SceneRotation(90, 0, 0),
                profile.getDirection(), profile.getLoop());
        assertArrayEquals(new double[]{1, 0, 0}, profile.getDirection(), 1e-12);
        assertEquals(128, distances.effectiveDistance(), 1e-6);
        assertEquals(SceneMotionMath.SeamRelation.SEAMLESS, SceneMotionMath.seamRelation(profile.getSourceBounds(),
                new SceneRotation(90, 0, 0), profile.getDirection(), profile.getLoop()));
        assertEquals(0, SceneOrbitMath.normalizeAngle(-Double.MIN_VALUE));
        assertEquals(0, SceneOrbitMath.floorMod(Double.NaN, 360));
        assertEquals(0, SceneMotionMath.phase(true, 1, Double.NaN, 0, 512));
    }
}
