package com.habitrain.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlackoutGlobalCooldownRulesTest {

    @Test
    void secondsAreClampedAndConvertedWithoutOverflow() {
        assertEquals(0, BlackoutGlobalCooldownRules.clampSeconds(-1));
        assertEquals(0, BlackoutGlobalCooldownRules.toTicks(0));
        assertEquals(800, BlackoutGlobalCooldownRules.toTicks(40));
        assertEquals(72_000, BlackoutGlobalCooldownRules.toTicks(9_999));
    }

    @Test
    void repositoryUsesUpstreamDefaultAndSameBounds() {
        ConfigRepository repository = new ConfigRepository();
        assertEquals(40, repository.getBlackoutGlobalCooldownSeconds());

        repository.setBlackoutGlobalCooldownSeconds(-10);
        assertEquals(0, repository.getBlackoutGlobalCooldownSeconds());

        repository.setBlackoutGlobalCooldownSeconds(9_999);
        assertEquals(3_600, repository.getBlackoutGlobalCooldownSeconds());
    }
}
