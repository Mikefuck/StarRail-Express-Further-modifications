package com.habitrain.core.game.sre;

import io.wifi.starrailexpress.content.item.CocktailItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.HoneyBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MilkBucketItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ThrowablePotionItem;

/** Minecraft item adapter for the shared consumable classification policy. */
public final class FoodDrinkConsumableClassifier {

    private FoodDrinkConsumableClassifier() {
    }

    public static ConsumableClassificationPolicy.Kind classify(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ConsumableClassificationPolicy.Kind.NONE;
        }
        Item item = stack.getItem();
        boolean directDrink = item instanceof CocktailItem
                || item instanceof PotionItem
                || item instanceof HoneyBottleItem
                || item instanceof MilkBucketItem;
        boolean throwablePotion = item instanceof ThrowablePotionItem;
        boolean hasFood = stack.get(DataComponents.FOOD) != null;
        return ConsumableClassificationPolicy.classify(directDrink, throwablePotion, hasFood);
    }
}
