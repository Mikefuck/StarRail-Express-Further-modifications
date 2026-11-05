package com.habitrain.core.game.sre.role.sins.component;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LustRestrictionStateTest {
    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void hidesOnlyEliminatedPlayersWhileAtLeastOneEffectiveLustExists() {
        var round = new LustRestrictionState();
        assertFalse(round.hidden(VICTIM, true));
        round.died(VICTIM);
        assertTrue(round.hidden(VICTIM, true));
        assertFalse(round.hidden(VICTIM, false));
        assertFalse(round.hidden(OTHER, true));
    }

    @Test void exemptionSurvivesRepeatedRejudgementAndReplacementEntityWithTheSameUuid() {
        var round = new LustRestrictionState();
        round.died(VICTIM);
        assertTrue(round.exempt(VICTIM));
        for (int tick = 0; tick < 300; tick++) {
            UUID reconnected = UUID.fromString(VICTIM.toString());
            round.died(reconnected);
            assertFalse(round.hidden(reconnected, true));
        }
        round.died(OTHER);
        assertTrue(round.hidden(OTHER, true));
    }

    @Test void sourceDisappearanceAndReturnDoNotResetThisDeathsExemption() {
        var round = new LustRestrictionState();
        round.died(VICTIM);
        round.exempt(VICTIM);
        assertFalse(round.hidden(VICTIM, false));
        assertFalse(round.hidden(VICTIM, true));
    }

    @Test void genuineRevivalInvalidatesExemptionBeforeNextDeath() {
        var round = new LustRestrictionState();
        round.died(VICTIM);
        round.exempt(VICTIM);
        round.revived(VICTIM);
        assertFalse(round.isDead(VICTIM));
        assertFalse(round.isExempt(VICTIM));
        round.died(VICTIM);
        assertTrue(round.hidden(VICTIM, true));
    }

    @Test void newRoundHasNoOfflinePlayersDeathOrExemption() {
        var oldRound = new LustRestrictionState();
        oldRound.died(VICTIM);
        oldRound.exempt(VICTIM);
        var newRound = new LustRestrictionState();
        assertFalse(newRound.isDead(VICTIM));
        assertFalse(newRound.isExempt(VICTIM));
        newRound.died(VICTIM);
        assertTrue(newRound.hidden(VICTIM, true));
    }

    @Test void clearingALivingPlayerCannotExemptAFutureDeath() {
        var round = new LustRestrictionState();
        assertFalse(round.exempt(VICTIM));
        round.died(VICTIM);
        assertTrue(round.hidden(VICTIM, true));
    }
}
