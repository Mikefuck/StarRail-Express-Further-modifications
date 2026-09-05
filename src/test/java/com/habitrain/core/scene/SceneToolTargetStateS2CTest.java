package com.habitrain.core.scene;

import com.habitrain.core.scene.network.SceneToolTargetStateS2C;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneToolTargetStateS2CTest {
    @Test
    void codecPreservesMapAndCustomBackground() {
        SceneToolTargetStateS2C original = new SceneToolTargetStateS2C(
                "habitrain:night_train", "city_night");
        ByteBuf buffer = Unpooled.buffer();

        SceneToolTargetStateS2C.CODEC.encode(buffer, original);
        SceneToolTargetStateS2C decoded = SceneToolTargetStateS2C.CODEC.decode(buffer);

        assertEquals("habitrain:night_train", decoded.mapKey());
        assertEquals("city_night", decoded.backgroundId());
    }
}
