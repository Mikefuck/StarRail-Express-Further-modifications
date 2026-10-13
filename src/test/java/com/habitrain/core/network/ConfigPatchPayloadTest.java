package com.habitrain.core.network;

import com.habitrain.core.config.*;
import com.google.gson.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigPatchPayloadTest {
    @Test void chineseC2sBudgetCountsUtf8Bytes() {
        var buffer = Unpooled.buffer();
        try {
            ConfigUpdatePayload.CODEC.encode(buffer, new ConfigUpdatePayload("中".repeat(10_000)));
            assertEquals(30_004, buffer.readableBytes());
            assertEquals("中".repeat(10_000), ConfigUpdatePayload.CODEC.decode(buffer).getConfigJson());
            assertThrows(EncoderException.class, () -> ConfigUpdatePayload.CODEC.encode(buffer, new ConfigUpdatePayload("中".repeat(10_001))));
        } finally { buffer.release(); }
    }
    @Test void oneTaskChangeStaysSmallInsideLargeConfiguration() {
        JsonObject before = new JsonObject(); JsonObject tasks = new JsonObject(); before.add("tasks", tasks);
        for (int i = 0; i < 2000; i++) { JsonObject task = new JsonObject(); task.addProperty("enabled", true); task.addProperty("note", "中".repeat(60)); tasks.add("task" + i, task); }
        JsonObject after = before.deepCopy(); after.getAsJsonObject("tasks").getAsJsonObject("task3").addProperty("enabled", false);
        var patch = ConfigPatch.filtered(ConfigPatch.diff(before, after), ConfigUpdateScope.BACKPACK_TASKS, "");
        assertTrue(patch.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 100);
        assertEquals(after, ConfigPatch.apply(before, patch, ConfigUpdateScope.BACKPACK_TASKS, ""));
    }
    @Test void worstCaseAcknowledgementLeavesSpaceForS2cHeaders() {
        var buffer = Unpooled.buffer();
        try {
            var value = new ConfigResultPayload("12345678-1234-1234-1234-123456789012", Long.MAX_VALUE, ConfigSaveResult.Status.FAILED, "坏".repeat(20_000), "x".repeat(ConfigPatch.MAX_SYNC_JSON_BYTES));
            ConfigResultPayload.CODEC.encode(buffer, value);
            assertTrue(buffer.readableBytes() < 1_048_000);
            var decoded = ConfigResultPayload.CODEC.decode(buffer); assertTrue(decoded.message().length() < 1000);
            assertEquals(value.authoritativeJson(), decoded.authoritativeJson());
        } finally { buffer.release(); }
    }
}
