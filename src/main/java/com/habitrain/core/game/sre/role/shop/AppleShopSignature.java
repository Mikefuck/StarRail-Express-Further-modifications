package com.habitrain.core.game.sre.role.shop;

import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Slot identity includes stack components, not just the underlying vanilla item ID. */
public record AppleShopSignature(ItemStack stack, int basePrice, String type, String currency, int weight) {
    public static AppleShopSignature of(ShopEntry entry) {
        return of(entry, entry.price());
    }

    public static AppleShopSignature of(ShopEntry entry, int basePrice) {
        return new AppleShopSignature(entry.stack().copy(), basePrice, entry.type().name(),
                entry.currency().name(), entry.weight());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AppleShopSignature that && basePrice == that.basePrice
                && weight == that.weight && type.equals(that.type) && currency.equals(that.currency)
                && stack.getCount() == that.stack.getCount()
                && ItemStack.isSameItemSameComponents(stack, that.stack);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(basePrice, type, currency, weight, stack.getCount(),
                ItemStack.hashItemAndComponents(stack));
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("stack", stack.save(registries));
        tag.putInt("base", basePrice);
        tag.putString("type", type);
        tag.putString("currency", currency);
        tag.putInt("weight", weight);
        return tag;
    }

    public static AppleShopSignature load(CompoundTag tag, HolderLookup.Provider registries) {
        return new AppleShopSignature(ItemStack.parseOptional(registries, tag.getCompound("stack")),
                tag.getInt("base"), tag.getString("type"), tag.getString("currency"), tag.getInt("weight"));
    }
}
