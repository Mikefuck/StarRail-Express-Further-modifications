package com.habitrain.core.game.sre.role.sins.shop;

import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.game.KillerKnifeShopEntry;
import io.wifi.starrailexpress.index.TMMItems;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class SevenSinShops {
    private SevenSinShops() {}

    public static final int ENVY_KNIFE_PRICE = 200;
    public static final int ENVY_GUN_PRICE = 300;
    public static final int ENVY_PSYCHO_PRICE = 500;
    public static final int ENVY_LOCKPICK_PRICE = 150;
    public static final int ENVY_BLACKOUT_PRICE = 150;

    public static List<ShopEntry> empty() {
        return new ArrayList<>();
    }

    /**
     * 嫉妒杀手店：刀 200、枪 300、狂暴 500、开锁 150、关灯 150。
     * 刀必须用 {@link KillerKnifeShopEntry} 以 stamp murder 耐久。
     */
    public static List<ShopEntry> envyShop() {
        List<ShopEntry> shop = new ArrayList<>();
        shop.add(new KillerKnifeShopEntry(ENVY_KNIFE_PRICE));
        shop.add(new ShopEntry(TMMItems.REVOLVER.getDefaultInstance(), ENVY_GUN_PRICE, ShopEntry.Type.WEAPON));
        shop.add(new ShopEntry(TMMItems.PSYCHO_MODE.getDefaultInstance(), ENVY_PSYCHO_PRICE, ShopEntry.Type.WEAPON) {
            @Override
            public boolean canBuy(@NotNull Player player) {
                if (player.getCooldowns().isOnCooldown(TMMItems.PSYCHO_MODE)) return false;
                return true;
            }

            @Override
            public boolean onBuy(@NotNull Player player) {
                if (player.getCooldowns().isOnCooldown(TMMItems.PSYCHO_MODE)) return false;
                return SREPlayerShopComponent.usePsychoMode(player);
            }
        });
        shop.add(new ShopEntry(TMMItems.LOCKPICK.getDefaultInstance(), ENVY_LOCKPICK_PRICE, ShopEntry.Type.TOOL));
        shop.add(new ShopEntry(TMMItems.BLACKOUT.getDefaultInstance(), ENVY_BLACKOUT_PRICE, ShopEntry.Type.TOOL) {
            @Override
            public boolean onBuy(@NotNull Player player) {
                return SREPlayerShopComponent.useBlackout(player);
            }
        });
        return shop;
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
