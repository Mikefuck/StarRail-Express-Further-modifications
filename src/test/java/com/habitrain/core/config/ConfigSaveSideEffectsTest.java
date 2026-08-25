package com.habitrain.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSaveSideEffectsTest {

    @Test
    void mainMenuSaveMarksColorDirtyAndDoesNotNetwork() {
        ConfigSaveSideEffects.Decision d = ConfigSaveSideEffects.afterLocalSave(false, false, true);
        assertTrue(d.markColorDirty());
        assertFalse(d.sendC2S());
        assertFalse(d.broadcastLan());
        assertFalse(d.refreshIntegratedRoles());
    }

    @Test
    void integratedHostSaveMarksColorDirtyAndBroadcastsLanGuests() {
        ConfigSaveSideEffects.Decision d = ConfigSaveSideEffects.afterLocalSave(true, true, true);
        assertTrue(d.markColorDirty());
        assertFalse(d.sendC2S());
        assertTrue(d.broadcastLan());
        assertTrue(d.refreshIntegratedRoles());
    }

    @Test
    void dedicatedAuthorizedSaveSendsC2S() {
        ConfigSaveSideEffects.Decision d = ConfigSaveSideEffects.afterLocalSave(true, false, true);
        assertTrue(d.markColorDirty());
        assertTrue(d.sendC2S());
        assertFalse(d.broadcastLan());
    }

    @Test
    void dedicatedUnauthorizedSaveDoesNotSendC2SButStillMarksDirty() {
        ConfigSaveSideEffects.Decision d = ConfigSaveSideEffects.afterLocalSave(true, false, false);
        assertTrue(d.markColorDirty());
        assertFalse(d.sendC2S());
        assertFalse(d.broadcastLan());
    }
}
