package com.habitrain.core.game.sre.role.sins.shop;

import com.habitrain.core.game.sre.role.HabiRoleItems;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.game.KillerKnifeShopEntry;
import io.wifi.starrailexpress.index.TMMItems;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class SevenSinShops {
    private SevenSinShops() {}

    public static final int ENVY_KNIFE_PRICE = 120;
    public static final int ENVY_GUN_PRICE = 285;
    public static final int ENVY_LOCKPICK_PRICE = 150;
    public static final int ENVY_BLACKOUT_PRICE = 150;

    public static final int PRIDE_KNIFE_PRICE = 50;
    public static final int PRIDE_GRENADE_PRICE = 200;
    public static final int PRIDE_PSYCHO_PRICE = 300;
    public static final int PRIDE_LOCKPICK_PRICE = 100;

    public static final int GLUTTONY_FOOD_PRICE = 5;
    public static final int GLUTTONY_GOLDEN_APPLE_PRICE = 75;
    public static final int GLUTTONY_MILK_PRICE = 300;
    public static final int GLUTTONY_HONEY_PRICE = 100;

    public static final int LUST_INCENSE_PRICE = 100;
    public static final int LUST_BOTTLE_PRICE = 350;
    public static final int LUST_STIMULANT_PRICE = 150;

    private static volatile List<ShopEntry> cachedGluttonyShop;

    public static List<ShopEntry> empty() {
        return new ArrayList<>();
    }

    /**
     * 嫉妒杀手店：刀 120、左轮手枪 285、开锁 150、关灯 150。
     * 刀必须用 {@link KillerKnifeShopEntry} 以 stamp murder 耐久。
     */
    public static List<ShopEntry> envyShop() {
        List<ShopEntry> shop = new ArrayList<>();
        shop.add(new KillerKnifeShopEntry(ENVY_KNIFE_PRICE));
        shop.add(new ShopEntry(TMMItems.REVOLVER.getDefaultInstance(), ENVY_GUN_PRICE, ShopEntry.Type.WEAPON));
        shop.add(new ShopEntry(TMMItems.LOCKPICK.getDefaultInstance(), ENVY_LOCKPICK_PRICE, ShopEntry.Type.TOOL));
        shop.add(new ShopEntry(TMMItems.BLACKOUT.getDefaultInstance(), ENVY_BLACKOUT_PRICE, ShopEntry.Type.TOOL) {
            @Override
            public boolean onBuy(@NotNull Player player) {
                return SREPlayerShopComponent.useBlackout(player);
            }
        });
        return shop;
    }

    /**
     * 傲慢固定店：刀 50、手雷 200、疯狂模式 300、撬锁 100。
     * 刀必须用 {@link KillerKnifeShopEntry}；手雷为 {@code TMMItems.GRENADE}。
     */
    public static List<ShopEntry> prideShop() {
        List<ShopEntry> shop = new ArrayList<>();
        shop.add(new KillerKnifeShopEntry(PRIDE_KNIFE_PRICE));
        shop.add(new ShopEntry(TMMItems.GRENADE.getDefaultInstance(), PRIDE_GRENADE_PRICE, ShopEntry.Type.WEAPON));
        shop.add(new ShopEntry(TMMItems.PSYCHO_MODE.getDefaultInstance(), PRIDE_PSYCHO_PRICE, ShopEntry.Type.WEAPON) {
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
        shop.add(new ShopEntry(TMMItems.LOCKPICK.getDefaultInstance(), PRIDE_LOCKPICK_PRICE, ShopEntry.Type.TOOL));
        return shop;
    }

    /**
     * 暴食店：上架常规食物（排除紫颂果、附魔金苹果及特殊技能食物），按合理价格区间定价；
     * 奶桶 300、蜜瓶 100（覆盖食物价）。缓存构建结果以避免每 tick 遍历全注册表。
     */
    public static List<ShopEntry> gluttonyShop() {
        List<ShopEntry> cached = cachedGluttonyShop;
        if (cached != null && !cached.isEmpty()) {
            return new ArrayList<>(cached);
        }
        List<ShopEntry> shop = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == null || item == Items.AIR) continue;
            if (isExcludedGluttonyItem(item)) continue;
            ItemStack stack = item.getDefaultInstance();
            if (isSpecialRoleFoodStack(stack)) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            int price;
            if (item == Items.MILK_BUCKET) {
                price = GLUTTONY_MILK_PRICE;
            } else if (item == Items.HONEY_BOTTLE) {
                price = GLUTTONY_HONEY_PRICE;
            } else if (item == Items.GOLDEN_APPLE) {
                price = GLUTTONY_GOLDEN_APPLE_PRICE;
            } else if (food != null) {
                int nutritionPrice = Math.clamp(food.nutrition() * 2, GLUTTONY_FOOD_PRICE, 25);
                price = !food.effects().isEmpty() ? Math.max(15, nutritionPrice) : GLUTTONY_FOOD_PRICE;
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
        if (!shop.isEmpty()) {
            cachedGluttonyShop = List.copyOf(shop);
        }
        return shop;
    }

    private static boolean isExcludedGluttonyItem(Item item) {
        return item == Items.CHORUS_FRUIT || item == Items.ENCHANTED_GOLDEN_APPLE;
    }

    private static boolean isSpecialRoleFoodStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        if (HabiRoleItems.isRedApple(stack)
                || HabiRoleItems.isPepperSpray(stack)
                || HabiRoleItems.isBouquet(stack)
                || HabiRoleItems.isScapegoatKnife(stack)
                || HabiRoleItems.hasFlag(stack, HabiRoleItems.TAG_LUST_INCENSE)) {
            return true;
        }
        return stack.has(DataComponents.CUSTOM_DATA) || stack.has(DataComponents.CUSTOM_NAME);
    }

    /** Incense is a consumable; stimulant applies immediately on purchase. */
    public static List<ShopEntry> lustShop() {
        List<ShopEntry> shop = new ArrayList<>();
        shop.add(new ShopEntry(com.habitrain.core.game.sre.role.HabiRoleItems.createLustIncense(),
                LUST_INCENSE_PRICE, ShopEntry.Type.TOOL));
        shop.add(new ShopEntry(org.agmas.noellesroles.init.ModItems.HALLUCINATION_BOTTLE.getDefaultInstance(),
                LUST_BOTTLE_PRICE, ShopEntry.Type.TOOL));
        ItemStack stimulant = new ItemStack(Items.POTION);
        stimulant.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.translatable(
                "item.habitrain_core.lust_stimulant"));
        stimulant.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
                net.minecraft.network.chat.Component.translatable("item.habitrain_core.lust_stimulant.desc"))));
        shop.add(new ShopEntry(stimulant, LUST_STIMULANT_PRICE, ShopEntry.Type.TOOL) {
            @Override public boolean onBuy(@NotNull Player player) {
                return com.habitrain.core.game.sre.role.sins.component.LustComponent.useStimulant(player);
            }
        });
        return shop;
    }
}
