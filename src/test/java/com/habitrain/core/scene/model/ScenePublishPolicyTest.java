package com.habitrain.core.scene.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ScenePublishPolicyTest {

    @Test
    public void testPolicyFromString() {
        assertEquals(ScenePublishPolicy.STRICT, ScenePublishPolicy.fromString(null));
        assertEquals(ScenePublishPolicy.STRICT, ScenePublishPolicy.fromString(""));
        assertEquals(ScenePublishPolicy.STRICT, ScenePublishPolicy.fromString("STRICT"));
        assertEquals(ScenePublishPolicy.STRICT, ScenePublishPolicy.fromString("strict"));
        assertEquals(ScenePublishPolicy.SKIP_AND_WARN, ScenePublishPolicy.fromString("SKIP_AND_WARN"));
        assertEquals(ScenePublishPolicy.SKIP_AND_WARN, ScenePublishPolicy.fromString("skip_and_warn "));
        assertEquals(ScenePublishPolicy.STRICT, ScenePublishPolicy.fromString("unknown_value"));
    }
}
