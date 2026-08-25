package com.habitrain.core.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSessionResetPolicyTest {

    @Test
    void joinMustNotClearEspCachesOnDedicatedClient() {
        assertFalse(ClientSessionResetPolicy.clearEspCachesOnJoin(false));
    }

    @Test
    void joinMustNotClearEspCachesOnIntegratedHost() {
        assertFalse(ClientSessionResetPolicy.clearEspCachesOnJoin(true));
    }

    @Test
    void disconnectClearsEspCaches() {
        assertTrue(ClientSessionResetPolicy.clearEspCachesOnDisconnect(false));
        assertTrue(ClientSessionResetPolicy.clearEspCachesOnDisconnect(true));
    }

    @Test
    void gameFinishedClearsEspCaches() {
        assertTrue(ClientSessionResetPolicy.clearEspCachesOnGameFinished());
    }

    @Test
    void dedicatedDisconnectReloadsLocalConfig() {
        assertTrue(ClientSessionResetPolicy.reloadLocalConfigOnDisconnect(false));
    }

    @Test
    void integratedHostDisconnectDoesNotReloadSharedConfig() {
        assertFalse(ClientSessionResetPolicy.reloadLocalConfigOnDisconnect(true));
    }
}
