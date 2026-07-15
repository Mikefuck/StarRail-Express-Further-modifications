package com.habitrain.core.game.sre.role.sins.shop;

import io.wifi.starrailexpress.util.ShopEntry;

import java.util.ArrayList;
import java.util.List;

public final class SevenSinShops {
    private SevenSinShops() {}

    public static List<ShopEntry> empty() {
        return new ArrayList<>();
    }

    /** Envy full shop filled in P1; P0 return empty or knife-only if items resolve. */
    public static List<ShopEntry> envyShop() {
        return empty();
    }

    public static List<ShopEntry> greedShop() {
        return empty(); // lockpick in P1/P3
    }

    public static List<ShopEntry> gluttonyShop() {
        return empty(); // dynamic in P1
    }

    public static List<ShopEntry> lustShop() {
        return empty();
    }
}
