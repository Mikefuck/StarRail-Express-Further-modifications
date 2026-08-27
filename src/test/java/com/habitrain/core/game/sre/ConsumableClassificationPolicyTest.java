package com.habitrain.core.game.sre;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsumableClassificationPolicyTest {

    @Test
    void directDrinkWinsOverFoodMetadata() {
        assertEquals(ConsumableClassificationPolicy.Kind.DRINK,
                ConsumableClassificationPolicy.classify(true, false, true));
    }

    @Test
    void throwablePotionIsNotAConsumableDrinkTarget() {
        assertEquals(ConsumableClassificationPolicy.Kind.NONE,
                ConsumableClassificationPolicy.classify(true, true, false));
    }

    @Test
    void ordinaryFoodIsAnEatTarget() {
        assertEquals(ConsumableClassificationPolicy.Kind.EAT,
                ConsumableClassificationPolicy.classify(false, false, true));
    }

    @Test
    void unrelatedItemIsIgnored() {
        assertEquals(ConsumableClassificationPolicy.Kind.NONE,
                ConsumableClassificationPolicy.classify(false, false, false));
    }
}
