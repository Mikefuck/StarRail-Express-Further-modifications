package com.habitrain.core.api.role.v2.definition;

import io.wifi.starrailexpress.util.ShopEntry;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Immutable economy profile: the shop entries a role offers.
 *
 * <p>An absent economy profile means the role keeps the upstream default shop.
 * When a {@link #live()} factory is present, {@code getShopEntries()} rebuilds
 * the list on every open so the shop UI cannot mutate a frozen snapshot.
 */
public record RoleEconomyProfile(List<ShopEntry> shopEntries,
                                 @Nullable Supplier<List<ShopEntry>> live) {

    public RoleEconomyProfile {
        Objects.requireNonNull(shopEntries, "shopEntries");
    }

    public RoleEconomyProfile(List<ShopEntry> shopEntries) {
        this(shopEntries, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Resolves the current shop list (live factory first). */
    public List<ShopEntry> resolve() {
        if (live != null) {
            List<ShopEntry> fresh = live.get();
            return fresh == null ? List.of() : List.copyOf(fresh);
        }
        return shopEntries;
    }

    public static final class Builder {
        private final List<ShopEntry> shopEntries = new ArrayList<>();
        private @Nullable Supplier<List<ShopEntry>> live;

        public Builder entry(ShopEntry entry) {
            this.shopEntries.add(Objects.requireNonNull(entry, "entry"));
            return this;
        }

        public Builder entries(ShopEntry... entries) {
            Collections.addAll(this.shopEntries, entries);
            return this;
        }

        public Builder live(Supplier<List<ShopEntry>> live) {
            this.live = Objects.requireNonNull(live, "live");
            return this;
        }

        public RoleEconomyProfile build() {
            return new RoleEconomyProfile(List.copyOf(shopEntries), live);
        }
    }
}
