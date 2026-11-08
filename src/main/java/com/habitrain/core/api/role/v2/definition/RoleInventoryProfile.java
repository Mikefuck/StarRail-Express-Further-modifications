package com.habitrain.core.api.role.v2.definition;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable inventory profile: the default items granted on role assignment.
 *
 * <p>An absent inventory profile means the role keeps the upstream default
 * items; when present, {@code ManagedSRERole} returns fresh copies of these
 * stacks from {@code getDefaultItems()}.
 */
public record RoleInventoryProfile(List<ItemStack> defaultItems) {

    public RoleInventoryProfile {
        Objects.requireNonNull(defaultItems, "defaultItems");
        // 审核 R-09 / ROLE-API-01：除不可变列表包装外，对可变 ItemStack 元素做深拷贝。
        List<ItemStack> copied = new ArrayList<>(defaultItems.size());
        for (ItemStack stack : defaultItems) {
            Objects.requireNonNull(stack, "item");
            copied.add(copyStack(stack));
        }
        defaultItems = List.copyOf(copied);
    }

    @Override
    public List<ItemStack> defaultItems() {
        List<ItemStack> copied = new ArrayList<>(defaultItems.size());
        for (ItemStack stack : defaultItems) {
            copied.add(copyStack(stack));
        }
        return List.copyOf(copied);
    }

    private static ItemStack copyStack(ItemStack stack) {
        return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<ItemStack> defaultItems = new ArrayList<>();

        public Builder item(ItemStack stack) {
            Objects.requireNonNull(stack, "item");
            this.defaultItems.add(copyStack(stack));
            return this;
        }

        public Builder items(ItemStack... stacks) {
            Objects.requireNonNull(stacks, "stacks");
            for (ItemStack stack : stacks) {
                item(stack);
            }
            return this;
        }

        public Builder items(Collection<ItemStack> stacks) {
            Objects.requireNonNull(stacks, "stacks");
            for (ItemStack stack : stacks) {
                item(stack);
            }
            return this;
        }

        public RoleInventoryProfile build() {
            return new RoleInventoryProfile(defaultItems);
        }
    }
}
