package com.habitrain.core.game.sre.role.sins.component;

public final class GreedPolicy {
    private GreedPolicy() {}

    public record IncomeShare(int coins, int remainder) {}

    public static IncomeShare incomeShare(int amount, boolean innocent, int remainder) {
        long quarters = (long) amount * (innocent ? 2 : 1) + remainder;
        return new IncomeShare((int) (quarters / 4), (int) (quarters % 4));
    }

    public static int estateShare(int coins, int survivors, int index) {
        if (survivors <= 0) return 0;
        return coins / survivors + (index < coins % survivors ? 1 : 0);
    }

    public static int collectionTarget(int players) {
        return Math.max(1, (int) Math.ceil(players / 3.0) - 1);
    }

    public static int doubledUnitPrice(int price, int bundleCount) {
        if (price < 0 || bundleCount <= 0) return -1;
        long cost = (2L * price + bundleCount - 1) / bundleCount;
        return cost > Integer.MAX_VALUE ? -1 : (int) cost;
    }
}
