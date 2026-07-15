package com.habitrain.core.game.sre.role.sins.shop;

import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.game.KillerKnifeShopEntry;
import io.wifi.starrailexpress.index.TMMItems;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

    public static final int GLUTTONY_FOOD_PRICE = 5;
    public static final int GLUTTONY_MILK_PRICE = 300;
    public static final int GLUTTONY_HONEY_PRICE = 100;

    public static final int LUST_LOCKPICK_PRICE = 300;

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

    /**
     * 暴食店：扫描 {@link BuiltInRegistries#ITEM} 中带 {@link DataComponents#FOOD} 的物品 @5；
     * 奶桶 300、蜜瓶 100（覆盖食物价）。每次调用返回新列表。
     */
    public static List<ShopEntry> gluttonyShop() {
        List<ShopEntry> shop = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == null || item == Items.AIR) continue;
            ItemStack stack = item.getDefaultInstance();
            boolean food = stack.get(DataComponents.FOOD) != null;
            int price;
            if (item == Items.MILK_BUCKET) {
                price = GLUTTONY_MILK_PRICE;
            } else if (item == Items.HONEY_BOTTLE) {
                price = GLUTTONY_HONEY_PRICE;
            } else if (food) {
                price = GLUTTONY_FOOD_PRICE;
            } else {
                continue;
            }
            shop.add(new ShopEntry(stack, price, ShopEntry.Type.TOOL));
        }
        // Ensure milk/honey are listed even if a pack omits FOOD on default stack.
        boolean hasMilk = false;
        boolean hasHoney = false;
        for (ShopEntry e : shop) {
            if (e == null) continue;
            ItemStack s = e.stack();
            if (s == null || s.isEmpty()) continue;
            if (s.is(Items.MILK_BUCKET)) hasMilk = true;
            if (s.is(Items.HONEY_BOTTLE)) hasHoney = true;
        }
        if (!hasMilk) {
            shop.add(new ShopEntry(Items.MILK_BUCKET.getDefaultInstance(), GLUTTONY_MILK_PRICE, ShopEntry.Type.TOOL));
        }
        if (!hasHoney) {
            shop.add(new ShopEntry(Items.HONEY_BOTTLE.getDefaultInstance(), GLUTTONY_HONEY_PRICE, ShopEntry.Type.TOOL));
        }
        return shop;
    }

    /** 色欲店：开锁 300。 */
    public static List<ShopEntry> lustShop() {
        List<ShopEntry> shop = new ArrayList<>();
        shop.add(new ShopEntry(TMMItems.LOCKPICK.getDefaultInstance(), LUST_LOCKPICK_PRICE, ShopEntry.Type.TOOL));
        return shop;
    }
}
