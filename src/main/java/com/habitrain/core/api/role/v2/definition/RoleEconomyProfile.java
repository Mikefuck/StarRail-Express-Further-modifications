package com.habitrain.core.api.role.v2.definition;

import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
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
        // 审核 R-09 / ROLE-API-02：校验非空并对标准 ShopEntry 的内部 ItemStack 做防御性拷贝。
        List<ShopEntry> copied = new ArrayList<>(shopEntries.size());
        for (ShopEntry entry : shopEntries) {
            copied.add(copyEntry(Objects.requireNonNull(entry, "entry")));
        }
        shopEntries = List.copyOf(copied);
    }

    public RoleEconomyProfile(List<ShopEntry> shopEntries) {
        this(shopEntries, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<ShopEntry> shopEntries() {
        List<ShopEntry> copied = new ArrayList<>(shopEntries.size());
        for (ShopEntry entry : shopEntries) {
            copied.add(copyEntry(entry));
        }
        return List.copyOf(copied);
    }

    /** Resolves the current shop list (live factory first). */
    public List<ShopEntry> resolve() {
        if (live != null) {
            List<ShopEntry> fresh = live.get();
            if (fresh == null || fresh.isEmpty()) {
                return List.of();
            }
            List<ShopEntry> copied = new ArrayList<>(fresh.size());
            for (ShopEntry entry : fresh) {
                if (entry != null) {
                    copied.add(copyEntry(entry));
                }
            }
            return List.copyOf(copied);
        }
        return shopEntries();
    }

    private static ShopEntry copyEntry(ShopEntry entry) {
        if (entry.getClass() == ShopEntry.class) {
            ItemStack stack = entry.stack();
            ItemStack cloned = (stack == null || stack.isEmpty()) ? ItemStack.EMPTY : stack.copy();
            return new ShopEntry(cloned, entry.price(), entry.type());
        }
        return entry;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RoleEconomyProfile other)) return false;
        if (!Objects.equals(this.live, other.live)) return false;
        if (this.shopEntries.size() != other.shopEntries.size()) return false;
        for (int i = 0; i < this.shopEntries.size(); i++) {
            if (!entriesEqual(this.shopEntries.get(i), other.shopEntries.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = Objects.hashCode(live);
        for (ShopEntry entry : shopEntries) {
            h = 31 * h + entryHash(entry);
        }
        return h;
    }

    private static boolean entriesEqual(ShopEntry a, ShopEntry b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.getClass() != b.getClass()) return false;
        if (a.price() != b.price() || a.type() != b.type()) return false;
        ItemStack sa = a.stack();
        ItemStack sb = b.stack();
        if (sa == sb) return true;
        if (sa == null || sb == null) return false;
        return ItemStack.matches(sa, sb);
    }

    private static int entryHash(ShopEntry e) {
        if (e == null) return 0;
        ItemStack s = e.stack();
        int sh = (s == null || s.isEmpty()) ? 0 : Objects.hash(s.getItem(), s.getCount());
        return Objects.hash(e.getClass(), e.price(), e.type(), sh);
    }

    public static final class Builder {
        private final List<ShopEntry> shopEntries = new ArrayList<>();
        private @Nullable Supplier<List<ShopEntry>> live;

        public Builder entry(ShopEntry entry) {
            this.shopEntries.add(copyEntry(Objects.requireNonNull(entry, "entry")));
            return this;
        }

        public Builder entries(ShopEntry... entries) {
            Objects.requireNonNull(entries, "entries");
            for (ShopEntry entry : entries) {
                entry(entry);
            }
            return this;
        }

        public Builder entries(Collection<ShopEntry> entries) {
            Objects.requireNonNull(entries, "entries");
            for (ShopEntry entry : entries) {
                entry(entry);
            }
            return this;
        }

        public Builder live(Supplier<List<ShopEntry>> live) {
            this.live = Objects.requireNonNull(live, "live");
            return this;
        }

        public RoleEconomyProfile build() {
            return new RoleEconomyProfile(shopEntries, live);
        }
    }
}
