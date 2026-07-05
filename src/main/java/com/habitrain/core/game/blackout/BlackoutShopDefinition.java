package com.habitrain.core.game.blackout;

import dev.doctor4t.wathe.util.ShopEntry;

import java.util.Objects;

public record BlackoutShopDefinition(
        String key,
        String displayName,
        String itemId,
        int count,
        int price,
        ShopEntry.Type type,
        boolean singlePurchasePerRound
) {
    public BlackoutShopDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(type, "type");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        if (price < 0) {
            throw new IllegalArgumentException("price must be non-negative");
        }
    }
}
