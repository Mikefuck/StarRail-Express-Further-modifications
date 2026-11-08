package com.habitrain.core.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigUpdateAdmitPolicyTest {
    @Test
    void retryWithNewRequestIdMustApplyEvenWhenRevisionAndPatchHashAreUnchanged() {
        assertEquals(ConfigUpdateAdmitPolicy.Result.APPLY,
                ConfigUpdateAdmitPolicy.admit("retry", "failed", 42L, 42L, 12345, 12345));
    }

    @Test
    void sameRequestIdIsReplayedRatherThanAppliedTwice() {
        assertEquals(ConfigUpdateAdmitPolicy.Result.SKIP_DUPLICATE,
                ConfigUpdateAdmitPolicy.admit("same-request", "same-request", 42L, 42L, 12345, 12345));
    }


    @Test
    void firstUpdateApplies() {
        assertEquals(ConfigUpdateAdmitPolicy.Result.APPLY,
                ConfigUpdateAdmitPolicy.admit("{\"a\":1}", null, 1000L, 0L, 2000L));
    }

    @Test
    void historicalJsonCanBeRestoredAfterAnotherAdministratorChangesAuthority() {
        assertEquals(ConfigUpdateAdmitPolicy.Result.APPLY,
                ConfigUpdateAdmitPolicy.admit("{\"a\":1}", "{\"a\":1}", 1100L, 1000L, 2000L));
    }

    @Test
    void differentJsonAppliesEvenInsideOldTwoSecondWindow() {
        assertEquals(ConfigUpdateAdmitPolicy.Result.APPLY,
                ConfigUpdateAdmitPolicy.admit("{\"a\":2}", "{\"a\":1}", 1100L, 1000L, 2000L));
    }
}
