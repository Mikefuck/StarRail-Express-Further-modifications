package com.habitrain.core.game.sre.role.idiot;

/** Tick-based rules shared by the server and the private client HUD. */
public final class IdiotRules {
    public static final int THROW_TICKS = 10;
    public static final int FIRST_HATCH_PITY = 60;
    public static final int BOOST_TICKS = 200;
    public static final int SLOW_TICKS = 20;
    public static final int HIT_PROTECTION_TICKS = 60;
    public static final int EGG_LIFETIME = 80;
    public static final int CHICK_LIFETIME = 300;
    public static final int OWNER_EGGS = 8, ROUND_EGGS = 32;
    public static final int OWNER_CHICKS = 3, ROUND_CHICKS = 12;
    private IdiotRules() {}

    /** Kept for the entire round, including role loss, death and reconnect. */
    public static final class Luck {
        private int failures;
        private boolean firstHatched;
        public boolean wouldHatch(double roll) {
            return roll < .02 || !firstHatched && failures >= FIRST_HATCH_PITY - 1;
        }
        public void record(boolean hatched) {
            if (hatched) firstHatched = true;
            else if (!firstHatched) failures = Math.min(FIRST_HATCH_PITY - 1, failures + 1);
        }
        public int remaining() { return firstHatched ? 0 : FIRST_HATCH_PITY - failures; }
        public boolean firstHatched() { return firstHatched; }
    }

    public static long refreshBoost(long previousUntil, long now) {
        return Math.max(previousUntil, now + BOOST_TICKS);
    }

    /** Compensate for vanilla Speed I; never stack with vanilla Speed II or higher. */
    public static double speedMultiplier(int vanillaAmplifier) {
        double vanilla = vanillaAmplifier < 0 ? 1 : 1 + .2 * (vanillaAmplifier + 1);
        return Math.max(0, 1.4 / vanilla - 1);
    }
}
