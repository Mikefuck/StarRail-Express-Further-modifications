package com.habitrain.core.game.blackout;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackoutVictoryCheckerWinPolicyTest {

    @Test
    void blackoutProbeIsNotACustomEndProposal() {
        assertFalse(BlackoutVictoryChecker.acceptCustomWinFromProposal("BLACKOUT"));
        assertFalse(BlackoutVictoryChecker.acceptCustomWinFromProposal(null));
        assertFalse(BlackoutVictoryChecker.acceptCustomWinFromProposal("LOVERS"));
    }

    @Test
    void factionAndTimerProposalsMayCarryCustomSin() {
        assertTrue(BlackoutVictoryChecker.acceptCustomWinFromProposal("KILLERS"));
        assertTrue(BlackoutVictoryChecker.acceptCustomWinFromProposal("PASSENGERS"));
        assertTrue(BlackoutVictoryChecker.acceptCustomWinFromProposal("TIME"));
    }

    @Test
    void customIdsMapToSevenSinsPaths() {
        assertNull(BlackoutVictoryChecker.resolveCustomSinId("sin_sloth"));
        assertEquals(ResourceLocation.parse("habitrain_core:sin_pride"),
                BlackoutVictoryChecker.resolveCustomSinId("habitrain_core:sin_pride"));
        assertEquals(ResourceLocation.parse("habitrain_core:sin_lust"),
                BlackoutVictoryChecker.resolveCustomSinId("sin_lust"));
        assertEquals(ResourceLocation.parse("habitrain_core:sin_greed"),
                BlackoutVictoryChecker.resolveCustomSinId("sin_greed"));
        assertNull(BlackoutVictoryChecker.resolveCustomSinId("sin_wrath"));
        assertNull(BlackoutVictoryChecker.resolveCustomSinId(null));
        assertNull(BlackoutVictoryChecker.resolveCustomSinId(" "));
    }
}
