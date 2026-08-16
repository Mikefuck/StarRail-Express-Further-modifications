package com.habitrain.core.api.role.v2.definition;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable inventory profile: the default items granted on role assignment.
 *
 * <p>An absent inventory profile means the role keeps the upstream default
 * items; when present, {@link ManagedSRERole} returns exactly these stacks from
 * {@code getDefaultItems()}.
 */
public record RoleInventoryProfile(List<ItemStack> defaultItems) {

    public RoleInventoryProfile {
        Objects.requireNonNull(defaultItems, "defaultItems");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<ItemStack> defaultItems = new ArrayList<>();

        public Builder item(ItemStack stack) {
            this.defaultItems.add(Objects.requireNonNull(stack, "item"));
            return this;
        }

        public Builder items(ItemStack... stacks) {
            Collections.addAll(this.defaultItems, stacks);
            return this;
        }

        public RoleInventoryProfile build() {
            return new RoleInventoryProfile(List.copyOf(defaultItems));
        }
    }
}
