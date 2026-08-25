package com.habitrain.core.game.sre;

import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SREGameStateProviderTest {

    @Test
    void customWinWriteNoopsWhenStoppingInactiveOrNull() {
        assertFalse(SREGameStateProvider.allowsCustomWinWrite(null));
        assertFalse(SREGameStateProvider.allowsCustomWinWrite(
                SREGameWorldComponent.GameStatus.STOPPING));
        assertFalse(SREGameStateProvider.allowsCustomWinWrite(
                SREGameWorldComponent.GameStatus.INACTIVE));
    }

    @Test
    void customWinWriteAllowedWhileMatchCanStillRun() {
        assertTrue(SREGameStateProvider.allowsCustomWinWrite(
                SREGameWorldComponent.GameStatus.ACTIVE));
        assertTrue(SREGameStateProvider.allowsCustomWinWrite(
                SREGameWorldComponent.GameStatus.STARTING));
        assertTrue(SREGameStateProvider.allowsCustomWinWrite(
                SREGameWorldComponent.GameStatus.INITIATING));
    }
}
