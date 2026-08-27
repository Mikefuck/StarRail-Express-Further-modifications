package com.habitrain.core.game.sre;

/** Pure precedence rules shared by task completion and task-point scanning. */
public final class ConsumableClassificationPolicy {

    public enum Kind { NONE, EAT, DRINK }

    private ConsumableClassificationPolicy() {
    }

    public static Kind classify(boolean directDrinkCandidate, boolean throwablePotion, boolean hasFood) {
        if (directDrinkCandidate && !throwablePotion) {
            return Kind.DRINK;
        }
        if (hasFood) {
            return Kind.EAT;
        }
        return Kind.NONE;
    }
}
