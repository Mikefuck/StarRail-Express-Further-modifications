package com.habitrain.core.game.sre.role.shop;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;

/** The owner-sync format, separate from CCA's launcher-dependent registration. */
public final class AppleShopStateCodec {
    private AppleShopStateCodec() {}

    public static void write(ApplePriceState<AppleShopSignature> state, int shopSize,
                             CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("role", state.role());
        tag.putLong("settles", state.settlesAt());
        tag.putLong("seed", state.seed());
        tag.putLong("revision", state.revision());
        tag.putBoolean("settled", state.settled());
        tag.putInt("size", shopSize);
        ListTag prices = new ListTag();
        state.quotes().forEach((index, quote) -> {
            CompoundTag price = quote.signature().save(registries);
            price.putInt("index", index);
            price.putInt("normal", quote.normalPrice());
            // Keep the independently rolled final price private until the scramble ends.
            price.putInt("price", state.settled() ? quote.price() : 0);
            prices.add(price);
        });
        tag.put("prices", prices);
    }

    public static int read(ApplePriceState<AppleShopSignature> state, CompoundTag tag,
                           HolderLookup.Provider registries) {
        var prices = new HashMap<Integer, ApplePriceState.Quote<AppleShopSignature>>();
        ListTag list = tag.getList("prices", Tag.TAG_COMPOUND);
        int shopSize = tag.getInt("size");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag price = list.getCompound(i);
            int index = price.getInt("index");
            int value = price.getInt("price");
            if (index < 0 || index >= shopSize || value < 0 || value > SnakeFruitPricePolicy.MAX_PRICE) continue;
            prices.put(index, new ApplePriceState.Quote<>(AppleShopSignature.load(price, registries),
                    price.getInt("normal"), value));
        }
        state.apply(tag.getString("role"), tag.getLong("settles"), tag.getLong("seed"),
                tag.getLong("revision"), tag.getBoolean("settled"), prices);
        return shopSize;
    }
}
