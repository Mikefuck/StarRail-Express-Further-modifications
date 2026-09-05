package com.habitrain.core.scene;

import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.network.SceneStagingDecisionC2S;
import com.habitrain.core.scene.network.SceneStagingOfferS2C;
import com.habitrain.core.scene.network.SceneStagingReportC2S;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneStagingPayloadTest {
    @Test
    void offerRoundTripsDescriptorAndExpiry() {
        SceneAssetDescriptor descriptor = new SceneAssetDescriptor(
                "a".repeat(64), 200L, 100L, 3, 1, "fp", 123L);
        SceneStagingOfferS2C original = new SceneStagingOfferS2C(
                "00000000-0000-0000-0000-000000000001", "map1", descriptor, 456L);
        ByteBuf buffer = Unpooled.buffer();
        SceneStagingOfferS2C.CODEC.encode(buffer, original);
        SceneStagingOfferS2C decoded = SceneStagingOfferS2C.CODEC.decode(buffer);
        assertEquals(original.stagingId(), decoded.stagingId());
        assertEquals(original.mapKey(), decoded.mapKey());
        assertEquals(descriptor, decoded.descriptor());
        assertEquals(456L, decoded.expiresAt());
    }

    @Test
    void reportAndExplicitDecisionRoundTrip() {
        SceneStagingReportC2S report = new SceneStagingReportC2S("stage", "map1",
                "a".repeat(64), true, "fp", "sodium=false");
        ByteBuf reportBuffer = Unpooled.buffer();
        SceneStagingReportC2S.CODEC.encode(reportBuffer, report);
        assertEquals(report, SceneStagingReportC2S.CODEC.decode(reportBuffer));

        SceneStagingDecisionC2S decision = new SceneStagingDecisionC2S("stage", "map1",
                "a".repeat(64), true);
        ByteBuf decisionBuffer = Unpooled.buffer();
        SceneStagingDecisionC2S.CODEC.encode(decisionBuffer, decision);
        assertEquals(decision, SceneStagingDecisionC2S.CODEC.decode(decisionBuffer));
    }
}
