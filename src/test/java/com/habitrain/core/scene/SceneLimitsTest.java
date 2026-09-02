package com.habitrain.core.scene;

import com.habitrain.core.scene.server.SceneTransferService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneLimitsTest {
    @Test
    void axisLimitIsExactlyThirtyTwoChunks() {
        assertEquals(32, SceneLimits.MAX_AXIS_CHUNKS);
        assertEquals(512, SceneLimits.MAX_AXIS_LENGTH);
    }

    @Test
    void loadingAndMatchBandwidthCapsUseRequestedRates() {
        assertEquals(5L * 1024L * 1024L,
                SceneTransferService.bytesPerSecond(SceneTransferService.BandwidthPhase.LOADING));
        assertEquals(1L * 1024L * 1024L,
                SceneTransferService.bytesPerSecond(SceneTransferService.BandwidthPhase.MATCH));
    }
}
