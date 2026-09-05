package com.habitrain.core.scene;

import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneRuntimeState;
import com.habitrain.core.scene.network.SceneAdditionalRuntimeStatesS2C;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneAdditionalRuntimeStatesS2CTest {
    @Test
    void codecRoundTripsFourAdditionalBackgrounds() {
        SceneProfile profile = new SceneProfile();
        profile.setEnabled(true);
        profile.setSpeedBlocksPerSecond(21.5);
        SceneAdditionalRuntimeStatesS2C original = new SceneAdditionalRuntimeStatesS2C(List.of(
                new SceneRuntimeState(true, 100L, 2,
                        "map::habiscene::background_1", "a".repeat(64), profile)));
        ByteBuf buffer = Unpooled.buffer();
        SceneAdditionalRuntimeStatesS2C.CODEC.encode(buffer, original);
        SceneAdditionalRuntimeStatesS2C decoded = SceneAdditionalRuntimeStatesS2C.CODEC.decode(buffer);
        assertEquals(1, decoded.states().size());
        assertEquals("map::habiscene::background_1", decoded.states().get(0).getMapKey());
        assertEquals(21.5, decoded.states().get(0).getProfile().getSpeedBlocksPerSecond(), 0.001);
        assertTrue(decoded.states().get(0).isActive());
    }

    @Test
    void payloadRejectsMoreThanFourAdditionalBackgrounds() {
        SceneRuntimeState state = new SceneRuntimeState(true, 0L, 1, "asset", "hash", new SceneProfile());
        assertThrows(IllegalArgumentException.class, () ->
                new SceneAdditionalRuntimeStatesS2C(List.of(state, state, state, state, state)));
    }
}
