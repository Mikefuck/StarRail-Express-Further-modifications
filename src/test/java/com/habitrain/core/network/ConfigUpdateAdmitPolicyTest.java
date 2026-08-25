package com.habitrain.core.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigUpdateAdmitPolicyTest {

    @Test
    void firstUpdateApplies() {
        assertEquals(ConfigUpdateAdmitPolicy.Result.APPLY,
                ConfigUpdateAdmitPolicy.admit("{\"a\":1}", null, 1000L, 0L, 2000L));
    }

    @Test
    void duplicateJsonIsSkippedEvenInsideCooldown() {
        assertEquals(ConfigUpdateAdmitPolicy.Result.SKIP_DUPLICATE,
                ConfigUpdateAdmitPolicy.admit("{\"a\":1}", "{\"a\":1}", 1100L, 1000L, 2000L));
    }

    @Test
    void differentJsonAppliesEvenInsideOldTwoSecondWindow() {
        assertEquals(ConfigUpdateAdmitPolicy.Result.APPLY,
                ConfigUpdateAdmitPolicy.admit("{\"a\":2}", "{\"a\":1}", 1100L, 1000L, 2000L));
    }
}
