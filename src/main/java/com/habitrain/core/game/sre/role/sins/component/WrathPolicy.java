package com.habitrain.core.game.sre.role.sins.component;

/** Pure arithmetic policy for Wrath; intentionally independent from Minecraft bootstrap. */
final class WrathPolicy {
    static final int MAX_RAGE_EFFECT_STACKS = 3;

    private WrathPolicy() {}

    static int effectStacksForRage(int rage) {
        return Math.min(MAX_RAGE_EFFECT_STACKS, Math.max(0, rage));
    }

    static int berserkThresholdForKillerCount(int killerCount) {
        return Math.max(1, killerCount - 1);
    }
}
